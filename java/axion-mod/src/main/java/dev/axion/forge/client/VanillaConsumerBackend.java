package dev.axion.forge.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.axion.AxionMod;
import dev.axion.asset.GeometryTransfer;
import dev.axion.forge.AxionEntity;
import dev.axion.render.BackendSelection;
import dev.axion.render.DepthOrder;
import dev.axion.render.EntityShader;
import dev.axion.render.MeshLook;
import dev.axion.render.RenderAsset;
import dev.axion.render.SurfacePass;
import dev.axion.render.TextureBinding;
import dev.axion.render.TextureRegion;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
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
 * filtrée comme à son téléversement — texture individuelle, ou tuile de l'atlas de l'asset dont
 * ses coordonnées de texture sont ramenées à la place (T-c) —, ou la texture neutre ; la couleur de ses sommets, établie au
 * chargement ; la pleine lumière s'il est sans éclairage. Les sommets passent par les shaders
 * d'entité de Minecraft, ce qui leur donne la lumière du monde (lightmap) et l'ombrage
 * directionnel des entités vanilla.
 *
 * <p>Passe 1, les surfaces opaques, passe 2, les surfaces découpées, puis passe 5, l'émission
 * (§19.10) : dans chaque passe, un lot par type de rendu. L'émission s'additionne par-dessus la
 * surface, sans lumière du monde ; celle d'une surface translucide, dessinée avant elle (R-1570),
 * en est atténuée — approximation déclarée. Passe 4, plus tard dans la frame : les surfaces
 * translucides, du plus loin au plus près, en lots consécutifs dont Minecraft trie les quads.
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
            drawSurfaces(frame, placed, SurfacePass.OPAQUE);
            drawSurfaces(frame, placed, SurfacePass.CUTOUT);
            drawEmission(frame, placed);
        }
        if (anyBox) {
            drawBoxes(frame);
        }
    }

    @Override
    public void renderTranslucent(Frame frame) {
        List<Part> parts = new ArrayList<>();
        for (Assembly assembly : frame.assemblies()) {
            RenderAsset asset = assembly.asset();
            if (asset == null) {
                continue;
            }
            Placed placed = null;
            for (GeometryTransfer.Draw draw : asset.mesh().draws()) {
                MeshLook look = asset.look(draw.mesh());
                if (look.pass() != SurfacePass.TRANSLUCENT || !look.visible()) {
                    continue;
                }
                if (placed == null) {
                    placed = new Placed(asset, AssemblyPlacement.of(frame.partialTick(), assembly.entity()));
                }
                parts.add(new Part(placed, draw, look, asset.albedo(draw.mesh())));
            }
        }
        if (parts.isEmpty()) {
            return;
        }
        double[] distances = new double[parts.size()];
        for (int rank = 0; rank < distances.length; rank++) {
            distances[rank] = squaredDistance(parts.get(rank), frame.camera());
        }
        // Dans l'ordre de profondeur, un lot par suite de surfaces de même type : changer de type
        // vide le lot, et le tri des quads ne vaut qu'au sein d'un lot.
        RenderType current = null;
        VertexConsumer out = null;
        for (int rank : DepthOrder.farToNear(distances)) {
            Part part = parts.get(rank);
            boolean doubleSided = part.look().doubleSided();
            RenderType type = AxionRenderTypes.entity(
                    bound(part.region()),
                    EntityShader.of(SurfacePass.TRANSLUCENT, doubleSided),
                    doubleSided);
            if (type != current) {
                if (current != null) {
                    frame.buffers().endBatch(current);
                }
                current = type;
                out = frame.buffers().getBuffer(type);
            }
            drawPart(frame, part, out, false);
        }
        frame.buffers().endBatch(current);
    }

    /**
     * {@return la distance au carré, de la caméra au centre d'un mesh posé}
     *
     * <p>Même chaîne que le dessin : transformation de repos du node, orientation du corps, position
     * du corps relative à la caméra.
     */
    private static double squaredDistance(Part part, Vec3 camera) {
        float[] m = part.draw().model();
        float[] c = part.look().center();
        // mat4x3 colonne-major (ADR-119) : trois axes puis la translation.
        Vector3f center = new Vector3f(
                m[0] * c[0] + m[3] * c[1] + m[6] * c[2] + m[9],
                m[1] * c[0] + m[4] * c[1] + m[7] * c[2] + m[10],
                m[2] * c[0] + m[5] * c[1] + m[8] * c[2] + m[11]);
        AssemblyPlacement at = part.assembly().at();
        at.rotation().transform(center);
        double x = at.x() - camera.x + center.x();
        double y = at.y() - camera.y + center.y();
        double z = at.z() - camera.z + center.z();
        return x * x + y * y + z * z;
    }

    /** Les surfaces d'une passe, passe 1 ou 2. */
    private static void drawSurfaces(Frame frame, List<Placed> placed, SurfacePass pass) {
        Map<RenderType, List<Part>> batches = new LinkedHashMap<>();
        for (Placed assembly : placed) {
            RenderAsset asset = assembly.asset();
            for (GeometryTransfer.Draw draw : asset.mesh().draws()) {
                MeshLook look = asset.look(draw.mesh());
                if (look.pass() != pass || !look.visible()) {
                    continue;
                }
                TextureRegion albedo = asset.albedo(draw.mesh());
                RenderType type = AxionRenderTypes.entity(
                        bound(albedo), EntityShader.of(pass, look.doubleSided()), look.doubleSided());
                batches.computeIfAbsent(type, unused -> new ArrayList<>()).add(new Part(assembly, draw, look, albedo));
            }
        }
        drawBatches(frame, batches, false);
    }

    /**
     * Passe 5 : l'émission des surfaces qui émettent — leur texture d'émission, ou du blanc —,
     * additive, par-dessus.
     */
    private static void drawEmission(Frame frame, List<Placed> placed) {
        Map<RenderType, List<Part>> batches = new LinkedHashMap<>();
        for (Placed assembly : placed) {
            RenderAsset asset = assembly.asset();
            for (GeometryTransfer.Draw draw : asset.mesh().draws()) {
                MeshLook look = asset.look(draw.mesh());
                if (!look.visible() || !asset.emits(draw.mesh())) {
                    continue;
                }
                TextureRegion emission = asset.emission(draw.mesh());
                RenderType type = AxionRenderTypes.entity(bound(emission), EntityShader.EYES, look.doubleSided());
                batches.computeIfAbsent(type, unused -> new ArrayList<>()).add(new Part(assembly, draw, look, emission));
            }
        }
        drawBatches(frame, batches, true);
    }

    /** {@return la texture que lie le type de rendu : celle de la région, ou la neutre} */
    private static TextureBinding bound(TextureRegion region) {
        return region == null ? NEUTRAL : region.binding();
    }

    /**
     * Dessine des lots, un par type de rendu : alterner les types d'un mesh à l'autre viderait le
     * tampon partagé à chaque changement.
     *
     * @param emission vrai pour l'émission : couleur émissive, pleine lumière
     */
    private static void drawBatches(Frame frame, Map<RenderType, List<Part>> batches, boolean emission) {
        for (Map.Entry<RenderType, List<Part>> batch : batches.entrySet()) {
            VertexConsumer out = frame.buffers().getBuffer(batch.getKey());
            for (Part part : batch.getValue()) {
                drawPart(frame, part, out, emission);
            }
            frame.buffers().endBatch(batch.getKey());
        }
    }

    /**
     * Dessine un mesh à la transformation de repos de son node.
     *
     * <p>Les types de rendu d'entité sont en quads : chaque triangle y entre comme un quad
     * dégénéré {@code (a, b, c, c)}, dont le second triangle n'a pas d'aire.
     *
     * @param emission vrai pour son émission : couleur émissive, pleine lumière
     */
    private static void drawPart(Frame frame, Part part, VertexConsumer out, boolean emission) {
        GeometryTransfer mesh = part.assembly().asset().mesh();
        GeometryTransfer.Mesh desc = mesh.meshes().get(part.draw().mesh());
        AssemblyPlacement at = part.assembly().at();
        MeshLook look = part.look();
        int light = emission || look.fullbright() ? LightTexture.FULL_BRIGHT : at.light();

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
        TextureRegion region = part.region();
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
            int colorA = emission ? look.emissiveColor() : look.colorOf(a);
            int colorB = emission ? look.emissiveColor() : look.colorOf(b);
            int colorC = emission ? look.emissiveColor() : look.colorOf(c);
            vertex(out, position, normal, scratch, mesh, region, base + a, colorA, light);
            vertex(out, position, normal, scratch, mesh, region, base + b, colorB, light);
            vertex(out, position, normal, scratch, mesh, region, base + c, colorC, light);
            vertex(out, position, normal, scratch, mesh, region, base + c, colorC, light);
        }
        pose.popPose();
    }

    /**
     * Émet un sommet.
     *
     * @param region place de l'image dans la texture liée ; {@code null} pour la texture neutre
     * @param vertex rang du sommet dans le tableau des sommets
     * @param color couleur ARGB du sommet
     */
    private static void vertex(
            VertexConsumer out,
            Matrix4f position,
            Matrix3f normal,
            Vector3f scratch,
            GeometryTransfer mesh,
            TextureRegion region,
            int vertex,
            int color,
            int light) {
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

        float u = uvs[vertex * 2];
        float v = uvs[vertex * 2 + 1];
        if (region != null) {
            u = region.u(u);
            v = region.v(v);
        }
        out.vertex(position, positions[vertex * 3], positions[vertex * 3 + 1], positions[vertex * 3 + 2])
                .color((color >>> 16) & 0xFF, (color >>> 8) & 0xFF, color & 0xFF, color >>> 24)
                .uv(u, v)
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

    /**
     * Un mesh à dessiner : son assembly, son dessin au repos, son apparence, et où lire sa texture
     * pour ce dessin — albedo ou émission ; {@code null} pour la texture neutre.
     */
    private record Part(Placed assembly, GeometryTransfer.Draw draw, MeshLook look, TextureRegion region) {}
}
