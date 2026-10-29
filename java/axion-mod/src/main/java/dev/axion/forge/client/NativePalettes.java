package dev.axion.forge.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.axion.render.NativeCulling;
import java.nio.ByteBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;

/**
 * Les palettes d'os du backend natif (C-66, ADR-155) : la table des matrices que le natif dépose dans
 * {@code RENDER_OUT} — matrices des dessins animés, puis palettes —, téléversée telle quelle, sans copie
 * Java (R-1460). Render thread seul (INV-12).
 *
 * <p><b>Chemin par défaut</b> (R-790) : un TBO {@code GL_RGBA32F}, trois texels par {@code mat4x3}. Le
 * shader lit la palette d'une instance à partir du {@code vec4} que porte son bloc ({@code palette_offset}).
 *
 * <p><b>Repli</b> (H-04) : un UBO lu par pages de {@link #PAGE_BYTES}, le minimum garanti d'un bloc
 * d'uniformes. Chaque dessin skinné lie la page qui commence au plus près avant sa palette, à l'alignement
 * que le pilote exige ; son bloc porte alors le décalage de sa palette dans la page. Il sert quand la table
 * dépasse ce que le TBO peut adresser, et le banc de rendu le force pour T-541.
 */
final class NativePalettes implements NativeCulling.MatrixTable, AutoCloseable {

    /** Une page d'UBO : 1024 {@code vec4}, le minimum garanti de {@code GL_MAX_UNIFORM_BLOCK_SIZE}. */
    static final int PAGE_BYTES = 16 * 1024;

    private static final int VEC4_BYTES = 16;
    private static final int MATRIX_VEC4 = 3;

    private int buffer;
    private int texture;
    /** {@code GL_UNIFORM_BUFFER_OFFSET_ALIGNMENT}, et les texels qu'un TBO peut adresser. */
    private int alignment;
    private int maxTexels;
    /** Le repli UBO est demandé (banc de rendu, T-541). */
    private boolean forcedUbo;
    /** Le chemin de la table de cette frame. */
    private boolean ubo;

    /**
     * Force le repli UBO, ou rend le TBO (T-541).
     *
     * @param wanted vrai pour l'UBO paginé
     */
    void forceUbo(boolean wanted) {
        forcedUbo = wanted;
    }

    /** {@return vrai si la table de cette frame est lue par pages d'UBO} */
    boolean ubo() {
        return ubo;
    }

    @Override
    public void upload(ByteBuffer renderOut, int offset, int bytes) {
        RenderSystem.assertOnRenderThread();
        if (buffer == 0) {
            buffer = GL15.glGenBuffers();
            NativeGlObjects.created();
            texture = GL11.glGenTextures();
            NativeGlObjects.created();
            alignment = Math.max(VEC4_BYTES, GL11.glGetInteger(GL31.GL_UNIFORM_BUFFER_OFFSET_ALIGNMENT));
            maxTexels = GL11.glGetInteger(GL31.GL_MAX_TEXTURE_BUFFER_SIZE);
        }
        ubo = forcedUbo || bytes / VEC4_BYTES > maxTexels;
        ByteBuffer table = renderOut.duplicate().order(renderOut.order());
        table.limit(offset + bytes).position(offset);
        int target = ubo ? GL31.GL_UNIFORM_BUFFER : GL31.GL_TEXTURE_BUFFER;
        GL15.glBindBuffer(target, buffer);
        // Une page liée près de la fin de la table reste dans le tampon : il en a une de plus.
        GL15.glBufferData(target, (long) bytes + (ubo ? PAGE_BYTES : 0), GL15.GL_STREAM_DRAW);
        GL15.glBufferSubData(target, 0, table);
        GL15.glBindBuffer(target, 0);
        if (!ubo) {
            GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, texture);
            GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER, GL30.GL_RGBA32F, buffer);
            GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, 0);
        }
    }

    /**
     * {@return le premier {@code vec4} de la palette qui commence à la matrice {@code first} : dans le TBO,
     * ou dans la page d'UBO que {@link #bindPage} lie}
     *
     * @param first première matrice de la palette dans la table
     */
    int offset(int first) {
        long vec4 = (long) first * MATRIX_VEC4;
        if (!ubo) {
            return (int) vec4;
        }
        long at = vec4 * VEC4_BYTES;
        return (int) ((at - page(at)) / VEC4_BYTES);
    }

    /** Lie le TBO à son unité pour une passe ; rien sous le repli UBO. */
    void bindForPass() {
        if (!ubo && texture != 0) {
            RenderSystem.activeTexture(GL13.GL_TEXTURE0 + NativeShaders.PALETTE_UNIT);
            GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, texture);
        }
    }

    /**
     * Lie la page d'UBO de la palette qui commence à la matrice {@code first}, pour le dessin qui suit.
     *
     * @param first première matrice de la palette dans la table
     */
    void bindPage(int first) {
        long at = (long) first * MATRIX_VEC4 * VEC4_BYTES;
        GL30.glBindBufferRange(GL31.GL_UNIFORM_BUFFER, NativeShaders.PALETTE_BINDING, buffer, page(at), PAGE_BYTES);
    }

    /** Délie le TBO et la page d'UBO : Minecraft n'en attend aucun. */
    void unbind() {
        if (texture == 0) {
            return;
        }
        RenderSystem.activeTexture(GL13.GL_TEXTURE0 + NativeShaders.PALETTE_UNIT);
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, 0);
        GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, NativeShaders.PALETTE_BINDING, 0);
    }

    /** {@return le début de la page qui précède l'octet {@code at}, à l'alignement du pilote} */
    private long page(long at) {
        return at / alignment * alignment;
    }

    @Override
    public void close() {
        RenderSystem.assertOnRenderThread();
        if (buffer != 0) {
            GL15.glDeleteBuffers(buffer);
            NativeGlObjects.deleted();
            GL11.glDeleteTextures(texture);
            NativeGlObjects.deleted();
            buffer = 0;
            texture = 0;
        }
    }
}
