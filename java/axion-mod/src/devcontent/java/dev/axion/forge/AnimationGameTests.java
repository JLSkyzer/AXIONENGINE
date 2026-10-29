package dev.axion.forge;

import dev.axion.forge.net.JoueurSimule;
import dev.axion.net.AnimationLayers;
import java.util.List;
import java.util.Optional;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * ADR-155, tranche 4 : l'animation de bout en bout, sur la glissière que génère
 * {@code tools/assets/animation_essai.py} — l'API du serveur, la commande au natif, le collider qui suit son
 * node (R-1430), et l'état que le descripteur v3 porte au client.
 */
@GameTestHolder("axion")
@PrefixGameTestTemplate(false)
public final class AnimationGameTests {

    /** Le dessus de la dalle du gabarit {@code plancher}, en coordonnées du test. */
    private static final double DESSUS_DALLE = 2.0;

    private static final float[] DROIT = {0f, 0f, 0f, 1f};

    private AnimationGameTests() {}

    /** Le bord +x de la hitbox, relatif à la position : celui de l'emprise physique (R-702). */
    private static double bordX(AxionEntity assembly) {
        return assembly.getBoundingBox().maxX - assembly.getX();
    }

    /** {@return la couche 0 du plus récent état que le joueur a reçu de l'assembly, ou vide} */
    private static Optional<AnimationLayers.Layer> recue(JoueurSimule joueur, AxionEntity assembly) {
        List<JoueurSimule.Recu> recus =
                joueur.recus().stream().filter(recu -> recu.handle() == assembly.getId()).toList();
        return recus.isEmpty() ? Optional.empty()
                : Optional.of(recus.get(recus.size() - 1).state().animation().layer(0));
    }

    /**
     * Le serveur joue « glisse » : hors du thread du serveur, l'appel est refusé (R-1755) ; sur lui, le
     * collider du coulisseau suit son node d'un bloc en +x, la hitbox avec lui, et le client reçoit la
     * couche qui joue. Arrêtée, la couche rend le coulisseau au repos, au serveur comme au client.
     */
    @GameTest(template = "test/plancher", timeoutTicks = 200)
    public static void adr155_LaGlissiereJoueSonClipAuServeurEtAuClient(GameTestHelper helper) {
        ObservateursDeTest.declarer(helper, new Vec3(4.0, DESSUS_DALLE, 4.0));
        AxionEntity glissiere = AssembliesDeTest.poser(
                helper, "axion:test/anime/glissiere", new Vec3(3.5, DESSUS_DALLE, 4.5), DROIT);
        JoueurSimule joueur = JoueurSimule.rejoindre(
                helper.getLevel(), "glissiere", helper.absoluteVec(new Vec3(4.0, DESSUS_DALLE, 7.0)));
        int[] etape = {0};
        helper.onEachTick(() -> {
            joueur.tick();
            if (glissiere.netState() == null) {
                return;
            }
            Optional<AnimationLayers.Layer> client = recue(joueur, glissiere);
            if (etape[0] == 0 && glissiere.animation().clips().equals(List.of("glisse"))) {
                helper.assertTrue(bordX(glissiere) < 0.6, "au repos, le bord +x est celui du socle : "
                        + bordX(glissiere));
                RuntimeException[] ailleurs = {null};
                Thread autre = new Thread(() -> {
                    try {
                        glissiere.animation().play("glisse", 0, 1f, 0f);
                    } catch (RuntimeException refus) {
                        ailleurs[0] = refus;
                    }
                }, "hors-du-serveur");
                autre.start();
                try {
                    autre.join();
                } catch (InterruptedException interrompu) {
                    Thread.currentThread().interrupt();
                }
                helper.assertTrue(ailleurs[0] instanceof IllegalStateException,
                        "R-1755 : hors du thread du serveur, play lève IllegalStateException : " + ailleurs[0]);
                helper.assertTrue(glissiere.animation().playing(0).isEmpty(), "le refus n'a rien changé");
                glissiere.animation().play("glisse", 0, 1f, 0f);
                helper.assertTrue(glissiere.animation().playing(0).equals(Optional.of("glisse")), "elle joue");
                etape[0] = 1;
            } else if (etape[0] == 1 && bordX(glissiere) > 1.2
                    && client.filter(couche -> couche.playing() && couche.clip() == 0).isPresent()) {
                glissiere.animation().stop(0, 0f);
                etape[0] = 2;
            } else if (etape[0] == 2 && bordX(glissiere) < 0.6
                    && client.filter(couche -> !couche.playing()).isPresent()) {
                etape[0] = 3;
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(etape[0] == 3, "jouée puis arrêtée, au serveur et au client : étape " + etape[0]
                    + ", bord +x " + bordX(glissiere));
            joueur.quitter();
        });
    }
}
