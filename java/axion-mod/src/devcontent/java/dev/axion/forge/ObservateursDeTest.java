package dev.axion.forge;

import dev.axion.physics.SimCommandProvider;
import dev.axion.physics.SimCommandStream;
import dev.axion.world.DimensionId;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Observateurs des GameTests (ADR-123, R-612) : sans observateur, tout corps est endormi, et un
 * serveur de GameTests n'a pas de joueur — le joueur factice de Minecraft n'entre pas dans un
 * monde Forge, faute de canal réseau. Chaque test déclare le centre de son gabarit, et ce
 * fournisseur les envoie à chaque tick par {@code SET_OBSERVERS}, avec les joueurs présents s'il
 * y en a : un {@code /test} lancé en partie ne les fait pas oublier.
 *
 * <p>Contenu de développement, jamais empaqueté (R-1790). Sur le thread du serveur seulement.
 */
final class ObservateursDeTest implements SimCommandProvider {

    /** Celui du serveur en cours ; les fournisseurs sont vidés à l'arrêt de chaque serveur. */
    private static ObservateursDeTest actuel;

    private final MinecraftServer serveur;
    private final Map<ServerLevel, List<double[]>> positions = new LinkedHashMap<>();

    private ObservateursDeTest(MinecraftServer serveur) {
        this.serveur = serveur;
    }

    /**
     * Déclare un observateur au point {@code relative} du gabarit du test, pour la durée du serveur.
     *
     * @param helper le test
     * @param relative position dans le gabarit, en blocs
     */
    static void declarer(GameTestHelper helper, Vec3 relative) {
        ServerLevel level = helper.getLevel();
        if (actuel == null || actuel.serveur != level.getServer()) {
            actuel = new ObservateursDeTest(level.getServer());
            RuntimeAccess.get().addCommandProvider(actuel);
            tracerSiDemande();
        }
        Vec3 point = helper.absoluteVec(relative);
        actuel.positions.computeIfAbsent(level, cle -> new ArrayList<>())
                .add(new double[] {point.x, point.y, point.z});
    }

    /**
     * Ouvre la trace de la simulation (C-71) dans le fichier que nomme la variable d'environnement
     * {@code AXION_GAMETEST_TRACE}, s'il y en a un : tous les tests d'un passage y écrivent, chaque
     * tuile comprise ; elle se ferme à l'arrêt du serveur.
     */
    private static void tracerSiDemande() {
        String chemin = System.getenv("AXION_GAMETEST_TRACE");
        if (chemin == null || chemin.isBlank()) {
            return;
        }
        try {
            RuntimeAccess.get().startTrace(Path.of(chemin));
        } catch (IOException echec) {
            throw new UncheckedIOException("trace des GameTests impossible : " + chemin, echec);
        }
    }

    @Override
    public SimCommandStream commandsForTick(long tick) {
        SimCommandStream stream = new SimCommandStream();
        positions.forEach((level, declares) -> {
            List<double[]> tous = new ArrayList<>(declares);
            for (ServerPlayer joueur : level.players()) {
                if (!joueur.isSpectator()) {
                    tous.add(new double[] {joueur.getX(), joueur.getY(), joueur.getZ()});
                }
            }
            int retenus = Math.min(tous.size(), SimCommandStream.MAX_OBSERVERS);
            stream.setObservers(
                    DimensionId.of(level.dimension().location().toString()),
                    tous.subList(tous.size() - retenus, tous.size()).toArray(new double[0][]));
        });
        return stream;
    }
}
