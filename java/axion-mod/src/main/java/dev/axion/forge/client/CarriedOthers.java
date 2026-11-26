package dev.axion.forge.client;

import dev.axion.AxionMod;
import dev.axion.forge.AuthoritativeThread;
import dev.axion.forge.AxionEntity;
import dev.axion.net.CarriedTrack;
import dev.axion.physics.BodyFrame;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Les autres entités qu'une assembly porte, chez ce client (ADR-184 D2, D3, CAP-14) : joueurs, mobs et objets. La position
 * brute que le serveur en envoie date d'un autre retard que le corps dessiné ; à 9 m/s elle les montre à plusieurs blocs de
 * lui, jambes en marche. Les snapshots disent leur place sur le corps ; chacune y est dessinée.
 *
 * <ul>
 *   <li><b>À chaque image</b>, elle est posée à sa place sur le corps tel que l'image le dessine.</li>
 *   <li><b>À son tick</b>, un être vivant part de sa place d'avant sur le corps du tick et vise celle d'après : son
 *       animation et son corps ne lisent que son mouvement sur le plateau, son regard suit comme d'ordinaire.</li>
 *   <li><b>Plus listée</b>, elle revient à la position de Minecraft, lissée sur 200 ms.</li>
 * </ul>
 *
 * <p>Le joueur local est porté par {@link CarriedPlayer} ; un passager, par son siège ; ce que ce client conduit, par
 * lui-même.
 */
@Mod.EventBusSubscriber(modid = AxionMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class CarriedOthers {

    /** Les ticks du retour à la position de Minecraft : 200 ms. */
    private static final int RELEASE_TICKS = 4;

    /** Ce qu'une assembly dit d'une entité portée. */
    private record Ridden(AxionEntity assembly, CarriedTrack.Place place) {}

    /** La place d'une entité sur son corps à son dernier tick. */
    private record Held(AxionEntity assembly, int body, double[] local) {}

    /** Les entités portées à ce tick, par identifiant réseau. */
    private static Map<Integer, Ridden> ticked = Map.of();

    private static final Map<Integer, Held> HELD = new HashMap<>();

    private CarriedOthers() {}

    /** @param event le début d'un tick du client, avant celui des entités */
    @SubscribeEvent
    public static void onCarriedClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        AuthoritativeThread.requireClient();
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            ticked = Map.of();
            HELD.clear();
            return;
        }
        if (minecraft.isPaused()) {
            return;
        }
        ticked = ridden(level, System.nanoTime());
        for (var held = HELD.keySet().iterator(); held.hasNext(); ) {
            int id = held.next();
            if (!ticked.containsKey(id)) {
                release(level.getEntity(id));
                held.remove();
            }
        }
    }

    /** @param event le tick d'un être vivant, avant que Minecraft ne l'avance vers sa position */
    @SubscribeEvent
    public static void onCarriedLivingTick(LivingEvent.LivingTickEvent event) {
        LivingEntity entity = event.getEntity();
        if (!entity.level().isClientSide()) {
            return;
        }
        AuthoritativeThread.requireClient();
        Ridden ridden = ticked.get(entity.getId());
        if (ridden == null || !follows(entity)) {
            return;
        }
        double[] poses = ridden.assembly().collisionPoses();
        int body = ridden.place().body();
        if (poses == null || body >= poses.length / BodyFrame.POSE) {
            return;
        }
        Held before = HELD.get(entity.getId());
        double[] local = ridden.place().local();
        double[] from = before != null && before.assembly() == ridden.assembly() && before.body() == body
                ? before.local() : local;
        double[] start = BodyFrame.toWorld(poses, body, from);
        double[] end = BodyFrame.toWorld(poses, body, local);
        entity.setPos(end[0], end[1], end[2]);
        // Parti de sa place d'avant sur le corps d'aujourd'hui : le mouvement lu est le sien seul.
        entity.xo = start[0];
        entity.yo = start[1];
        entity.zo = start[2];
        entity.xOld = start[0];
        entity.yOld = start[1];
        entity.zOld = start[2];
        entity.lerpX = end[0];
        entity.lerpY = end[1];
        entity.lerpZ = end[2];
        HELD.put(entity.getId(), new Held(ridden.assembly(), body, local));
    }

    /**
     * Pose les entités portées à l'image qui commence.
     *
     * @param minecraft le client
     * @param partialTick fraction du tick écoulée
     */
    static void onFrame(Minecraft minecraft, float partialTick) {
        AuthoritativeThread.requireClient();
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.isPaused()) {
            return;
        }
        for (Map.Entry<Integer, Ridden> entry : ridden(level, 0L).entrySet()) {
            Entity entity = level.getEntity(entry.getKey());
            if (entity == null || !follows(entity)) {
                continue;
            }
            Ridden ridden = entry.getValue();
            AxionEntity assembly = ridden.assembly();
            double[] poses = assembly.framePoses(assembly.renderPose(partialTick));
            int body = ridden.place().body();
            if (poses == null || body >= poses.length / BodyFrame.POSE) {
                continue;
            }
            double[] world = BodyFrame.toWorld(poses, body, ridden.place().local());
            SeatedPassengers.place(entity, world[0], world[1], world[2]);
        }
    }

    /**
     * Les entités que les assemblies disent porter à un instant ; si deux en listent une, le temps d'un changement de
     * support, celle dont le descripteur est le plus récent.
     */
    private static Map<Integer, Ridden> ridden(ClientLevel level, long nanos) {
        Map<Integer, Ridden> ridden = new HashMap<>();
        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof AxionEntity assembly) || !assembly.interpolating()) {
                continue;
            }
            for (Map.Entry<Integer, CarriedTrack.Place> carried : assembly.carriedAt(nanos).entrySet()) {
                ridden.merge(carried.getKey(), new Ridden(assembly, carried.getValue()),
                        (one, other) -> one.place().tick() >= other.place().tick() ? one : other);
            }
        }
        return ridden;
    }

    /** {@return vrai si cette entité suit la place que les snapshots disent} */
    private static boolean follows(Entity entity) {
        return entity != Minecraft.getInstance().player && !entity.isRemoved() && !entity.isPassenger()
                && !entity.isControlledByLocalInstance();
    }

    /** Un être vivant descendu vise la dernière position reçue du serveur, en 200 ms ; le reste suit Minecraft. */
    private static void release(Entity entity) {
        if (entity instanceof LivingEntity living && follows(living)) {
            Vec3 server = living.getPositionCodec().decode(0L, 0L, 0L);
            living.lerpTo(server.x, server.y, server.z, living.getYRot(), living.getXRot(), RELEASE_TICKS, false);
        }
    }
}
