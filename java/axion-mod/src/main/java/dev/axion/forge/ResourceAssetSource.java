package dev.axion.forge;

import dev.axion.asset.AssetSiblings;
import dev.axion.asset.AssetSource;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
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

    /** Préfixe des definitions (C-27), relatif à la racine du gestionnaire. */
    public static final String DEFINITIONS = "axion/definitions";

    /** Contenu par chemin, dans l'ordre d'énumération ; {@code null} si illisible. */
    private final Map<String, byte[]> files = new LinkedHashMap<>();
    private final Map<String, IOException> unreadable = new HashMap<>();
    private final List<String> failures = new ArrayList<>();

    /**
     * Énumère et lit les sources de modèles sous un préfixe, avec leurs fichiers
     * d'options (ADR-135) et leurs fichiers voisins possibles (ADR-139).
     *
     * <p>Un voisin possible est n'importe quel fichier qui n'est pas une image :
     * rien ne dit d'avance lequel une source référencera. Il n'est gardé que
     * jusqu'à {@code maxSiblingBytes} — {@code assets.max_source_bytes} —, pour
     * qu'un fichier volumineux posé parmi les modèles ne soit pas retenu en
     * mémoire à chaque rechargement. Au-delà, il reste énuméré mais sa lecture
     * échoue, comme celle d'une ressource illisible : une source qui le demande
     * se compile sans lui.
     *
     * @param manager gestionnaire de ressources du rechargement en cours
     * @param prefix préfixe à explorer, par exemple {@link #MODELS}
     * @param maxSiblingBytes taille au-delà de laquelle un voisin n'est pas lu
     */
    public ResourceAssetSource(ResourceManager manager, String prefix, long maxSiblingBytes) {
        this(manager, prefix, ResourceAssetSource::isModelFile, maxSiblingBytes);
    }

    /**
     * Énumère et lit les ressources sous un préfixe, filtrées par chemin.
     *
     * @param manager gestionnaire de ressources du rechargement en cours
     * @param prefix préfixe à explorer
     * @param accepted indique si un chemin est à lire
     */
    public ResourceAssetSource(
            ResourceManager manager, String prefix, Predicate<String> accepted) {
        this(manager, prefix, accepted, Long.MAX_VALUE);
    }

    private ResourceAssetSource(
            ResourceManager manager,
            String prefix,
            Predicate<String> accepted,
            long maxSiblingBytes) {
        Map<ResourceLocation, Resource> found =
                manager.listResources(prefix, location -> accepted.test(location.getPath()));

        // L'ordre décide de l'ordre de compilation, donc de ce qui est prêt en
        // premier. Trié pour que deux démarrages du même pack produisent la
        // même séquence : sans cela, un défaut qui dépend de l'ordre
        // n'apparaîtrait qu'un démarrage sur deux.
        List<ResourceLocation> ordered = new ArrayList<>(found.keySet());
        ordered.sort(ResourceLocation::compareTo);

        for (ResourceLocation location : ordered) {
            long limit = AssetSiblings.isCandidate(location.getPath())
                    ? maxSiblingBytes
                    : Long.MAX_VALUE;
            try (InputStream stream = found.get(location).open()) {
                files.put(location.toString(), readBounded(stream, limit));
            } catch (IOException failure) {
                // Une ressource illisible ne fait pas échouer l'énumération :
                // les autres assets n'y sont pour rien, et R-522 veut que le
                // monde se charge quand même. Elle reste pourtant énumérée, et
                // sa lecture rend l'échec : à celui qui la lit de le consigner
                // — un asset de secours, une definition refusée, une source
                // dont les options manquent — plutôt que de la voir disparaître.
                // Une source dont le `.axion.json` aurait disparu serait
                // compilée avec les défauts, sans que rien ne le dise (ADR-135).
                files.put(location.toString(), null);
                unreadable.put(location.toString(), failure);
                failures.add(location + " — " + failure);
            }
        }
    }

    /**
     * Indique si un chemin est à lire avec les modèles : une source, le fichier
     * d'options qui l'accompagne, ou un fichier qu'elle peut référencer — tout ce
     * qui n'est pas une image (ADR-139 §1). Un {@code .mtl} ou un buffer glTF
     * porte le nom que l'auteur lui donne, et le gestionnaire n'est pas gardé
     * pour le lire plus tard : ce que le compilateur demandera doit déjà être là.
     * Une image ne l'est jamais : le compilateur n'en lit aucune (R-532).
     */
    private static boolean isModelFile(String path) {
        return !AssetSiblings.isImage(path);
    }

    /**
     * Lit une ressource sans retenir plus de {@code limit} octets.
     *
     * @throws IOException si la ressource est illisible, ou plus grande que la borne
     */
    private static byte[] readBounded(InputStream stream, long limit) throws IOException {
        if (limit >= Integer.MAX_VALUE - 8) {
            return stream.readAllBytes();
        }
        byte[] bytes = stream.readNBytes((int) limit + 1);
        if (bytes.length > limit) {
            throw new IOException("non lue : plus de " + limit
                    + " octets (assets.max_source_bytes, ADR-139)");
        }
        return bytes;
    }

    @Override
    public List<String> list() {
        return List.copyOf(files.keySet());
    }

    @Override
    public byte[] read(String path) throws IOException {
        IOException failure = unreadable.get(path);
        if (failure != null) {
            throw new IOException("illisible à l'énumération : " + failure.getMessage(), failure);
        }
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

    /** {@return le nombre de fichiers énumérés : sources, options et voisins} */
    public int size() {
        return files.size();
    }
}
