package dev.axion.forge.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.axion.AxionMod;
import dev.axion.asset.GeometryTransfer;
import dev.axion.forge.AxionEntity;
import dev.axion.render.BackendSelection;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Backend VANILLA_CONSUMER (C-61, ADR-118) : rend à travers les {@code RenderType} et
 * {@code VertexConsumer} de Minecraft, sans aucun appel OpenGL direct (R-741).
 *
 * <p>Chaque assembly est dessinée avec la géométrie que le natif a décodée (ADR-119) : un
 * dessin par node visible, à sa transformation de repos, sous la position interpolée de
 * l'entité. Les sommets passent par les types de rendu d'entité de Minecraft, ce qui leur
 * donne la lumière du monde (lightmap) et l'ombrage directionnel des entités vanilla.
 *
 * <p>Tant que la géométrie n'est pas prête — chargement en arrière-plan, échec, multijoueur
 * —, l'assembly est dessinée comme la <b>boîte</b> de T1, aux dimensions de son entité :
 * rien ne disparaît, et la boîte dit qu'il manque quelque chose.
 *
 * <p>Pas de matériaux encore (section MATL hors périmètre d'ADR-119) : une texture blanche,
 * modulée par la couleur des sommets. Les meshes transparents attendent la passe translucide
 * (T3) ; l'orientation du corps, sa propre tranche.
 */
final class VanillaConsumerBackend implements RenderBackend {

    /** Texture neutre : la couleur vient des sommets. */
    private static final ResourceLocation WHITE =
            new ResourceLocation(AxionMod.MODID, "textures/misc/white.png");

    /** Meshes opaques, faces arrière éliminées. */
    private static final RenderType SOLID = RenderType.entitySolid(WHITE);

    /** Meshes {@code DOUBLE_SIDED} : les deux faces sont dessinées. */
    private static final RenderType DOUBLE_SIDED = RenderType.entityCutoutNoCull(WHITE);

    /** Couleur de la boîte de repli (RGB). */
    private static final float RED = 0.35f;
    private static final float GREEN = 0.55f;
    private static final float BLUE = 0.95f;

    /** Ombrage par face de la boîte : sans éclairage propre, il donne à lire le volume. */
    private static final float SHADE_TOP = 1.0f;
    private static final float SHADE_X = 0.8f;
    private static final float SHADE_Z = 0.65f;
    private static final float SHADE_BOTTOM = 0.5f;

    /** Éclairement minimal de la boîte, pour qu'elle reste lisible dans le noir. */
    private static final float MIN_LIGHT = 0.3f;

    /** En deçà, une normale transformée n'a plus de direction exploitable. */
    private static final float MIN_NORMAL_LENGTH_SQUARED = 1e-12f;

    @Override
    public BackendSelection.Kind kind() {
        return BackendSelection.Kind.VANILLA;
    }

    @Override
    public void renderOpaque(Frame frame) {
        boolean anyMesh = false;
        boolean anyBox = false;
        for (Assembly assembly : frame.assemblies()) {
            if (assembly.mesh() != null) {
                anyMesh = true;
            } else {
                anyBox = true;
            }
        }
        // Un lot par type de rendu : alterner les types d'une assembly à l'autre viderait le
        // tampon partagé à chaque changement.
        if (anyMesh) {
            drawMeshes(frame, SOLID, false);
            drawMeshes(frame, DOUBLE_SIDED, true);
        }
        if (anyBox) {
            drawBoxes(frame);
        }
    }

    /** Dessine les meshes opaques d'un côté (simple ou double face), en un lot. */
    private static void drawMeshes(Frame frame, RenderType type, boolean doubleSided) {
        VertexConsumer out = null;
        for (Assembly assembly : frame.assemblies()) {
            GeometryTransfer mesh = assembly.mesh();
            if (mesh == null) {
                continue;
            }
            Placement at = null;
            for (GeometryTransfer.Draw draw : mesh.draws()) {
                GeometryTransfer.Mesh part = mesh.meshes().get(draw.mesh());
                if (part.has(GeometryTransfer.MESH_TRANSPARENT)
                        || part.has(GeometryTransfer.MESH_DOUBLE_SIDED) != doubleSided) {
                    continue;
                }
                if (out == null) {
                    out = frame.buffers().getBuffer(type);
                }
                if (at == null) {
                    at = Placement.of(frame, assembly.entity());
                }
                drawPart(frame, at, mesh, part, draw, out);
            }
        }
        if (out != null) {
            frame.buffers().endBatch(type);
        }
    }

    /**
     * Dessine un mesh à la transformation de repos de son node.
     *
     * <p>Les types de rendu d'entité sont en quads : chaque triangle y entre comme un quad
     * dégénéré {@code (a, b, c, c)}, dont le second triangle n'a pas d'aire.
     */
    private static void drawPart(
            Frame frame,
            Placement at,
            GeometryTransfer mesh,
            GeometryTransfer.Mesh part,
            GeometryTransfer.Draw draw,
            VertexConsumer out) {
        PoseStack pose = frame.pose();
        Vec3 camera = frame.camera();
        pose.pushPose();
        pose.translate(at.x - camera.x, at.y - camera.y, at.z - camera.z);

        // mat4x3 colonne-major (ADR-119) : trois axes puis la translation. Le constructeur
        // de JOML prend lui aussi les colonnes dans l'ordre.
        float[] m = draw.model();
        Matrix4f model = new Matrix4f(
                m[0], m[1], m[2], 0.0f,
                m[3], m[4], m[5], 0.0f,
                m[6], m[7], m[8], 0.0f,
                m[9], m[10], m[11], 1.0f);
        PoseStack.Pose last = pose.last();
        last.pose().mul(model);
        // Normales : inverse-transposée, juste même sous une échelle non uniforme.
        last.normal().mul(model.normal(new Matrix3f()));
        // Une transformation miroir retourne les faces : l'ordre des sommets la compense.
        boolean mirrored = model.determinant3x3() < 0.0f;

        Matrix4f position = last.pose();
        Matrix3f normal = last.normal();
        Vector3f scratch = new Vector3f();
        int[] indices = mesh.indices();
        int base = part.vertexOffset();
        int end = part.indexOffset() + part.indexCount();
        for (int i = part.indexOffset(); i < end; i += 3) {
            int a = base + indices[i];
            int b = base + indices[i + 1];
            int c = base + indices[i + 2];
            if (mirrored) {
                int swap = b;
                b = c;
                c = swap;
            }
            vertex(out, position, normal, scratch, mesh, a, at.light);
            vertex(out, position, normal, scratch, mesh, b, at.light);
            vertex(out, position, normal, scratch, mesh, c, at.light);
            vertex(out, position, normal, scratch, mesh, c, at.light);
        }
        pose.popPose();
    }

    private static void vertex(
            VertexConsumer out,
            Matrix4f position,
            Matrix3f normal,
            Vector3f scratch,
            GeometryTransfer mesh,
            int vertex,
            int light) {
        float[] positions = mesh.positions();
        float[] normals = mesh.normals();
        float[] uvs = mesh.uvs();
        byte[] colors = mesh.colors();

        // Le format d'entité stocke la normale sur trois octets, sans la renormaliser : elle
        // doit sortir unitaire de la transformation.
        scratch.set(normals[vertex * 3], normals[vertex * 3 + 1], normals[vertex * 3 + 2]).mul(normal);
        if (scratch.lengthSquared() < MIN_NORMAL_LENGTH_SQUARED) {
            scratch.set(0.0f, 1.0f, 0.0f).mul(normal);
        }
        scratch.normalize();

        out.vertex(position, positions[vertex * 3], positions[vertex * 3 + 1], positions[vertex * 3 + 2])
                .color(
                        Byte.toUnsignedInt(colors[vertex * 4]),
                        Byte.toUnsignedInt(colors[vertex * 4 + 1]),
                        Byte.toUnsignedInt(colors[vertex * 4 + 2]),
                        Byte.toUnsignedInt(colors[vertex * 4 + 3]))
                .uv(uvs[vertex * 2], uvs[vertex * 2 + 1])
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(light)
                .normal(scratch.x(), scratch.y(), scratch.z())
                .endVertex();
    }

    /**
     * Position interpolée d'une assembly et lumière du monde à son centre.
     *
     * @param x position interpolée, bas-centre de l'entité
     * @param y position interpolée
     * @param z position interpolée
     * @param light lumière empaquetée (bloc et ciel)
     */
    private record Placement(double x, double y, double z, int light) {

        static Placement of(Frame frame, AxionEntity assembly) {
            float pt = frame.partialTick();
            double x = Mth.lerp(pt, assembly.xo, assembly.getX());
            double y = Mth.lerp(pt, assembly.yo, assembly.getY());
            double z = Mth.lerp(pt, assembly.zo, assembly.getZ());
            int light = LevelRenderer.getLightColor(
                    assembly.level(), BlockPos.containing(x, y + assembly.getBbHeight() / 2.0, z));
            return new Placement(x, y, z, light);
        }
    }

    /** Boîtes de repli des assemblies sans géométrie prête, en un lot. */
    private static void drawBoxes(Frame frame) {
        RenderType type = RenderType.debugQuads();
        VertexConsumer out = frame.buffers().getBuffer(type);
        for (Assembly assembly : frame.assemblies()) {
            if (assembly.mesh() == null) {
                drawBox(frame, assembly.entity(), out);
            }
        }
        frame.buffers().endBatch(type);
    }

    private static void drawBox(Frame frame, AxionEntity assembly, VertexConsumer out) {
        Placement at = Placement.of(frame, assembly);
        float half = assembly.getBbWidth() / 2.0f;
        float height = assembly.getBbHeight();

        int level = Math.max(LightTexture.block(at.light), LightTexture.sky(at.light));
        float light = MIN_LIGHT + (1.0f - MIN_LIGHT) * level / 15.0f;

        PoseStack pose = frame.pose();
        Vec3 camera = frame.camera();
        pose.pushPose();
        pose.translate(at.x - camera.x, at.y - camera.y, at.z - camera.z);
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
