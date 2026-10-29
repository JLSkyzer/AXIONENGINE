package dev.axion.forge.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.axion.bootstrap.BootstrapOutcome;
import dev.axion.debug.DebugGeometry;
import dev.axion.debug.DebugOverlays;
import dev.axion.debug.NativeDebugLoader;
import dev.axion.forge.AssemblyRuntime;
import dev.axion.forge.AxionEntity;
import dev.axion.lifecycle.AxionRuntime;
import dev.axion.net.Interpolation;
import dev.axion.world.DimensionId;
import java.util.Arrays;
import java.util.List;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Overlays de debug des assemblies (C-67, ADR-121 et son amendement A1).
 *
 * <p>Une fois par tick client, demande au natif la géométrie des overlays allumés ; à chaque
 * frame, la dessine en lignes vanilla ({@code RenderType.lines()}, R-741). Une plage en repère
 * du corps se pose à la pose interpolée de son assembly — celle de son maillage
 * ({@link AssemblyPlacement}). Une plage en repère d'origine se pose à la caméra de l'appel
 * moins la caméra courante, en {@code double} : ce sont les données du dernier pas, qui peuvent
 * avoir un tick de retard sur le maillage interpolé.
 *
 * <p>Les overlays de l'animation — {@code bones}, {@code nodes}, {@code pivots} — viennent du contexte
 * de rendu, à chaque frame après son culling : la pose que cette frame a donnée à chaque instance
 * animée, en repère d'origine relatif à sa caméra (ADR-155, tranche 6b). Ils se dessinent à travers
 * les maillages ({@link AxionRenderTypes#XRAY_LINES}) : un os est dans la peau qu'il porte.
 *
 * <p>Couleurs : {@code colliders} selon le corps ({@link #COLLIDERS_LEGEND}), les autres
 * overlays une couleur chacun ({@link #legendOf}).
 *
 * <p>Éteint, un overlay ne coûte qu'un test de drapeau par tick et par frame : aucun appel
 * natif, aucun tampon, aucun draw call (R-800, R-2280). Tick et rendu ont lieu sur le même
 * thread client : l'état n'a pas besoin de verrou.
 */
final class DebugOverlayRenderer {

    private static final Logger LOGGER = LoggerFactory.getLogger("axion");

    /**
     * Budget de segments par appel natif. Valeur de conception, pas une mesure : de quoi
     * tracer plus d'un millier de boîtes autour de la caméra. Au-delà, les corps les plus
     * lointains sont omis, et l'omission est dite (ADR-121). Le coût réel se mesurera avec le
     * banc de C-72.
     */
    static final int MAX_SEGMENTS = 16_384;

    /** Couleur d'un corps dynamique éveillé (RGB). */
    static final int AWAKE = 0x33FF4D;

    /** Couleur d'un corps dynamique endormi (RGB). */
    static final int SLEEPING = 0x7388BF;

    /** Couleur d'un corps statique (RGB). */
    static final int STATIC = 0xFF991A;

    /** Couleur d'un corps cinématique (RGB). */
    static final int KINEMATIC = 0xFF4DFF;

    /** Légende des couleurs de l'overlay {@code colliders}, telle que la commande l'affiche. */
    static final String COLLIDERS_LEGEND =
            "vert : actif, gris-bleu : endormi, orange : statique, magenta : cinématique";

    /** Couleur de l'overlay {@code aabb} (RGB). */
    static final int AABB_COLOR = 0x33E6FF;

    /** Couleurs des axes x, y et z de l'overlay {@code bodies} (RGB). */
    static final int[] AXIS_COLORS = {0xFF3333, 0x33FF4D, 0x3366FF};

    /** Couleur de l'overlay {@code contacts} (RGB). */
    static final int CONTACT_COLOR = 0xFF3333;

    /** Couleur de l'overlay {@code normals} (RGB). */
    static final int NORMAL_COLOR = 0xFFE633;

    /** Couleur de l'overlay {@code world_tiles} (RGB). */
    static final int TILE_COLOR = 0xB3B3B3;

    /** Couleur d'un joint libre, dans {@code joints} (RGB). */
    static final int JOINT_COLOR = 0x66FF66;

    /** Couleur de la plage d'un joint libre, dans {@code limits} (RGB). */
    static final int LIMIT_COLOR = 0x4D99FF;

    /** Couleur d'un joint grippé, dans {@code joints} et {@code limits} (RGB). */
    static final int JOINT_JAMMED_COLOR = 0xFFB31A;

    /** Couleur d'un joint rompu, dans {@code joints} (RGB). */
    static final int JOINT_BROKEN_COLOR = 0xFF1A66;

    /** Couleur de l'emprise à la pose dessinée, dans {@code network} (RGB). */
    static final int NETWORK_SHOWN = 0x9966FF;

    /** Couleur de l'emprise au dernier état reçu, dans {@code network} (RGB). */
    static final int NETWORK_RECEIVED = 0xFF8000;

    /** Demi-côté de la boîte d'une assembly sans emprise, dans {@code network}, en blocs. */
    static final float NETWORK_NO_BOUNDS = 0.25f;

    /** Couleur de l'overlay {@code center_of_mass} (RGB). */
    static final int MASS_COLOR = 0xFFFFFF;

    /** Couleur de l'overlay {@code bones} (RGB). */
    static final int BONE_COLOR = 0xFFD24D;

    /** Couleur de l'overlay {@code pivots} (RGB). */
    static final int PIVOT_COLOR = 0xFF66CC;

    /** En deçà, un segment n'a pas de direction : le shader de lignes ne saurait l'épaissir. */
    private static final float MIN_SEGMENT_LENGTH = 1e-6f;

    private final DebugOverlays overlays;

    /** Lecteur adossé au contexte natif ; créé au premier besoin, {@code null} sans natif. */
    private NativeDebugLoader loader;

    /** Géométrie du dernier tick, en repère des corps. */
    private DebugGeometry geometry = DebugGeometry.empty();

    /** Géométrie des overlays de l'animation à la dernière frame, en repère d'origine (ADR-155, tranche 6b). */
    private DebugGeometry animation = DebugGeometry.empty();

    /** Omission faute de budget déjà dite depuis l'allumage : une fois. */
    private boolean truncationReported;

    /** Assemblies que {@code network} a dessinées à la dernière image. */
    private int networkDrawn;

    /** Assemblies que {@code lod} et {@code culling} ont dessinées à la dernière image. */
    private int lodDrawn;
    private int cullingDrawn;

    /** Couleur de l'emprise d'une assembly retenue, dans {@code culling} (RGB). */
    static final int CULLING_KEPT = 0x40E040;

    /** Couleur de l'emprise d'une assembly écartée, dans {@code culling} (RGB). */
    static final int CULLING_REJECTED = 0xFF3030;

    /** Couleur de l'emprise selon le niveau de détail, dans {@code lod} (RGB) : 0, 1, 2, puis au-delà. */
    static final int[] LOD_COLORS = {0x20C0A0, 0xE0E040, 0xFF9020, 0xFF3030};

    /** Échec natif déjà dit depuis l'allumage : une fois. */
    private boolean failureReported;

    /**
     * @param overlays overlays allumés, que la commande client modifie
     */
    DebugOverlayRenderer(DebugOverlays overlays) {
        this.overlays = overlays;
    }

    /**
     * Fin de tick client : (re)demande la géométrie des overlays allumés.
     *
     * <p>Un seul appel natif par tick, pas par frame : en repère des corps, la géométrie ne
     * change qu'avec leur forme, et l'attente du verrou de session se limite à cet appel
     * (ADR-121).
     *
     * @param runtime runtime d'AXION, ou {@code null} avant la construction du mod
     * @param minecraft client
     */
    void tick(AxionRuntime runtime, Minecraft minecraft) {
        // Le natif ne reçoit que les siens : network se dessine en Java, et un bit inconnu y serait refusé.
        long mask = overlays.nativeMask();
        if (mask == 0L) {
            reset();
            return;
        }
        ClientLevel level = minecraft.level;
        Camera camera = minecraft.gameRenderer.getMainCamera();
        NativeDebugLoader source = loader(runtime);
        if (level == null || !camera.isInitialized() || source == null) {
            geometry = DebugGeometry.empty();
            return;
        }
        Vec3 eye = camera.getPosition();
        NativeDebugLoader.Fetched fetched = source.fetch(
                mask,
                DimensionId.of(level.dimension().location().toString()),
                eye.x,
                eye.y,
                eye.z,
                MAX_SEGMENTS);
        if (!fetched.ok()) {
            geometry = DebugGeometry.empty();
            if (!failureReported) {
                failureReported = true;
                LOGGER.warn("AXION : géométrie de debug refusée par le natif (code {})", fetched.code());
            }
            return;
        }
        geometry = fetched.geometry();
        reportTruncation(geometry, minecraft);
    }

    /**
     * Après le culling d'une frame : (re)demande au contexte de rendu la géométrie des overlays de l'animation
     * allumés, à la pose que cette frame a donnée à chaque instance animée (ADR-155, tranche 6b). Un appel par
     * frame, l'animation changeant à chaque image, et seulement si l'un d'eux est allumé (R-2280). L'appel ne
     * prend pas le verrou de session (ADR-154 D2).
     *
     * @param runtime runtime d'AXION, ou {@code null} avant la construction du mod
     * @param camera la caméra de la frame, celle de ses candidats
     */
    void animate(AxionRuntime runtime, Vec3 camera) {
        long mask = overlays.renderMask();
        NativeDebugLoader source = mask == 0L ? null : loader(runtime);
        if (source == null) {
            animation = DebugGeometry.empty();
            return;
        }
        NativeDebugLoader.Fetched fetched = source.fetchAnimation(mask, camera.x, camera.y, camera.z, MAX_SEGMENTS);
        if (!fetched.ok()) {
            animation = DebugGeometry.empty();
            if (!failureReported) {
                failureReported = true;
                LOGGER.warn("AXION : géométrie de l'animation refusée par le natif (code {})", fetched.code());
            }
            return;
        }
        animation = fetched.geometry();
        reportTruncation(animation, Minecraft.getInstance());
    }

    /** Aucun culling à cette frame : rien de l'animation à tracer. */
    void forgetAnimation() {
        animation = DebugGeometry.empty();
    }

    /** Dit, une fois depuis l'allumage, que le budget a omis des plages. */
    private void reportTruncation(DebugGeometry fetched, Minecraft minecraft) {
        if (fetched.truncated() && !truncationReported) {
            truncationReported = true;
            String line = fetched.omittedRanges() + " plage(s) omise(s) par les overlays de debug, au-delà de "
                    + MAX_SEGMENTS + " segments : les plus lointaines de la caméra";
            LOGGER.warn("AXION : {}", line);
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(Component.literal("AXION : " + line), false);
            }
        }
    }

    /**
     * Dessine les overlays allumés sur les assemblies de la frame.
     *
     * @param frame contexte de la frame, assemblies comprises
     */
    void draw(RenderBackend.Frame frame) {
        networkDrawn = 0;
        lodDrawn = 0;
        cullingDrawn = 0;
        boolean network = overlays.isOn(DebugOverlays.Overlay.NETWORK);
        boolean lod = overlays.isOn(DebugOverlays.Overlay.LOD);
        boolean culled = overlays.isOn(DebugOverlays.Overlay.CULLING);
        if (overlays.mask() == 0L
                || (geometry.ranges().isEmpty() && animation.ranges().isEmpty() && !network && !lod && !culled)) {
            return;
        }
        RenderType type = RenderType.lines();
        VertexConsumer out = null;
        PoseStack pose = frame.pose();
        Vector3f scratch = new Vector3f();
        // Repère du corps : à la pose interpolée de chaque assembly, comme son maillage.
        for (RenderBackend.Assembly assembly : frame.assemblies()) {
            AxionEntity entity = assembly.entity();
            List<DebugGeometry.Range> ranges = geometry.bodyRangesOf(
                    AssemblyRuntime.handleIndexOf(entity), AssemblyRuntime.GENERATION);
            if (ranges.isEmpty()) {
                continue;
            }
            if (out == null) {
                out = frame.buffers().getBuffer(type);
            }
            pose.pushPose();
            AssemblyPlacement.of(frame.partialTick(), entity).apply(pose, frame.camera());
            PoseStack.Pose last = pose.last();
            for (DebugGeometry.Range range : ranges) {
                emitRange(out, last.pose(), last.normal(), scratch, range);
            }
            pose.popPose();
        }
        // Repère d'origine : la caméra de l'appel moins la caméra courante, en double.
        if (!geometry.originRanges().isEmpty()) {
            if (out == null) {
                out = frame.buffers().getBuffer(type);
            }
            Vec3 eye = frame.camera();
            pose.pushPose();
            pose.translate(geometry.originX() - eye.x, geometry.originY() - eye.y, geometry.originZ() - eye.z);
            PoseStack.Pose last = pose.last();
            for (DebugGeometry.Range range : geometry.originRanges()) {
                emitRange(out, last.pose(), last.normal(), scratch, range);
            }
            pose.popPose();
        }
        if (network) {
            out = drawNetwork(frame, out, type, scratch);
        }
        if (lod || culled) {
            out = drawCulling(frame, out, type, scratch, lod, culled);
        }
        if (out != null) {
            frame.buffers().endBatch(type);
        }
        // L'animation, à travers les maillages : la caméra de sa frame moins la caméra courante, en double.
        if (!animation.originRanges().isEmpty()) {
            RenderType xray = AxionRenderTypes.XRAY_LINES;
            VertexConsumer lines = frame.buffers().getBuffer(xray);
            Vec3 eye = frame.camera();
            pose.pushPose();
            pose.translate(animation.originX() - eye.x, animation.originY() - eye.y, animation.originZ() - eye.z);
            PoseStack.Pose last = pose.last();
            for (DebugGeometry.Range range : animation.originRanges()) {
                emitRange(lines, last.pose(), last.normal(), scratch, range);
            }
            pose.popPose();
            frame.buffers().endBatch(xray);
        }
    }

    /**
     * {@code network} (31.4) : pour chaque assembly, son emprise à la pose dessinée et au dernier état reçu, et le
     * trait de l'une à l'autre — l'écart que l'interpolation tient entre ce qui se voit et ce que le serveur a dit.
     *
     * @return le tampon, ouvert s'il a fallu dessiner
     */
    private VertexConsumer drawNetwork(RenderBackend.Frame frame, VertexConsumer out, RenderType type, Vector3f scratch) {
        Vec3 eye = frame.camera();
        PoseStack.Pose last = frame.pose().last();
        for (RenderBackend.Assembly assembly : frame.assemblies()) {
            AxionEntity.NetworkView view = assembly.entity().networkView();
            if (view == null) {
                continue;
            }
            if (out == null) {
                out = frame.buffers().getBuffer(type);
            }
            float[][] segments = networkSegments(view.shown(), view.received(), eye.x, eye.y, eye.z);
            emit(out, last.pose(), last.normal(), scratch, segments[0], NETWORK_SHOWN);
            emit(out, last.pose(), last.normal(), scratch, segments[1], NETWORK_RECEIVED);
            networkDrawn++;
        }
        return out;
    }

    /**
     * {@return les segments de {@code network} pour une assembly, relatifs à la caméra : d'abord l'emprise à la pose
     * dessinée et le trait vers le dernier état reçu, puis l'emprise à ce dernier état}
     *
     * @param shown pose dessinée
     * @param received dernier état reçu
     * @param cameraX position de la caméra
     * @param cameraY position de la caméra
     * @param cameraZ position de la caméra
     */
    static float[][] networkSegments(Interpolation.Pose shown, Interpolation.Pose received,
            double cameraX, double cameraY, double cameraZ) {
        float[] link = {
            (float) (shown.x() - cameraX), (float) (shown.y() - cameraY), (float) (shown.z() - cameraZ),
            (float) (received.x() - cameraX), (float) (received.y() - cameraY), (float) (received.z() - cameraZ),
        };
        float[] shownBox = box(shown, cameraX, cameraY, cameraZ);
        float[] first = Arrays.copyOf(shownBox, shownBox.length + link.length);
        System.arraycopy(link, 0, first, shownBox.length, link.length);
        return new float[][] {first, box(received, cameraX, cameraY, cameraZ)};
    }

    /** {@return les douze arêtes de l'emprise d'une pose, alignée sur le monde, relatives à la caméra} */
    private static float[] box(Interpolation.Pose pose, double cameraX, double cameraY, double cameraZ) {
        float[] min = pose.boundsMin() != null
                ? pose.boundsMin() : new float[] {-NETWORK_NO_BOUNDS, -NETWORK_NO_BOUNDS, -NETWORK_NO_BOUNDS};
        float[] max = pose.boundsMax() != null
                ? pose.boundsMax() : new float[] {NETWORK_NO_BOUNDS, NETWORK_NO_BOUNDS, NETWORK_NO_BOUNDS};
        float x0 = (float) (pose.x() - cameraX) + min[0];
        float y0 = (float) (pose.y() - cameraY) + min[1];
        float z0 = (float) (pose.z() - cameraZ) + min[2];
        float x1 = (float) (pose.x() - cameraX) + max[0];
        float y1 = (float) (pose.y() - cameraY) + max[1];
        float z1 = (float) (pose.z() - cameraZ) + max[2];
        return new float[] {
            x0, y0, z0, x1, y0, z0, x1, y0, z0, x1, y0, z1, x1, y0, z1, x0, y0, z1, x0, y0, z1, x0, y0, z0,
            x0, y1, z0, x1, y1, z0, x1, y1, z0, x1, y1, z1, x1, y1, z1, x0, y1, z1, x0, y1, z1, x0, y1, z0,
            x0, y0, z0, x0, y1, z0, x1, y0, z0, x1, y1, z0, x1, y0, z1, x1, y1, z1, x0, y0, z1, x0, y1, z1,
        };
    }

    /**
     * {@code lod} et {@code culling} (31.4) : l'emprise de chaque assembly candidate au culling de la frame,
     * colorée par son niveau de détail, ou retenue en vert et écartée en rouge.
     *
     * @return le tampon, ouvert s'il a fallu dessiner
     */
    private VertexConsumer drawCulling(RenderBackend.Frame frame, VertexConsumer out, RenderType type, Vector3f scratch,
            boolean lod, boolean culled) {
        Vec3 eye = frame.camera();
        PoseStack.Pose last = frame.pose().last();
        for (RenderBackend.Assembly assembly : frame.assemblies()) {
            AxionEntity entity = assembly.entity();
            Integer decision = AxionRenderPass.cullingDecision(entity);
            if (decision == null) {
                continue;
            }
            if (out == null) {
                out = frame.buffers().getBuffer(type);
            }
            // L'emprise de l'entité, à la pose dessinée : celle du tick, décalée de l'interpolation.
            AxionEntity.RenderPose pose = entity.renderPose(frame.partialTick());
            var box = entity.getBoundingBox();
            double dx = pose.x() - entity.getX() - eye.x;
            double dy = pose.y() - entity.getY() - eye.y;
            double dz = pose.z() - entity.getZ() - eye.z;
            float[] edges = boxEdges(box.minX + dx, box.minY + dy, box.minZ + dz, box.maxX + dx, box.maxY + dy,
                    box.maxZ + dz);
            if (lod && decision >= 0) {
                emit(out, last.pose(), last.normal(), scratch, edges, lodColor(decision));
                lodDrawn++;
            }
            if (culled) {
                emit(out, last.pose(), last.normal(), scratch, edges, decision >= 0 ? CULLING_KEPT : CULLING_REJECTED);
                cullingDrawn++;
            }
        }
        return out;
    }

    /** {@return la couleur de l'emprise d'un niveau de détail, dans {@code lod}} */
    static int lodColor(int level) {
        return LOD_COLORS[Math.min(Math.max(level, 0), LOD_COLORS.length - 1)];
    }

    /** {@return les douze arêtes d'une boîte, six flottants chacune} */
    static float[] boxEdges(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        float x0 = (float) minX;
        float y0 = (float) minY;
        float z0 = (float) minZ;
        float x1 = (float) maxX;
        float y1 = (float) maxY;
        float z1 = (float) maxZ;
        return new float[] {
            x0, y0, z0, x1, y0, z0, x1, y0, z0, x1, y0, z1, x1, y0, z1, x0, y0, z1, x0, y0, z1, x0, y0, z0,
            x0, y1, z0, x1, y1, z0, x1, y1, z0, x1, y1, z1, x1, y1, z1, x0, y1, z1, x0, y1, z1, x0, y1, z0,
            x0, y0, z0, x0, y1, z0, x1, y0, z0, x1, y1, z0, x1, y0, z1, x1, y1, z1, x0, y0, z1, x0, y1, z1,
        };
    }

    /** {@return le nombre d'assemblies que {@code lod} a dessinées à la dernière image} */
    int lodDrawn() {
        return lodDrawn;
    }

    /** {@return le nombre d'assemblies que {@code culling} a dessinées à la dernière image} */
    int cullingDrawn() {
        return cullingDrawn;
    }

    /** {@return le nombre d'assemblies que {@code network} a dessinées à la dernière image} */
    int networkDrawn() {
        return networkDrawn;
    }

    /**
     * Émet une plage dans la couleur de son overlay ; les axes de {@code bodies} et de {@code nodes}, chacun
     * dans la sienne. Une plage d'un overlay que ce client ne connaît pas est sautée.
     *
     * @return le nombre de segments émis
     */
    static int emitRange(
            VertexConsumer out, Matrix4f position, Matrix3f normal, Vector3f scratch, DebugGeometry.Range range) {
        DebugOverlays.Overlay overlay = DebugOverlays.Overlay.ofRank(range.overlay());
        if (overlay == null) {
            return 0;
        }
        float[] segments = range.segments();
        if (overlay != DebugOverlays.Overlay.BODIES && overlay != DebugOverlays.Overlay.NODES) {
            return emit(out, position, normal, scratch, segments, colorOf(overlay, range.flags()));
        }
        int emitted = 0;
        for (int axis = 0; axis < range.segmentCount(); axis++) {
            int from = axis * DebugGeometry.FLOATS_PER_SEGMENT;
            float[] one = Arrays.copyOfRange(segments, from, from + DebugGeometry.FLOATS_PER_SEGMENT);
            emitted += emit(out, position, normal, scratch, one, AXIS_COLORS[axis % AXIS_COLORS.length]);
        }
        return emitted;
    }

    /**
     * {@return la couleur RGB d'une plage : celle de son overlay ; pour {@code colliders}, celle
     * de son corps}
     *
     * @param overlay overlay de la plage
     * @param flags drapeaux de la plage
     */
    static int colorOf(DebugOverlays.Overlay overlay, int flags) {
        return switch (overlay) {
            case COLLIDERS, BODIES -> colorOf(flags);
            case AABB -> AABB_COLOR;
            case CONTACTS -> CONTACT_COLOR;
            case NORMALS -> NORMAL_COLOR;
            case JOINTS -> jointColorOf(flags, JOINT_COLOR);
            case LIMITS -> jointColorOf(flags, LIMIT_COLOR);
            case BONES -> BONE_COLOR;
            case NODES -> AXIS_COLORS[0];
            case PIVOTS -> PIVOT_COLOR;
            case WORLD_TILES -> TILE_COLOR;
            case CENTER_OF_MASS -> MASS_COLOR;
            case NETWORK -> NETWORK_RECEIVED;
            case LOD -> LOD_COLORS[0];
            case CULLING -> CULLING_KEPT;
        };
    }

    /**
     * {@return la couleur RGB d'une plage {@code joints} ou {@code limits} : l'état de son joint, rompu
     * d'abord, puis grippé ; {@code free} sinon (ADR-151 D7)}
     *
     * @param flags drapeaux {@code JOINT_*} de la plage
     * @param free couleur d'un joint libre
     */
    static int jointColorOf(int flags, int free) {
        if ((flags & DebugGeometry.JOINT_BROKEN) != 0) {
            return JOINT_BROKEN_COLOR;
        }
        return (flags & DebugGeometry.JOINT_JAMMED) != 0 ? JOINT_JAMMED_COLOR : free;
    }

    /**
     * {@return la légende des couleurs d'un overlay, telle que la commande l'affiche}
     *
     * @param overlay overlay allumé
     */
    static String legendOf(DebugOverlays.Overlay overlay) {
        return switch (overlay) {
            case COLLIDERS -> COLLIDERS_LEGEND;
            case AABB -> "cyan : la boîte de chaque collider, alignée sur le monde, au dernier pas";
            case BODIES -> "axes du corps : rouge x, vert y, bleu z";
            case CONTACTS -> "rouge : les points de contact du dernier pas";
            case NORMALS -> "jaune : les normales de contact, du premier collider vers le second";
            case JOINTS -> "vert : un joint libre, d'un ancrage à l'autre, avec son axe ; ambre : grippé ; rose :"
                    + " rompu, ses ancrages écartés";
            case LIMITS -> "bleu : la plage permise d'un joint et le trait de sa position ; ambre : resserrée par"
                    + " un grippage";
            case BONES -> "or : chaque os d'une instance animée, de son os parent à lui, vu à travers la peau";
            case NODES -> "axes de chaque node d'une instance animée : rouge x, vert y, bleu z";
            case PIVOTS -> "rose : l'origine de chaque node animé, autour de laquelle il tourne";
            case WORLD_TILES -> "gris : les colliders des tuiles du monde";
            case CENTER_OF_MASS -> "blanc : le centre de masse de chaque corps";
            case NETWORK -> "violet : l'emprise à la pose dessinée, interpolée ; orange : au dernier état reçu du"
                    + " serveur ; le trait les relie";
            case LOD -> "l'emprise de chaque assembly dessinée, par niveau de détail : turquoise 0, jaune 1, orange 2,"
                    + " rouge 3 et au-delà";
            case CULLING -> "vert : une assembly retenue par le culling ; rouge : écartée — hors du champ, trop"
                    + " loin, ou au-delà du plafond d'instances";
        };
    }

    /** {@return la géométrie du dernier tick, en repère des corps} */
    DebugGeometry geometry() {
        return geometry;
    }

    /** {@return la géométrie des overlays de l'animation à la dernière frame} */
    DebugGeometry animationGeometry() {
        return animation;
    }

    /**
     * {@return vrai si le lecteur natif a été créé : il ne l'est qu'au premier tick d'un overlay
     * allumé — éteints, les overlays ne demandent rien au natif (R-2280, T-551)}
     */
    boolean hasNativeLoader() {
        return loader != null;
    }

    /** Oublie la géométrie et les écarts déjà dits : overlays éteints, ou sortie du monde. */
    void reset() {
        geometry = DebugGeometry.empty();
        animation = DebugGeometry.empty();
        truncationReported = false;
        failureReported = false;
    }

    /**
     * {@return la couleur RGB d'un corps : son genre d'abord, puis son sommeil}
     *
     * @param flags drapeaux {@code BODY_*} de sa plage
     */
    static int colorOf(int flags) {
        if ((flags & DebugGeometry.BODY_STATIC) != 0) {
            return STATIC;
        }
        if ((flags & DebugGeometry.BODY_KINEMATIC) != 0) {
            return KINEMATIC;
        }
        return (flags & DebugGeometry.BODY_SLEEPING) != 0 ? SLEEPING : AWAKE;
    }

    /**
     * Émet des segments en lignes : deux sommets par segment, la direction du segment en
     * normale — c'est d'elle que le shader de lignes tire l'épaisseur à l'écran. Un segment
     * sans longueur n'a pas de direction : il est sauté.
     *
     * @param out tampon de sommets au format des lignes
     * @param position transformation des positions (repère du corps vers la vue)
     * @param normal transformation des directions
     * @param scratch vecteur de travail
     * @param segments {@link DebugGeometry#FLOATS_PER_SEGMENT} flottants par segment
     * @param rgb couleur
     * @return le nombre de segments émis
     */
    static int emit(
            VertexConsumer out,
            Matrix4f position,
            Matrix3f normal,
            Vector3f scratch,
            float[] segments,
            int rgb) {
        float r = ((rgb >> 16) & 0xFF) / 255.0f;
        float g = ((rgb >> 8) & 0xFF) / 255.0f;
        float b = (rgb & 0xFF) / 255.0f;
        int emitted = 0;
        for (int i = 0; i + DebugGeometry.FLOATS_PER_SEGMENT <= segments.length;
                i += DebugGeometry.FLOATS_PER_SEGMENT) {
            float ax = segments[i];
            float ay = segments[i + 1];
            float az = segments[i + 2];
            float bx = segments[i + 3];
            float by = segments[i + 4];
            float bz = segments[i + 5];
            scratch.set(bx - ax, by - ay, bz - az);
            float length = scratch.length();
            if (!(length > MIN_SEGMENT_LENGTH)) {
                continue;
            }
            scratch.div(length).mul(normal);
            out.vertex(position, ax, ay, az)
                    .color(r, g, b, 1.0f)
                    .normal(scratch.x(), scratch.y(), scratch.z())
                    .endVertex();
            out.vertex(position, bx, by, bz)
                    .color(r, g, b, 1.0f)
                    .normal(scratch.x(), scratch.y(), scratch.z())
                    .endVertex();
            emitted++;
        }
        return emitted;
    }

    /** {@return le lecteur de géométrie, créé au premier besoin, ou {@code null} sans natif} */
    private NativeDebugLoader loader(AxionRuntime runtime) {
        if (loader == null && runtime != null) {
            BootstrapOutcome outcome = runtime.outcome();
            if (outcome != null && outcome.isReady()) {
                loader = new NativeDebugLoader(outcome.context());
            }
        }
        return loader;
    }
}
