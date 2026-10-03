package dev.axion.forge.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * Texture d'un asset, préparée par AXION et téléversée sur le fil de rendu (ADR-122 §7).
 *
 * <p>Ses niveaux de mipmaps sont produits hors du fil de rendu ; {@link #upload} les téléverse
 * selon la recette de l'atlas vanilla — {@code TextureUtil.prepareImage}, puis un
 * {@code NativeImage.upload} par niveau —, sans appel OpenGL direct (R-741).
 *
 * <p>{@link #load} ne fait rien : ses octets viennent d'AXION, pas d'un fichier du resource
 * pack. Un rechargement de ressources ne la relit donc pas ; AXION la libère et la reconstruit
 * lui-même (R-752).
 */
final class AxionTexture extends AbstractTexture {

    private final boolean smooth;
    private final boolean clamp;

    /** Niveaux de mipmaps, base comprise, jusqu'au téléversement ; {@code null} ensuite. */
    private NativeImage[] levels;

    /**
     * Crée une texture prête à téléverser.
     *
     * @param levels niveaux de mipmaps, base comprise ; la texture en devient propriétaire
     * @param smooth filtrage linéaire ; au plus proche sinon
     * @param clamp coordonnées écrêtées au bord ; répétées sinon
     */
    AxionTexture(NativeImage[] levels, boolean smooth, boolean clamp) {
        this.levels = levels;
        this.smooth = smooth;
        this.clamp = clamp;
    }

    @Override
    public void load(ResourceManager manager) {
        // Rien : la texture se téléverse par upload, depuis les octets qu'AXION a préparés.
    }

    /**
     * Téléverse tous les niveaux, sur le fil de rendu, et rend leur mémoire.
     */
    void upload() {
        RenderSystem.assertOnRenderThreadOrInit();
        NativeImage[] images = levels;
        levels = null;
        int maxLevel = images.length - 1;
        int next = 0;
        try {
            TextureUtil.prepareImage(getId(), maxLevel, images[0].getWidth(), images[0].getHeight());
            for (; next <= maxLevel; next++) {
                NativeImage image = images[next];
                // Fermée par le téléversement lui-même (dernier argument).
                image.upload(next, 0, 0, 0, 0, image.getWidth(), image.getHeight(), smooth, clamp, maxLevel > 0, true);
            }
        } finally {
            for (; next <= maxLevel; next++) {
                images[next].close();
            }
        }
    }

    /** Rend la mémoire des niveaux s'ils n'ont pas été téléversés. */
    void discard() {
        NativeImage[] images = levels;
        levels = null;
        if (images != null) {
            for (NativeImage image : images) {
                image.close();
            }
        }
    }

    @Override
    public void close() {
        discard();
        super.close();
    }
}
