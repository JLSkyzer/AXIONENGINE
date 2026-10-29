package dev.axion.render;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-155, ABI 10 : les commandes d'animation et les matrices de {@code RENDER_OUT}, octet par octet. */
class AnimationCommandsTest {

    @Test
    @DisplayName("Une commande suit RenderAnimate puis AnimCommand, little-endian, ses nodes de masque après")
    void disposition() {
        byte[] bytes = new AnimationCommands()
                .play(42, 3, 7, 12.5, 1, 2, 0.75f, 0.25f, 3.5f, 2f)
                .mask(42, 3, 7, 13.0, 1, new int[] {4, 9})
                .bytes();
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int record = AnimationCommands.HEADER_BYTES + AnimationCommands.COMMAND_BYTES;
        assertEquals(record * 2 + 8, bytes.length);

        assertArrayEquals(new int[] {42, 0, 3, 7}, new int[] {b.getInt(0), b.getInt(4), b.getInt(8), b.getInt(12)});
        assertEquals(12.5, b.getDouble(16));
        assertEquals(AnimationCommands.PLAY, b.getInt(24));
        assertEquals(1, b.getInt(28), "couche");
        assertEquals(2, b.getInt(32), "clip");
        assertEquals(0.75f, b.getFloat(36), "poids");
        assertEquals(0.25f, b.getFloat(40), "fondu");
        assertEquals(2f, b.getFloat(44), "vitesse");
        assertEquals(0, b.getInt(48), "aucun node de masque");
        assertEquals(3.5f, b.getFloat(52), "temps de clip");

        assertEquals(AnimationCommands.SET_MASK, b.getInt(record + 24));
        assertEquals(2, b.getInt(record + 48));
        assertEquals(4, b.getInt(record + record));
        assertEquals(9, b.getInt(record + record + 4));
    }

    @Test
    @DisplayName("RENDER_OUT schéma 3 : la matrice de chaque dessin d'un retenu animé, aucune au repos")
    void matricesDeRenderOut() {
        // Deux retenus de deux dessins : le premier au repos, le second animé à la matrice 0.
        int retained = 2;
        int meshTotal = 4;
        ByteBuffer out = ByteBuffer.allocate(
                        RenderPrepCodec.OUT_HEADER_BYTES + retained * RenderPrepCodec.OUT_RECORD_BYTES + meshTotal * 4
                                + 2 * RenderPrepCodec.MATRIX_FLOATS * 4)
                .order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(0, 1).putInt(4, retained).putInt(8, meshTotal).putInt(12, 0).putInt(16, 2);
        for (int rank = 0; rank < retained; rank++) {
            int at = RenderPrepCodec.OUT_HEADER_BYTES + rank * RenderPrepCodec.OUT_RECORD_BYTES;
            out.putInt(at + 16, rank * 2).putInt(at + 20, 2)
                    .putInt(at + 24, rank == 0 ? RenderPrepCodec.NO_MATRICES : 0);
        }
        int table = RenderPrepCodec.OUT_HEADER_BYTES + retained * RenderPrepCodec.OUT_RECORD_BYTES + meshTotal * 4;
        for (int i = 0; i < 2 * RenderPrepCodec.MATRIX_FLOATS; i++) {
            out.putFloat(table + i * 4, i);
        }
        assertNull(RenderPrepCodec.retainedMatrices(out, 0, retained, 0));
        float[][] matrices = RenderPrepCodec.retainedMatrices(out, 0, retained, 1);
        assertEquals(2, matrices.length);
        assertEquals(0f, matrices[0][0]);
        assertEquals(11f, matrices[0][11]);
        assertEquals(12f, matrices[1][0]);
    }

    @Test
    @DisplayName("RENDER_IN schéma 2 : le tick de rendu en tête, huit octets réservés à zéro")
    void tickDeRendu() {
        ByteBuffer in = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
        in.putLong(8, -1L).putLong(16, -1L);
        RenderPrepCodec.putRenderTick(in, 8, 1234.25);
        assertEquals(1234.25, in.getDouble(8));
        assertEquals(0L, in.getLong(16));
    }
}
