package dev.axion.forge.client;

import dev.axion.forge.AuthoritativeThread;
import dev.axion.forge.AxionEntity;
import dev.axion.seat.SeatPlacement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import org.joml.Vector3f;

/**
 * Les passagers des assemblies, posés à chaque image (client ; ADR-158 D3, précisé par ADR-159).
 *
 * <p>Une assembly est dessinée à la pose de l'interpolation datée de l'image ; Minecraft, lui, dessine une
 * entité entre ses deux derniers ticks. Posé au seul tick, un passager — et la caméra du joueur assis — aurait un
 * tick de retard sur son siège : 75 cm à 15 m/s. Au début de chaque image, après les ticks et avant la caméra et
 * le rendu, chaque passager est donc posé au siège de la pose dessinée, ses positions ancienne et courante
 * confondues : quel que soit le {@code partialTick}, l'image le montre assis à sa place. Le tick suivant le repose
 * comme avant, et rien de ce qui part au serveur ne vient d'ici.
 *
 * <p>Le joueur local tourne aussi avec l'assembly, d'image en image, comme le fait {@code Entity.turn} avec la
 * souris : son regard, sa tête et son corps gardent leur angle par rapport au véhicule. Les autres joueurs
 * tournent chez eux et arrivent tournés ; les tourner ici les tournerait deux fois.
 */
final class SeatedPassengers {

    /** L'assembly où le joueur local est assis, {@code -1} sans. */
    private static int vehicle = -1;
    /** Le lacet de cette assembly à l'image précédente, en degrés. */
    private static float lastYaw;

    private SeatedPassengers() {}

    /**
     * Pose les passagers à l'image qui commence.
     *
     * @param minecraft le client
     * @param partialTick fraction du tick écoulée, celle avec laquelle l'image sera dessinée
     */
    static void onFrame(Minecraft minecraft, float partialTick) {
        AuthoritativeThread.requireClient();
        ClientLevel level = minecraft.level;
        if (level == null) {
            vehicle = -1;
            return;
        }
        LocalPlayer player = minecraft.player;
        boolean seated = false;
        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof AxionEntity assembly) || !assembly.isVehicle() || !assembly.interpolating()) {
                continue;
            }
            AxionEntity.RenderPose shown = assembly.renderPose(partialTick);
            for (Entity passenger : assembly.getPassengers()) {
                SeatPlacement.Pose seat = assembly.seatPoseAt(assembly.seatOf(passenger), shown);
                if (seat != null) {
                    place(passenger, seat.x(), seat.y() + passenger.getMyRidingOffset(), seat.z());
                }
            }
            if (player != null && player.getVehicle() == assembly) {
                seated = true;
                turnWith(player, assembly.getId(), yaw(shown));
            }
        }
        if (!seated) {
            vehicle = -1;
        }
    }

    private static void place(Entity passenger, double x, double y, double z) {
        passenger.setPos(x, y, z);
        passenger.xo = x;
        passenger.yo = y;
        passenger.zo = z;
        passenger.xOld = x;
        passenger.yOld = y;
        passenger.zOld = z;
    }

    /** Tourne le joueur de ce que l'assembly a tourné depuis l'image précédente ; rien à la première. */
    private static void turnWith(LocalPlayer player, int id, float yaw) {
        if (vehicle != id) {
            vehicle = id;
            lastYaw = yaw;
            return;
        }
        float delta = Mth.wrapDegrees(yaw - lastYaw);
        lastYaw = yaw;
        if (delta == 0f) {
            return;
        }
        player.setYRot(player.getYRot() + delta);
        player.yRotO += delta;
        player.setYHeadRot(player.getYHeadRot() + delta);
        player.yHeadRotO += delta;
        player.yBodyRot += delta;
        player.yBodyRotO += delta;
    }

    /** {@return le lacet de Minecraft, en degrés, de l'avant de la pose (son {@code -Z}, §6.5)} */
    static float yaw(AxionEntity.RenderPose shown) {
        Vector3f forward = shown.rotation().transform(new Vector3f(0f, 0f, -1f));
        return (float) Math.toDegrees(Math.atan2(-forward.x, forward.z));
    }
}
