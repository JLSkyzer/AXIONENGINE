package dev.axion.forge.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.axion.forge.AxionEntity;
import dev.axion.render.BackendSelection;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * Backend VANILLA_CONSUMER (C-61, ADR-118) : rend à travers les {@code RenderType} et
 * {@code VertexConsumer} de Minecraft, sans aucun appel OpenGL direct (R-741).
 *
 * <p>Tranche 1 : chaque assembly est dessinée comme une <b>boîte</b> aux dimensions de son
 * entité, à sa position interpolée — ce qui valide la passe centrale de bout en bout (stage,
 * transformations, interpolation, lumière) sans dépendre du maillage. La tranche 2 remplace
 * cette boîte par la géométrie GEOM décodée en natif.
 */
final class VanillaConsumerBackend implements RenderBackend {

    /** Couleur de la boîte (RGB). */
    private static final float RED = 0.35f;
    private static final float GREEN = 0.55f;
    private static final float BLUE = 0.95f;

    /** Ombrage par face : sans éclairage propre, il donne à lire le volume. */
    private static final float SHADE_TOP = 1.0f;
    private static final float SHADE_X = 0.8f;
    private static final float SHADE_Z = 0.65f;
    private static final float SHADE_BOTTOM = 0.5f;

    /** Éclairement minimal, pour qu'un objet dans le noir reste lisible. */
    private static final float MIN_LIGHT = 0.3f;

    @Override
    public BackendSelection.Kind kind() {
        return BackendSelection.Kind.VANILLA;
    }

    @Override
    public void renderOpaque(Frame frame) {
        if (frame.assemblies().isEmpty()) {
            return;
        }
        RenderType type = RenderType.debugQuads();
        VertexConsumer out = frame.buffers().getBuffer(type);
        for (AxionEntity assembly : frame.assemblies()) {
            drawBox(frame, assembly, out);
        }
        frame.buffers().endBatch(type);
    }

    private static void drawBox(Frame frame, AxionEntity assembly, VertexConsumer out) {
        float pt = frame.partialTick();
        double x = Mth.lerp(pt, assembly.xo, assembly.getX());
        double y = Mth.lerp(pt, assembly.yo, assembly.getY());
        double z = Mth.lerp(pt, assembly.zo, assembly.getZ());
        float half = assembly.getBbWidth() / 2.0f;
        float height = assembly.getBbHeight();

        int packed = LevelRenderer.getLightColor(
                assembly.level(), BlockPos.containing(x, y + height / 2.0, z));
        int level = Math.max(LightTexture.block(packed), LightTexture.sky(packed));
        float light = MIN_LIGHT + (1.0f - MIN_LIGHT) * level / 15.0f;

        PoseStack pose = frame.pose();
        Vec3 camera = frame.camera();
        pose.pushPose();
        pose.translate(x - camera.x, y - camera.y, z - camera.z);
        Matrix4f m = pose.last().pose();

        float x0 = -half;
        float x1 = half;
        float z0 = -half;
        float z1 = half;
        float y0 = 0.0f;
        float y1 = height;

        quad(out, m, light * SHADE_TOP, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0);
        quad(out, m, light * SHADE_BOTTOM, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
        quad(out, m, light * SHADE_Z, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0);
        quad(out, m, light * SHADE_Z, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
        quad(out, m, light * SHADE_X, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
        quad(out, m, light * SHADE_X, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1);

        pose.popPose();
    }

    private static void quad(
            VertexConsumer out,
            Matrix4f m,
            float shade,
            float ax, float ay, float az,
            float bx, float by, float bz,
            float cx, float cy, float cz,
            float dx, float dy, float dz) {
        float r = RED * shade;
        float g = GREEN * shade;
        float b = BLUE * shade;
        out.vertex(m, ax, ay, az).color(r, g, b, 1.0f).endVertex();
        out.vertex(m, bx, by, bz).color(r, g, b, 1.0f).endVertex();
        out.vertex(m, cx, cy, cz).color(r, g, b, 1.0f).endVertex();
        out.vertex(m, dx, dy, dz).color(r, g, b, 1.0f).endVertex();
    }
}
