package dev.axion.forge.client;

import dev.axion.AxionMod;
import dev.axion.forge.RuntimeAccess;
import dev.axion.lifecycle.AxionRuntime;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * La connexion du client, vue du client (ADR-150 D5). Classe à part, abonnée pour {@link Dist#CLIENT}
 * seulement : l'événement nomme des classes du client, qu'un serveur dédié n'a pas.
 */
@Mod.EventBusSubscriber(modid = AxionMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ClientNetworkEvents {

    private ClientNetworkEvents() {}

    /** À la déconnexion, les definitions reçues d'un serveur distant s'oublient : le suivant enverra les siennes. */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        AxionRuntime runtime = RuntimeAccess.get();
        if (runtime != null) {
            runtime.onRemoteDisconnect();
        }
    }
}
