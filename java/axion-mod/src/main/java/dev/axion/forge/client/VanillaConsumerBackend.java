package dev.axion.forge.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.axion.AxionMod;
import dev.axion.asset.GeometryTransfer;
import dev.axion.forge.AxionEntity;
import dev.axion.render.BackendSelection;
import dev.axion.render.EntityShader;
import dev.axion.render.MeshLook;
import dev.axion.render.RenderAsset;
import dev.axion.render.SurfacePass;
import dev.axion.render.TextureBinding;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Backend VANILLA_CONSUMER (C-61, ADR-118) : rend à travers les {@code RenderType} et
 * {@code VertexConsumer} de Minecraft, sans aucun appel OpenGL direct (R-741).
 *
 * <p>Chaque assembly est dessinée avec la géométrie que le natif a décodée (ADR-119) : un dessin
 * par mesh de node visible, à sa transformation de repos, sous la position interpolée de
 * l'entité. Chaque mesh y prend l'apparence de son matériau (ADR-122 §7) : sa texture d'albedo,
 * filtrée comme à son téléversement, ou la texture neutre ; la couleur de ses sommets, établie au
 * chargement ; la pleine lumière s'il est sans éclairage. Les sommets passent par les shaders
 * d'entité de Minecraft, ce qui leur donne la lumière du monde (lightmap) et l'ombrage
 * directionnel des entités vanilla.
 *
 * <p>Passe 1, les surfaces opaques, puis passe 2, les surfaces découpées (§19.10) : dans chaque
 * passe, un lot par type de rendu. Les surfaces translucides attendent la passe 4, et l'émissive
 * la passe 5 (ADR-122, T-b3).
 *
 * <p>Tant que rien n'est prêt — chargement en arrière-plan, échec, multijoueur —, l'assembly est
 * dessinée comme la <b>boîte</b> de T1, aux dimensions de son entité : rien ne disparaît, et la
 * boîte dit qu'il manque quelque chose.
 *
 * <p>Le maillage tourne avec le corps, autour de son origine — celle de l'asset, au bas-centre
 * —, à l'orientation interpolée entre deux ticks, posé par {@link AssemblyPlacement}. La
 * hitbox vanilla, elle, reste alignée sur les axes : c'est l'AABB qui enclôt le corps tourné
 * (R-702, ADR-120).
 */
final class VanillaConsumerBackend implements RenderBackend {

    /**
     * Texture neutre (ADR-122 §7) : blanche, la couleur vient du matériau et des sommets.
     *
     * <p>Le constructeur est marqué déprécié, mais il vient de Minecraft 1.20.1 et existe dans
     * toute la branche Forge 47 ; {@code fromNamespaceAndPath}, rétroporté de 1.21, n'est
     * vérifié que dans la version de compilation (47.4.23), alors que le mod accepte
     * {@code [47,)}. Le remplacer risquerait un {@code NoSuchMethodError} sur une 47
     * antérieure.
     */
    private static final ResourceLocation WHITE =
            new ResourceLocation(AxionMod.MODID, "textures/misc/white.png");

    /** La texture neutre, liée comme une ressource vanilla : au plus proche, sans mipmap. */
    private static final TextureBinding NEUTRAL = new TextureBinding(WHITE.toString(), false, false);

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
        List<Placed> placed = new ArrayList<>();
        boolean anyBox = false;
        for (Assembly assembly : frame.assemblies()) {
            if (assembly.asset() != null) {
                placed.add(new Placed(assembly.asset(), AssemblyPlacement.of(frame.partialTick(), assembly.entity())));
            } else {
                anyBox = true;
            }
        }
        if (!placed.isEmpty()) {
            drawPass(frame, placed, SurfacePass.OPAQUE);
            drawPass(frame, placed, SurfacePass.CUTOUT);
        }
        if (anyBox) {
            drawBoxes(frame);
        }
    }

    /**
     * Dessine les surfaces d'une passe, un lot par type de rendu : alterner les types d'un mesh à
     * l'autre viderait le tampon partagé à chaque changement.
     */
    private static void drawPass(Frame frame, List<Placed> placed, SurfacePass pass) {
        Map<RenderType, List<Part>> batches = new LinkedHashMap<>();
        for (Placed assembly : placed) {
            RenderAsset asset = assembly.asset();
            for (GeometryTransfer.Draw draw : asset.mesh().draws()) {
                MeshLook look = asset.look(draw.mesh());
                if (look.pass() != pass || !look.visible()) {
                    continue;
                }
                TextureBinding albedo = asset.albedo(draw.mesh());
                RenderType type = AxionRenderTypes.entity(
                        albedo == null ? NEUTRAL : albedo,
                        EntityShader.of(pass, look.doubleSided()),
                        look.doubleSided());
                batches.computeIfAbsent(type, unused -> new ArrayList<>()).add(new Part(assembly, draw, look));
            }
        }
        for (Map.Entry<RenderType, List<Part>> batch : batches.entrySet()) {
            VertexConsumer out = frame.buffers().getBuffer(batch.getKey());
            for (Part part : batch.getValue()) {
                drawPart(frame, part, out);
            }
            frame.buffers().endBatch(batch.getKey());
        }
    }

    /**
     * Dessine un mesh à la transformation de repos de son node.
     *
     * <p>Les types de rendu d'entité sont en quads : chaque triangle y entre comme un quad
     * dégénéré {@code (a, b, c, c)}, dont le second triangle n'a pas d'aire.
     */
    private static void drawPart(Frame frame, Part part, VertexConsumer out) {
        GeometryTransfer mesh = part.assembly().asset().mesh();
        GeometryTransfer.Mesh desc = mesh.meshes().get(part.draw().mesh());
        AssemblyPlacement at = part.assembly().at();
        MeshLook look = part.look();
        int light = look.fullbright() ? LightTexture.FULL_BRIGHT : at.light();

        PoseStack pose = frame.pose();
        pose.pushPose();
        at.apply(pose, frame.camera());

        // mat4x3 colonne-major (ADR-119) : trois axes puis la translation. Le constructeur
        // de JOML prend lui aussi les colonnes dans l'ordre.
        float[] m = part.draw().model();
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
        int base = desc.vertexOffset();
        int end = desc.indexOffset() + desc.indexCount();
        for (int i = desc.indexOffset(); i < end; i += 3) {
            int a = indices[i];
            int b = indices[i + 1];
            int c = indices[i + 2];
            if (mirrored) {
                int swap = b;
                b = c;
                c = swap;
            }
            vertex(out, position, normal, scratch, mesh, base, a, look, light);
            vertex(out, position, normal, scratch, mesh, base, b, look, light);
            vertex(out, position, normal, scratch, mesh, base, c, look, light);
            vertex(out, position, normal, scratch, mesh, base, c, look, light);
        }
        pose.popPose();
    }

    /**
     * Émet un sommet.
     *
     * @param base premier sommet du mesh, dans le tableau des sommets
     * @param local rang du sommet dans le mesh, tel que l'écrivent ses indices
     */
    private static void vertex(
            VertexConsumer out,
            Matrix4f position,
            Matrix3f normal,
            Vector3f scratch,
            GeometryTransfer mesh,
            int base,
            int local,
            MeshLook look,
            int light) {
        int vertex = base + local;
        float[] positions = mesh.positions();
        float[] normals = mesh.normals();
        float[] uvs = mesh.uvs();

        // Le format d'entité stocke la normale sur trois octets, sans la renormaliser : elle
        // doit sortir unitaire de la transformation.
        scratch.set(normals[vertex * 3], normals[vertex * 3 + 1], normals[vertex * 3 + 2]).mul(normal);
        if (scratch.lengthSquared() < MIN_NORMAL_LENGTH_SQUARED) {
            scratch.set(0.0f, 1.0f, 0.0f).mul(normal);
        }
        scratch.normalize();

        int color = look.colorOf(local);
        out.vertex(position, positions[vertex * 3], positions[vertex * 3 + 1], positions[vertex * 3 + 2])
                .color((color >>> 16) & 0xFF, (color >>> 8) & 0xFF, color & 0xFF, color >>> 24)
                .uv(uvs[vertex * 2], uvs[vertex * 2 + 1])
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(light)
                .normal(scratch.x(), scratch.y(), scratch.z())
                .endVertex();
    }

    /** Boîtes de repli des assemblies sans géométrie prête, en un lot. */
    private static void drawBoxes(Frame frame) {
        RenderType type = RenderType.debugQuads();
        VertexConsumer out = frame.buffers().getBuffer(type);
        for (Assembly assembly : frame.assemblies()) {
            if (assembly.asset() == null) {
                drawBox(frame, assembly.entity(), out);
            }
        }
        frame.buffers().endBatch(type);
    }

    private static void drawBox(Frame frame, AxionEntity assembly, VertexConsumer out) {
        AssemblyPlacement at = AssemblyPlacement.of(frame.partialTick(), assembly);
        float half = assembly.getBbWidth() / 2.0f;
        float height = assembly.getBbHeight();

        int level = Math.max(LightTexture.block(at.light()), LightTexture.sky(at.light()));
        float light = MIN_LIGHT + (1.0f - MIN_LIGHT) * level / 15.0f;

        PoseStack pose = frame.pose();
        pose.pushPose();
        at.apply(pose, frame.camera());
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

    /** Une assembly prête, posée une fois pour la frame. */
    private record Placed(RenderAsset asset, AssemblyPlacement at) {}

    /** Un mesh à dessiner : son assembly, son dessin au repos, son apparence. */
    private record Part(Placed assembly, GeometryTransfer.Draw draw, MeshLook look) {}
}
