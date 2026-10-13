package dev.axion.asset;

/**
 * Le fichier d'options de compilation d'une source (PARTIE 6.4, ADR-135).
 *
 * <p>Le {@code <modèle>.axion.json} se tient à côté de sa source : même
 * répertoire du pack, même nom sans l'extension de la source —
 * {@code voiture.glb} → {@code voiture.axion.json}. {@code axion-cli} suit la
 * même règle ({@code sibling_options_path}) : la source qu'un auteur compile à
 * la main et celle que le jeu compile lisent le même fichier (R-830).
 *
 * <p>Le fichier n'est pas interprété ici : son texte part tel quel au natif, dont
 * le lecteur est celui de la CLI. Java ne juge pas les options, il les
 * transporte — deux lecteurs finiraient par ne plus dire la même chose.
 */
public final class AssetOptionsFiles {

    /** Suffixe d'un fichier d'options. */
    public static final String SUFFIX = ".axion.json";

    private AssetOptionsFiles() {
        throw new AssertionError("classe utilitaire, non instanciable");
    }

    /**
     * {@return vrai si le chemin désigne un fichier d'options}
     *
     * @param path chemin d'une ressource
     */
    public static boolean isOptionsFile(String path) {
        return path.endsWith(SUFFIX);
    }

    /**
     * Le fichier d'options voisin d'une source.
     *
     * <p>L'extension est ce qui suit le dernier point du nom ; un nom qui commence
     * par un point n'en a pas, comme pour {@code Path::file_stem} côté Rust.
     *
     * @param sourcePath chemin de la source, {@code ns:axion/models/voiture.glb}
     * @return le chemin de son fichier d'options,
     *     {@code ns:axion/models/voiture.axion.json}
     */
    public static String siblingOf(String sourcePath) {
        int name = Math.max(sourcePath.lastIndexOf('/'), sourcePath.lastIndexOf(':')) + 1;
        int dot = sourcePath.lastIndexOf('.');
        int stem = dot > name ? dot : sourcePath.length();
        return sourcePath.substring(0, stem) + SUFFIX;
    }
}
