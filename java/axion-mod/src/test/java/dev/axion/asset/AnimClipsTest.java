package dev.axion.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-155, ADR-156 D4 : les clips de l'asset, tels que le résumé de la definition compilée les donne. */
class AnimClipsTest {

    @Test
    @DisplayName("Un clip se joue par son nom, un nom vide jamais ; ses pistes se comptent")
    void parLeNom() {
        AnimClips clips = new AnimClips(List.of("marche", "", "révérence"), new int[] {3, 1, 2});
        assertEquals(0, clips.indexOf("marche"));
        assertEquals(2, clips.indexOf("révérence"));
        assertEquals(-1, clips.indexOf(""), "un nom vide ne se joue pas");
        assertEquals(-1, clips.indexOf("course"));
        assertEquals(2, clips.tracksOf(2));
        assertEquals(0, clips.tracksOf(-1));
        assertEquals(new AnimClips(List.of("a"), new int[] {1}), new AnimClips(List.of("a"), new int[] {1}));
        assertThrows(IllegalArgumentException.class, () -> new AnimClips(List.of("a"), new int[0]),
                "un nombre de pistes par nom");
    }
}
