package dev.axion.forge.client;

import dev.axion.AxionMod;
import dev.axion.forge.AuthoritativeThread;
import dev.axion.forge.AxionEntity;
import dev.axion.forge.CarrySupports;
import dev.axion.physics.BodyFrame;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Le joueur local porté par une assembly (ADR-178 D3, CAP-14), chez le client.
 *
 * <p>Un support (D2) garde la place du joueur dans le repère du corps qui le porte. Trois moments :
 *
 * <ul>
 *   <li><b>au début de son tick</b>, il est reposé à sa place <b>dans le corps à la pose du tick</b> — celle que la collision
 *       balaie —, si bien que son mouvement part du plateau qui a bougé ;</li>
 *   <li><b>à la fin du tick</b>, sa place dans le corps se recalcule de là où son mouvement l'a mené ; un joueur qui quitte le
 *       support en l'air garde la vitesse horizontale que le plateau lui donnait ;</li>
 *   <li><b>au début de chaque image</b>, il est posé à la pose <b>dessinée</b> à cette image, entre ses deux dernières places,
 *       et tourne avec elle — comme un passager assis (ADR-158 D3) : sans cela, il tremblerait d'un tick de retard sur le
 *       plateau.</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = AxionMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class CarriedPlayer {

    /** Le support que le dernier tick a trouvé, et ce que le plateau a déplacé le joueur au début du tick. */
    private static CarrySupports.Support carried;
    private static Vec3 carry = Vec3.ZERO;
    /** Le lacet du corps à la dernière image, pour tourner le joueur de ce qu'il a tourné. */
    private static CarrySupports.Support turnedWith;
    private static float lastYaw;

    private CarriedPlayer() {}

    /** @param event le tick du joueur local */
    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!event.side.isClient() || !(event.player instanceof LocalPlayer player) || player != minecraft.player) {
            return;
        }
        AuthoritativeThread.requireClient();
        if (event.phase == TickEvent.Phase.START) {
            start(player);
        } else {
            end(player);
        }
    }

    private static void start(LocalPlayer player) {
        carry = Vec3.ZERO;
        carried = CarrySupports.of(player);
        if (carried == null || carried.local() == null) {
            return;
        }
        double[] poses = carried.assembly().collisionPoses();
        if (poses == null) {
            return;
        }
        double[] world = BodyFrame.toWorld(poses, carried.body(), carried.local());
        carry = new Vec3(world[0] - player.getX(), world[1] - player.getY(), world[2] - player.getZ());
        player.setPos(world[0], world[1], world[2]);
    }

    private static void end(LocalPlayer player) {
        CarrySupports.Support now = CarrySupports.of(player);
        if (now == null) {
            // Un saut ou le bord : il emporte la vitesse que le plateau lui donnait ce tick, à l'horizontale.
            if (carried != null && !player.onGround() && !player.isPassenger()) {
                player.setDeltaMovement(player.getDeltaMovement().add(carry.x, 0.0, carry.z));
            }
            carried = null;
            return;
        }
        double[] poses = now.assembly().collisionPoses();
        if (poses != null) {
            now.place(BodyFrame.toLocal(poses, now.body(), player.getX(), player.getY(), player.getZ()));
        }
        carried = now;
    }

    /**
     * Pose le joueur à l'image qui commence.
     *
     * @param minecraft le client
     * @param partialTick fraction du tick écoulée
     */
    static void onFrame(Minecraft minecraft, float partialTick) {
        AuthoritativeThread.requireClient();
        LocalPlayer player = minecraft.player;
        CarrySupports.Support support = player == null || minecraft.isPaused() ? null : CarrySupports.of(player);
        if (support == null || support.local() == null || player.isPassenger()) {
            turnedWith = null;
            return;
        }
        AxionEntity assembly = support.assembly();
        double[] poses = assembly.framePoses(assembly.renderPose(partialTick));
        if (poses == null) {
            return;
        }
        double[] before = support.previousLocal();
        double[] after = support.local();
        double[] between = {Mth.lerp(partialTick, before[0], after[0]), Mth.lerp(partialTick, before[1], after[1]),
                Mth.lerp(partialTick, before[2], after[2])};
        double[] world = BodyFrame.toWorld(poses, support.body(), between);
        SeatedPassengers.place(player, world[0], world[1], world[2]);
        float yaw = BodyFrame.yaw(poses, support.body());
        if (turnedWith == support) {
            SeatedPassengers.turn(player, Mth.wrapDegrees(yaw - lastYaw));
        }
        turnedWith = support;
        lastYaw = yaw;
    }
}
