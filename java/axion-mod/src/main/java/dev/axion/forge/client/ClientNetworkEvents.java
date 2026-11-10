package dev.axion.forge.client;

import dev.axion.AxionMod;
import dev.axion.forge.AxionEntity;
import dev.axion.forge.RuntimeAccess;
import dev.axion.forge.net.AxionNetwork;
import dev.axion.lifecycle.AxionRuntime;
import dev.axion.seat.GearSelector;
import dev.axion.seat.VehicleInput;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * La connexion du client, vue du client (ADR-150 D5). Classe à part, abonnée pour {@link Dist#CLIENT}
 * seulement : l'événement nomme des classes du client, qu'un serveur dédié n'a pas.
 */
@Mod.EventBusSubscriber(modid = AxionMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ClientNetworkEvents {

    /** Le rapport que les touches de boîte ont choisi (ADR-159 D6) ; le thread du client seul le touche. */
    private static final GearSelector GEAR = new GearSelector();

    private ClientNetworkEvents() {}

    /**
     * Une assembly quitte le monde du client — sa disparition, la fin de son suivi : ce que le récepteur
     * des snapshots gardait d'elle s'oublie, et le serveur la renverra en entier si elle revient.
     */
    @SubscribeEvent
    public static void onEntityLeave(EntityLeaveLevelEvent event) {
        if (!event.getLevel().isClientSide() || !(event.getEntity() instanceof AxionEntity)) {
            return;
        }
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection != null) {
            AxionNetwork.forgetClientEntity(connection.getConnection(), event.getEntity().getId());
        }
    }

    /**
     * À la déconnexion, les definitions reçues d'un serveur distant s'oublient : le suivant enverra les
     * siennes. L'horloge de rendu aussi : le suivant annoncera son retard d'interpolation.
     */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientSnapshots.reset();
        AxionRuntime runtime = RuntimeAccess.get();
        if (runtime != null) {
            runtime.onRemoteDisconnect();
        }
    }

    /**
     * Fin d'un tick client : un joueur assis à un siège qui déclare des contrôles envoie ses entrées, une
     * fois par tick (R-710, ADR-158 D4), le rapport choisi par les touches de boîte compris (ADR-159 D6). Rien
     * sinon, ni dans un jeu en pause.
     */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        ClientPacketListener connection = minecraft.getConnection();
        if (event.phase != TickEvent.Phase.END || player == null || connection == null || minecraft.isPaused()) {
            return;
        }
        if (!(player.getVehicle() instanceof AxionEntity assembly)) {
            GEAR.reset();
            return;
        }
        int seat = assembly.seatOf(player);
        byte gear = GEAR.select(assembly.getId(), VehicleKeys.presses(VehicleKeys.GEAR_UP),
                VehicleKeys.presses(VehicleKeys.GEAR_DOWN), assembly.gearRange());
        if (assembly.seatTakesInput(seat)) {
            AxionNetwork.sendInput(connection.getConnection(), assembly.getId(), seat, VehicleInput.ofKeys(
                    player.input.forwardImpulse, player.input.leftImpulse, player.input.jumping, gear));
        }
    }

    /**
     * Une image commence, avant le rendu du monde : l'horloge de rendu de 21.7 note son heure, à laquelle
     * toutes ses passes se datent, et la pause d'un monde solo, qui suspend aussi son serveur. Puis les
     * passagers des assemblies sont posés à leur siège de cette image, avant que la caméra ne se place.
     */
    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            Minecraft minecraft = Minecraft.getInstance();
            ClientSnapshots.onFrame(System.nanoTime(), minecraft.isPaused());
            SeatedPassengers.onFrame(minecraft, event.renderTickTime);
        }
    }
}
