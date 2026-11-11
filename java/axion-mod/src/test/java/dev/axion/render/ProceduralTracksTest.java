package dev.axion.render;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.axion.net.AssemblyState;
import dev.axion.net.Interpolation;
import dev.axion.net.Interpolation.WheelPose;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-159 D9 : l'évaluation au client des pistes {@code procedural} (§18.3). */
class ProceduralTracksTest {

    private static final List<String> NOMS = List.of("caisse", "roue_g", "jante_g", "roue_d");

    private static ProceduralTracks pistes(String pistes, List<String> journal) {
        JsonObject racine = JsonParser.parseString(
                "{\"wheels\":[{\"node\":\"roue_g\"},{\"node\":\"roue_d\"}],\"procedural\":[" + pistes + "]}")
                .getAsJsonObject();
        return ProceduralTracks.of(racine, NOMS, journal::add);
    }

    private static final List<WheelPose> ROUES =
            List.of(new WheelPose(0.5f, 10f, 0.25f, true), new WheelPose(-0.5f, 0f, 0f, false));

    private static ProceduralTracks.Frame roues(List<WheelPose> roues, double[] angles) {
        return ProceduralTracks.Frame.ofWheels(roues, angles);
    }

    @Test
    @DisplayName("scale : la compression devient une translation sur son axe")
    void scale() {
        ProceduralTracks pistes = pistes(
                "{\"node\":\"roue_g\",\"channel\":\"translation\",\"axis\":\"y\","
                        + "\"source\":\"wheel.roue_g.compression\",\"scale\":0.2}", new ArrayList<>());
        ProceduralTracks.Pose pose = pistes.evaluate(roues(ROUES, new double[2]));
        assertArrayEquals(new int[] {1}, pose.nodes());
        assertArrayEquals(new float[] {0f, 0.1f, 0f, 0f, 0f, 0f, 1f}, pose.deltas(), 1e-6f);
    }

    @Test
    @DisplayName("map tient ses bouts, et clamp borne après scale")
    void mapEtClamp() {
        String piste = "{\"node\":\"roue_g\",\"channel\":\"translation\",\"axis\":\"x\","
                + "\"source\":\"wheel.roue_g.compression\",\"map\":[[0,0],[1,2]],\"scale\":3,\"clamp\":[-1,2.5]}";
        ProceduralTracks pistes = pistes(piste, new ArrayList<>());
        assertEquals(2.5f, pistes.evaluate(roues(ROUES, new double[2])).deltas()[0], 1e-6f, "1,0 × 3 borné à 2,5");
        assertEquals(0f, pistes.evaluate(roues(List.of(new WheelPose(-5f, 0f, 0f, true), ROUES.get(1)), new double[2]))
                .deltas()[0], 1e-6f, "sous le premier point : tenu");
    }

    @Test
    @DisplayName("une rotation se compose dans l'ordre de déclaration, depuis l'angle intégré")
    void rotation() {
        ProceduralTracks pistes = pistes(
                "{\"node\":\"roue_g\",\"channel\":\"rotation\",\"axis\":\"y\",\"source\":\"wheel.roue_g.steer_angle\"},"
                        + "{\"node\":\"roue_g\",\"channel\":\"rotation\",\"axis\":\"x\","
                        + "\"source\":\"wheel.roue_g.spin_angle\",\"scale\":-1}", new ArrayList<>());
        float[] d = pistes.evaluate(roues(ROUES, new double[] {1.0, 0.0})).deltas();
        double sy = Math.sin(0.125);
        double cy = Math.cos(0.125);
        double sx = Math.sin(-0.5);
        double cx = Math.cos(-0.5);
        assertEquals(cy * sx, d[3], 1e-5);
        assertEquals(sy * cx, d[4], 1e-5);
        assertEquals(-sy * sx, d[5], 1e-5);
        assertEquals(cy * cx, d[6], 1e-5);
    }

    @Test
    @DisplayName("une source inconnue de ce jeu est ignorée, une seule fois au journal")
    void sourceInconnue() {
        List<String> journal = new ArrayList<>();
        String inconnue = "{\"node\":\"roue_g\",\"channel\":\"rotation\",\"axis\":\"z\",\"source\":\"body.pitch\"}";
        ProceduralTracks pistes = pistes(inconnue + "," + inconnue, journal);
        assertSame(ProceduralTracks.NONE, pistes);
        assertTrue(pistes.isEmpty());
        assertEquals(1, journal.size(), journal.toString());
        assertNull(pistes.evaluate(roues(ROUES, new double[2])));
    }

    @Test
    @DisplayName("un node absent ou une map non croissante écartent la piste")
    void pistesMalFormees() {
        List<String> journal = new ArrayList<>();
        pistes("{\"node\":\"absent\",\"channel\":\"translation\",\"axis\":\"y\",\"source\":\"wheel.roue_g.compression\"},"
                + "{\"node\":\"roue_g\",\"channel\":\"translation\",\"axis\":\"y\","
                + "\"source\":\"wheel.roue_g.compression\",\"map\":[[1,0],[0,1]]}", journal);
        assertEquals(2, journal.size(), journal.toString());
    }

    private static ProceduralTracks pistesDuVehicule(String pistes, List<String> journal) {
        JsonObject racine = JsonParser.parseString("{\"wheels\":[{\"node\":\"roue_g\"},{\"node\":\"roue_d\"}],"
                + "\"seats\":[{\"name\":\"conducteur\"},{\"name\":\"passager\"}],\"procedural\":[" + pistes + "]}")
                .getAsJsonObject();
        return ProceduralTracks.of(racine, NOMS, journal::add);
    }

    private static final Interpolation.VehiclePose VEHICULE =
            new Interpolation.VehiclePose(3000f, 0.4f, -1, AssemblyState.Vehicle.RUNNING, 0.6f, 0.2f, -0.5f, 1f, 12f, -11f);

    private static ProceduralTracks.Frame cadre(double secondes, float heure, float ciel, float pluie) {
        return new ProceduralTracks.Frame(ROUES, new double[2], VEHICULE, AssemblyState.VariableValues.NONE,
                new boolean[] {true, false}, secondes, heure, ciel, pluie);
    }

    private static float translation(ProceduralTracks pistes, ProceduralTracks.Frame cadre) {
        return pistes.evaluate(cadre).deltas()[0];
    }

    @Test
    @DisplayName("ADR-163 D1 : le volant tourne avec vehicle.steer, la position de la direction")
    void volant() {
        List<String> journal = new ArrayList<>();
        ProceduralTracks pistes = pistesDuVehicule("{\"node\":\"caisse\",\"channel\":\"rotation\",\"axis\":\"z\","
                + "\"source\":\"vehicle.steer\",\"scale\":-5}", journal);
        assertTrue(journal.isEmpty(), journal.toString());
        float[] d = pistes.evaluate(cadre(0, 0f, 0f, 0f)).deltas();
        double demi = -0.5 * -5.0 / 2;
        assertEquals(Math.sin(demi), d[5], 1e-5, "2,5 rad autour de z");
        assertEquals(Math.cos(demi), d[6], 1e-5);
    }

    @Test
    @DisplayName("ADR-163 D1 : chaque source du véhicule, du moteur, de la boîte et des sièges se lit")
    void sourcesDuVehicule() {
        String[][] attendus = {
            {"vehicle.speed", "12"}, {"vehicle.forward_speed", "-11"}, {"vehicle.throttle", "0.6"},
            {"vehicle.brake", "0.2"}, {"vehicle.steer", "-0.5"}, {"vehicle.handbrake", "1"},
            {"engine.rpm", "3000"}, {"engine.load", "0.4"}, {"engine.running", "1"},
            {"transmission.gear", "-1"}, {"transmission.shifting", "0"},
            {"seat.conducteur.occupied", "1"}, {"seat.passager.occupied", "0"},
            {"wheel.roue_g.on_ground", "1"}, {"wheel.roue_d.on_ground", "0"},
            {"time.seconds", "4.5"}, {"world.time", "0.75"}, {"world.sky_light", "11"}, {"world.rain", "0.25"},
        };
        for (String[] attendu : attendus) {
            List<String> journal = new ArrayList<>();
            ProceduralTracks pistes = pistesDuVehicule("{\"node\":\"caisse\",\"channel\":\"translation\",\"axis\":\"x\","
                    + "\"source\":\"" + attendu[0] + "\"}", journal);
            assertTrue(journal.isEmpty(), attendu[0] + " : " + journal);
            assertEquals(Float.parseFloat(attendu[1]), translation(pistes, cadre(4.5, 0.75f, 11f, 0.25f)), 1e-5f,
                    attendu[0]);
        }
    }

    @Test
    @DisplayName("ADR-163 D1 : un siège inconnu, une source sans producteur ou une variable non déclarée sont ignorés")
    void sourcesNonPrisesEnCharge() {
        for (String source : new String[] {"seat.absent.occupied", "wheel.roue_g.slip_long", "var.inconnue",
                "engine.temperature", "world", "vehicle.speed.x"}) {
            List<String> journal = new ArrayList<>();
            assertSame(ProceduralTracks.NONE, pistesDuVehicule("{\"node\":\"caisse\",\"channel\":\"translation\","
                    + "\"axis\":\"x\",\"source\":\"" + source + "\"}", journal), source);
            assertEquals(1, journal.size(), source + " : " + journal);
        }
    }

    @Test
    @DisplayName("ADR-163 D1 : une rotation menée par time.seconds reste exacte après des jours de jeu")
    void rotationAuLongCours() {
        ProceduralTracks pistes = pistesDuVehicule("{\"node\":\"caisse\",\"channel\":\"rotation\",\"axis\":\"y\","
                + "\"source\":\"time.seconds\",\"scale\":3.0}", new ArrayList<>());
        double secondes = 40.0 * 86400.0 + 0.125;
        float[] d = pistes.evaluate(cadre(secondes, 0f, 0f, 0f)).deltas();
        double angle = Math.IEEEremainder(3.0 * secondes, 2.0 * Math.PI);
        assertEquals(Math.sin(angle / 2), d[4], 1e-5, "sin de la moitié de l'angle ramené");
        assertEquals(Math.cos(angle / 2), d[6], 1e-5);
    }

    @Test
    @DisplayName("ADR-163 D1 : world.time vaut 0 à minuit et 0,5 à midi, depuis le temps du jour de Minecraft")
    void heureDuJour() {
        assertEquals(0.25f, ProceduralTracks.worldTime(0L), 1e-6f, "le lever de Minecraft, 6 h");
        assertEquals(0.5f, ProceduralTracks.worldTime(6000L), 1e-6f, "midi");
        assertEquals(0f, ProceduralTracks.worldTime(18000L), 1e-6f, "minuit");
        assertEquals(0.5f, ProceduralTracks.worldTime(6000L + 24000L * 1000L), 1e-6f, "mille jours plus tard");
        assertEquals(0.75f, ProceduralTracks.worldTime(-12000L), 1e-6f, "un temps négatif reste dans le jour : 18 h");
    }

    @Test
    @DisplayName("ADR-163 D1 : sans état de roue, une piste de roue laisse son node au repos, les autres s'évaluent")
    void sansRoues() {
        ProceduralTracks pistes = pistesDuVehicule("{\"node\":\"roue_g\",\"channel\":\"translation\",\"axis\":\"y\","
                + "\"source\":\"wheel.roue_g.compression\"},{\"node\":\"caisse\",\"channel\":\"translation\","
                + "\"axis\":\"x\",\"source\":\"engine.rpm\",\"scale\":0.001}", new ArrayList<>());
        ProceduralTracks.Frame sans = new ProceduralTracks.Frame(List.of(), new double[0], VEHICULE,
                AssemblyState.VariableValues.NONE, new boolean[0], 0.0, 0f, 0f, 0f);
        ProceduralTracks.Pose pose = pistes.evaluate(sans);
        assertArrayEquals(new int[] {1, 0}, pose.nodes());
        assertArrayEquals(new float[] {0f, 0f, 0f, 0f, 0f, 0f, 1f, 3f, 0f, 0f, 0f, 0f, 0f, 1f}, pose.deltas(), 1e-6f);
    }

    @Test
    @DisplayName("l'angle s'intègre au tick de rendu, sans jamais reculer")
    void integration() {
        ProceduralTracks.Spin spin = new ProceduralTracks.Spin();
        List<WheelPose> roues = List.of(new WheelPose(0f, 2f, 0f, true));
        assertEquals(0.0, spin.advance(100.0, roues)[0], 1e-9, "la première image ne fait que dater");
        assertEquals(0.2, spin.advance(102.0, roues)[0], 1e-9, "2 rad/s pendant 2 ticks");
        assertEquals(0.2, spin.advance(101.0, roues)[0], 1e-9, "un tick de rendu qui recule n'ajoute rien");
    }

    @Test
    @DisplayName("ADR-163 D2 : var.<n> lit la variable reçue, son défaut tant qu'elle ne l'est pas")
    void variables() {
        JsonObject racine = JsonParser.parseString("{\"variables\":{\"phares\":{\"type\":\"int\",\"default\":1},"
                + "\"cote.gauche\":{\"type\":\"float\"}},\"procedural\":["
                + "{\"node\":\"caisse\",\"channel\":\"translation\",\"axis\":\"x\",\"source\":\"var.phares\"},"
                + "{\"node\":\"caisse\",\"channel\":\"translation\",\"axis\":\"y\",\"source\":\"var.cote.gauche\"}]}")
                .getAsJsonObject();
        List<String> journal = new ArrayList<>();
        ProceduralTracks pistes = ProceduralTracks.of(racine, NOMS, journal::add);
        assertTrue(journal.isEmpty(), journal.toString());
        ProceduralTracks.Frame rien = ProceduralTracks.Frame.ofWheels(List.of(), new double[0]);
        float[] d = pistes.evaluate(rien).deltas();
        assertEquals(1f, d[0], 1e-6f, "pas encore reçue : son défaut");
        assertEquals(0f, d[1], 1e-6f);
        AssemblyState.VariableValues recues = new AssemblyState.VariableValues(2, 0b11L, new float[] {2f, 0.5f});
        ProceduralTracks.Frame cadre = new ProceduralTracks.Frame(List.of(), new double[0],
                Interpolation.VehiclePose.NONE, recues, new boolean[0], 0.0, 0f, 0f, 0f);
        d = pistes.evaluate(cadre).deltas();
        assertEquals(2f, d[0], 1e-6f, "reçue");
        assertEquals(0.5f, d[1], 1e-6f, "un nom à point");
        ProceduralTracks.Frame filtre = new ProceduralTracks.Frame(List.of(), new double[0],
                Interpolation.VehiclePose.NONE, recues.only(0b10L), new boolean[0], 0.0, 0f, 0f, 0f);
        assertEquals(1f, pistes.evaluate(filtre).deltas()[0], 1e-6f, "retirée pour ce joueur : son défaut");
    }

    /** Une definition à variables et pistes données, sur les nodes de {@link #NOMS}. */
    private static ProceduralTracks apparence(String pistes, List<String> journal) {
        JsonObject racine = JsonParser.parseString("{\"variables\":{\"gyrophare\":{\"type\":\"bool\"}},"
                + "\"procedural\":[" + pistes + "]}").getAsJsonObject();
        return ProceduralTracks.of(racine, NOMS, journal::add);
    }

    private static ProceduralTracks.Frame instant(double secondes, float gyrophare) {
        return new ProceduralTracks.Frame(List.of(), new double[0], Interpolation.VehiclePose.NONE,
                new AssemblyState.VariableValues(1, 1L, new float[] {gyrophare}), new boolean[0], secondes, 0f, 0f, 0f);
    }

    @Test
    @DisplayName("ADR-163 D3 : le gyrophare de l'ADR — emissive en créneau sur time.seconds, gate var.gyrophare")
    void gyrophare() {
        List<String> journal = new ArrayList<>();
        ProceduralTracks pistes = apparence("{\"node\":\"roue_g\",\"channel\":\"emissive\","
                + "\"source\":\"time.seconds\",\"period\":0.5,\"map\":[[0,1],[0.25,1],[0.2501,0],[0.5,0]],"
                + "\"gate\":\"var.gyrophare\"}", journal);
        assertTrue(journal.isEmpty(), journal.toString());
        assertEquals(1f, pistes.evaluate(instant(100.1, 1f)).looks()[1], 1e-6f, "allumé, première moitié");
        assertEquals(0f, pistes.evaluate(instant(100.4, 1f)).looks()[1], 1e-6f, "allumé, seconde moitié");
        assertEquals(1f, pistes.evaluate(instant(1e6 + 0.1, 1f)).looks()[1], 1e-6f, "la période tient après des jours");
        assertEquals(0f, pistes.evaluate(instant(100.1, 0f)).looks()[1], 1e-6f, "porte fermée : éteint");
        ProceduralTracks.Pose pose = pistes.evaluate(instant(100.1, 1f));
        assertArrayEquals(new float[] {0f, 0f, 0f, 0f, 0f, 0f, 1f}, pose.deltas(), 1e-6f, "aucun delta");
        assertEquals(1f, pose.looks()[0], "toujours visible");
    }

    @Test
    @DisplayName("ADR-163 D3 : visibility exige chacune de ses pistes ; emissive et color se multiplient")
    void canauxDApparence() {
        ProceduralTracks pistes = apparence(
                "{\"node\":\"roue_g\",\"channel\":\"visibility\",\"source\":\"var.gyrophare\"},"
                + "{\"node\":\"roue_g\",\"channel\":\"visibility\",\"source\":\"time.seconds\",\"map\":[[0,1],[10,1]]},"
                + "{\"node\":\"roue_g\",\"channel\":\"emissive\",\"source\":\"time.seconds\",\"scale\":0.5},"
                + "{\"node\":\"roue_g\",\"channel\":\"emissive\",\"source\":\"time.seconds\",\"scale\":0.5},"
                + "{\"node\":\"roue_g\",\"channel\":\"color\",\"axis\":\"g\",\"source\":\"var.gyrophare\",\"scale\":-1},"
                + "{\"node\":\"caisse\",\"channel\":\"rotation\",\"axis\":\"y\",\"source\":\"time.seconds\"}",
                new ArrayList<>());
        float[] looks = pistes.evaluate(instant(3.0, 1f)).looks();
        assertEquals(2 * ProceduralTracks.LOOK_FLOATS, looks.length, "deux nœuds pilotés");
        assertArrayEquals(new float[] {1f, 2.25f, 1f, 0f, 1f}, java.util.Arrays.copyOfRange(looks, 0, 5), 1e-6f,
                "visible, 1,5 × 1,5, vert négatif ramené à 0");
        assertArrayEquals(new float[] {1f, 1f, 1f, 1f, 1f}, java.util.Arrays.copyOfRange(looks, 5, 10), 1e-6f,
                "la caisse n'a que sa rotation : apparence au repos");
        assertEquals(0f, pistes.evaluate(instant(3.0, 0f)).looks()[0], "une piste sous 0,5 cache le nœud");
    }

    @Test
    @DisplayName("ADR-163 D3 : sans piste d'apparence, une pose ne porte pas d'apparence")
    void sansApparence() {
        ProceduralTracks pistes = apparence(
                "{\"node\":\"caisse\",\"channel\":\"rotation\",\"axis\":\"y\",\"source\":\"time.seconds\"}",
                new ArrayList<>());
        assertNull(pistes.evaluate(instant(1.0, 0f)).looks());
    }

    @Test
    @DisplayName("ADR-163 D3 : smoothing suit sa valeur par un lissage exponentiel, en secondes du serveur")
    void lissage() {
        ProceduralTracks pistes = apparence("{\"node\":\"roue_g\",\"channel\":\"emissive\","
                + "\"source\":\"var.gyrophare\",\"smoothing\":0.5}", new ArrayList<>());
        ProceduralTracks.State etat = new ProceduralTracks.State();
        assertEquals(0f, pistes.evaluate(instant(10.0, 0f), etat).looks()[1], 1e-6f, "première image : la valeur");
        float apres = pistes.evaluate(instant(10.5, 1f), etat).looks()[1];
        assertEquals(1.0 - Math.exp(-1.0), apres, 1e-5, "une constante de temps plus tard : 63 %");
        float encore = pistes.evaluate(instant(10.5, 1f), etat).looks()[1];
        assertEquals(apres, encore, 1e-6f, "même instant : rien n'avance");
        assertEquals(1f, pistes.evaluate(instant(10.5, 1f)).looks()[1], 1e-6f, "sans état, pas de lissage");
    }

    @Test
    @DisplayName("ADR-163 D3 : un axe qui ne va pas au canal, un canal inconnu ou une gate sans source écartent la piste")
    void canauxMalFormes() {
        for (String piste : new String[] {
            "{\"node\":\"roue_g\",\"channel\":\"color\",\"axis\":\"x\",\"source\":\"time.seconds\"}",
            "{\"node\":\"roue_g\",\"channel\":\"rotation\",\"axis\":\"r\",\"source\":\"time.seconds\"}",
            "{\"node\":\"roue_g\",\"channel\":\"emissive\",\"axis\":\"x\",\"source\":\"time.seconds\"}",
            "{\"node\":\"roue_g\",\"channel\":\"opacity\",\"source\":\"time.seconds\"}",
            "{\"node\":\"roue_g\",\"channel\":\"emissive\",\"source\":\"time.seconds\",\"gate\":\"var.absente\"}",
            "{\"node\":\"roue_g\",\"channel\":\"emissive\",\"source\":\"time.seconds\",\"period\":-1}"}) {
            List<String> journal = new ArrayList<>();
            assertSame(ProceduralTracks.NONE, apparence(piste, journal), piste);
            assertEquals(1, journal.size(), piste + " : " + journal);
        }
    }
}
