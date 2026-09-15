package dev.axion.bench;

import dev.axion.bridge.BufferKinds;
import dev.axion.bridge.NativeBridge;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * B-07 — coût par élément d'un transfert en lot à travers la FFI (PARTIE 30.2,
 * R-2240).
 *
 * <p>Complément de {@link B07FfiRoundtrip} : là où l'aller-retour vide mesure le
 * coût fixe d'un appel, celui-ci mesure le coût de remettre un **lot** de {@code
 * elements} éléments au natif par le tampon de transfert partagé (C-14). Un lot
 * coûte deux appels FFI amortis — acquisition et relâche — plus l'écriture des
 * éléments dans le tampon direct ; faire varier {@code elements} montre le coût
 * marginal par élément.
 *
 * <p>Le protocole est celui du jeu (R-270) : acquérir à chaque itération plutôt
 * que conserver une vue. Le tampon acquis pour une même taille est réutilisé
 * sans réallocation.
 *
 * <p>Exécution : {@code cargo build -p ax-ffi --release} puis
 * {@code ./gradlew :axion-mod:jmh}. Un contexte natif serveur est initialisé une
 * fois par exécution, et fermé à la fin.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class B07FfiBatch {

    /** Taille d'un élément dans le tampon, en octets (un {@code long}). */
    private static final int ELEMENT_BYTES = 8;

    /** Nombre d'éléments transférés par lot. */
    @Param({"64", "256", "1024", "4096"})
    public int elements;

    private long ctx;

    /**
     * Charge la bibliothèque, initialise un contexte serveur, et vérifie le
     * protocole de transfert une fois — hors mesure, pour ne pas chronométrer un
     * chemin en erreur si l'hypothèse est fausse.
     */
    @Setup(Level.Trial)
    public void init() {
        NativeBenchSupport.loadOnce();
        ctx = NativeBridge.initialize(NativeBenchSupport.defaultConfig(), NativeBridge.SIDE_SERVER);
        if (ctx <= 0) {
            throw new IllegalStateException("initialisation refusée, code " + ctx);
        }
        int code = transferBatch();
        if (code != NativeBridge.OK) {
            throw new IllegalStateException("transfert de lot refusé au setup, code " + code);
        }
    }

    /** Ferme le contexte natif. */
    @TearDown(Level.Trial)
    public void shutdown() {
        if (ctx > 0) {
            NativeBridge.close(ctx);
            ctx = 0;
        }
    }

    /**
     * {@return le code de relâche, renvoyé pour que JMH ne supprime pas l'appel}
     *
     * <p>Acquiert le tampon d'entrée, y écrit {@code elements} valeurs, le
     * relâche : un cycle de transfert de lot complet.
     */
    @Benchmark
    public int batchTransfer() {
        return transferBatch();
    }

    /** Acquiert, remplit et relâche le tampon d'entrée pour {@code elements}. */
    private int transferBatch() {
        long payloadLen = (long) elements * ELEMENT_BYTES;
        ByteBuffer buffer = NativeBridge.acquire(ctx, BufferKinds.SIM_IN, payloadLen);
        if (buffer == null) {
            throw new IllegalStateException("acquisition du tampon refusée");
        }
        int generation = buffer.getInt(8);
        buffer.putLong(16, payloadLen);
        buffer.putInt(24, elements);
        for (int index = 0; index < elements; index++) {
            buffer.putLong(BufferKinds.HEADER_BYTES + index * ELEMENT_BYTES, index);
        }
        return NativeBridge.release(ctx, BufferKinds.SIM_IN, generation);
    }
}
