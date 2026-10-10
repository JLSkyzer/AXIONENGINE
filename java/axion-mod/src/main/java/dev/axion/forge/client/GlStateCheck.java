package dev.axion.forge.client;

import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Contrôle de l'état GL autour des passes d'AXION, en développement (R-1503, T-472, ADR-126).
 *
 * <p>Sous {@code -Daxion.debug.gl=true}, chaque passe relève l'état GL réel avant et après elle, et
 * vide {@code glGetError} après elle ; une différence ou une erreur est comptée et journalisée. Coût
 * nul en production : sans la propriété, rien n'est lu.
 *
 * <p>Sont comparés les états que Minecraft garde en cache et qu'une passe doit lui rendre tels
 * qu'elle les a trouvés (R-1500) : mélange et ses fonctions, profondeur, faces cachées, masque de
 * couleur, décalage de polygones, scissor, viewport, FBO de lecture et d'écriture, unité de texture
 * active ; puis ce que le backend natif touche lui-même et que le garde rend (R-1501, R-1502) : sens
 * des faces avant, VAO, tampon de sommets lié, programme. Les textures liées n'y sont pas : elles
 * ne changent que par les traqueurs de Minecraft, qui les relient à chaque dessin.
 *
 * <p>Les erreurs déjà en attente à l'entrée d'une passe ne sont pas les siennes : elles sont vidées
 * et comptées à part.
 *
 * <p>Render thread seul (R-1504).
 */
public final class GlStateCheck {

    /** {@code -Daxion.debug.gl=true} : le contrôle est actif. Lu une fois. */
    public static final boolean ENABLED = Boolean.getBoolean("axion.debug.gl");

    private static final Logger LOGGER = LoggerFactory.getLogger("axion");

    /** Au-delà, les écarts sont comptés sans être journalisés : le journal reste lisible. */
    private static final int LOG_LIMIT = 20;

    /** Erreurs lues d'un coup au plus : un pilote peut en accumuler, jamais sans fin. */
    private static final int MAX_ERRORS_DRAINED = 64;

    /** Noms des valeurs relevées, dans l'ordre de {@link #read()}. */
    private static final String[] FIELDS = {
        "GL_BLEND", "GL_BLEND_SRC_RGB", "GL_BLEND_DST_RGB", "GL_BLEND_SRC_ALPHA", "GL_BLEND_DST_ALPHA",
        "GL_BLEND_EQUATION_RGB", "GL_BLEND_EQUATION_ALPHA",
        "GL_DEPTH_TEST", "GL_DEPTH_WRITEMASK", "GL_DEPTH_FUNC",
        "GL_CULL_FACE", "GL_CULL_FACE_MODE",
        "GL_COLOR_WRITEMASK.r", "GL_COLOR_WRITEMASK.g", "GL_COLOR_WRITEMASK.b", "GL_COLOR_WRITEMASK.a",
        "GL_POLYGON_OFFSET_FILL", "GL_POLYGON_OFFSET_FACTOR", "GL_POLYGON_OFFSET_UNITS",
        "GL_SCISSOR_TEST", "GL_SCISSOR_BOX.x", "GL_SCISSOR_BOX.y", "GL_SCISSOR_BOX.w", "GL_SCISSOR_BOX.h",
        "GL_VIEWPORT.x", "GL_VIEWPORT.y", "GL_VIEWPORT.w", "GL_VIEWPORT.h",
        "GL_DRAW_FRAMEBUFFER_BINDING", "GL_READ_FRAMEBUFFER_BINDING",
        "GL_ACTIVE_TEXTURE",
        "GL_COLOR_LOGIC_OP", "GL_STENCIL_TEST",
        "GL_FRONT_FACE",
        "GL_VERTEX_ARRAY_BINDING", "GL_ARRAY_BUFFER_BINDING", "GL_CURRENT_PROGRAM",
    };

    private static long checks;
    private static long differences;
    private static long errors;
    private static long errorsBeforePasses;
    private static long logged;

    private GlStateCheck() {}

    /** État GL relevé à l'entrée d'une passe. */
    public static final class Snapshot {
        private final int[] values;

        private Snapshot(int[] values) {
            this.values = values;
        }
    }

    /**
     * Entrée d'une passe : vide les erreurs déjà en attente, qui ne sont pas les siennes, et relève
     * l'état.
     *
     * @return le relevé, à rendre à {@link #after}
     */
    public static Snapshot before() {
        RenderSystem.assertOnRenderThread();
        errorsBeforePasses += drainErrors().size();
        return new Snapshot(read());
    }

    /**
     * Sortie d'une passe : compare l'état à celui de l'entrée et vide les erreurs, qui sont alors
     * les siennes.
     *
     * @param pass nom de la passe, pour le journal
     * @param before relevé de l'entrée
     */
    public static void after(String pass, Snapshot before) {
        RenderSystem.assertOnRenderThread();
        checks++;
        List<Integer> raised = drainErrors();
        int[] now = read();
        for (int i = 0; i < now.length; i++) {
            if (now[i] != before.values[i]) {
                differences++;
                report("AXION : passe {} — {} n'est pas restauré : {} avant, {} après (R-1503)",
                        pass, FIELDS[i], before.values[i], now[i]);
            }
        }
        for (int code : raised) {
            errors++;
            report("AXION : passe {} — glGetError 0x{} (R-1503)", pass, Integer.toHexString(code), null, null);
        }
    }

    /** {@return les passes contrôlées depuis le lancement : zéro, et le contrôle n'a rien vu} */
    public static long checks() {
        return checks;
    }

    /** {@return les états trouvés différents à la sortie d'une passe, depuis le lancement} */
    public static long differences() {
        return differences;
    }

    /** {@return les erreurs GL levées pendant les passes d'AXION, depuis le lancement} */
    public static long errors() {
        return errors;
    }

    /** {@return les erreurs GL déjà en attente à l'entrée des passes : celles d'autres que lui} */
    public static long errorsBeforePasses() {
        return errorsBeforePasses;
    }

    private static void report(String format, Object pass, Object what, Object first, Object second) {
        if (logged < LOG_LIMIT) {
            logged++;
            LOGGER.error(format, pass, what, first, second);
        }
    }

    private static List<Integer> drainErrors() {
        List<Integer> raised = new ArrayList<>();
        for (int i = 0; i < MAX_ERRORS_DRAINED; i++) {
            int code = GL11.glGetError();
            if (code == GL11.GL_NO_ERROR) {
                break;
            }
            raised.add(code);
        }
        return raised;
    }

    /** Lit l'état, dans l'ordre de {@link #FIELDS}. Lecture seule : rien n'est modifié. */
    private static int[] read() {
        int[] values = new int[FIELDS.length];
        int i = 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            values[i++] = enabled(GL11.GL_BLEND);
            values[i++] = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
            values[i++] = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
            values[i++] = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
            values[i++] = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
            values[i++] = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB);
            values[i++] = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);
            values[i++] = enabled(GL11.GL_DEPTH_TEST);
            values[i++] = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK) ? 1 : 0;
            values[i++] = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
            values[i++] = enabled(GL11.GL_CULL_FACE);
            values[i++] = GL11.glGetInteger(GL11.GL_CULL_FACE_MODE);
            ByteBuffer mask = stack.malloc(4);
            GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, mask);
            for (int c = 0; c < 4; c++) {
                values[i++] = mask.get(c) != 0 ? 1 : 0;
            }
            values[i++] = enabled(GL11.GL_POLYGON_OFFSET_FILL);
            values[i++] = Float.floatToIntBits(GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_FACTOR));
            values[i++] = Float.floatToIntBits(GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_UNITS));
            values[i++] = enabled(GL11.GL_SCISSOR_TEST);
            int[] box = new int[4];
            GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, box);
            for (int value : box) {
                values[i++] = value;
            }
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, box);
            for (int value : box) {
                values[i++] = value;
            }
            values[i++] = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            values[i++] = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            values[i++] = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            values[i++] = enabled(GL11.GL_COLOR_LOGIC_OP);
            values[i++] = enabled(GL11.GL_STENCIL_TEST);
            values[i++] = GL11.glGetInteger(GL11.GL_FRONT_FACE);
            values[i++] = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
            values[i++] = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
            values[i++] = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        }
        return values;
    }

    private static int enabled(int capability) {
        return GL11.glIsEnabled(capability) ? 1 : 0;
    }
}
