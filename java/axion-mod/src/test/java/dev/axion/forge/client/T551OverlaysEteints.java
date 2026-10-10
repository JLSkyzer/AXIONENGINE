package dev.axion.forge.client;

import dev.axion.debug.DebugOverlays;
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
 * T-551 — R-800, R-2280 : le coût d'un overlay éteint, mesuré par le harnais (PARTIE 30, R-2240).
 *
 * <p>Éteints, les overlays coûtent, à chaque frame, le test de leur masque — la passe DEBUG ne
 * s'ouvre pas ({@code AxionRenderPass.onRenderLevelStage}) —, et à chaque tick client, un pas qui
 * revient aussitôt, sans appel natif ({@link DebugOverlayRenderer#tick}). Les deux chemins sont
 * mesurés ici, tels quels. Que rien ne parte au natif, ne remplisse un tampon ni n'émette un dessin,
 * {@code DebugOverlayRendererTest} le vérifie.
 *
 * <p>Un résultat ne se publie qu'archivé par le harnais, sur matériel de référence (R-2230, R-2252).
 *
 * <h2>Exécution</h2>
 *
 * <pre>
 * ./gradlew :axion-mod:jmh -Pjmh.args="T551"
 * </pre>
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class T551OverlaysEteints {

    private DebugOverlays overlays;
    private DebugOverlayRenderer renderer;

    /** Tous les overlays éteints, comme au lancement (R-800 : désactivés par défaut). */
    @Setup
    public void eteindre() {
        overlays = new DebugOverlays();
        renderer = new DebugOverlayRenderer(overlays);
    }

    /** {@return le test de chaque frame : la passe DEBUG s'ouvre-t-elle ?} */
    @Benchmark
    public boolean testParFrame() {
        return overlays.mask() != 0L;
    }

    /** Le pas de chaque tick client, overlays éteints : il revient sans rien demander au natif. */
    @Benchmark
    public void pasParTick() {
        renderer.tick(null, null);
    }
}
