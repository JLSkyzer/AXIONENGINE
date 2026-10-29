package dev.axion.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.axion.asset.AnimClips;
import dev.axion.net.AnimationLayers;
import dev.axion.net.AnimationLayers.Layer;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** C-37, ADR-155 D5 : l'animation d'une assembly côté serveur, et ce qu'elle refuse. */
class AssemblyAnimationTest {

    private static final AnimClips MARCHE = new AnimClips(List.of("marche", "salut", ""), new int[] {3, 2, 1});

    private long now;
    private boolean authoritative = true;

    private AssemblyAnimation animation(AnimClips clips) {
        AssemblyAnimation animation = new AssemblyAnimation(() -> now, () -> {
            if (!authoritative) {
                throw new IllegalStateException("hors du thread autoritatif");
            }
        });
        animation.bind(clips);
        return animation;
    }

    @Test
    @DisplayName("Jouer, régler la vitesse, arrêter : l'état de la couche est la dernière commande")
    void etats() {
        AssemblyAnimation animation = animation(MARCHE);
        assertEquals(List.of("marche", "salut", ""), animation.clips());
        now = 100;
        animation.setSpeed(1, 2f);
        assertSame(AnimationLayers.NONE, animation.state(), "une couche arrêtée garde sa vitesse pour plus tard");
        animation.play("salut", 1, 0.5f, 0.25f);
        assertEquals(new Layer(1, 100L, 0f, 2f, 0.5f, 0.25f), animation.state().layer(1));
        assertEquals(Optional.of("salut"), animation.playing(1));
        assertEquals(Optional.empty(), animation.playing(0));

        now = 140;
        animation.stop(1, 0.5f);
        assertEquals(Layer.stopped(140L, 0.5f), animation.state().layer(1));
        assertEquals(Optional.empty(), animation.playing(1));
    }

    @Test
    @DisplayName("Une vitesse changée garde le temps de clip : pendant le fondu au même tick, après sans fondu")
    void vitesse() {
        AssemblyAnimation animation = animation(MARCHE);
        now = 100;
        animation.play("marche", 0, 1f, 2f);
        now = 110;
        double before = animation.state().layer(0).clipTimeAt(110);
        animation.setSpeed(0, 3f);
        Layer fading = animation.state().layer(0);
        assertEquals(100L, fading.tick(), "pendant le fondu, le tick du fondu reste");
        assertEquals(2f, fading.fade());
        assertEquals(before, fading.clipTimeAt(110), 1e-6);

        now = 200;
        double later = fading.clipTimeAt(200);
        animation.setSpeed(0, 0.5f);
        assertEquals(new Layer(0, 200L, (float) later, 0.5f, 1f, 0f), animation.state().layer(0),
                "le fondu fini, la couche repart du tick du changement, sans fondu");
        AnimationLayers same = animation.state();
        animation.setSpeed(0, 0.5f);
        assertSame(same, animation.state(), "la même vitesse ne change rien");
    }

    @Test
    @DisplayName("R-1755 : hors du thread autoritatif, ce qui change l'animation lève IllegalStateException")
    void thread() {
        AssemblyAnimation animation = animation(MARCHE);
        authoritative = false;
        assertThrows(IllegalStateException.class, () -> animation.play("marche", 0, 1f, 0f));
        assertThrows(IllegalStateException.class, () -> animation.stop(0, 0f));
        assertThrows(IllegalStateException.class, () -> animation.setSpeed(0, 2f));
        assertEquals(Optional.empty(), animation.playing(0), "lire ne change rien");
        assertSame(AnimationLayers.NONE, animation.state());
    }

    @Test
    @DisplayName("ADR-155 D5 : un clip inconnu, une couche ou un nombre hors bornes lèvent IllegalArgumentException")
    void refus() {
        AssemblyAnimation animation = animation(MARCHE);
        assertThrows(IllegalArgumentException.class, () -> animation.play("course", 0, 1f, 0f));
        assertThrows(IllegalArgumentException.class, () -> animation.play("", 0, 1f, 0f), "un clip sans nom");
        assertThrows(IllegalArgumentException.class, () -> animation.play(null, 0, 1f, 0f));
        assertThrows(IllegalArgumentException.class, () -> animation.play("marche", 4, 1f, 0f));
        assertThrows(IllegalArgumentException.class, () -> animation.play("marche", -1, 1f, 0f));
        assertThrows(IllegalArgumentException.class, () -> animation.play("marche", 0, 1.5f, 0f));
        assertThrows(IllegalArgumentException.class, () -> animation.play("marche", 0, Float.NaN, 0f));
        assertThrows(IllegalArgumentException.class, () -> animation.play("marche", 0, 1f, -1f));
        assertThrows(IllegalArgumentException.class, () -> animation.play("marche", 0, 1f, Float.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> animation.stop(4, 0f));
        assertThrows(IllegalArgumentException.class, () -> animation.stop(0, Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> animation.setSpeed(0, Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> animation.setSpeed(9, 1f));
        assertThrows(IllegalArgumentException.class, () -> animation.playing(4));
        assertSame(AnimationLayers.NONE, animation.state(), "un refus ne change rien");
    }

    @Test
    @DisplayName("R-200 : comme le natif, le fondu est coupé puis la lecture refusée au-delà de 256 pistes")
    void r200() {
        // La séquence du test natif r200_au_dela_de_256_pistes_actives_le_fondu_est_coupe_puis_la_lecture_refusee.
        AssemblyAnimation animation = animation(new AnimClips(List.of("deux_cents", "cinquante", "cent"),
                new int[] {200, 50, 100}));
        now = 0;
        animation.play("deux_cents", 0, 1f, 0f);
        now = 20;
        animation.play("cinquante", 0, 1f, 0.5f);
        assertEquals(0.5f, animation.state().layer(0).fade(), "200 + 50 : le fondu tient");
        AnimationLayers before = animation.state();
        assertThrows(IllegalArgumentException.class, () -> animation.play("cent", 1, 1f, 0f), "250 + 100");
        assertSame(before, animation.state());

        now = 40;
        animation.play("deux_cents", 0, 1f, 0f);
        animation.play("cent", 0, 1f, 0.5f);
        assertEquals(new Layer(2, 40L, 0f, 1f, 1f, 0f), animation.state().layer(0),
                "200 + 100 en fondu dépasserait : le fondu est coupé");
    }

    @Test
    @DisplayName("R-200 : un fondu achevé ailleurs ne compte plus, comme au natif")
    void r200FonduAcheve() {
        // La séquence du test natif r200_un_fondu_acheve_ailleurs_ne_compte_plus_meme_sans_evaluation.
        AssemblyAnimation animation = animation(new AnimClips(List.of("deux_cents", "cinquante", "cent"),
                new int[] {200, 50, 100}));
        now = 0;
        animation.play("deux_cents", 0, 1f, 0f);
        now = 20;
        animation.play("cinquante", 0, 1f, 0.5f);
        now = 40;
        animation.play("cent", 1, 1f, 0f);
        assertEquals(Optional.of("cent"), animation.playing(1));
    }

    @Test
    @DisplayName("D'autres clips remettent l'animation au repos ; les mêmes la gardent")
    void liaison() {
        AssemblyAnimation animation = animation(MARCHE);
        animation.play("marche", 0, 1f, 0f);
        AnimationLayers playing = animation.state();
        animation.bind(new AnimClips(List.of("marche", "salut", ""), new int[] {3, 2, 1}));
        assertSame(playing, animation.state());
        animation.bind(new AnimClips(List.of("salut"), new int[] {2}));
        assertSame(AnimationLayers.NONE, animation.state());
        assertEquals(Optional.empty(), animation.playing(0));
    }
}
