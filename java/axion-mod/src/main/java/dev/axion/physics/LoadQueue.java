package dev.axion.physics;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * La file des créations d'assemblies, avancée sous budget à chaque tick (R-1730, ADR-153 D4) : une
 * création au chargement n'occupe pas le thread autoritatif au-delà de
 * {@code persistence.max_load_ns_per_tick}, l'excédent attend le tick suivant, et la file avance d'au
 * moins une création par tick — elle ne s'arrête jamais.
 *
 * <p>Le coût d'une création est sa préparation Java, mesurée, plus son coût natif : la durée de la
 * soumission du tick précédent rapportée aux créations qu'elle portait. Les autres commandes du tick y
 * entrent aussi : c'est une majoration, qui ralentit la file sans jamais dépasser le budget.
 *
 * <p>Logique pure, sans Forge ; sur le thread autoritatif seulement.
 *
 * @param <T> ce qui se crée
 */
public final class LoadQueue<T> {

    private final ArrayDeque<T> waiting = new ArrayDeque<>();
    private final Set<T> queued = new HashSet<>();
    private final LongSupplier clock;

    /** Coût natif estimé d'une création, en nanosecondes : nul avant la première mesure. */
    private long nativeEstimateNs;

    /** Dernière préparation mesurée, en nanosecondes : le coût Java prévu de la suivante. */
    private long lastPrepNs;

    /** Créations faites au dernier tick, que porte la soumission que mesure {@link #submitted}. */
    private int createdLastTick;

    /**
     * @param clock horloge monotone, en nanosecondes ({@code System::nanoTime} en jeu)
     */
    public LoadQueue(LongSupplier clock) {
        this.clock = clock;
    }

    /** Met en file ; sans effet pour ce qui y est déjà. */
    public void offer(T item) {
        if (queued.add(item)) {
            waiting.addLast(item);
        }
    }

    /** {@return le nombre de créations en attente} */
    public int size() {
        return waiting.size();
    }

    /**
     * Mesure la soumission du tick qui a porté les créations de {@link #drain} : son coût, rapporté à
     * ses créations, devient le coût natif estimé d'une création. Sans création, l'estimation reste.
     *
     * @param submitNs durée de la soumission, en nanosecondes
     */
    public void submitted(long submitNs) {
        if (createdLastTick > 0 && submitNs >= 0) {
            nativeEstimateNs = submitNs / createdLastTick;
        }
    }

    /**
     * Avance la file pour ce tick : traite dans l'ordre d'arrivée tant que le coût cumulé — préparations
     * mesurées et coûts natifs estimés — laisse l'élément suivant, à son coût prévu (dernière préparation,
     * plus le coût natif), sous le budget ; et au moins un élément, pour que la file ne s'arrête jamais.
     * {@code create} rend faux pour ce qui ne se crée pas (retiré, inerte) : sans coût natif, il ne
     * compte pas parmi les créations du tick.
     *
     * @param budgetNs budget du tick, en nanosecondes
     * @param create crée, et rend vrai si une création part au natif
     * @return le nombre de créations du tick
     */
    public int drain(long budgetNs, Predicate<T> create) {
        long spent = 0;
        int created = 0;
        int processed = 0;
        while (!waiting.isEmpty()) {
            if (processed > 0 && spent + lastPrepNs + nativeEstimateNs > budgetNs) {
                break;
            }
            T item = waiting.pollFirst();
            queued.remove(item);
            long start = clock.getAsLong();
            boolean sent = create.test(item);
            lastPrepNs = clock.getAsLong() - start;
            spent += lastPrepNs;
            processed++;
            if (sent) {
                spent += nativeEstimateNs;
                created++;
            }
        }
        createdLastTick = created;
        return created;
    }
}
