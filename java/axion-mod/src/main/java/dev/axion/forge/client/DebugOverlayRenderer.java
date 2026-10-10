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
import dev.axion.world.DimensionId;
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
 * Overlays de debug des assemblies (C-67, ADR-121).
 *
 * <p>Une fois par tick client, demande au natif la géométrie des overlays allumés ; à chaque
 * frame, la dessine à la pose interpolée de chaque assembly — celle de son maillage
 * ({@link AssemblyPlacement}) —, en lignes vanilla ({@code RenderType.lines()}, R-741).
 *
 * <p>Premier overlay : {@code colliders}, la vraie forme des corps, tournée avec eux. Couleur
 * selon le corps : {@link #COLLIDERS_LEGEND}.
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

    /** En deçà, un segment n'a pas de direction : le shader de lignes ne saurait l'épaissir. */
    private static final float MIN_SEGMENT_LENGTH = 1e-6f;

    private final DebugOverlays overlays;

    /** Lecteur adossé au contexte natif ; créé au premier besoin, {@code null} sans natif. */
    private NativeDebugLoader loader;

    /** Géométrie du dernier tick, en repère des corps. */
    private DebugGeometry geometry = DebugGeometry.empty();

    /** Omission faute de budget déjà dite depuis l'allumage : une fois. */
    private boolean truncationReported;

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
        long mask = overlays.mask();
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
        if (geometry.truncated() && !truncationReported) {
            truncationReported = true;
            String line = geometry.omittedBodies() + " corps omis par l'overlay de debug, au-delà de "
                    + MAX_SEGMENTS + " segments : les plus lointains de la caméra";
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
        if (!overlays.isOn(DebugOverlays.Overlay.COLLIDERS) || geometry.bodies().isEmpty()) {
            return;
        }
        RenderType type = RenderType.lines();
        VertexConsumer out = null;
        PoseStack pose = frame.pose();
        Vector3f scratch = new Vector3f();
        for (RenderBackend.Assembly assembly : frame.assemblies()) {
            AxionEntity entity = assembly.entity();
            DebugGeometry.Body body = geometry.bodyOf(
                    AssemblyRuntime.handleIndexOf(entity), AssemblyRuntime.GENERATION);
            if (body == null || body.segmentCount() == 0) {
                continue;
            }
            if (out == null) {
                out = frame.buffers().getBuffer(type);
            }
            pose.pushPose();
            AssemblyPlacement.of(frame.partialTick(), entity).apply(pose, frame.camera());
            PoseStack.Pose last = pose.last();
            emit(out, last.pose(), last.normal(), scratch, body.segments(), colorOf(body));
            pose.popPose();
        }
        if (out != null) {
            frame.buffers().endBatch(type);
        }
    }

    /** {@return la géométrie du dernier tick, en repère des corps} */
    DebugGeometry geometry() {
        return geometry;
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
        truncationReported = false;
        failureReported = false;
    }

    /**
     * {@return la couleur RGB d'un corps : son genre d'abord, puis son sommeil}
     *
     * @param body corps tracé
     */
    static int colorOf(DebugGeometry.Body body) {
        if (body.has(DebugGeometry.BODY_STATIC)) {
            return STATIC;
        }
        if (body.has(DebugGeometry.BODY_KINEMATIC)) {
            return KINEMATIC;
        }
        return body.has(DebugGeometry.BODY_SLEEPING) ? SLEEPING : AWAKE;
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
