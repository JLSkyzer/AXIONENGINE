package dev.axion.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.axion.net.AnimationLayers.Change;
import dev.axion.net.AnimationLayers.Layer;
import dev.axion.render.AnimationCommands;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-155, tranche 4 : les commandes qui mènent un natif d'un état de couches à l'autre. */
class AnimationLayersTest {

    @Test
    @DisplayName("Les codes d'action sont ceux d'anim_action, que les commandes de rendu écrivent")
    void codes() {
        assertEquals(AnimationCommands.PLAY, Change.PLAY);
        assertEquals(AnimationCommands.STOP, Change.STOP);
        assertEquals(AnimationCommands.SET_SPEED, Change.SET_SPEED);
    }

    @Test
    @DisplayName("Depuis le repos, l'état entier se rejoue par tick croissant ; une couche arrêtée depuis le vide ne donne rien")
    void rejeuDepuisLeRepos() {
        AnimationLayers state = AnimationLayers.NONE
                .with(2, new Layer(1, 300L, 0.5f, 2f, 0.5f, 0.25f))
                .with(0, new Layer(0, 100L, 0f, 1f, 1f, 0f))
                .with(3, Layer.stopped(50L, 1f));
        List<Change> changes = state.changesFrom(AnimationLayers.NONE);
        assertEquals(List.of(
                new Change(Change.PLAY, 0, 100.0, 0, 1f, 0f, 0f, 1f),
                new Change(Change.PLAY, 2, 300.0, 1, 0.5f, 0.25f, 0.5f, 2f)), changes);
        assertEquals(List.of(), state.changesFrom(state));
    }

    @Test
    @DisplayName("ADR-157 D1 : un seul clip, à poids plein, fondu d'entrée fini ; sinon la pose compose plusieurs clips")
    void unSeulClip() {
        Layer seul = new Layer(0, 100L, 0f, 1f, 1f, 0f);
        assertTrue(AnimationLayers.NONE.with(0, seul).singleClipAt(150.0));
        assertFalse(AnimationLayers.NONE.singleClipAt(150.0), "rien ne joue : le repos, pas un clip");
        // Le fondu d'entrée de 0,5 s, soit 10 ticks : un clip et le repos, mêlés, jusqu'au tick 110.
        Layer fondu = new Layer(0, 100L, 0f, 1f, 1f, 0.5f);
        assertFalse(AnimationLayers.NONE.with(0, fondu).singleClipAt(105.0));
        assertTrue(AnimationLayers.NONE.with(0, fondu).singleClipAt(110.0));
        assertFalse(AnimationLayers.NONE.with(0, new Layer(0, 100L, 0f, 1f, 0.5f, 0f)).singleClipAt(150.0),
                "poids partiel");
        assertFalse(AnimationLayers.NONE.with(0, seul).with(1, new Layer(1, 100L, 0f, 1f, 1f, 0f)).singleClipAt(150.0),
                "deux couches");
        // Une couche qui s'arrête en fondu montre encore son clip, mêlé au repos.
        assertFalse(AnimationLayers.NONE.with(0, Layer.stopped(140L, 1f)).singleClipAt(150.0));
        assertFalse(AnimationLayers.NONE.with(0, seul).with(1, Layer.stopped(140L, 1f)).singleClipAt(150.0),
                "un clip et le fondu d'arrêt d'un autre");
        assertTrue(AnimationLayers.NONE.with(0, seul).with(1, Layer.stopped(100L, 1f)).singleClipAt(150.0),
                "le fondu d'arrêt fini ne montre plus rien");
    }

    @Test
    @DisplayName("Arrêter une couche qui joue donne STOP à son tick, avec son fondu")
    void arret() {
        AnimationLayers before = AnimationLayers.NONE.with(1, new Layer(4, 10L, 0f, 1f, 1f, 0f));
        AnimationLayers after = before.with(1, Layer.stopped(40L, 0.5f));
        assertEquals(List.of(new Change(Change.STOP, 1, 40.0, 0, 0f, 0.5f, 0f, 1f)), after.changesFrom(before));
    }

    @Test
    @DisplayName("Une vitesse changée pendant le fondu donne SET_SPEED à l'instant où les temps de clip se rejoignent")
    void vitesseDuranteLeFondu() {
        // Clip 3 depuis le tick 100, fondu de 2 s ; au tick 110, la vitesse passe de 1 à 3.
        Layer before = new Layer(3, 100L, 0f, 1f, 1f, 2f);
        double clipTime = before.clipTimeAt(110.0);
        float origin = (float) (clipTime - (110 - 100) / 20.0 * 3f);
        Layer after = new Layer(3, 100L, origin, 3f, 1f, 2f);
        List<Change> changes = AnimationLayers.NONE.with(0, after)
                .changesFrom(AnimationLayers.NONE.with(0, before));
        assertEquals(1, changes.size());
        Change change = changes.get(0);
        assertEquals(Change.SET_SPEED, change.action());
        assertEquals(3f, change.speed());
        assertEquals(110.0, change.tick(), 1e-4);
        assertEquals(clipTime, after.clipTimeAt(change.tick()), 1e-6, "le temps de clip ne saute pas");
    }

    @Test
    @DisplayName("Un autre clip, un autre poids ou un autre tick donnent PLAY")
    void rejeu() {
        Layer base = new Layer(3, 100L, 0f, 1f, 1f, 0.5f);
        AnimationLayers before = AnimationLayers.NONE.with(0, base);
        for (Layer after : List.of(new Layer(2, 100L, 0f, 1f, 1f, 0.5f), new Layer(3, 100L, 0f, 1f, 0.5f, 0.5f),
                new Layer(3, 140L, 0f, 1f, 1f, 0.5f), new Layer(3, 140L, 2f, 2f, 1f, 0f))) {
            List<Change> changes = AnimationLayers.NONE.with(0, after).changesFrom(before);
            assertEquals(1, changes.size(), after.toString());
            assertEquals(Change.PLAY, changes.get(0).action(), after.toString());
            assertEquals(after.tick(), changes.get(0).tick());
            assertEquals(after.origin(), changes.get(0).origin());
        }
        assertTrue(AnimationLayers.NONE.changesFrom(before).stream().allMatch(c -> c.action() == Change.STOP));
    }
}
