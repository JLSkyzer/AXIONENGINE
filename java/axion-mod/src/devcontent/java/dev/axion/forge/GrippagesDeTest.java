package dev.axion.forge;

import dev.axion.physics.SimCommandProvider;
import dev.axion.physics.SimCommandStream;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;

/**
 * Grippages des GameTests (ADR-151 D6) : en M4, rien en jeu n'émet encore {@code JAM_JOINT} — C-42 (M6),
 * C-43 et l'action {@code jam_joint} (M7) le feront. Ce fournisseur l'émet pour un test, au tick suivant, par
 * le même chemin que leurs commandes : le flux {@code SIM_IN} du runtime.
 *
 * <p>Contenu de développement, jamais empaqueté (R-1790). Sur le thread du serveur seulement.
 */
final class GrippagesDeTest implements SimCommandProvider {

    /** Celui du serveur en cours ; les fournisseurs sont vidés à l'arrêt de chaque serveur. */
    private static GrippagesDeTest actuel;

    private final MinecraftServer serveur;

    /** Les grippages à émettre au prochain tick. */
    private final List<Grippage> enAttente = new ArrayList<>();

    private record Grippage(int handle, int joint, float desalignement) {}

    private GrippagesDeTest(MinecraftServer serveur) {
        this.serveur = serveur;
    }

    /**
     * Grippe un joint d'une assembly au prochain tick.
     *
     * @param helper le test
     * @param assembly l'assembly du joint
     * @param joint rang du joint dans sa definition
     * @param desalignement désalignement d'ancrage, en blocs ; 0 rend ses réglages d'origine
     */
    static void gripper(GameTestHelper helper, AxionEntity assembly, int joint, float desalignement) {
        MinecraftServer serveur = helper.getLevel().getServer();
        if (actuel == null || actuel.serveur != serveur) {
            actuel = new GrippagesDeTest(serveur);
            RuntimeAccess.get().addCommandProvider(actuel);
        }
        actuel.enAttente.add(new Grippage(AssemblyRuntime.handleIndexOf(assembly), joint, desalignement));
        // ADR-153 D2 : le désalignement commandé est ce que le NBT garde du grippage.
        assembly.commandMisalignment(joint, desalignement);
    }

    @Override
    public SimCommandStream commandsForTick(long tick) {
        SimCommandStream commandes = new SimCommandStream();
        for (Grippage grippage : enAttente) {
            commandes.jamJoint(grippage.handle(), AssemblyRuntime.GENERATION, grippage.joint(), grippage.desalignement());
        }
        enAttente.clear();
        return commandes;
    }
}
