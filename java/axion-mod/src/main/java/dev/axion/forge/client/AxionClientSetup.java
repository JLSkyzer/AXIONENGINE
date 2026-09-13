package dev.axion.forge.client;

import dev.axion.AxionMod;
import dev.axion.forge.AxionEntities;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
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
    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(AxionEntities.ASSEMBLY.get(), AxionEntityRenderer::new);
    }
}
