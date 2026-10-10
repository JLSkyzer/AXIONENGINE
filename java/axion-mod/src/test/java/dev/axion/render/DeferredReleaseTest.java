package dev.axion.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-502 — une ressource rendue n'est détruite qu'après 3 frames sans usage, jamais quand elle sert
 * encore (fiche 5.49, R-751, ADR-127 §3). Le render thread, lui, se vérifie au collage GL.
 */
class DeferredReleaseTest {

    @Test
    @DisplayName("Rendue à la frame 10, une ressource est détruite à la 13 : trois frames sans usage")
    void detruiteApresTroisFrames() {
        DeferredRelease<String> file = new DeferredRelease<>();
        file.release("arène 0", 10);
        assertEquals(List.of(), file.due(11));
        assertEquals(List.of(), file.due(12));
        assertEquals(List.of("arène 0"), file.due(13));
        assertEquals(List.of(), file.due(14), "détruite une fois");
    }

    @Test
    @DisplayName("Une ressource qui resservait entre-temps est gardée")
    void reutiliseeElleEstGardee() {
        DeferredRelease<String> file = new DeferredRelease<>();
        file.release("arène 0", 10);
        file.use("arène 0");
        assertFalse(file.pending("arène 0"));
        assertEquals(List.of(), file.due(20));
    }

    @Test
    @DisplayName("Rendue deux fois, elle compte depuis la première")
    void renduDeuxFoisCompteDepuisLaPremiere() {
        DeferredRelease<String> file = new DeferredRelease<>();
        file.release("arène 0", 10);
        file.release("arène 0", 12);
        assertEquals(List.of("arène 0"), file.due(13));
    }

    @Test
    @DisplayName("Les ressources échues sortent dans l'ordre où elles ont été rendues")
    void dansLOrdre() {
        DeferredRelease<String> file = new DeferredRelease<>();
        file.release("b", 5);
        file.release("a", 5);
        file.release("c", 6);
        assertEquals(List.of("b", "a"), file.due(8));
        assertTrue(file.pending("c"));
        assertEquals(List.of("c"), file.due(9));
    }

    @Test
    @DisplayName("Tout vider d'un coup — arrêt, rechargement de ressources — rend tout ce qui attendait")
    void toutViderRendToutCeQuiAttendait() {
        DeferredRelease<String> file = new DeferredRelease<>();
        file.release("a", 5);
        file.release("b", 6);
        assertEquals(List.of("a", "b"), file.drainAll());
        assertEquals(List.of(), file.due(100));
    }
}
