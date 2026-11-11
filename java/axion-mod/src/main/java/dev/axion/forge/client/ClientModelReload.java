package dev.axion.forge.client;

import com.mojang.logging.LogUtils;
import dev.axion.api.content.ContentKind;
import dev.axion.asset.LayeredAssetSource;
import dev.axion.content.RegisteredContent;
import dev.axion.forge.ResourceAssetSource;
import dev.axion.forge.RuntimeAccess;
import dev.axion.lifecycle.AxionRuntime;
import java.util.concurrent.CompletableFuture;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;

/**
 * Les modèles du client, à chaque rechargement de ses ressources (ADR-150 D3, ADR-162 D5).
 *
 * <p>Les assets ne passent jamais par le réseau (R-1640) : le client compile chez lui. Ses modèles sont ceux que les
 * mods installés chez lui ont enregistrés (ADR-162 D2). Par-dessus, un resource pack — y compris celui qu'un serveur
 * pousse — peut remplacer l'<em>apparence</em> d'un modèle, sous {@code assets/<ns>/axion/models/**} : la forme
 * visible et ses textures, jamais la physique, que le serveur compile depuis le JAR des mods (ADR-162 D5).
 *
 * <p>Les packs sont énumérés et lus dans la phase de préparation, hors du thread du client. Le contenu enregistré est
 * pris dans la phase d'application : au premier rechargement, que le client lance pendant le chargement des mods, la
 * préparation peut précéder l'enregistrement ; l'application, elle, suit la barrière qui attend la fin de ce
 * chargement.
 */
final class ClientModelReload extends SimplePreparableReloadListener<ResourceAssetSource> {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * ADR-162 D9 : après {@code /axion dev reload} en solo, recompile les modèles du client depuis le contenu
     * réenregistré, sans recharger toutes ses ressources. Appelé du thread du serveur intégré : les packs se lisent
     * sur un thread de fond, et l'application se fait sur le thread du client, comme lors d'un rechargement ; puis
     * AXION rend ses maillages et ses textures, reconstruits à la demande.
     */
    static void refresh() {
        Minecraft minecraft = Minecraft.getInstance();
        ResourceManager manager = minecraft.getResourceManager();
        ClientModelReload reload = new ClientModelReload();
        CompletableFuture.supplyAsync(() -> reload.prepare(manager, InactiveProfiler.INSTANCE), Util.backgroundExecutor())
                .thenAcceptAsync(packs -> {
                    reload.apply(packs, manager, InactiveProfiler.INSTANCE);
                    AxionRenderPass.onResourcesReloaded();
                }, minecraft)
                .exceptionally(failure -> {
                    LOGGER.error("AXION : client : modèles non rechargés après /axion dev reload", failure);
                    return null;
                });
    }

    @Override
    protected ResourceAssetSource prepare(ResourceManager manager, ProfilerFiller profiler) {
        AxionRuntime runtime = RuntimeAccess.get();
        long maxSiblingBytes = runtime == null ? Long.MAX_VALUE : runtime.maxSiblingBytes();
        return new ResourceAssetSource(manager, ResourceAssetSource.MODELS, maxSiblingBytes);
    }

    @Override
    protected void apply(ResourceAssetSource packs, ResourceManager manager, ProfilerFiller profiler) {
        packs.failures().forEach(failure -> LOGGER.warn("AXION : modèle illisible — {}", failure));
        AxionRuntime runtime = RuntimeAccess.get();
        if (runtime == null) {
            return;
        }
        if (!RegisteredContent.published()) {
            LOGGER.warn("AXION : client : aucun contenu enregistré ; seuls les modèles des resource packs sont "
                    + "connus");
        }
        LayeredAssetSource source = new LayeredAssetSource(packs, RegisteredContent.source(ContentKind.MODEL));
        int pending = runtime.onClientAssetReload(source);
        LOGGER.info("AXION : client : {} modèle(s) énuméré(s), {} à compiler", source.list().size(), pending);
    }
}
