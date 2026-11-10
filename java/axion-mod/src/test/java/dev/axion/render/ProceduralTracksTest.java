package dev.axion.render;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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

    @Test
    @DisplayName("scale : la compression devient une translation sur son axe")
    void scale() {
        ProceduralTracks pistes = pistes(
                "{\"node\":\"roue_g\",\"channel\":\"translation\",\"axis\":\"y\","
                        + "\"source\":\"wheel.roue_g.compression\",\"scale\":0.2}", new ArrayList<>());
        ProceduralTracks.Pose pose = pistes.evaluate(ROUES, new double[2]);
        assertArrayEquals(new int[] {1}, pose.nodes());
        assertArrayEquals(new float[] {0f, 0.1f, 0f, 0f, 0f, 0f, 1f}, pose.deltas(), 1e-6f);
    }

    @Test
    @DisplayName("map tient ses bouts, et clamp borne après scale")
    void mapEtClamp() {
        String piste = "{\"node\":\"roue_g\",\"channel\":\"translation\",\"axis\":\"x\","
                + "\"source\":\"wheel.roue_g.compression\",\"map\":[[0,0],[1,2]],\"scale\":3,\"clamp\":[-1,2.5]}";
        ProceduralTracks pistes = pistes(piste, new ArrayList<>());
        assertEquals(2.5f, pistes.evaluate(ROUES, new double[2]).deltas()[0], 1e-6f, "1,0 × 3 borné à 2,5");
        assertEquals(0f, pistes.evaluate(List.of(new WheelPose(-5f, 0f, 0f, true), ROUES.get(1)), new double[2])
                .deltas()[0], 1e-6f, "sous le premier point : tenu");
    }

    @Test
    @DisplayName("une rotation se compose dans l'ordre de déclaration, depuis l'angle intégré")
    void rotation() {
        ProceduralTracks pistes = pistes(
                "{\"node\":\"roue_g\",\"channel\":\"rotation\",\"axis\":\"y\",\"source\":\"wheel.roue_g.steer_angle\"},"
                        + "{\"node\":\"roue_g\",\"channel\":\"rotation\",\"axis\":\"x\","
                        + "\"source\":\"wheel.roue_g.spin_angle\",\"scale\":-1}", new ArrayList<>());
        float[] d = pistes.evaluate(ROUES, new double[] {1.0, 0.0}).deltas();
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
        String inconnue = "{\"node\":\"roue_g\",\"channel\":\"rotation\",\"axis\":\"z\",\"source\":\"engine.rpm\"}";
        ProceduralTracks pistes = pistes(inconnue + "," + inconnue, journal);
        assertSame(ProceduralTracks.NONE, pistes);
        assertTrue(pistes.isEmpty());
        assertEquals(1, journal.size(), journal.toString());
        assertNull(pistes.evaluate(ROUES, new double[2]));
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

    @Test
    @DisplayName("l'angle s'intègre au tick de rendu, sans jamais reculer")
    void integration() {
        ProceduralTracks.Spin spin = new ProceduralTracks.Spin();
        List<WheelPose> roues = List.of(new WheelPose(0f, 2f, 0f, true));
        assertEquals(0.0, spin.advance(100.0, roues)[0], 1e-9, "la première image ne fait que dater");
        assertEquals(0.2, spin.advance(102.0, roues)[0], 1e-9, "2 rad/s pendant 2 ticks");
        assertEquals(0.2, spin.advance(101.0, roues)[0], 1e-9, "un tick de rendu qui recule n'ajoute rien");
    }
}
