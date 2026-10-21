package dev.axion.forge;

import com.mojang.logging.LogUtils;
import dev.axion.forge.net.JoueurSimule;
import dev.axion.net.AssemblyState;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import org.slf4j.Logger;

/**
 * Les campagnes du harnais réseau (ADR-150 D8) : des GameTests trop longs pour la CI ordinaire, qui ne
 * s'enregistrent que si la variable d'environnement {@code AXION_CAMPAGNE} les nomme.
 *
 * <pre>
 * AXION_CAMPAGNE=t425 ./gradlew :axion-mod:runGameTestServer
 * </pre>
 *
 * <p>Contenu de développement, jamais empaqueté (R-1790).
 */
@GameTestHolder("axion")
public final class CampagneReseauGameTests {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** La variable d'environnement qui nomme la campagne à jouer. */
    private static final String CAMPAGNE = "AXION_CAMPAGNE";

    /** Une heure du serveur, à vingt ticks par seconde. */
    static final int TICKS_PAR_HEURE = 72_000;

    private static final String CUBE = "axion:test_cube";
    private static final float[] DROIT = {0f, 0f, 0f, 1f};
    private static final double DESSUS_DALLE = 2.0;
    private static final int JOUEURS = 20;
    private static final int CUBES = 30;
    /** Une vague de cubes toutes les trente secondes : les précédents disparaissent, d'autres tombent. */
    private static final int VAGUE = 600;
    /** Toutes les dix secondes, trois joueurs passent hors de portée de suivi, ou y reviennent. */
    private static final int DEPLACEMENTS = 200;
    /** Chaque minute, un joueur se déconnecte, et revient au tick suivant. */
    private static final int RECONNEXIONS = 1_200;
    /** Ticks d'états du serveur gardés pour comparer : un état reçu au tick C est celui du tick C − 1. */
    private static final int FENETRE = 8;

    private CampagneReseauGameTests() {}

    /** {@return les campagnes que nomme {@code AXION_CAMPAGNE}, aucune sinon} */
    @GameTestGenerator
    public static Collection<TestFunction> campagnes() {
        if (!"t425".equals(System.getenv(CAMPAGNE))) {
            return List.of();
        }
        return List.of(new TestFunction("campagne_t425", "t425_uneHeureVingtJoueursSansDesynchronisation",
                "axion:test/plancher_16", TICKS_PAR_HEURE + 400, 0L, true, CampagneReseauGameTests::t425));
    }

    /**
     * T-425 : une heure du serveur, vingt joueurs simulés. Les cubes tombent par vagues et disparaissent, des
     * joueurs sortent de la portée de suivi et y reviennent, un joueur se reconnecte chaque minute ; chaque état
     * reçu, par chaque joueur, est celui que le serveur tenait au tick qu'il porte — aucune désynchronisation —,
     * et aucun client ne relève de faute. La comparaison se fait au fil de l'eau, en mémoire bornée.
     */
    private static void t425(GameTestHelper helper) {
        Campagne campagne = new Campagne(helper);
        helper.onEachTick(campagne::tick);
        helper.succeedWhen(() -> {
            if (helper.getTick() < TICKS_PAR_HEURE) {
                throw new GameTestAssertException("la campagne tourne : tick " + helper.getTick());
            }
            campagne.conclure();
        });
    }

    /** L'état d'une campagne : les joueurs, les cubes, les états du serveur à comparer, et les comptes. */
    private static final class Campagne {
        private final GameTestHelper helper;
        private final Random aleas = new Random(425);
        private final List<JoueurSimule> joueurs = new ArrayList<>();
        private final List<Vec3> places = new ArrayList<>();
        private final boolean[] loin = new boolean[JOUEURS];
        private final List<AxionEntity> cubes = new ArrayList<>();
        /** Par entité, l'état de chacun des derniers ticks. */
        private final Map<Integer, Map<Long, AssemblyState>> etats = new HashMap<>();
        private int revient = -1;
        private long fait = -1;
        private long verifies;
        private long reconnexions;
        private long sorties;
        private long apparitions;
        private final long debutMural = System.nanoTime();

        Campagne(GameTestHelper helper) {
            this.helper = helper;
            for (int index = 0; index < JOUEURS; index++) {
                double angle = 2 * Math.PI * index / JOUEURS;
                places.add(new Vec3(8 + 10 * Math.cos(angle), DESSUS_DALLE, 8 + 10 * Math.sin(angle)));
            }
        }

        private String nom(int index) {
            return "sim-t425-" + index;
        }

        void tick() {
            long tick = helper.getLevel().getServer().getTickCount();
            if (tick == fait) {
                return;
            }
            fait = tick;
            long elapsed = helper.getTick();
            if (elapsed < 2) {
                return;
            }
            if (joueurs.isEmpty()) {
                for (int index = 0; index < JOUEURS; index++) {
                    joueurs.add(JoueurSimule.rejoindre(helper.getLevel(), nom(index), helper.absoluteVec(places.get(index))));
                }
            }
            noter(tick);
            if (elapsed % VAGUE == 2) {
                vague();
            }
            if (elapsed % DEPLACEMENTS == 0) {
                deplacer();
            }
            if (revient >= 0) {
                joueurs.set(revient, JoueurSimule.rejoindre(helper.getLevel(), nom(revient),
                        helper.absoluteVec(places.get(revient))));
                loin[revient] = false;
                revient = -1;
            } else if (elapsed % RECONNEXIONS == 600) {
                int index = (int) ((elapsed / RECONNEXIONS) % JOUEURS);
                joueurs.get(index).quitter();
                revient = index;
                reconnexions++;
            }
            for (int index = 0; index < joueurs.size(); index++) {
                if (index != revient) {
                    lire(joueurs.get(index));
                }
            }
        }

        /**
         * Les états de la fin du tick précédent, ceux que les snapshots lus à ce tick portent — de toutes les
         * assemblies du monde : les joueurs suivent aussi celles que d'autres tests ont laissées à portée.
         */
        private void noter(long tick) {
            for (AxionEntity assembly : helper.getLevel().getEntities(AxionEntities.ASSEMBLY.get(), entite -> true)) {
                AssemblyState etat = assembly.netState();
                if (etat != null) {
                    etats.computeIfAbsent(assembly.getId(), id -> new HashMap<>()).put(tick - 1, etat);
                }
            }
            etats.values().forEach(parTick -> parTick.keySet().removeIf(t -> t < tick - FENETRE));
            etats.values().removeIf(Map::isEmpty);
        }

        private void vague() {
            for (AxionEntity cube : cubes) {
                cube.discard();
                sorties++;
            }
            cubes.clear();
            for (int index = 0; index < CUBES; index++) {
                Vec3 position = new Vec3(1.5 + aleas.nextInt(14), DESSUS_DALLE + 3 + aleas.nextInt(6),
                        1.5 + aleas.nextInt(14));
                cubes.add(AssembliesDeTest.poser(helper, CUBE, position, DROIT));
                apparitions++;
            }
        }

        private void deplacer() {
            for (int fois = 0; fois < 3; fois++) {
                int index = aleas.nextInt(JOUEURS);
                if (index == revient) {
                    continue;
                }
                Vec3 place = helper.absoluteVec(places.get(index));
                // Bien au-delà des 32 blocs de suivi du serveur de GameTests, ou de retour à sa place.
                double x = loin[index] ? place.x : place.x + 96;
                joueurs.get(index).joueur().teleportTo(helper.getLevel(), x, place.y, place.z, 0f, 0f);
                loin[index] = !loin[index];
            }
        }

        private void lire(JoueurSimule joueur) {
            joueur.tick();
            for (JoueurSimule.Recu recu : joueur.drainRecus()) {
                Map<Long, AssemblyState> parTick = etats.get(recu.handle());
                AssemblyState attendu = parTick == null ? null : parTick.get(recu.serverTick());
                helper.assertTrue(attendu != null, joueur.joueur().getGameProfile().getName() + " : état reçu de "
                        + recu.handle() + " au tick " + recu.serverTick() + ", que le serveur n'a pas tenu");
                helper.assertTrue(attendu.equals(recu.state()), joueur.joueur().getGameProfile().getName()
                        + " : désynchronisation de " + recu.handle() + " au tick " + recu.serverTick() + " : reçu "
                        + recu.state() + ", le serveur tenait " + attendu);
                verifies++;
            }
            for (String ligne : joueur.drainJournal()) {
                helper.assertTrue(!ligne.startsWith("E-") && !ligne.contains("redemandée"),
                        joueur.joueur().getGameProfile().getName() + " : " + ligne);
            }
        }

        void conclure() {
            for (JoueurSimule joueur : joueurs) {
                joueur.quitter();
            }
            double minutes = (System.nanoTime() - debutMural) / 60e9;
            LOGGER.info(String.format(Locale.ROOT, "AXION : T-425 : %d ticks du serveur (une heure), %d joueurs,"
                            + " %d apparitions et %d disparitions de cubes, %d reconnexions ; %d états reçus, tous"
                            + " identiques à ceux du serveur ; %.1f min de temps mural",
                    TICKS_PAR_HEURE, JOUEURS, apparitions, sorties, reconnexions, verifies, minutes));
            helper.assertTrue(verifies > 0, "aucun état vérifié : la campagne n'a rien éprouvé");
        }
    }
}
