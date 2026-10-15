package dev.axion.asset;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Les fichiers voisins possibles d'une source (ADR-139, R-830).
 *
 * <p>Un {@code .mtl} ou un buffer glTF externe peut porter n'importe quel nom : à
 * l'énumération, tout fichier des modèles qui n'est ni une source, ni un
 * {@code .axion.json}, ni une image est donc gardé. Le compilateur ne lit jamais d'image :
 * une image à URI externe n'est qu'un chemin dans {@code TEXR} (R-532). Une source ne voit
 * que les fichiers de son répertoire et de ses sous-répertoires, puisqu'une référence ne
 * remonte jamais (R-531).
 */
public final class AssetSiblings {

    /** Extensions des images, que le compilateur ne lit pas. */
    private static final List<String> IMAGES = List.of(".png", ".jpg", ".jpeg", ".ktx2", ".webp");

    private AssetSiblings() {
        throw new AssertionError("classe utilitaire, non instanciable");
    }

    /**
     * {@return vrai si le chemin désigne une image}
     *
     * @param path chemin d'une ressource
     */
    public static boolean isImage(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return IMAGES.stream().anyMatch(lower::endsWith);
    }

    /**
     * {@return vrai si le fichier peut servir de voisin : ni source, ni options, ni image}
     *
     * @param path chemin d'une ressource
     */
    public static boolean isCandidate(String path) {
        return SourceFormats.fromPath(path) == SourceFormats.UNKNOWN
                && !AssetOptionsFiles.isOptionsFile(path)
                && !isImage(path);
    }

    /**
     * {@return le répertoire d'un chemin de ressource, séparateur final compris}
     *
     * <p>{@code ns:axion/models/voiture/voiture.gltf} → {@code ns:axion/models/voiture/}.
     *
     * @param path chemin d'une ressource
     */
    public static String directoryOf(String path) {
        int slash = path.lastIndexOf('/');
        if (slash >= 0) {
            return path.substring(0, slash + 1);
        }
        int colon = path.indexOf(':');
        return colon >= 0 ? path.substring(0, colon + 1) : "";
    }

    /**
     * {@return les voisins possibles d'une source parmi les ressources énumérées, triés}
     *
     * @param source chemin de la source
     * @param listed ressources énumérées
     */
    public static List<String> candidatesOf(String source, Collection<String> listed) {
        String directory = directoryOf(source);
        List<String> out = new ArrayList<>();
        for (String path : listed) {
            if (!path.equals(source) && path.startsWith(directory) && isCandidate(path)) {
                out.add(path);
            }
        }
        out.sort(null);
        return out;
    }

    /**
     * {@return le nom d'un voisin relatif au répertoire de la source, celui que le natif
     * demande}
     *
     * @param source chemin de la source
     * @param sibling chemin d'un voisin, sous le répertoire de la source
     */
    public static String relative(String source, String sibling) {
        return sibling.substring(directoryOf(source).length());
    }
}
