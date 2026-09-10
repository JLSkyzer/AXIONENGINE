package dev.axion.forge;

import dev.axion.asset.AssetSource;
import dev.axion.asset.SourceFormats;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * Sources d'assets lues dans un {@link ResourceManager} (C-20 étape 1).
 *
 * <p>Les fichiers sont <strong>lus au moment de l'énumération</strong>, pas à
 * la demande. Un {@code ResourceManager} est remplacé à chaque rechargement de
 * packs, et le garder pour lire plus tard reviendrait à lire dans un
 * gestionnaire périmé — ce qui, sur un serveur qui recharge ses datapacks,
 * arrive exactement au moment où l'on croit avoir fini.
 *
 * <p>Le préfixe est relatif à la racine du gestionnaire. Le même
 * {@code axion/models} désigne donc {@code assets/&lt;ns&gt;/axion/models} côté
 * client et {@code data/&lt;ns&gt;/axion/models} côté serveur : ce sont deux
 * gestionnaires distincts, et chacun ne voit que sa racine.
 */
public final class ResourceAssetSource implements AssetSource {

    /** Préfixe des modèles, relatif à la racine du gestionnaire. */
    public static final String MODELS = "axion/models";

    private final Map<String, byte[]> files = new LinkedHashMap<>();
    private final List<String> failures = new ArrayList<>();

    /**
     * Énumère et lit les sources sous un préfixe.
     *
     * @param manager gestionnaire de ressources du rechargement en cours
     * @param prefix préfixe à explorer, par exemple {@link #MODELS}
     */
    public ResourceAssetSource(ResourceManager manager, String prefix) {
        Map<ResourceLocation, Resource> found =
                manager.listResources(prefix, location -> isSource(location.getPath()));

        // L'ordre décide de l'ordre de compilation, donc de ce qui est prêt en
        // premier. Trié pour que deux démarrages du même pack produisent la
        // même séquence : sans cela, un défaut qui dépend de l'ordre
        // n'apparaîtrait qu'un démarrage sur deux.
        List<ResourceLocation> ordered = new ArrayList<>(found.keySet());
        ordered.sort(ResourceLocation::compareTo);

        for (ResourceLocation location : ordered) {
            try (InputStream stream = found.get(location).open()) {
                files.put(location.toString(), stream.readAllBytes());
            } catch (IOException failure) {
                // Une ressource illisible ne fait pas échouer l'énumération :
                // les autres assets n'y sont pour rien, et R-522 veut que le
                // monde se charge quand même.
                failures.add(location + " — " + failure);
            }
        }
    }

    /** Indique si un chemin désigne une source d'asset compilable. */
    private static boolean isSource(String path) {
        return SourceFormats.fromPath(path) != SourceFormats.UNKNOWN;
    }

    @Override
    public List<String> list() {
        return List.copyOf(files.keySet());
    }

    @Override
    public byte[] read(String path) throws IOException {
        byte[] content = files.get(path);
        if (content == null) {
            throw new IOException("ressource absente de l'énumération : " + path);
        }
        return content;
    }

    /** {@return les ressources qui n'ont pas pu être lues} */
    public List<String> failures() {
        return List.copyOf(failures);
    }

    /** {@return le nombre de sources énumérées} */
    public int size() {
        return files.size();
    }
}
