package dev.axion.forge.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import net.minecraft.client.renderer.RenderType;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

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
 * referme ensuite comme il l'aurait fait sans AXION. La couche en place se déduit du stage.
 *
 * <p>Les autres stages sont publiés hors de toute couche, et pas forcément à l'état par défaut :
 * sous Embeddium et Oculus, shaderpack coupé, {@code AFTER_ENTITIES} arrive test de profondeur
 * allumé (banc sous shaders du 2026-10-12, R-1503), là où Minecraft seul le laisse éteint — et les
 * passes d'AXION finissent à l'état par défaut. Le garde y lit donc, à l'ouverture, ce que les
 * passes changent et que Minecraft suit — mélange et sa fonction, test, écriture et fonction de
 * profondeur, faces cachées —, et le rend par {@code RenderSystem} à la fermeture.
 *
 * <p>Le backend natif (C-60)
 * touche en outre lui-même ce que Minecraft ne suit pas — VAO, tampon de sommets — et le programme
 * (R-1501) : le garde lit ces trois liaisons à l'ouverture et les rend à la fermeture, pour tout
 * backend ; le traqueur de {@code BufferUploader}, qui croit savoir quel VAO est lié, est invalidé,
 * et Minecraft relie le sien au dessin suivant. Le contrôle de développement ({@link GlStateCheck},
 * R-1503) vérifie que l'état rendu est bien celui trouvé.
 *
 * <p>Render thread seul (R-1504).
 */
final class GlStateGuard {

    /** La couche de blocs dont l'état est en place à l'entrée de la passe, ou {@code null}. */
    private final RenderType layer;

    /** Liaisons trouvées à l'entrée de la passe. */
    private final int vertexArray;
    private final int arrayBuffer;
    private final int program;

    /** Hors couche : l'état trouvé, que les passes changent et que Minecraft suit ; {@code null} dans une couche. */
    private final int[] fixedFunction;

    private GlStateGuard(RenderType layer, int vertexArray, int arrayBuffer, int program, int[] fixedFunction) {
        this.layer = layer;
        this.vertexArray = vertexArray;
        this.arrayBuffer = arrayBuffer;
        this.program = program;
        this.fixedFunction = fixedFunction;
    }

    /**
     * Ouvre le garde d'une passe.
     *
     * @param stage stage auquel la passe est publiée
     * @return le garde, à refermer par {@link #restore()} quoi qu'il arrive
     */
    static GlStateGuard open(RenderLevelStageEvent.Stage stage) {
        RenderSystem.assertOnRenderThread();
        RenderType layer = layerOf(stage);
        return new GlStateGuard(
                layer,
                GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING),
                GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING),
                GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),
                layer == null ? readFixedFunction() : null);
    }

    /** {@return mélange, ses facteurs, profondeur et faces cachées, dans l'ordre de {@link #restore()}} */
    private static int[] readFixedFunction() {
        return new int[] {
            GL11.glIsEnabled(GL11.GL_BLEND) ? 1 : 0,
            GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB),
            GL11.glGetInteger(GL14.GL_BLEND_DST_RGB),
            GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA),
            GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA),
            GL11.glIsEnabled(GL11.GL_DEPTH_TEST) ? 1 : 0,
            GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK) ? 1 : 0,
            GL11.glGetInteger(GL11.GL_DEPTH_FUNC),
            GL11.glIsEnabled(GL11.GL_CULL_FACE) ? 1 : 0,
        };
    }

    /** Rétablit l'état trouvé à l'entrée de la passe. */
    void restore() {
        GlStateManager._glBindVertexArray(vertexArray);
        GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, arrayBuffer);
        GlStateManager._glUseProgram(program);
        BufferUploader.invalidate();
        if (layer != null) {
            layer.setupRenderState();
            return;
        }
        int[] found = fixedFunction;
        if (found[0] != 0) {
            RenderSystem.enableBlend();
        } else {
            RenderSystem.disableBlend();
        }
        RenderSystem.blendFuncSeparate(found[1], found[2], found[3], found[4]);
        if (found[5] != 0) {
            RenderSystem.enableDepthTest();
        } else {
            RenderSystem.disableDepthTest();
        }
        RenderSystem.depthMask(found[6] != 0);
        RenderSystem.depthFunc(found[7]);
        if (found[8] != 0) {
            RenderSystem.enableCull();
        } else {
            RenderSystem.disableCull();
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
