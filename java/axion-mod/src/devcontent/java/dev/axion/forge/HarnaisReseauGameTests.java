package dev.axion.forge;

import com.mojang.logging.LogUtils;
import dev.axion.asset.AssetSource;
import dev.axion.definition.DefinitionRegistry;
import dev.axion.diag.JavaMetrics;
import dev.axion.diag.NetworkMetrics;
import dev.axion.forge.net.AxionNetwork;
import dev.axion.forge.net.JoueurSimule;
import dev.axion.lifecycle.AxionRuntime;
import dev.axion.net.AssemblyState;
import dev.axion.net.Interpolation;
import dev.axion.net.PlayerSync;
import dev.axion.net.ServerSnapshot;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.slf4j.Logger;

/**
 * Le chemin réseau en jeu, à travers Forge, avec des joueurs simulés (ADR-150 D8) : de vrais joueurs du
 * serveur sur des connexions Netty embarquées, et le client que chacun serait (voir {@link JoueurSimule}).
 *
 * <p>Chaque test tourne dans son lot, seul : un joueur simulé observe (R-612) et suit tout ce qui
 * l'entoure, et deux tests simultanés se mêleraient. Contenu de développement, jamais empaqueté (R-1790).
 *
 * <p>Chronologie d'un tick du serveur : le tick des GameTests passe avant la fin du tick de Forge, où
 * l'état des assemblies se met à jour puis part dans les snapshots ({@code AxionForgeEntrypoint}). Au
 * tick {@code C}, la connexion d'un joueur simulé tient donc les trames du tick {@code C − 1}, et
 * {@code netState()} l'état qu'elles portent. Rien ne garantit que le rappel de chaque tick passe dès le
 * tick du corps du test : une assembly posée là pourrait envoyer son premier snapshot avant qu'un état soit
 * noté. Les scènes se posent donc au deuxième tick, quand ce rappel tourne déjà. Et les rappels d'un même
 * tick n'ont pas d'ordre — Minecraft les range dans une table de hachage : le travail d'un tick (noter les
 * états, puis lire ce que le joueur a reçu) se fait une fois au plus, par qui l'appelle le premier.
 */
@GameTestHolder("axion")
@PrefixGameTestTemplate(false)
public final class HarnaisReseauGameTests {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String CUBE = "axion:test_cube";

    /** Ticks par seconde du serveur. */
    private static final int TICKS_PAR_SECONDE = 20;

    private static final float[] DROIT = {0f, 0f, 0f, 1f};

    /** Le dessus de la dalle du gabarit {@code plancher}, en coordonnées du test. */
    private static final double DESSUS_DALLE = 2.0;

    private HarnaisReseauGameTests() {}

    /** Les états d'une assembly que ses snapshots ont portés, par tick du serveur. */
    private static final class EtatsDuServeur {
        private final AxionEntity assembly;
        private final Map<Long, AssemblyState> parTick = new HashMap<>();

        EtatsDuServeur(AxionEntity assembly) {
            this.assembly = assembly;
        }

        /** À chaque tick, avant le joueur simulé : l'état de la fin du tick précédent. */
        void noter(GameTestHelper helper) {
            AssemblyState etat = assembly.netState();
            if (etat != null) {
                parTick.put(helper.getLevel().getServer().getTickCount() - 1L, etat);
            }
        }

        /**
         * Chaque état que le joueur a reçu de l'assembly est celui du serveur au tick qu'il porte : aucune
         * désynchronisation.
         *
         * @return le nombre d'états reçus vérifiés
         */
        int verifier(GameTestHelper helper, JoueurSimule joueur, long depuisTick) {
            int verifies = 0;
            for (JoueurSimule.Recu recu : joueur.recus()) {
                if (recu.handle() != assembly.getId() || recu.serverTick() < depuisTick) {
                    continue;
                }
                AssemblyState attendu = parTick.get(recu.serverTick());
                helper.assertTrue(attendu != null, "état reçu au tick " + recu.serverTick()
                        + ", que le serveur n'a pas tenu (ticks notés " + new java.util.TreeSet<>(parTick.keySet()).first()
                        + " à " + new java.util.TreeSet<>(parTick.keySet()).last() + ", reçus "
                        + joueur.recus().stream().filter(r -> r.handle() == assembly.getId()).map(JoueurSimule.Recu::serverTick).toList()
                        + ")");
                helper.assertTrue(attendu.equals(recu.state()), "désynchronisation au tick " + recu.serverTick()
                        + " : reçu " + recu.state() + ", le serveur tenait " + attendu);
                verifies++;
            }
            return verifies;
        }
    }

    /** Une scène du harnais : une assembly, un joueur simulé, et les états du serveur à comparer. */
    private static final class Scene {
        AxionEntity cube;
        JoueurSimule joueur;
        EtatsDuServeur serveur;
        long fait = -1;

        /** Le travail d'un tick : les états du serveur, puis ce que le joueur a reçu ; une fois par tick au plus. */
        void tick(GameTestHelper helper) {
            long maintenant = helper.getLevel().getServer().getTickCount();
            if (joueur == null || maintenant == fait) {
                return;
            }
            fait = maintenant;
            serveur.noter(helper);
            joueur.tick();
        }

        /**
         * Le rappel de chaque tick, posé dans le corps du test, et la scène posée au deuxième tick.
         *
         * @param helper le test
         * @param cube position du cube, en coordonnées du test
         * @param joueur position du joueur, en coordonnées du test
         * @param nom nom du joueur simulé
         */
        static Scene poser(GameTestHelper helper, Vec3 cube, Vec3 joueur, String nom) {
            Scene scene = new Scene();
            helper.onEachTick(() -> scene.tick(helper));
            helper.runAfterDelay(2, () -> {
                scene.cube = AssembliesDeTest.poser(helper, CUBE, cube, DROIT);
                scene.serveur = new EtatsDuServeur(scene.cube);
                scene.joueur = JoueurSimule.rejoindre(helper.getLevel(), nom, helper.absoluteVec(joueur));
            });
            return scene;
        }
    }

    /**
     * Une scène de charge : une grille de cubes lâchés de hauteurs étagées, des joueurs simulés autour, et ce que
     * le serveur envoie à chacun à chaque tick — les octets de message que le plafond compte (R-1664) —, avec la
     * durée de l'envoi ({@code axion.net.send_ns}) et celle du décodage des clients simulés.
     */
    private static final class Charge {
        final List<AxionEntity> cubes = new ArrayList<>();
        final List<EtatsDuServeur> etats = new ArrayList<>();
        final List<JoueurSimule> joueurs = new ArrayList<>();
        final Map<JoueurSimule, List<Long>> octets = new HashMap<>();
        final List<Long> envoiNanos = new ArrayList<>();
        final List<Long> decodageNanos = new ArrayList<>();
        long fait = -1;

        /** Le travail d'un tick : les états, les octets et les durées, puis la lecture ; une fois par tick au plus. */
        void tick(GameTestHelper helper) {
            long maintenant = helper.getLevel().getServer().getTickCount();
            if (joueurs.isEmpty() || maintenant == fait) {
                return;
            }
            fait = maintenant;
            for (EtatsDuServeur etats : this.etats) {
                etats.noter(helper);
            }
            // L'envoi de la fin du tick précédent, comme les états notés.
            envoiNanos.add(metrique("send_ns", false, "ns"));
            long debut = System.nanoTime();
            for (JoueurSimule joueur : joueurs) {
                PlayerSync synchronisation = joueur.synchronisation();
                if (synchronisation != null) {
                    octets.get(joueur).add(synchronisation.lastTickBytes());
                }
                joueur.tick();
            }
            decodageNanos.add(System.nanoTime() - debut);
        }

        /**
         * @param helper le test, sur le gabarit {@code plancher_16}
         * @param cote côté de la grille, en cubes
         * @param pas écart des cubes, en blocs
         * @param positions positions des joueurs, en coordonnées du test
         * @param nom préfixe du nom des joueurs simulés
         * @param plafond leur plafond de débit, en octets par seconde ; {@code 0} : celui de la configuration
         */
        static Charge poser(GameTestHelper helper, int cote, double pas, List<Vec3> positions, String nom, long plafond) {
            Charge charge = new Charge();
            helper.onEachTick(() -> charge.tick(helper));
            helper.runAfterDelay(2, () -> {
                for (int i = 0; i < cote; i++) {
                    for (int k = 0; k < cote; k++) {
                        Vec3 position = new Vec3(1.5 + pas * i, DESSUS_DALLE + 4 + (i + k) % 5, 1.5 + pas * k);
                        AxionEntity cube = AssembliesDeTest.poser(helper, CUBE, position, DROIT);
                        charge.cubes.add(cube);
                        charge.etats.add(new EtatsDuServeur(cube));
                    }
                }
                for (int index = 0; index < positions.size(); index++) {
                    charge.ajouter(JoueurSimule.rejoindre(helper.getLevel(), nom + "-" + index,
                            helper.absoluteVec(positions.get(index)), plafond));
                }
            });
            return charge;
        }

        void ajouter(JoueurSimule joueur) {
            joueurs.add(joueur);
            octets.put(joueur, new ArrayList<>());
        }

        void retirer(JoueurSimule joueur) {
            joueur.quitter();
            joueurs.remove(joueur);
        }

        JoueurSimule joueur() {
            return joueurs.get(0);
        }

        void quitter() {
            for (JoueurSimule joueur : List.copyOf(joueurs)) {
                retirer(joueur);
            }
        }

        double secondes(JoueurSimule joueur) {
            return (double) octets.get(joueur).size() / TICKS_PAR_SECONDE;
        }

        long total(JoueurSimule joueur) {
            return octets.get(joueur).stream().mapToLong(Long::longValue).sum();
        }

        /** {@return le plus d'octets envoyés au joueur sur vingt ticks consécutifs} */
        long pireSeconde(JoueurSimule joueur) {
            List<Long> parTick = octets.get(joueur);
            long pire = 0;
            long fenetre = 0;
            for (int index = 0; index < parTick.size(); index++) {
                fenetre += parTick.get(index);
                if (index >= TICKS_PAR_SECONDE) {
                    fenetre -= parTick.get(index - TICKS_PAR_SECONDE);
                }
                pire = Math.max(pire, fenetre);
            }
            return pire;
        }

        long plusGrosMessage(JoueurSimule joueur) {
            return maximum(octets.get(joueur));
        }

        /**
         * Le seau d'ADR-150 (une seconde de débit, plein au départ) : sur la durée, jamais plus que le plafond
         * par seconde, plus la seconde du seau ; sur une seconde, au plus le seau et son remplissage ; et la
         * première entrée d'un snapshot peut le creuser d'un message.
         */
        void sousLePlafond(GameTestHelper helper, JoueurSimule joueur, long plafond) {
            double secondes = secondes(joueur);
            long total = total(joueur);
            long pire = pireSeconde(joueur);
            long marge = plusGrosMessage(joueur);
            helper.assertTrue(total <= plafond * (secondes + 1) + marge, String.format(Locale.ROOT,
                    "%s : %d octets en %.1f s pour un plafond de %d o/s", joueur.joueur().getGameProfile().getName(),
                    total, secondes, plafond));
            helper.assertTrue(pire <= 2 * plafond + marge, joueur.joueur().getGameProfile().getName()
                    + " : une seconde à " + pire + " octets, au-delà du seau d'un plafond de " + plafond + " o/s");
        }

        /** Aucune désynchronisation, et aucun snapshot perdu en route. */
        void fidele(GameTestHelper helper, JoueurSimule joueur) {
            for (EtatsDuServeur etats : this.etats) {
                etats.verifier(helper, joueur, 0);
            }
            sansFaute(helper, joueur);
            PlayerSync synchronisation = joueur.synchronisation();
            helper.assertTrue(joueur.acquittements() == synchronisation.sender().sentSnapshots(),
                    joueur.acquittements() + " snapshots reçus sur " + synchronisation.sender().sentSnapshots()
                            + " envoyés");
        }
    }

    private static long maximum(List<Long> valeurs) {
        return valeurs.stream().mapToLong(Long::longValue).max().orElse(0);
    }

    private static double moyenne(List<Long> valeurs) {
        return valeurs.stream().mapToLong(Long::longValue).average().orElse(0);
    }

    private static long plafondDeLaConfiguration() {
        return RuntimeAccess.get().outcome().config().getInt("net.max_bytes_per_second_per_player");
    }

    private static long metrique(String nom, boolean compteur, String unite) {
        String complet = NetworkMetrics.PREFIX + nom;
        return (compteur ? JavaMetrics.GLOBAL.counter(complet, unite) : JavaMetrics.GLOBAL.gauge(complet, unite)).value();
    }

    private static JavaMetrics.Budget budgetDuDebit() {
        return JavaMetrics.GLOBAL.budget(NetworkMetrics.BANDWIDTH_BUDGET, "bytes_per_second");
    }

    /**
     * T-411 : quarante-neuf cubes lâchés autour d'un joueur, au plafond de la configuration. Le débit qu'il
     * reçoit est mesuré, sur la durée et sur sa pire seconde, et reste sous le plafond sans que rien
     * n'attende ; chaque état reçu est celui du serveur, et aucun snapshot ne se perd.
     */
    @GameTest(template = "test/plancher_16", batch = "harnais_reseau_t411", timeoutTicks = 300)
    public static void t411_leDebitMesureResteSousLePlafond(GameTestHelper helper) {
        long plafond = plafondDeLaConfiguration();
        Charge charge = Charge.poser(helper, 7, 2, List.of(new Vec3(8, DESSUS_DALLE, 8)), "sim-t411", 0);
        helper.runAfterDelay(200, () -> {
            try {
                JoueurSimule joueur = charge.joueur();
                charge.tick(helper);
                double secondes = charge.secondes(joueur);
                LOGGER.info(String.format(Locale.ROOT, "AXION : T-411 : %d assemblies, %d octets de snapshot en %.1f s"
                                + " — %.0f o/s en moyenne, %d o/s la pire seconde, %d snapshots ; plafond %d o/s",
                        charge.cubes.size(), charge.total(joueur), secondes, charge.total(joueur) / secondes,
                        charge.pireSeconde(joueur), joueur.synchronisation().sender().sentSnapshots(), plafond));
                helper.assertTrue(charge.total(joueur) > 0, "aucun octet envoyé : la scène ne mesure rien");
                helper.assertTrue(metrique("players_synced", false, "count") == 1
                                && metrique("assemblies_tracked", false, "count") >= charge.cubes.size(),
                        "les métriques du tick ne disent pas le joueur et ses " + charge.cubes.size() + " assemblies");
                charge.sousLePlafond(helper, joueur, plafond);
                helper.assertTrue(joueur.synchronisation().sender().delayedEntries() == 0,
                        "sous le plafond, des entrées ont attendu : " + joueur.synchronisation().sender().delayedEntries());
                charge.fidele(helper, joueur);
            } finally {
                charge.quitter();
            }
            helper.succeed();
        });
    }

    /**
     * T-418 : les mêmes cubes, pour un joueur dont le débit est plafonné à 2 Kio/s. Le plafond retarde des
     * entrées, le budget compte ses dépassements, et le débit tient le seau ; rien n'est omis : chaque cube
     * finit par livrer son dernier état, et chaque état reçu est celui du serveur.
     */
    @GameTest(template = "test/plancher_16", batch = "harnais_reseau_t418", timeoutTicks = 420)
    public static void t418_unPlafondSerreRetardeSansRienOmettre(GameTestHelper helper) {
        long plafond = 2048;
        long depassementsAvant = budgetDuDebit().overruns().value();
        Charge charge = Charge.poser(helper, 7, 2, List.of(new Vec3(8, DESSUS_DALLE, 8)), "sim-t418", plafond);
        // Retardé n'est pas omis : sous un plafond serré, l'état récent d'un cube encore éveillé attend son tour.
        // Ce qui se prouve, c'est que chacun finit par livrer son dernier état, avant le délai du test.
        helper.succeedWhen(() -> {
            helper.assertTrue(helper.getTick() >= 200, "les cubes tombent encore");
            charge.tick(helper);
            JoueurSimule joueur = charge.joueur();
            for (AxionEntity cube : charge.cubes) {
                JoueurSimule.Recu dernier = null;
                for (JoueurSimule.Recu recu : joueur.recus()) {
                    if (recu.handle() == cube.getId()) {
                        dernier = recu;
                    }
                }
                helper.assertTrue(dernier != null && dernier.state().equals(cube.netState()),
                        "le cube " + cube.getId() + " n'a pas livré son dernier état : omission");
            }
            long retardees = joueur.synchronisation().sender().delayedEntries();
            helper.assertTrue(retardees > 0, "le plafond serré n'a rien retardé : la scène ne l'éprouve pas");
            helper.assertTrue(budgetDuDebit().overruns().value() > depassementsAvant,
                    "le budget du débit n'a compté aucun dépassement");
            charge.sousLePlafond(helper, joueur, plafond);
            charge.fidele(helper, joueur);
            LOGGER.info(String.format(Locale.ROOT, "AXION : T-418 : %d assemblies, plafond %d o/s : %d entrée(s)"
                            + " retardée(s), %d octets en %.1f s, %d o/s la pire seconde ; tout livré au tick %d",
                    charge.cubes.size(), plafond, retardees, charge.total(joueur), charge.secondes(joueur),
                    charge.pireSeconde(joueur), helper.getTick()));
            charge.quitter();
        });
    }

    /**
     * T-423 : cent cubes lâchés, dix joueurs simulés autour. La bande passante de chacun et de tous, et le temps
     * de l'envoi des snapshots à chaque tick, sont mesurés et rapportés au journal ; chaque joueur suit les cent
     * assemblies, sous le plafond, sans désynchronisation ni perte. 21.11 ne fixe pas de seuil de temps : il se
     * mesure, il ne se décrète pas.
     */
    @GameTest(template = "test/plancher_16", batch = "harnais_reseau_t423", timeoutTicks = 300)
    public static void t423_centAssembliesEtDixJoueursSeMesurent(GameTestHelper helper) {
        long plafond = plafondDeLaConfiguration();
        List<Vec3> autour = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            double angle = 2 * Math.PI * index / 10;
            autour.add(new Vec3(8 + 9 * Math.cos(angle), DESSUS_DALLE, 8 + 9 * Math.sin(angle)));
        }
        Charge charge = Charge.poser(helper, 10, 1.5, autour, "sim-t423", 0);
        helper.runAfterDelay(200, () -> {
            try {
                charge.tick(helper);
                double secondes = charge.secondes(charge.joueur());
                List<Long> parJoueur = new ArrayList<>();
                long pire = 0;
                for (JoueurSimule joueur : charge.joueurs) {
                    parJoueur.add(charge.total(joueur));
                    pire = Math.max(pire, charge.pireSeconde(joueur));
                }
                long tous = parJoueur.stream().mapToLong(Long::longValue).sum();
                LOGGER.info(String.format(Locale.ROOT, "AXION : T-423 : %d assemblies, %d joueurs, %.1f s : par joueur"
                                + " %.0f o/s en moyenne (de %.0f à %.0f), %d o/s la pire seconde ; tous joueurs %.0f o/s ;"
                                + " envoi des snapshots %.0f µs par tick en moyenne, %.0f µs au pire ; décodage des %d"
                                + " clients %.0f µs par tick en moyenne ; plafond %d o/s",
                        charge.cubes.size(), charge.joueurs.size(), secondes, tous / secondes / charge.joueurs.size(),
                        parJoueur.stream().mapToLong(Long::longValue).min().orElse(0) / secondes,
                        parJoueur.stream().mapToLong(Long::longValue).max().orElse(0) / secondes, pire, tous / secondes,
                        moyenne(charge.envoiNanos) / 1000, maximum(charge.envoiNanos) / 1000.0, charge.joueurs.size(),
                        moyenne(charge.decodageNanos) / 1000, plafond));
                helper.assertTrue(charge.cubes.size() == 100 && charge.joueurs.size() == 10, "la scène n'est pas posée");
                helper.assertTrue(maximum(charge.envoiNanos) > 0, "le temps de l'envoi n'est pas mesuré");
                for (JoueurSimule joueur : charge.joueurs) {
                    helper.assertTrue(joueur.synchronisation().tracked().size() >= charge.cubes.size(),
                            joueur.joueur().getGameProfile().getName() + " ne suit que "
                                    + joueur.synchronisation().tracked().size() + " assemblies");
                    charge.sousLePlafond(helper, joueur, plafond);
                    charge.fidele(helper, joueur);
                }
            } finally {
                charge.quitter();
            }
            helper.succeed();
        });
    }

    /**
     * T-424 : un joueur se déconnecte, puis revient sous le même nom — la part « déformation » attend M6. Sa
     * nouvelle connexion repart de rien : chaque assembly, endormie ou non, lui parvient en entier sans rien
     * redemander — aucun delta sur une base que seule l'ancienne connexion tenait —, et chaque état reçu est
     * celui du serveur.
     */
    @GameTest(template = "test/plancher_16", batch = "harnais_reseau_t424", timeoutTicks = 300)
    public static void t424_uneReconnexionRecoitLEtatComplet(GameTestHelper helper) {
        Vec3 milieu = new Vec3(8, DESSUS_DALLE, 8);
        Charge charge = Charge.poser(helper, 4, 3, List.of(milieu), "sim-t424", 0);
        helper.runAfterDelay(80, () -> {
            helper.assertTrue(!charge.joueur().recus().isEmpty(), "le joueur n'a rien reçu avant de partir");
            charge.retirer(charge.joueur());
        });
        helper.runAfterDelay(100, () -> charge.ajouter(
                JoueurSimule.rejoindre(helper.getLevel(), "sim-t424-0", helper.absoluteVec(milieu))));
        helper.runAfterDelay(200, () -> {
            try {
                JoueurSimule revenu = charge.joueur();
                charge.tick(helper);
                for (AxionEntity cube : charge.cubes) {
                    boolean recu = revenu.recus().stream().anyMatch(r -> r.handle() == cube.getId());
                    helper.assertTrue(recu, "le cube " + cube.getId() + " n'est pas parvenu à la nouvelle connexion");
                }
                charge.fidele(helper, revenu);
            } finally {
                charge.quitter();
            }
            helper.succeed();
        });
    }

    private static void sansFaute(GameTestHelper helper, JoueurSimule joueur) {
        List<String> fautes = joueur.journal().stream()
                .filter(ligne -> ligne.startsWith("E-") || ligne.contains("redemandée") || ligne.contains("inattendu"))
                .toList();
        helper.assertTrue(fautes.isEmpty(), "le client simulé a relevé : " + fautes);
    }

    /**
     * T-410 dans le harnais : un cube lâché de six blocs tombe chez le serveur. Le joueur simulé reçoit, par
     * le vrai canal, chacun de ses états tels que le serveur les tenait au tick qu'ils portent ; ses
     * acquittements, repassés par Forge, atteignent l'émetteur ; son interpolation finit où le serveur pose
     * le cube. À sa sortie, sa synchronisation s'oublie.
     */
    @GameTest(template = "test/plancher", batch = "harnais_reseau_t410", timeoutTicks = 300)
    public static void t410_unJoueurSimuleSuitUnCubeLache(GameTestHelper helper) {
        Vec3 lache = new Vec3(1.5, DESSUS_DALLE + 6, 1.5);
        long acquittementsAvant = metrique("acks_received", true, "count");
        Scene scene = Scene.poser(helper, lache, new Vec3(4.5, DESSUS_DALLE, 4.5), "sim-t410");
        helper.runAfterDelay(120, () -> {
            AxionEntity cube = scene.cube;
            JoueurSimule joueur = scene.joueur;
            EtatsDuServeur serveur = scene.serveur;
            double depart = helper.absoluteVec(lache).y;
            try {
                scene.tick(helper);
                helper.assertTrue(depart - cube.getY() >= 2.0,
                        "le cube n'est pas tombé chez le serveur : " + depart + " → " + cube.getY());
                int verifies = serveur.verifier(helper, joueur, 0);
                helper.assertTrue(verifies >= 10, "trop peu d'états reçus : " + verifies);
                sansFaute(helper, joueur);
                PlayerSync synchronisation = joueur.synchronisation();
                helper.assertTrue(synchronisation != null, "le joueur simulé n'a pas de synchronisation");
                helper.assertTrue(synchronisation.sender().acked() == synchronisation.sender().sequence(),
                        "acquitté " + synchronisation.sender().acked() + " sur " + synchronisation.sender().sequence()
                                + " : les acquittements n'atteignent pas l'émetteur");
                long comptes = metrique("acks_received", true, "count") - acquittementsAvant;
                helper.assertTrue(comptes == joueur.acquittements(), "axion.net.acks_received a compté " + comptes
                        + " acquittements, le client en a envoyé " + joueur.acquittements());
                Interpolation.Pose pose = joueur.pose(cube.getId());
                helper.assertTrue(pose != null && Math.abs(pose.y() - cube.getY()) <= 0.01,
                        "le client simulé affiche " + (pose == null ? "rien" : pose.y()) + ", le serveur pose le cube à "
                                + cube.getY());
            } finally {
                joueur.quitter();
            }
            helper.assertTrue(joueur.synchronisation() == null, "la synchronisation survit à la sortie du joueur");
            helper.assertTrue(helper.getLevel().getServer().getPlayerList().getPlayer(joueur.joueur().getUUID()) == null,
                    "le joueur simulé est resté sur le serveur");
            helper.succeed();
        });
    }

    /**
     * T-422 dans le harnais : des trames fautives arrivent du client par le vrai canal — drapeaux inconnus,
     * trame vide, message vide. Le serveur les ignore et les compte, sans lever ni couper la connexion, et le
     * joueur continue de recevoir et d'acquitter.
     */
    @GameTest(template = "test/plancher", batch = "harnais_reseau_t422", timeoutTicks = 120)
    public static void t422_desTramesFautivesSontIgnoreesSansCouperLaConnexion(GameTestHelper helper) {
        long refusesAvant = metrique("packets_rejected", true, "count");
        Scene scene = Scene.poser(helper, new Vec3(1.5, DESSUS_DALLE + 4, 1.5), new Vec3(4.5, DESSUS_DALLE, 4.5),
                "sim-t422");
        helper.runAfterDelay(20, () -> {
            scene.joueur.envoyer(new byte[] {(byte) 0xFF, 0x13, 0x37});
            scene.joueur.envoyer(new byte[0]);
            scene.joueur.envoyer(new byte[] {0x00});
        });
        helper.runAfterDelay(80, () -> {
            try {
                scene.tick(helper);
                helper.assertTrue(scene.joueur.connecte()
                                && helper.getLevel().getServer().getPlayerList().getPlayer(scene.joueur.joueur().getUUID()) != null,
                        "une trame fautive a coupé la connexion");
                long refuses = metrique("packets_rejected", true, "count") - refusesAvant;
                helper.assertTrue(refuses >= 3, "trois trames fautives, " + refuses + " refus comptés");
                PlayerSync synchronisation = scene.joueur.synchronisation();
                helper.assertTrue(synchronisation.sender().acked() == synchronisation.sender().sequence(),
                        "après les trames fautives, l'acquittement ne suit plus");
                scene.serveur.verifier(helper, scene.joueur, 0);
            } finally {
                scene.joueur.quitter();
            }
            helper.succeed();
        });
    }

    /**
     * R-1690 dans le harnais : un client renvoie en boucle son dernier acquittement, deux cents trames par
     * seconde pour une limite de quarante. Le serveur ignore et compte ce qui dépasse, et le déconnecte au bout
     * de cinq secondes d'abus — pas avant.
     */
    @GameTest(template = "test/plancher", batch = "harnais_reseau_r1690", timeoutTicks = 260)
    public static void r1690_unClientQuiInondeLeServeurEstDeconnecte(GameTestHelper helper) {
        long limiteesAvant = metrique("packets_limited", true, "count");
        long refusesAvant = metrique("packets_rejected", true, "count");
        Scene scene = Scene.poser(helper, new Vec3(1.5, DESSUS_DALLE + 4, 1.5), new Vec3(4.5, DESSUS_DALLE, 4.5),
                "sim-r1690");
        long[] debut = {-1};
        helper.onEachTick(() -> {
            if (scene.joueur == null || !scene.joueur.connecte() || scene.joueur.dernierAcquittement() == null) {
                return;
            }
            if (debut[0] < 0) {
                debut[0] = helper.getTick();
            }
            for (int index = 0; index < 10; index++) {
                scene.joueur.envoyer(scene.joueur.dernierAcquittement());
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(debut[0] >= 0, "l'inondation n'a pas commencé");
            helper.assertTrue(helper.getLevel().getServer().getPlayerList().getPlayer(scene.joueur.joueur().getUUID()) == null,
                    "le client qui inonde le serveur est encore connecté");
            long secondes = (helper.getTick() - debut[0]) / TICKS_PAR_SECONDE;
            helper.assertTrue(secondes >= PlayerSync.ABUSE_SECONDS,
                    "déconnecté après " + secondes + " s : un abus plus bref que " + PlayerSync.ABUSE_SECONDS
                            + " s ne déconnecte pas");
            long limitees = metrique("packets_limited", true, "count") - limiteesAvant;
            long refus = metrique("packets_rejected", true, "count") - refusesAvant;
            helper.assertTrue(limitees > 0, "aucune trame comptée comme limitée");
            helper.assertTrue(refus >= PlayerSync.ABUSE_SECONDS, "une ligne par seconde d'abus au journal, "
                    + refus + " comptée(s)");
        });
    }

    /**
     * Un client que le serveur déconnecte lit encore ce qu'il avait reçu avant, mais n'envoie plus rien —
     * comme un vrai client. Le client simulé de ce harnais renvoyait l'acquittement d'une trame lue après la
     * fermeture sur la connexion fermée, et {@code r1690} échouait sur une {@code ClosedChannelException}
     * selon que la dernière trame du serveur précédait ou non la déconnexion. Le client cesse de lire dix
     * ticks — une trame au moins l'attend alors, même à 2 Hz —, puis la connexion se ferme et il lit.
     */
    @GameTest(template = "test/plancher", batch = "harnais_reseau_deconnexion", timeoutTicks = 100)
    public static void unClientDeconnecteLitSansRienEnvoyer(GameTestHelper helper) {
        AssembliesDeTest.poser(helper, CUBE, new Vec3(1.5, DESSUS_DALLE + 4, 1.5), DROIT);
        JoueurSimule joueur = JoueurSimule.rejoindre(helper.getLevel(), "sim-deconnexion",
                helper.absoluteVec(new Vec3(4.5, DESSUS_DALLE, 4.5)));
        int[] tick = {0};
        helper.onEachTick(() -> {
            tick[0]++;
            if (tick[0] < 20) {
                joueur.tick();
            } else if (tick[0] == 30) {
                int recues = joueur.tramesRecues();
                int acquittements = joueur.acquittements();
                helper.assertTrue(acquittements > 0, "aucun acquittement avant la déconnexion");
                joueur.joueur().connection.disconnect(Component.literal("déconnexion du test"));
                helper.assertTrue(!joueur.connecte(), "la connexion est restée ouverte");
                joueur.tick();
                helper.assertTrue(joueur.tramesRecues() > recues, "aucune trame en attente : le cas n'a pas eu lieu");
                helper.assertTrue(joueur.acquittements() == acquittements,
                        "un acquittement est parti sur la connexion fermée");
                joueur.quitter();
                helper.succeed();
            }
        });
    }

    /**
     * Le renvoi du registre en jeu, à travers Forge (ADR-150 D5) : après un rechargement qui change les
     * definitions, le registre repart vers le joueur distant, et son client tient la definition ajoutée ;
     * le retour aux packs repart de même.
     */
    @GameTest(template = "test/plancher", batch = "harnais_reseau_registre", timeoutTicks = 100)
    public static void unRegistreRechargeRepartEnJeu(GameTestHelper helper) {
        AxionRuntime runtime = RuntimeAccess.get();
        long octetsAvant = metrique("registry_bytes_sent", true, "bytes");
        JoueurSimule joueur = JoueurSimule.rejoindre(helper.getLevel(), "sim-registre",
                helper.absoluteVec(new Vec3(4.5, DESSUS_DALLE, 4.5)));
        ResourceAssetSource courantes = RegistreReseauGameTests.definitionsCourantes(helper);
        AssetSource avecUneDePlus = RegistreReseauGameTests.avecUneDefinitionDePlus(courantes);
        try {
            helper.assertTrue(joueur.registres().size() == 1,
                    "le registre de la connexion n'a pas été appliqué : " + joueur.registres().size());
            long aLaConnexion = metrique("registry_bytes_sent", true, "bytes") - octetsAvant;
            helper.assertTrue(aLaConnexion > 0, "les octets du registre de la connexion ne se comptent pas");
            helper.assertTrue(joueur.registres().get(0).ids().equals(runtime.definitions().ids()),
                    "le client tient " + joueur.registres().get(0).ids());
            recharger(runtime, avecUneDePlus);
            joueur.tick();
            helper.assertTrue(joueur.registres().size() == 2, "le registre rechargé n'est pas reparti en jeu");
            DefinitionRegistry tenues = joueur.registres().get(1);
            helper.assertTrue(tenues.get(RegistreReseauGameTests.AJOUTEE).isPresent(),
                    RegistreReseauGameTests.AJOUTEE + " n'atteint pas le client");
            helper.assertTrue(metrique("registry_bytes_sent", true, "bytes") - octetsAvant > aLaConnexion,
                    "les octets du registre renvoyé en jeu ne se comptent pas");
            sansFaute(helper, joueur);
        } finally {
            // Les autres tests retrouvent le registre des packs ; il repart vers le joueur, lui aussi.
            recharger(runtime, courantes);
            joueur.tick();
            joueur.quitter();
        }
        helper.assertTrue(joueur.registres().size() == 3
                        && joueur.registres().get(2).get(RegistreReseauGameTests.AJOUTEE).isEmpty(),
                "le retour aux packs n'est pas reparti en jeu");
        helper.succeed();
    }

    /** Ce que fait le rechargement des definitions côté serveur ({@code AxionForgeEntrypoint}). */
    private static void recharger(AxionRuntime runtime, AssetSource definitions) {
        ServerSnapshot avant = runtime.netSnapshot();
        runtime.onDefinitionReload(definitions);
        AxionNetwork.onRegistryReloaded(avant, runtime.netSnapshot());
    }

    /**
     * La fin du suivi, à travers Minecraft et Forge : le joueur s'éloigne au-delà de la portée de suivi,
     * Minecraft retire le cube de son monde et son client l'oublie ; de retour, il le reçoit en entier,
     * sans rien redemander, et ce qu'il reçoit est l'état du serveur.
     */
    @GameTest(template = "test/plancher", batch = "harnais_reseau_suivi", timeoutTicks = 200)
    public static void unJoueurQuiSEloigneOublieEtRecoitDeNouveau(GameTestHelper helper) {
        Vec3 presDuCube = new Vec3(4.5, DESSUS_DALLE, 4.5);
        Scene scene = Scene.poser(helper, new Vec3(1.5, DESSUS_DALLE + 0.5, 1.5), presDuCube, "sim-suivi");
        Vec3 pres = helper.absoluteVec(presDuCube);
        long[] retour = {Long.MAX_VALUE};
        helper.runAfterDelay(40, () -> {
            helper.assertTrue(scene.joueur.pose(scene.cube.getId()) != null, "le joueur proche n'a rien reçu du cube");
            // Bien au-delà des 32 blocs de suivi du serveur de GameTests (distance de vue de 2 tronçons).
            scene.joueur.joueur().teleportTo(helper.getLevel(), pres.x + 96, pres.y, pres.z, 0f, 0f);
        });
        helper.runAfterDelay(80, () -> {
            helper.assertTrue(scene.joueur.pose(scene.cube.getId()) == null,
                    "le client n'a pas oublié le cube hors de portée");
            helper.assertTrue(!scene.joueur.synchronisation().tracked().contains(scene.cube.getId()),
                    "le serveur suit encore le cube pour un joueur hors de portée");
            retour[0] = helper.getLevel().getServer().getTickCount();
            scene.joueur.joueur().teleportTo(helper.getLevel(), pres.x, pres.y, pres.z, 0f, 0f);
        });
        helper.runAfterDelay(120, () -> {
            JoueurSimule joueur = scene.joueur;
            AxionEntity cube = scene.cube;
            try {
                scene.tick(helper);
                helper.assertTrue(joueur.pose(cube.getId()) != null, "le joueur revenu n'a rien reçu du cube");
                int verifies = scene.serveur.verifier(helper, joueur, retour[0]);
                helper.assertTrue(verifies >= 1, "aucun état reçu depuis le retour");
                sansFaute(helper, joueur);
            } finally {
                joueur.quitter();
            }
            helper.succeed();
        });
    }
}
