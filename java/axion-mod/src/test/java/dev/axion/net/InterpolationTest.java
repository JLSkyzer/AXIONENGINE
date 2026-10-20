package dev.axion.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.Random;
import java.util.function.DoubleFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * L'interpolation de 21.7 (ADR-150 D7), à horloge simulée : Hermite, slerp, extrapolation bornée puis
 * figement (T-414), correction douce ou téléportation, et le mouvement à travers un réseau simulé — retard et
 * gigue (T-413), snapshots jetés à l'émission (T-412).
 */
class InterpolationTest {

    private static final double EXTRAPOLATION_MS = 250;
    private static final double SNAP = 2.0;
    private static final float[] DROIT = {0f, 0f, 0f, 1f};

    private static AssemblyState etat(double[] p, double[] v, float[] q, float[] w) {
        return AssemblyState.of(p, q, new float[] {(float) v[0], (float) v[1], (float) v[2]}, w,
                new float[] {-0.5f, -0.5f, -0.5f}, new float[] {0.5f, 0.5f, 0.5f}, false);
    }

    private static AssemblyState etat(double x, double vx) {
        return etat(new double[] {x, 64, 0}, new double[] {vx, 0, 0}, DROIT, new float[3]);
    }

    private static float[] autourDeY(double radians) {
        return new float[] {0f, (float) Math.sin(radians / 2), 0f, (float) Math.cos(radians / 2)};
    }

    private static double angleAutourDeY(float[] q) {
        return 2 * Math.atan2(q[1], q[3]);
    }

    @Test
    @DisplayName("21.7 : Hermite sur position et vitesse reproduit une chute, aux pas de la quantification près")
    void hermiteReproduitUneChute() {
        Interpolation interpolation = new Interpolation(EXTRAPOLATION_MS, SNAP);
        DoubleFunction<double[]> position = t -> new double[] {5 * t, 80 + 3 * t - 4.905 * t * t, -2 * t};
        DoubleFunction<double[]> vitesse = t -> new double[] {5, 3 - 9.81 * t, -2};
        for (long tick = 0; tick <= 40; tick += 2) {
            double t = tick * 0.05;
            interpolation.push(tick, etat(position.apply(t), vitesse.apply(t), DROIT, new float[3]), 0, 0);
        }
        double pire = 0;
        for (double ms = 0; ms <= 2000; ms += 7) {
            Interpolation.Pose pose = interpolation.sample(ms, ms);
            double[] vrai = position.apply(ms / 1000.0);
            pire = Math.max(pire, Math.max(Math.abs(pose.x() - vrai[0]),
                    Math.max(Math.abs(pose.y() - vrai[1]), Math.abs(pose.z() - vrai[2]))));
        }
        assertTrue(pire < 3e-3, "écart à la trajectoire " + pire + " bloc");
    }

    /** L'angle, en degrés, de la rotation qui mène de {@code q} à l'orientation de {@code attendu} radians autour de Y. */
    private static double ecartAutourDeY(double attendu, float[] q) {
        float[] r = autourDeY(attendu);
        double dot = Math.abs(r[0] * q[0] + r[1] * q[1] + r[2] * q[2] + r[3] * q[3]);
        return Math.toDegrees(2 * Math.acos(Math.min(1.0, dot)));
    }

    @Test
    @DisplayName("21.7 : la rotation passe par slerp, au plus court, et l'emprise glisse")
    void laRotationPasseParSlerp() {
        Interpolation interpolation = new Interpolation(EXTRAPOLATION_MS, SNAP);
        interpolation.push(0, etat(new double[] {0, 64, 0}, new double[3], DROIT, new float[3]), 0, 0);
        interpolation.push(2, AssemblyState.of(new double[] {0, 64, 0}, autourDeY(Math.PI / 2), new float[3],
                new float[3], new float[] {-1.5f, -0.5f, -0.5f}, new float[] {0.5f, 0.5f, 1.5f}, false), 0, 0);
        Interpolation.Pose milieu = interpolation.sample(50, 50);
        assertEquals(Interpolation.Mode.INTERPOLATED, milieu.mode());
        assertEquals(Math.PI / 4, angleAutourDeY(milieu.rotation()), Math.toRadians(0.3));
        assertEquals(-1.0f, milieu.boundsMin()[0], 1e-3f);
        assertEquals(1.0f, milieu.boundsMax()[2], 1e-3f);

        // 170° puis −60° : le plus court passe par 180°, de 130°. Leurs quaternions, chacun avec sa plus grande
        // composante positive comme les rend la quantification, sont de produit scalaire négatif.
        Interpolation court = new Interpolation(EXTRAPOLATION_MS, SNAP);
        court.push(0, etat(new double[] {0, 64, 0}, new double[3], autourDeY(Math.toRadians(170)), new float[3]), 0, 0);
        court.push(2, etat(new double[] {0, 64, 0}, new double[3], autourDeY(Math.toRadians(-60)), new float[3]), 0, 0);
        double ecart = ecartAutourDeY(Math.toRadians(235), court.sample(50, 50).rotation());
        assertTrue(ecart < 0.3, "à mi-chemin du plus court, à " + ecart + "° près");
    }

    @Test
    @DisplayName("T-414 : sans snapshot, l'extrapolation suit les vitesses pendant 250 ms, puis l'assembly se fige")
    void t414_extrapolationBorneePuisFigement() {
        Interpolation interpolation = new Interpolation(EXTRAPOLATION_MS, SNAP);
        interpolation.push(0, etat(new double[] {-4, 64, 0}, new double[] {4, 0, 0}, DROIT, new float[] {0, (float) Math.PI, 0}), 0, 0);
        interpolation.push(20, etat(new double[] {0, 64, 0}, new double[] {4, 0, 0}, DROIT, new float[] {0, (float) Math.PI, 0}), 0, 0);

        Interpolation.Pose a = interpolation.sample(1100, 1100);
        assertEquals(Interpolation.Mode.EXTRAPOLATED, a.mode());
        assertEquals(0.4, a.x(), 2e-3);
        Interpolation.Pose borne = interpolation.sample(1250, 1250);
        assertEquals(Interpolation.Mode.EXTRAPOLATED, borne.mode());
        assertEquals(1.0, borne.x(), 2e-3);
        assertEquals(Math.PI / 4, angleAutourDeY(borne.rotation()), Math.toRadians(0.5), "π rad/s pendant 250 ms");
        Interpolation.Pose figee = interpolation.sample(1251, 1251);
        assertEquals(Interpolation.Mode.FROZEN, figee.mode());
        assertEquals(borne.x(), figee.x(), 1e-12);
        assertEquals(borne.x(), interpolation.sample(5000, 5000).x(), 1e-12, "figée, elle ne bouge plus");
    }

    @Test
    @DisplayName("21.7 : au retour d'un snapshot, l'écart se résorbe en 200 ms ; au-delà de net.snap_threshold, téléportation")
    void leRetourDUnSnapshotCorrigeSansSaut() {
        Interpolation interpolation = new Interpolation(EXTRAPOLATION_MS, SNAP);
        interpolation.push(0, etat(-4, 4), 0, 0);
        interpolation.push(20, etat(0, 4), 0, 0);
        assertEquals(1.0, interpolation.sample(1300, 1300).x(), 2e-3, "figée à +1 après 250 ms d'extrapolation");
        // L'assembly s'est arrêtée à 0,5 : le snapshot arrive, l'affichage ne saute pas.
        interpolation.push(26, etat(0.5, 0), 1300, 1300);
        assertEquals(1.0, interpolation.sample(1300, 1300).x(), 2e-3, "aucun saut à l'arrivée");
        assertEquals(0.75, interpolation.sample(1400, 1400).x(), 2e-3, "à mi-chemin après 100 ms");
        assertEquals(0.5, interpolation.sample(1500, 1500).x(), 2e-3, "résorbé après 200 ms");

        Interpolation loin = new Interpolation(EXTRAPOLATION_MS, SNAP);
        loin.push(0, etat(-4, 4), 0, 0);
        loin.push(20, etat(0, 4), 0, 0);
        loin.sample(1300, 1300);
        loin.push(26, etat(5, 0), 1300, 1300);
        assertEquals(5.0, loin.sample(1300, 1300).x(), 2e-3, "4 blocs d'écart : téléportation");
    }

    @Test
    @DisplayName("21.7 : l'orientation se corrige elle aussi en 200 ms, sans saut")
    void laRotationSeCorrigeSansSaut() {
        Interpolation interpolation = new Interpolation(EXTRAPOLATION_MS, SNAP);
        float[] tourne = {0f, (float) Math.PI, 0f};
        interpolation.push(0, etat(new double[] {0, 64, 0}, new double[3], DROIT, tourne), 0, 0);
        interpolation.push(20, etat(new double[] {0, 64, 0}, new double[3], DROIT, tourne), 0, 0);
        assertEquals(Math.PI / 4, angleAutourDeY(interpolation.sample(1300, 1300).rotation()), Math.toRadians(0.5),
                "figée après π rad/s pendant 250 ms");
        // Elle s'était arrêtée droite : seule l'orientation diffère.
        interpolation.push(26, etat(new double[] {0, 64, 0}, new double[3], DROIT, new float[3]), 1300, 1300);
        assertEquals(Math.PI / 4, angleAutourDeY(interpolation.sample(1300, 1300).rotation()), Math.toRadians(0.5),
                "aucun saut à l'arrivée");
        assertEquals(Math.PI / 8, angleAutourDeY(interpolation.sample(1400, 1400).rotation()), Math.toRadians(0.5));
        assertEquals(0, angleAutourDeY(interpolation.sample(1500, 1500).rotation()), Math.toRadians(0.5));
    }

    @Test
    @DisplayName("L'extrapolation et le seuil de téléportation sont ceux de la configuration du client")
    void lesReglagesSontCeuxDuClient() {
        Interpolation interpolation = Interpolation.of(RenderClockTest.config(
                Map.of("net.extrapolation_max_ms", 400L, "net.snap_threshold", 0.5)));
        interpolation.push(0, etat(-4, 4), 0, 0);
        interpolation.push(20, etat(0, 4), 0, 0);
        Interpolation.Pose pose = interpolation.sample(1400, 1400);
        assertEquals(Interpolation.Mode.EXTRAPOLATED, pose.mode());
        assertEquals(1.6, pose.x(), 2e-3, "4 m/s pendant 400 ms");
        // Arrêtée à 0,9 : 0,7 bloc d'écart, au-delà de 0,5 : téléportation.
        interpolation.push(28, etat(0.9, 0), 1400, 1400);
        assertEquals(0.9, interpolation.sample(1400, 1400).x(), 2e-3);
    }

    @Test
    @DisplayName("Avant tout snapshot, rien ; le premier est tenu ; le tampon est borné")
    void avantToutSnapshotRien() {
        Interpolation interpolation = new Interpolation(EXTRAPOLATION_MS, SNAP);
        assertNull(interpolation.sample(0, 0));
        interpolation.push(10, etat(3, 0), 0, 0);
        Interpolation.Pose tenue = interpolation.sample(100, 100);
        assertEquals(Interpolation.Mode.HELD, tenue.mode());
        assertEquals(3.0, tenue.x(), 2e-3);
        interpolation.push(10, etat(9, 0), 100, 100);
        interpolation.push(4, etat(9, 0), 100, 100);
        assertEquals(1, interpolation.buffered(), "un snapshot qui n'est pas plus récent que le dernier est ignoré");
        assertEquals(3.0, interpolation.sample(100, 100).x(), 2e-3);
        for (long tick = 11; tick < 200; tick++) {
            interpolation.push(tick, etat(tick, 0), 0, 0);
        }
        assertEquals(Interpolation.MAX_SAMPLES, interpolation.buffered());
    }

    /** Un réseau simulé : le serveur émet à 10 Hz ; chaque snapshot arrive après un retard, dans l'ordre. */
    private static final class Reseau {
        final ServerClock horloge = new ServerClock();
        final Interpolation interpolation = new Interpolation(EXTRAPOLATION_MS, SNAP);
        final ArrayDeque<double[]> enRoute = new ArrayDeque<>();
        final double delai;
        double derniereArrivee;

        Reseau(double delaiInterpolation) {
            this.delai = delaiInterpolation;
        }

        void emettre(long tick, double arriveeMs) {
            derniereArrivee = Math.max(derniereArrivee, arriveeMs);
            enRoute.addLast(new double[] {tick, derniereArrivee});
        }

        /** Une image du client à {@code maintenant} : les arrivées, puis la pose. */
        Interpolation.Pose image(double maintenant, DoubleFunction<AssemblyState> etatA) {
            while (!enRoute.isEmpty() && enRoute.peekFirst()[1] <= maintenant) {
                double[] arrivee = enRoute.removeFirst();
                long tick = (long) arrivee[0];
                horloge.onSnapshot(tick, (long) (arrivee[1] * 1e6));
                interpolation.push(tick, etatA.apply(tick * 50.0), rendu(maintenant), maintenant);
            }
            return horloge.known() ? interpolation.sample(rendu(maintenant), maintenant) : null;
        }

        double rendu(double maintenant) {
            return horloge.serverMillis((long) (maintenant * 1e6)) - delai;
        }
    }

    @Test
    @DisplayName("T-413 : 200 ms de retard et ±30 ms de gigue, le mouvement s'affiche sans saccade")
    void t413_latenceSansSaccade() {
        Random random = new Random(413);
        Reseau reseau = new Reseau(100);
        DoubleFunction<AssemblyState> etat = ms -> etat(5 * ms / 1000.0, 5);
        for (long tick = 0; tick <= 400; tick += 2) {
            reseau.emettre(tick, tick * 50.0 + 200 + (random.nextDouble() * 60 - 30));
        }
        double frame = 1000.0 / 60;
        double precedente = Double.NaN;
        double pireSaut = 0;
        double pireEcart = 0;
        for (double maintenant = 0; maintenant < 20_000; maintenant += frame) {
            Interpolation.Pose pose = reseau.image(maintenant, etat);
            if (pose == null || maintenant < 1500) {
                precedente = pose == null ? Double.NaN : pose.x();
                continue;
            }
            if (!Double.isNaN(precedente)) {
                pireSaut = Math.max(pireSaut, Math.abs(pose.x() - precedente - 5 * frame / 1000.0));
            }
            precedente = pose.x();
            double rendu = reseau.rendu(maintenant);
            if (rendu <= 400 * 50.0) {
                pireEcart = Math.max(pireEcart, Math.abs(pose.x() - 5 * rendu / 1000.0));
            }
        }
        System.out.printf("T-413 : écart d'une image au pas attendu au plus %.4f bloc, à la trajectoire %.4f bloc%n",
                pireSaut, pireEcart);
        assertTrue(pireSaut < 0.02, "saccade de " + pireSaut + " bloc entre deux images");
        assertTrue(pireEcart < 0.02, "écart à la trajectoire " + pireEcart + " bloc");
    }

    @Test
    @DisplayName("T-412 : 20 % des snapshots jetés à l'émission, une trajectoire courbe reste plausible")
    void t412_pertePlausible() {
        Random random = new Random(412);
        Reseau reseau = new Reseau(100);
        // Un cercle de 5 blocs à 1 rad/s : vitesse tangente de 5 m/s.
        DoubleFunction<AssemblyState> etat = ms -> {
            double t = ms / 1000.0;
            return etat(new double[] {5 * Math.cos(t), 64, 5 * Math.sin(t)},
                    new double[] {-5 * Math.sin(t), 0, 5 * Math.cos(t)}, DROIT, new float[3]);
        };
        int jetes = 0;
        int serie = 0;
        int pireSerie = 0;
        for (long tick = 0; tick <= 400; tick += 2) {
            if (random.nextDouble() < 0.2) {
                jetes++;
                pireSerie = Math.max(pireSerie, ++serie);
                continue;
            }
            serie = 0;
            reseau.emettre(tick, tick * 50.0 + 50);
        }
        // Un snapshot arrive quand le rendu est 100 ms derrière lui ; après n pertes, le suivant arrive
        // n × 100 ms plus tard. Le rendu extrapole jusque-là, 250 ms au plus, puis fige : voilà l'écart au
        // cercle que 21.7 admet au bout du plus long trou. L'interpolation ne doit rien y ajouter.
        double trou = pireSerie * 0.1;
        double admis = Math.hypot(5 - 5 * Math.cos(trou), 5 * Math.min(trou, 0.25) - 5 * Math.sin(trou));
        double frame = 1000.0 / 60;
        double pire = 0;
        double pirePas = 0;
        double[] precedente = null;
        for (double maintenant = 0; maintenant < 20_000; maintenant += frame) {
            Interpolation.Pose pose = reseau.image(maintenant, etat);
            double rendu = reseau.rendu(maintenant);
            if (pose == null || maintenant < 1500 || rendu > 400 * 50.0) {
                precedente = null;
                continue;
            }
            double t = rendu / 1000.0;
            pire = Math.max(pire, Math.hypot(pose.x() - 5 * Math.cos(t), pose.z() - 5 * Math.sin(t)));
            if (precedente != null) {
                pirePas = Math.max(pirePas, Math.hypot(pose.x() - precedente[0], pose.z() - precedente[1]));
            }
            precedente = new double[] {pose.x(), pose.z()};
        }
        // Le pas d'une image : 5 m/s pendant 1/60 s, plus la correction qui résorbe l'écart admis en 200 ms.
        double pasAdmis = 5 * frame / 1000 + admis * frame / Interpolation.CORRECTION_MILLIS;
        System.out.printf("T-412 : %d snapshot(s) jeté(s) sur 201, jusqu'à %d de suite ; écart à la trajectoire au "
                + "plus %.4f bloc (admis %.4f), pas d'une image au plus %.4f bloc (admis %.4f)%n",
                jetes, pireSerie, pire, admis, pirePas, pasAdmis);
        assertTrue(jetes > 20, "le scénario doit jeter des snapshots : " + jetes);
        assertTrue(pireSerie >= 3, "le scénario doit dépasser la borne d'extrapolation : " + pireSerie);
        // 0,01 bloc de marge : quantification, et reliquat d'une correction d'un trou précédent.
        assertTrue(pire <= admis + 0.01, "écart à la trajectoire " + pire + " bloc, admis " + admis);
        assertTrue(pirePas <= pasAdmis + 0.005, "pas d'une image de " + pirePas + " bloc : téléportation");
    }

    @Test
    @DisplayName("L'horloge du serveur vise l'arrivée la moins retardée, sur une fenêtre glissante")
    void lHorlogeViseLArriveeLaMoinsRetardee() {
        ServerClock horloge = new ServerClock();
        assertTrue(Double.isNaN(horloge.serverMillis(0)));
        assertTrue(Double.isNaN(horloge.targetMillis(0)));
        // Ticks 0, 2, 4 arrivés après 120, 80 et 150 ms : le décalage visé est celui de 80 ms.
        horloge.onSnapshot(0, 120_000_000L);
        horloge.onSnapshot(2, 180_000_000L);
        horloge.onSnapshot(4, 350_000_000L);
        assertEquals(500 - 80, horloge.targetMillis(500_000_000L), 1e-6);
        // Deux secondes plus tard, l'arrivée rapide est sortie de la fenêtre : le serveur a pris du retard.
        horloge.onSnapshot(50, 2_700_000_000L);
        assertEquals(2700 - 200, horloge.targetMillis(2_700_000_000L), 1e-6);
    }

    @Test
    @DisplayName("L'estimation rejoint la cible en glissant de 5 % au plus, et ne saute qu'au-delà de 250 ms")
    void lEstimationGlisseVersLaCible() {
        ServerClock horloge = new ServerClock();
        horloge.onSnapshot(20, 1_100_000_000L);
        assertEquals(1000, horloge.serverMillis(1_100_000_000L), 1e-6, "la première estimation est la cible");
        // Une arrivée moins retardée de 10 ms : l'estimation avance de 105 ms par 100 ms jusqu'à la rejoindre.
        horloge.onSnapshot(22, 1_190_000_000L);
        assertEquals(1105, horloge.serverMillis(1_200_000_000L), 1e-6);
        assertEquals(1105, horloge.serverMillis(1_200_000_000L), 1e-6, "même heure, même valeur");
        assertEquals(1210, horloge.serverMillis(1_300_000_000L), 1e-6);
        assertEquals(1310, horloge.serverMillis(1_400_000_000L), 1e-6, "rejointe, elle suit le temps local");
        // La cible recule de 110 ms : l'estimation ralentit à 95 ms par 100 ms, sans reculer.
        assertEquals(3310, horloge.serverMillis(3_400_000_000L), 1e-6);
        horloge.onSnapshot(66, 3_500_000_000L);
        assertEquals(3405, horloge.serverMillis(3_500_000_000L), 1e-6);
        assertEquals(3500, horloge.serverMillis(3_600_000_000L), 1e-6);
        // 500 ms d'écart : glisser prendrait dix secondes, elle saute.
        horloge.onSnapshot(108, 6_000_000_000L);
        assertEquals(5400, horloge.serverMillis(6_000_000_000L), 1e-6);
    }
}
