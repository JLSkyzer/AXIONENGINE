package dev.axion.bench;

import dev.axion.bridge.NativeBridge;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * B-07 — coût de l'aller-retour FFI à vide (PARTIE 30.2, R-2240).
 *
 * <p>Mesure le coût fixe d'un appel natif : {@link NativeBridge#nativeAbiVersion()}
 * ne fait que traverser la frontière JNI et revenir, sans contexte ni travail.
 * C'est l'« aller-retour vide » de B-07. Le point d'entrée existe déjà (C-14) :
 * ce benchmark ne touche pas l'ABI, il la mesure.
 *
 * <p><strong>La facette « coût par élément en lot » de B-07 n'est pas ici.</strong>
 * Elle exige un contexte natif vivant ({@code axion_init}) et une opération de
 * lot sur un tampon de transfert : un benchmark à part, à écrire quand cette
 * mesure aura son cas. On ne mesure pas à vide ce qui se mesure en charge.
 *
 * <h2>Exécution</h2>
 *
 * <pre>
 * cargo build -p ax-ffi --release
 * ./gradlew :axion-mod:jmh
 * </pre>
 *
 * <p>La bibliothèque native est chargée depuis {@code axion.native.dir}, que la
 * tâche {@code jmh} renseigne vers {@code target/release} et propage au JVM
 * forké. Sans elle, le {@link #loadNative() setup} échoue en disant comment la
 * produire, plutôt que de mesurer un appel qui ne peut pas aboutir.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class B07FfiRoundtrip {

    /** Nom logique de la bibliothèque native, sans préfixe ni extension. */
    private static final String LIBRARY = "axion_native";

    /**
     * Charge la bibliothèque native une fois par exécution, puis vérifie l'ABI.
     *
     * <p>Le nom de fichier est celui de la plateforme
     * ({@code axion_native.dll}, {@code libaxion_native.so},
     * {@code libaxion_native.dylib}), résolu par {@link System#mapLibraryName}.
     */
    @Setup
    public void loadNative() {
        // La variable d'environnement passe le chemin au JVM forké sans le couper
        // sur ses espaces ; la propriété système reste un repli pour un lancement
        // manuel. Voir la tâche `jmh` du build.
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
    }

    /**
     * {@return la version d'ABI, renvoyée pour que JMH ne supprime pas l'appel}
     *
     * <p>Un appel JNI qui franchit la frontière et revient : le coût mesuré est
     * celui de la traversée, hors tout travail natif.
     */
    @Benchmark
    public int emptyRoundtrip() {
        return NativeBridge.nativeAbiVersion();
    }
}
