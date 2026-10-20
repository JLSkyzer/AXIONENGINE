package dev.axion.forge.client;

import dev.axion.AxionMod;
import dev.axion.forge.AxionEntity;
import dev.axion.forge.RuntimeAccess;
import dev.axion.forge.net.AxionNetwork;
import dev.axion.lifecycle.AxionRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
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
     * Une image commence, avant le rendu du monde : l'horloge de rendu de 21.7 note son heure, à laquelle
     * toutes ses passes se datent, et la pause d'un monde solo, qui suspend aussi son serveur.
     */
    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            ClientSnapshots.onFrame(System.nanoTime(), Minecraft.getInstance().isPaused());
        }
    }
}
