package dev.axion.forge.client;

import com.mojang.blaze3d.systems.RenderSystem;

/**
 * Les objets GL que le backend natif tient en vie — tampons, VAO, programmes —, comptés à leur
 * création et à leur destruction. Une bascule de backend doit les rendre tous (R-744, T-492) : le
 * backend le vérifie à sa fermeture, et le banc de rendu, d'une bascule à l'autre.
 *
 * <p>Render thread seul (R-751, INV-12).
 */
final class NativeGlObjects {

    private static int live;

    /** Fermetures du backend natif depuis le lancement, et ce que la dernière a laissé en vie. */
    private static int closes;
    private static int leftAtLastClose;

    private NativeGlObjects() {}

    /**
     * Le backend natif vient de se fermer : ce qui reste en vie est une fuite.
     *
     * @return les objets laissés en vie
     */
    static int closed() {
        RenderSystem.assertOnRenderThread();
        closes++;
        leftAtLastClose = live;
        return live;
    }

    /** {@return les fermetures du backend natif depuis le lancement} */
    static int closes() {
        RenderSystem.assertOnRenderThread();
        return closes;
    }

    /** {@return les objets que la dernière fermeture a laissés en vie} */
    static int leftAtLastClose() {
        RenderSystem.assertOnRenderThread();
        return leftAtLastClose;
    }

    /** Un objet vient d'être créé. */
    static void created() {
        RenderSystem.assertOnRenderThread();
        live++;
    }

    /** Un objet vient d'être détruit. */
    static void deleted() {
        RenderSystem.assertOnRenderThread();
        live--;
    }

    /** {@return les objets créés et pas encore détruits} */
    static int live() {
        RenderSystem.assertOnRenderThread();
        return live;
    }
}
