package dev.axion.forge.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.axion.render.TextureRules;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GLCapabilities;

/**
 * Le filtrage anisotrope des textures d'AXION (ADR-164, R-570) : {@code render.anisotropy}, borné par le maximum du
 * GPU, posé sur chaque texture à son téléversement — donc dans les deux backends.
 *
 * <p>C'est un paramètre de l'objet texture : posé une fois par {@code GlStateManager}, comme Minecraft pose son
 * filtre, il vaut ensuite à chaque échantillonnage. Le dessin du backend vanilla n'appelle toujours aucune fonction
 * GL (R-741, lu comme portant sur le dessin depuis ADR-146). Sans l'extension, rien n'est posé.
 */
public final class TextureAnisotropy {

    /** {@code GL_TEXTURE_MAX_ANISOTROPY} : cœur de GL 4.6, {@code ARB_} et {@code EXT_texture_filter_anisotropic}. */
    static final int TEXTURE_MAX_ANISOTROPY = 0x84FE;

    /** {@code GL_MAX_TEXTURE_MAX_ANISOTROPY} : le plus haut niveau que le GPU accepte. */
    static final int MAX_TEXTURE_MAX_ANISOTROPY = 0x84FF;

    private static volatile long requested = 1;
    private static volatile boolean supported;
    private static volatile float gpuMax = 1f;
    private static volatile float level = 1f;
    private static volatile float lastRead = Float.NaN;
    private static final AtomicLong APPLICATIONS = new AtomicLong();

    private TextureAnisotropy() {}

    /**
     * Lit ce que le GPU accepte et retient le niveau à poser, sur le fil de rendu, au choix du backend.
     *
     * @param requestedLevel {@code render.anisotropy}, de 1 à 16
     */
    static void configure(long requestedLevel) {
        RenderSystem.assertOnRenderThreadOrInit();
        GLCapabilities caps = GL.getCapabilities();
        boolean available = caps.OpenGL46 || caps.GL_ARB_texture_filter_anisotropic
                || caps.GL_EXT_texture_filter_anisotropic;
        float max = available ? GL11.glGetFloat(MAX_TEXTURE_MAX_ANISOTROPY) : 1f;
        requested = requestedLevel;
        gpuMax = max;
        supported = available;
        level = TextureRules.anisotropy(requestedLevel, max);
    }

    /**
     * Pose le niveau sur la texture liée à {@code GL_TEXTURE_2D} de l'unité active, puis le relit pour le banc
     * (ADR-164). Rien sans l'extension.
     */
    static void applyToBoundTexture() {
        if (!supported) {
            return;
        }
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, TEXTURE_MAX_ANISOTROPY, level);
        lastRead = GL11.glGetTexParameterf(GL11.GL_TEXTURE_2D, TEXTURE_MAX_ANISOTROPY);
        APPLICATIONS.incrementAndGet();
    }

    /** {@return {@code render.anisotropy}, tel que lu au choix du backend} */
    public static long requested() {
        return requested;
    }

    /** {@return vrai si le GPU offre le filtrage anisotrope} */
    public static boolean supported() {
        return supported;
    }

    /** {@return le plus haut niveau que le GPU accepte ; 1 sans l'extension} */
    public static float gpuMax() {
        return gpuMax;
    }

    /** {@return le niveau posé sur chaque texture d'AXION} */
    public static float level() {
        return level;
    }

    /** {@return le niveau relu sur la dernière texture téléversée ; {@code NaN} avant la première} */
    public static float lastRead() {
        return lastRead;
    }

    /** {@return le nombre de textures sur lesquelles le niveau a été posé} */
    public static long applications() {
        return APPLICATIONS.get();
    }

    /** {@return la ligne du journal au choix du backend (R-1493)} */
    static String describe() {
        if (!supported) {
            return "filtrage anisotrope absent du GPU : render.anisotropy = " + requested + " sans effet";
        }
        return String.format(Locale.ROOT, "filtrage anisotrope ×%.0f (render.anisotropy = %d, maximum du GPU ×%.0f)",
                level, requested, gpuMax);
    }
}
