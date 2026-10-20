package dev.axion.forge.client;

import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import dev.axion.forge.net.AxionNetwork;
import dev.axion.AxionMod;
import dev.axion.forge.AxionEntities;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Enregistrements propres au client.
 *
 * <p>Classe à part, abonnée pour {@link Dist#CLIENT} seulement : un serveur
 * dédié ne charge aucune classe de rendu, et les référencer depuis le point
 * d'entrée commun le ferait échouer au chargement.
 */
@Mod.EventBusSubscriber(modid = AxionMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class AxionClientSetup {

    private AxionClientSetup() {}

    /**
     * Enregistre le rendu de l'entité d'AXION.
     *
     * @param event événement d'enregistrement des rendus
     */
    /**
     * Pose ce que le client fait des états reçus par un snapshot (ADR-150 D6) : le code commun du réseau
     * ne nomme aucune classe du client.
     *
     * @param event mise en place du client
     */
    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        AxionNetwork.installClientSnapshots(ClientSnapshots::apply);
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(AxionEntities.ASSEMBLY.get(), AxionEntityRenderer::new);
    }

    /**
     * Rechargement des ressources du client. Les modèles d'abord : le client les compile chez lui,
     * depuis ses resource packs puis ses mods (ADR-150 D3). Puis AXION rend ses maillages et libère
     * ses textures (R-752), reconstruits à la demande depuis le registre neuf — une texture de
     * ressource a pu changer avec le resource pack, et le réglage des mipmaps avec les options.
     *
     * @param event enregistrement des écouteurs de rechargement
     */
    @SubscribeEvent
    public static void registerReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(new ClientModelReload());
        event.registerReloadListener(
                (ResourceManagerReloadListener) manager -> AxionRenderPass.onResourcesReloaded());
    }
}
