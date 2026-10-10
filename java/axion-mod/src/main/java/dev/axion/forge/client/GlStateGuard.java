package dev.axion.forge.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.RenderType;
import net.minecraftforge.client.event.RenderLevelStageEvent;

/**
 * Garde d'une passe d'AXION (R-1502) : rend à Minecraft, à la sortie de la passe, l'état GL qu'elle
 * a trouvé — par {@code RenderSystem} seulement (R-1500).
 *
 * <p>Forge publie les stages des couches de blocs, {@code AFTER_TRANSLUCENT_BLOCKS} compris, depuis
 * {@code LevelRenderer.renderChunkLayer}, <b>avant</b> que la couche ne referme son état : la passe
 * trouve celui de la couche en place (bytecode de Forge 47.4.23 : {@code dispatchRenderStage}, puis
 * {@code RenderType.clearRenderState}). Les {@code RenderType} d'AXION, en se refermant, ramènent
 * l'état aux valeurs par défaut — mélange et test de profondeur coupés —, et un autre mod abonné au
 * même stage le trouverait cassé. Le garde rejoue donc la mise en place de la couche, que Minecraft
 * referme ensuite comme il l'aurait fait sans AXION. Les autres stages sont publiés hors de toute
 * couche, à l'état par défaut, que les {@code RenderType} d'AXION rendent tel quel : rien à
 * rétablir.
 *
 * <p>Aucune lecture de l'état GL : la couche en place se déduit du stage. Le contrôle de
 * développement ({@link GlStateCheck}, R-1503) vérifie que l'état rendu est bien celui trouvé. Le
 * backend natif (C-60), qui touchera lui-même VAO, tampons et programme, étendra ce garde (R-1501).
 *
 * <p>Render thread seul (R-1504).
 */
final class GlStateGuard {

    /** La couche de blocs dont l'état est en place à l'entrée de la passe, ou {@code null}. */
    private final RenderType layer;

    private GlStateGuard(RenderType layer) {
        this.layer = layer;
    }

    /**
     * Ouvre le garde d'une passe.
     *
     * @param stage stage auquel la passe est publiée
     * @return le garde, à refermer par {@link #restore()} quoi qu'il arrive
     */
    static GlStateGuard open(RenderLevelStageEvent.Stage stage) {
        RenderSystem.assertOnRenderThread();
        return new GlStateGuard(layerOf(stage));
    }

    /** Rétablit l'état trouvé à l'entrée de la passe. */
    void restore() {
        if (layer != null) {
            layer.setupRenderState();
        }
    }

    /**
     * {@return la couche de blocs dont Forge publie ce stage, ou {@code null} si le stage est publié
     * hors de toute couche} La correspondance est celle de Forge, interrogée : aucune n'est écrite
     * ici.
     *
     * @param stage stage d'une passe
     */
    static RenderType layerOf(RenderLevelStageEvent.Stage stage) {
        for (RenderType layer : RenderType.chunkBufferLayers()) {
            if (RenderLevelStageEvent.Stage.fromRenderType(layer) == stage) {
                return layer;
            }
        }
        return null;
    }
}
