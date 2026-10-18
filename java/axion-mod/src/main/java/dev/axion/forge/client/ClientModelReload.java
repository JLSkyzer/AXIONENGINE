package dev.axion.forge.client;

import com.mojang.logging.LogUtils;
import dev.axion.asset.LayeredAssetSource;
import dev.axion.forge.ResourceAssetSource;
import dev.axion.forge.RuntimeAccess;
import dev.axion.lifecycle.AxionRuntime;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.forgespi.language.IModInfo;
import net.minecraftforge.resource.ResourcePackLoader;
import org.slf4j.Logger;

/**
 * Les modèles du client, à chaque rechargement de ses ressources (ADR-150 D3).
 *
 * <p>Les assets ne passent jamais par le réseau (R-1640) : le client compile chez lui. Il énumère
 * d'abord {@code assets/<ns>/axion/models/**} de ses resource packs — y compris le pack qu'un serveur
 * pousse —, puis, pour ce qu'aucun pack ne fournit, {@code data/<ns>/axion/models/**} des mods
 * installés chez lui : un mod ne livre son modèle qu'une fois, dans {@code data/}, où le serveur le
 * lit. Un pack remplace un fichier du mod, comme partout dans Minecraft.
 *
 * <p>L'énumération et la lecture ont lieu dans la phase de préparation, hors du thread du client ;
 * le registre est publié dans la phase d'application.
 */
final class ClientModelReload extends SimplePreparableReloadListener<ClientModelReload.Prepared> {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Ce que la préparation a lu : les modèles superposés, et ce qui n'a pas pu l'être. */
    record Prepared(LayeredAssetSource source, List<String> failures) {}

    @Override
    protected Prepared prepare(ResourceManager manager, ProfilerFiller profiler) {
        AxionRuntime runtime = RuntimeAccess.get();
        long maxSiblingBytes = runtime == null ? Long.MAX_VALUE : runtime.maxSiblingBytes();
        ResourceAssetSource packs =
                new ResourceAssetSource(manager, ResourceAssetSource.MODELS, maxSiblingBytes);
        ResourceAssetSource mods = modData(maxSiblingBytes);
        List<String> failures = new ArrayList<>(packs.failures());
        failures.addAll(mods.failures());
        return new Prepared(new LayeredAssetSource(packs, mods), failures);
    }

    @Override
    protected void apply(Prepared prepared, ResourceManager manager, ProfilerFiller profiler) {
        prepared.failures().forEach(failure -> LOGGER.warn("AXION : modèle illisible — {}", failure));
        AxionRuntime runtime = RuntimeAccess.get();
        if (runtime == null) {
            return;
        }
        int pending = runtime.onClientAssetReload(prepared.source());
        LOGGER.info("AXION : client : {} modèle(s) énuméré(s), {} à compiler",
                prepared.source().list().size(), pending);
    }

    /**
     * {@return les modèles de {@code data/} des mods installés sur ce client}
     *
     * <p>Le client n'a pas de gestionnaire de données : les packs des mods, que Forge tient pour
     * eux, sont relus ici sous {@link PackType#SERVER_DATA}. Fermer le gestionnaire temporaire ne
     * ferme rien d'utile : le {@code close} d'un pack de mod de Forge ne fait rien.
     */
    private static ResourceAssetSource modData(long maxSiblingBytes) {
        List<PackResources> packs = new ArrayList<>();
        for (IModInfo mod : ModList.get().getMods()) {
            ResourcePackLoader.getPackFor(mod.getModId()).ifPresent(packs::add);
        }
        try (MultiPackResourceManager data = new MultiPackResourceManager(PackType.SERVER_DATA, packs)) {
            return new ResourceAssetSource(data, ResourceAssetSource.MODELS, maxSiblingBytes);
        }
    }
}
