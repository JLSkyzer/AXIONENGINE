package dev.axion.forge.client;

import dev.axion.AxionMod;
import dev.axion.camera.ViewRig;
import dev.axion.definition.Seating;
import dev.axion.definition.VehicleViews;
import dev.axion.forge.AxionEntity;
import dev.axion.seat.SeatPlacement;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * La caméra de véhicule (ADR-170 D1, D2, D5), chez le client, sans Mixin.
 *
 * <ul>
 *   <li>Assis dans une assembly qui a des vues pour son siège, la touche de perspective de Minecraft (F5) les fait
 *       défiler : ses clics sont consommés au début du tick, avant que Minecraft ne les lise. La perspective suit la
 *       sorte de la vue ; celle d'avant la montée revient à la descente.</li>
 *   <li>À chaque image, {@code ComputeCameraAngles} — que Forge déclenche juste après {@code Camera.setup}, avant le
 *       rendu — pose la caméra de la vue : sa position par {@code Camera.setPosition} (SRG {@code m_90584_}, protégée,
 *       appelée par réflexion), son orientation par l'événement. {@code ComputeFov} donne son champ de vision.</li>
 *   <li>Repli : sans cette méthode, les vues qui se posent ailleurs que l'œil du joueur se dessinent comme la troisième
 *       personne de Minecraft ; le journal le dit une fois.</li>
 *   <li>Un autre mod qui pose la caméra après AXION l'emporte : la vue AXION s'abandonne pour la session, et le journal
 *       le dit une fois (R-1912).</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = AxionMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class VehicleCamera {

    private static final Logger LOGGER = LoggerFactory.getLogger(VehicleCamera.class);

    /** {@code Camera.setPosition(double, double, double)}, nom SRG de 1.20.1. */
    private static final String SET_POSITION = "m_90584_";

    /** La vue choisie, par definition et par siège, le temps de la session (D2). */
    private static final Map<String, Integer> chosen = new HashMap<>();
    private static final ViewRig rig = new ViewRig();

    private static Method setPosition;
    private static boolean positionUnavailable;
    private static boolean overridden;
    /** La perspective d'avant la montée ; {@code null} hors d'un véhicule à vues. */
    private static CameraType before;
    /** La vue active, et où elle a posé la caméra à cette image ; {@code null} sans. */
    private static VehicleViews.View active;
    private static Vec3 placed;
    private static long lastFrameNanos;
    private static Vec3 lastTarget;
    private static Vec3 velocity = Vec3.ZERO;

    private VehicleCamera() {}

    /** {@return la vue active du joueur local, {@code null} sans} */
    public static VehicleViews.View active() {
        return active;
    }

    /** Début du tick : F5 fait défiler les vues du siège, et la perspective suit la vue (D2). */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        // La vue d'une autre entité (spectateur, caméra d'un mod ou d'un film) n'est pas celle du conducteur.
        Seat seat = player == null || minecraft.getCameraEntity() != player ? null : seat(player);
        if (seat == null || overridden) {
            leave(minecraft);
            return;
        }
        if (before == null) {
            before = minecraft.options.getCameraType();
            rig.reset();
        }
        String key = seat.assembly().definitionId() + "#" + seat.rank();
        int index = Math.floorMod(chosen.getOrDefault(key, 0), seat.views().size());
        while (minecraft.options.keyTogglePerspective.consumeClick()) {
            index = (index + 1) % seat.views().size();
            rig.reset();
        }
        chosen.put(key, index);
        VehicleViews.View view = seat.views().get(index);
        active = view;
        CameraType wanted = view.kind() == VehicleViews.Kind.COCKPIT ? CameraType.FIRST_PERSON
                : CameraType.THIRD_PERSON_BACK;
        if (minecraft.options.getCameraType() != wanted) {
            minecraft.options.setCameraType(wanted);
        }
    }

    /** Chaque image, après {@code Camera.setup} : la caméra de la vue active (D1, D5). */
    @SubscribeEvent
    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        placed = null;
        VehicleViews.View view = active;
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (view == null || player == null || event.getCamera().getEntity() != player
                || !(player.getVehicle() instanceof AxionEntity assembly)) {
            return;
        }
        float partialTick = (float) event.getPartialTick();
        AxionEntity.RenderPose shown = assembly.renderPose(partialTick);
        float vehicleYaw = SeatedPassengers.yaw(shown);
        float relativeYaw = Mth.wrapDegrees(player.getViewYRot(partialTick) - vehicleYaw);
        float playerPitch = player.getViewXRot(partialTick);
        long now = System.nanoTime();
        double dt = lastFrameNanos == 0 ? 0 : (now - lastFrameNanos) / 1e6;
        lastFrameNanos = now;
        Vec3 target = new Vec3(shown.x(), shown.y(), shown.z());
        if (lastTarget != null && dt > 0) {
            Vec3 measured = target.subtract(lastTarget).scale(1000.0 / dt);
            velocity = velocity.add(measured.subtract(velocity).scale(Math.min(1, dt / 100.0)));
        }
        lastTarget = target;
        switch (view.kind()) {
            case FOLLOW -> {
                if (positionUnavailable) {
                    return;
                }
                double size = Math.max(assembly.getBoundingBox().getXsize(), assembly.getBoundingBox().getZsize());
                ViewRig.Shot shot = rig.follow(view, new double[] {target.x, target.y, target.z}, vehicleYaw,
                        new double[] {velocity.x, velocity.y, velocity.z}, size, relativeYaw, now / 1e6, dt,
                        (from, to) -> free(minecraft, player, from, to));
                place(event, shot.x(), shot.y(), shot.z(), shot.yaw(), shot.pitch());
            }
            case COCKPIT -> {
                int seat = assembly.seatOf(player);
                float[] look = ViewRig.bounded(vehicleYaw, 0, relativeYaw, playerPitch, view.lookYaw(),
                        view.lookPitch());
                SeatPlacement.Pose pose = view.eye() == null ? null : assembly.seatPoseAt(seat, shown);
                if (pose == null || positionUnavailable) {
                    // L'œil du joueur, celui de Minecraft : seul le regard se borne.
                    event.setYaw(look[0]);
                    event.setPitch(look[1]);
                    return;
                }
                double[] eye = ViewRig.transform(new double[] {pose.x(), pose.y(), pose.z()}, pose.rotation(),
                        view.eye());
                place(event, eye[0], eye[1], eye[2], look[0], look[1]);
            }
            case FIXED -> {
                SeatPlacement.Pose pose = assembly.nodePoseAt(view.node(), shown);
                if (pose == null || positionUnavailable) {
                    return;
                }
                double[] forward = ViewRig.transform(new double[] {0, 0, 0}, pose.rotation(), new float[] {0, 0, -1});
                float[] base = ViewRig.angles(forward[0], forward[1], forward[2]);
                float[] look = ViewRig.bounded(base[0], base[1], Mth.wrapDegrees(player.getViewYRot(partialTick)
                        - vehicleYaw), playerPitch, view.lookYaw(), view.lookPitch());
                place(event, pose.x(), pose.y(), pose.z(), look[0], look[1]);
            }
        }
    }

    /** Après tous les autres : si un mod a déplacé la caméra après AXION, AXION s'efface (R-1912). */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onCameraAnglesLast(ViewportEvent.ComputeCameraAngles event) {
        if (placed != null && event.getCamera().getPosition().distanceToSqr(placed) > 1e-6 && !overridden) {
            overridden = true;
            LOGGER.warn("AXION : un autre mod pose la caméra après AXION ; les vues de véhicule s'effacent pour la"
                    + " session (ADR-170 D5, R-1912)");
        }
    }

    /** Le champ de vision de la vue active, quand elle en déclare un (D1). */
    @SubscribeEvent
    public static void onFov(ViewportEvent.ComputeFov event) {
        VehicleViews.View view = active;
        if (view != null && event.usedConfiguredFov() && !Double.isNaN(view.fov()) && !overridden
                && event.getCamera().getEntity() == Minecraft.getInstance().player) {
            event.setFOV(view.fov());
        }
    }

    private static void place(ViewportEvent.ComputeCameraAngles event, double x, double y, double z, float yaw,
            float pitch) {
        if (!position(event.getCamera(), x, y, z)) {
            return;
        }
        event.setYaw(yaw);
        event.setPitch(pitch);
        placed = new Vec3(x, y, z);
    }

    /** {@return vrai si la caméra a pris la position ; faux au repli, dit une fois} */
    private static boolean position(Camera camera, double x, double y, double z) {
        if (positionUnavailable) {
            return false;
        }
        try {
            if (setPosition == null) {
                setPosition = ObfuscationReflectionHelper.findMethod(Camera.class, SET_POSITION, double.class,
                        double.class, double.class);
            }
            setPosition.invoke(camera, x, y, z);
            return true;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            positionUnavailable = true;
            LOGGER.warn("AXION : la caméra ne se pose pas ({}) ; les vues de véhicule se replient sur la troisième"
                    + " personne de Minecraft (ADR-170 D5)", failure.toString());
            return false;
        }
    }

    /** {@return la part libre d'un segment dans le monde, 1 sans bloc} */
    private static double free(Minecraft minecraft, LocalPlayer player, double[] from, double[] to) {
        Vec3 start = new Vec3(from[0], from[1], from[2]);
        Vec3 end = new Vec3(to[0], to[1], to[2]);
        BlockHitResult hit = minecraft.level.clip(new ClipContext(start, end, ClipContext.Block.VISUAL,
                ClipContext.Fluid.NONE, player));
        if (hit.getType() == HitResult.Type.MISS) {
            return 1;
        }
        double length = start.distanceTo(end);
        return length <= 0 ? 1 : start.distanceTo(hit.getLocation()) / length;
    }

    /** La descente, ou un véhicule sans vue : la perspective d'avant revient. */
    private static void leave(Minecraft minecraft) {
        active = null;
        placed = null;
        lastTarget = null;
        lastFrameNanos = 0;
        velocity = Vec3.ZERO;
        if (before != null) {
            minecraft.options.setCameraType(before);
            before = null;
        }
    }

    private record Seat(AxionEntity assembly, int rank, List<VehicleViews.View> views) {}

    private static Seat seat(LocalPlayer player) {
        if (!(player.getVehicle() instanceof AxionEntity assembly)) {
            return null;
        }
        Seating seating = assembly.declaredSeating();
        int rank = assembly.seatOf(player);
        if (seating == null || rank < 0) {
            return null;
        }
        List<VehicleViews.View> views = seating.views().forSeat(rank);
        return views.isEmpty() ? null : new Seat(assembly, rank, views);
    }
}
