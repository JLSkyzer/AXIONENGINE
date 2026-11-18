package dev.axion.render;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-163 D3 : l'apparence de chaque dessin, héritée des nœuds que les pistes pilotent. */
class NodeLooksTest {

    /** Une section {@code NODE} : un en-tête de 8 octets, puis 80 octets par nœud, le parent à l'octet 8. */
    private static byte[] section(int... parents) {
        ByteBuffer buffer = ByteBuffer.allocate(8 + 80 * parents.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0, parents.length);
        for (int node = 0; node < parents.length; node++) {
            buffer.putInt(8 + 80 * node + 8, parents[node]);
        }
        return buffer.array();
    }

    @Test
    @DisplayName("la table des parents se lit, et se refuse si un parent suit son enfant")
    void parents() {
        assertArrayEquals(new int[] {-1, 0, 1, 0}, NodeLooks.parents(section(-1, 0, 1, 0)));
        assertNull(NodeLooks.parents(section(-1, 2, 0)), "un parent après son enfant");
        assertNull(NodeLooks.parents(new byte[4]), "tronquée");
    }

    @Test
    @DisplayName("un nœud caché cache ses enfants ; émission et teinte se multiplient le long de la chaîne")
    void heritage() {
        int[] parents = {-1, 0, 1, 0};
        // Le nœud 1 : émission 2, teinte rouge ; le nœud 3 : caché.
        ProceduralTracks.Pose pose = new ProceduralTracks.Pose(new int[] {1, 3}, new float[14],
                new float[] {1f, 2f, 1f, 0.5f, 0.5f, 0f, 1f, 1f, 1f, 1f});
        // Quatre dessins : sur les nœuds 0, 1, 2 (enfant de 1), 3.
        float[] looks = NodeLooks.ofDraws(parents, pose, List.of(0, 1, 2, 3));
        assertFalse(NodeLooks.hidden(looks, 0));
        assertArrayEquals(new float[] {1f, 1f, 1f, 1f}, NodeLooks.tint(looks, 0, true), "la racine, au repos");
        assertArrayEquals(new float[] {1f, 0.5f, 0.5f, 1f}, NodeLooks.tint(looks, 1, false), "teinte en surface");
        assertArrayEquals(new float[] {2f, 1f, 1f, 1f}, NodeLooks.tint(looks, 1, true), "teinte × émission");
        assertArrayEquals(new float[] {2f, 1f, 1f, 1f}, NodeLooks.tint(looks, 2, true), "l'enfant hérite");
        assertTrue(NodeLooks.hidden(looks, 3));
        assertNull(NodeLooks.ofDraws(parents, new ProceduralTracks.Pose(new int[] {0}, new float[7]), List.of(0)),
                "sans apparence, rien");
    }

    @Test
    @DisplayName("le backend vanilla multiplie ses octets, bornés à 255 ; l'alpha ne change pas")
    void octets() {
        float[] looks = {1f, 3f, 1f, 0.5f, 0f};
        assertEquals(0x80FF4000, NodeLooks.tinted(0x80FF8080, looks, 0, false), "rouge, vert à moitié, bleu éteint");
        assertEquals(0x80FFC000, NodeLooks.tinted(0x80FF8080, looks, 0, true), "× 3 en émission, saturé");
        assertEquals(0x12345678, NodeLooks.tinted(0x12345678, null, 0, true), "sans apparence, inchangée");
    }

    @Test
    @DisplayName("ADR-174 D3 : une action prime sur le canal de son node, et ses enfants en héritent")
    void lEtatDesNodesPrimeSurLesPistes() {
        int[] parents = {-1, 0, 1, 0};
        // La piste montre le nœud 1 (visibilité 1), émission 2, teinte rouge ; une action le cache, et pose
        // l'émission 0,5 et la teinte verte au nœud 3.
        ProceduralTracks.Pose pose = new ProceduralTracks.Pose(new int[] {1}, new float[7],
                new float[] {1f, 2f, 1f, 0.5f, 0.5f});
        dev.axion.net.NodeStates etats = new dev.axion.net.NodeStates(List.of(
                new dev.axion.net.NodeStates.State(1, true, Float.NaN, null),
                new dev.axion.net.NodeStates.State(3, false, 0.5f, new float[] {0f, 1f, 0f})));
        float[] looks = NodeLooks.ofDraws(parents, pose, etats, List.of(0, 1, 2, 3));
        assertFalse(NodeLooks.hidden(looks, 0));
        assertTrue(NodeLooks.hidden(looks, 1), "caché par l'action, malgré la piste");
        assertTrue(NodeLooks.hidden(looks, 2), "son enfant aussi");
        assertArrayEquals(new float[] {0f, 0.5f, 0f, 1f}, NodeLooks.tint(looks, 3, true), "émission et teinte posées");
        assertArrayEquals(new float[] {0f, 1f, 0f, 1f}, NodeLooks.tint(looks, 3, false));
        float[] seules = NodeLooks.ofDraws(parents, null, etats, List.of(1, 3));
        assertTrue(NodeLooks.hidden(seules, 0), "sans piste, l'action vaut seule");
        assertNull(NodeLooks.ofDraws(parents, null, dev.axion.net.NodeStates.NONE, List.of(0)),
                "ni piste ni action : rien");
    }
}
