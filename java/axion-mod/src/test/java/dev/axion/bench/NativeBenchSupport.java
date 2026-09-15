package dev.axion.bench;

import dev.axion.bridge.CborWriter;
import dev.axion.bridge.NativeBridge;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Chargement de la bibliothèque native et configuration, communs aux benchmarks
 * JMH (B-07).
 *
 * <p>La bibliothèque est chargée une fois par JVM ({@link System#load} verrouille
 * un chemin unique) ; le dossier vient de {@code AXION_NATIVE_DIR}, que la tâche
 * Gradle {@code jmh} renseigne et propage au JVM forké. Une variable
 * d'environnement porte un chemin avec espaces sans être coupée, contrairement à
 * un {@code -jvmArgs} que JMH redécoupe sur les espaces.
 */
final class NativeBenchSupport {

    /** Nom logique de la bibliothèque native, sans préfixe ni extension. */
    private static final String LIBRARY = "axion_native";

    private static boolean loaded;

    private NativeBenchSupport() {
        throw new AssertionError("classe utilitaire, non instanciable");
    }

    /**
     * Charge la bibliothèque native une fois par JVM, puis vérifie l'ABI.
     *
     * <p>Idempotent : un second appel ne recharge pas. Le nom de fichier est
     * celui de la plateforme, résolu par {@link System#mapLibraryName}.
     */
    static synchronized void loadOnce() {
        if (loaded) {
            return;
        }
        String dir = System.getenv("AXION_NATIVE_DIR");
        if (dir == null || dir.isBlank()) {
            dir = System.getProperty("axion.native.dir");
        }
        if (dir == null || dir.isBlank()) {
            throw new IllegalStateException(
                    "AXION_NATIVE_DIR absent : lancer via ./gradlew :axion-mod:jmh, "
                            + "qui le renseigne vers target/release et le propage au fork");
        }
        Path library = Paths.get(dir, System.mapLibraryName(LIBRARY));
        if (!Files.exists(library)) {
            throw new IllegalStateException(
                    "bibliothèque native absente : " + library
                            + " — la construire : cargo build -p ax-ffi --release");
        }
        System.load(library.toAbsolutePath().toString());

        int abi = NativeBridge.nativeAbiVersion();
        if (abi != NativeBridge.EXPECTED_ABI_VERSION) {
            throw new IllegalStateException(
                    "ABI " + abi + " inattendue, " + NativeBridge.EXPECTED_ABI_VERSION + " attendue");
        }
        loaded = true;
    }

    /**
     * {@return une configuration native minimale, encodée en CBOR}
     *
     * <p>Les mêmes clés que le test de la frontière : de quoi initialiser un
     * contexte serveur sans dépendre d'un fichier.
     */
    static byte[] defaultConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("sim.max_substeps", 6L);
        config.put("physics.gravity", -12.5);
        config.put("general.enabled", true);
        config.put("deformation.quality", "high");
        return CborWriter.encodeMap(config);
    }
}
