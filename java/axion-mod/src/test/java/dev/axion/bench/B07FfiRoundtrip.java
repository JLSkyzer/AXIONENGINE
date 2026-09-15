package dev.axion.bench;

import dev.axion.bridge.NativeBridge;
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
 * ce benchmark ne touche pas l'ABI, il la mesure. La facette « coût par élément
 * en lot » est mesurée à part, par {@link B07FfiBatch}.
 *
 * <h2>Exécution</h2>
 *
 * <pre>
 * cargo build -p ax-ffi --release
 * ./gradlew :axion-mod:jmh
 * </pre>
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class B07FfiRoundtrip {

    /** Charge la bibliothèque native et vérifie l'ABI avant de mesurer. */
    @Setup
    public void loadNative() {
        NativeBenchSupport.loadOnce();
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
