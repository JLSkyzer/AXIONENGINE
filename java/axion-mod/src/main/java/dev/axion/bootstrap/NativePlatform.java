package dev.axion.bootstrap;

import java.util.Locale;
import java.util.Optional;

/**
 * Plateforme d'exécution et bibliothèque native correspondante (C-03).
 *
 * <p>L'identifiant de plateforme suit l'arborescence du JAR (33.2) :
 * {@code natives/<os>-<arch>/}. Les cinq combinaisons reconnues sont celles que
 * la table des plateformes déclare (34.2) ; toute autre conduit à un mode
 * {@code DISABLED} assumé, avec un message clair, plutôt qu'à une tentative de
 * chargement vouée à l'échec.
 *
 * @param id identifiant de plateforme, par exemple {@code windows-x86_64}
 * @param libraryFileName nom du fichier de bibliothèque sur cette plateforme
 */
public record NativePlatform(String id, String libraryFileName) {

    /**
     * Nom de base de la bibliothèque native.
     *
     * <p>R-420 impose qu'il soit unique au projet : le chargeur appelle
     * {@code System.load} sur un chemin absolu, mais un nom générique resterait
     * une source de confusion avec la bibliothèque native d'un autre mod.
     */
    public static final String LIBRARY_NAME = "axion_native";

    /** Racine des bibliothèques natives dans le JAR. */
    private static final String RESOURCE_ROOT = "/natives/";

    /**
     * Détecte la plateforme courante depuis les propriétés système.
     *
     * @return la plateforme, ou vide si elle n'est pas supportée
     */
    public static Optional<NativePlatform> detect() {
        return of(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    }

    /**
     * Résout une plateforme depuis un couple système/architecture.
     *
     * <p>Séparée de {@link #detect()} pour être vérifiable : la table des
     * plateformes se teste sans dépendre de la machine qui exécute les tests.
     *
     * @param osName valeur de {@code os.name}
     * @param osArch valeur de {@code os.arch}
     * @return la plateforme, ou vide si la combinaison n'est pas supportée
     */
    public static Optional<NativePlatform> of(String osName, String osArch) {
        String os = normalizedOs(osName);
        String arch = normalizedArch(osArch);
        if (os == null || arch == null) {
            return Optional.empty();
        }
        // Windows sur ARM n'est pas dans la table des plateformes supportées :
        // il ne s'agit pas d'un oubli, aucun binaire n'est produit pour lui.
        if ("windows".equals(os) && !"x86_64".equals(arch)) {
            return Optional.empty();
        }
        return Optional.of(new NativePlatform(os + "-" + arch, libraryFileName(os)));
    }

    /**
     * {@return le chemin de ressource de la bibliothèque dans le JAR}
     */
    public String libraryResource() {
        return RESOURCE_ROOT + id + "/" + libraryFileName;
    }

    /**
     * {@return le chemin de ressource de l'empreinte attendue}
     */
    public String checksumResource() {
        return libraryResource() + ".sha256";
    }

    private static String normalizedOs(String osName) {
        String value = osName.toLowerCase(Locale.ROOT);
        // macOS d'abord, et Windows par son préfixe : « darwin » contient
        // « win », et un test de sous-chaîne dans l'autre ordre ferait passer
        // macOS pour Windows.
        if (value.contains("mac") || value.contains("darwin")) {
            return "macos";
        }
        if (value.startsWith("windows")) {
            return "windows";
        }
        if (value.contains("linux")) {
            return "linux";
        }
        return null;
    }

    private static String normalizedArch(String osArch) {
        String value = osArch.toLowerCase(Locale.ROOT);
        return switch (value) {
            // La JVM rapporte « amd64 » sur Windows et Linux, « x86_64 » sur
            // macOS ; « arm64 » sur macOS, « aarch64 » ailleurs.
            case "amd64", "x86_64", "x64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            default -> null;
        };
    }

    private static String libraryFileName(String os) {
        return switch (os) {
            case "windows" -> LIBRARY_NAME + ".dll";
            case "macos" -> "lib" + LIBRARY_NAME + ".dylib";
            default -> "lib" + LIBRARY_NAME + ".so";
        };
    }
}
