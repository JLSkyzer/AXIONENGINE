package dev.axion.diag;

import dev.axion.physics.SimulationTrace.Tile;
import java.util.List;

/**
 * Les métriques des tuiles de collision du monde (C-38), publiées dans l'export de
 * {@code /axion metrics}.
 *
 * <p>La fiche C-38 n'en nomme aucune ; R-500 veut, pour chaque composant, ses opérations, ses
 * erreurs, sa mémoire et sa durée :
 *
 * <ul>
 *   <li>opérations — tuiles construites, et libérées, faute d'assembly proche ;
 *   <li>replis — tuiles posées en champ de hauteurs au-delà de 4096 boîtes (R-641), sections
 *       non chargées tenues pour pleines et solides (R-640) ;
 *   <li>mémoire — tuiles tenues, celles que le natif garde ;
 *   <li>durée — la construction des tuiles d'un tick.
 * </ul>
 *
 * <p>La borne de C-38 est {@code world.tiles_per_tick} : au-delà, une section attend le tick
 * suivant. Sa consommation est le nombre de tuiles construites dans le tick, son dépassement le
 * nombre de sections restées en file, et chaque tick qui en laisse est compté. Ce n'est pas un
 * budget de la table 25.2 : son imputation et son comportement en surcharge par le gouverneur
 * viennent avec C-77 (R-1850, M5).
 */
public final class WorldTileMetrics {

    /** Préfixe des métriques des tuiles du monde. */
    public static final String PREFIX = "axion.world.";

    /** Les métriques du jeu, dans le registre global. */
    public static final WorldTileMetrics GLOBAL = new WorldTileMetrics(JavaMetrics.GLOBAL);

    private final JavaMetrics.Metric built;
    private final JavaMetrics.Metric released;
    private final JavaMetrics.Metric heightfields;
    private final JavaMetrics.Metric unloaded;
    private final JavaMetrics.Metric builtLastTick;
    private final JavaMetrics.Metric pending;
    private final JavaMetrics.Metric backlogTicks;
    private final JavaMetrics.Metric loaded;
    private final JavaMetrics.Metric buildNanos;

    /**
     * Déclare les métriques des tuiles du monde.
     *
     * @param metrics registre où les publier
     */
    public WorldTileMetrics(JavaMetrics metrics) {
        built = metrics.counter(PREFIX + "tiles_built", "count");
        released = metrics.counter(PREFIX + "tiles_released", "count");
        heightfields = metrics.counter(PREFIX + "tiles_heightfield", "count");
        unloaded = metrics.counter(PREFIX + "sections_unloaded", "count");
        builtLastTick = metrics.gauge(PREFIX + "tiles_built_last_tick", "count");
        pending = metrics.gauge(PREFIX + "tiles_pending", "count");
        backlogTicks = metrics.counter(PREFIX + "ticks_with_backlog", "count");
        loaded = metrics.gauge(PREFIX + "tiles_loaded", "count");
        buildNanos = metrics.gauge(PREFIX + "tile_build_ns", "ns");
    }

    /**
     * Ce qu'un tick a fait des tuiles.
     *
     * @param tiles tuiles posées et retirées pendant le tick
     * @param pendingAfter sections encore en file après lui
     * @param loadedAfter tuiles tenues après lui
     * @param nanos durée de la construction, en nanosecondes
     */
    public void tick(List<Tile> tiles, int pendingAfter, int loadedAfter, long nanos) {
        int builtNow = 0;
        for (Tile tile : tiles) {
            if (tile.released()) {
                released.increment();
            } else {
                builtNow++;
                if (tile.boxes() == Tile.HEIGHTFIELD) {
                    heightfields.increment();
                }
            }
        }
        built.add(builtNow);
        builtLastTick.set(builtNow);
        pending.set(pendingAfter);
        if (pendingAfter > 0) {
            backlogTicks.increment();
        }
        loaded.set(loadedAfter);
        buildNanos.set(Math.max(0, nanos));
    }

    /** Une section non chargée est tenue pour pleine et solide (R-640). */
    public void unloadedSection() {
        unloaded.increment();
    }
}
