package dev.axion.asset;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-155, tranche 4 : les noms et les pistes des clips, lus de la section {@code ANIM}. */
class AnimClipsTest {

    /**
     * Une section {@code ANIM} à la disposition de {@code crates/ax-asset/src/a3d/animation.rs} : des
     * clips aux pistes consécutives, trois flottants de clé par piste, les noms à la suite.
     */
    static byte[] section(String[] names, int[] tracks) {
        int trackTotal = 0;
        for (int count : tracks) {
            trackTotal += count;
        }
        byte[][] encoded = new byte[names.length][];
        int namesSize = 0;
        for (int i = 0; i < names.length; i++) {
            encoded[i] = names[i].getBytes(StandardCharsets.UTF_8);
            namesSize += encoded[i].length;
        }
        int keys = trackTotal * 3;
        ByteBuffer out = ByteBuffer.allocate(16 + names.length * 24 + trackTotal * 16 + names.length * 8 + keys * 4
                + namesSize).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(names.length).putInt(trackTotal).putInt(keys).putInt(namesSize);
        int first = 0;
        for (int i = 0; i < names.length; i++) {
            out.putLong(i + 1L).putFloat(1f).putInt(first).putInt(tracks[i]).putInt(0);
            first += tracks[i];
        }
        for (int t = 0; t < trackTotal; t++) {
            out.putInt(0).put((byte) 0).put((byte) 0).putShort((short) 0).putInt(t * 3).putInt(1);
        }
        int offset = 0;
        for (byte[] name : encoded) {
            out.putInt(offset).putInt(name.length);
            offset += name.length;
        }
        for (int k = 0; k < keys; k++) {
            out.putFloat(k);
        }
        for (byte[] name : encoded) {
            out.put(name);
        }
        return out.array();
    }

    @Test
    @DisplayName("Les noms et les pistes de chaque clip se lisent, nom vide compris")
    void lecture() {
        AnimClips clips = AnimClips.decode(section(new String[] {"marche", "", "révérence"}, new int[] {3, 1, 2}));
        assertEquals(List.of("marche", "", "révérence"), clips.names());
        assertArrayEquals(new int[] {3, 1, 2}, clips.tracks());
        assertEquals(0, clips.indexOf("marche"));
        assertEquals(2, clips.indexOf("révérence"));
        assertEquals(-1, clips.indexOf(""), "un nom vide ne se joue pas");
        assertEquals(-1, clips.indexOf("course"));
        assertEquals(2, clips.tracksOf(2));
        assertEquals(0, clips.tracksOf(-1));
        assertSame(AnimClips.NONE, AnimClips.decode(null));
        assertEquals(AnimClips.decode(section(new String[] {"a"}, new int[] {1})),
                AnimClips.decode(section(new String[] {"a"}, new int[] {1})));
    }

    @Test
    @DisplayName("Une section tronquée, trop longue, ou au nom hors table ou mal encodé est refusée")
    void refus() {
        byte[] bon = section(new String[] {"marche"}, new int[] {2});
        assertThrows(IllegalArgumentException.class, () -> AnimClips.decode(new byte[8]));
        assertThrows(IllegalArgumentException.class, () -> AnimClips.decode(Arrays.copyOf(bon, bon.length - 1)));
        assertThrows(IllegalArgumentException.class, () -> AnimClips.decode(Arrays.copyOf(bon, bon.length + 1)));

        int ref = 16 + 24 + 2 * 16;
        byte[] horsTable = bon.clone();
        ByteBuffer.wrap(horsTable).order(ByteOrder.LITTLE_ENDIAN).putInt(ref, 3);
        assertThrows(IllegalArgumentException.class, () -> AnimClips.decode(horsTable));

        byte[] pasUtf8 = bon.clone();
        pasUtf8[pasUtf8.length - 1] = (byte) 0xFF;
        assertThrows(IllegalArgumentException.class, () -> AnimClips.decode(pasUtf8));
    }
}
