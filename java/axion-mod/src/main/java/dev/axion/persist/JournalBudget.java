package dev.axion.persist;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Le budget du journal latéral sur le thread autoritatif, {@code persistence.journal_ns_per_tick}
 * (R-1744, ADR-153 D6).
 *
 * <p>Tout le temps que le journal prend au thread autoritatif est mesuré et compté contre le budget du
 * tick. Une sauvegarde arrivée quand le budget est épuisé est différée : elle attend, une par assembly — la
 * plus récente remplace la précédente —, et part en fin de tick dans ce qui reste du budget, une au moins
 * pour que la file avance toujours. Un budget nul ou négatif n'a pas de plafond.
 *
 * <p>Logique pure, sans Forge ; sur le thread autoritatif seulement.
 *
 * @param <T> une sauvegarde
 */
public final class JournalBudget<T> {

    private final long budgetNs;
    private final LongSupplier clock;
    private final LinkedHashMap<UUID, T> deferred = new LinkedHashMap<>();

    /** Temps pris depuis la dernière fin de tick, en nanosecondes. */
    private long spentNs;

    /**
     * @param budgetNs budget par tick, en nanosecondes ; nul ou négatif : aucun
     * @param clock horloge monotone, en nanosecondes ({@code System::nanoTime} en jeu)
     */
    public JournalBudget(long budgetNs, LongSupplier clock) {
        this.budgetNs = budgetNs;
        this.clock = clock;
    }

    private boolean exhausted() {
        return budgetNs > 0 && spentNs >= budgetNs;
    }

    /**
     * Une sauvegarde : soumise tout de suite si le budget du tick le permet, sinon différée.
     *
     * @param submit remet la sauvegarde au journal ; son temps compte dans le budget
     */
    public void save(UUID uuid, T save, Consumer<T> submit) {
        long start = clock.getAsLong();
        deferred.remove(uuid);
        if (exhausted()) {
            deferred.put(uuid, save);
        } else {
            submit.accept(save);
        }
        spentNs += clock.getAsLong() - start;
    }

    /**
     * Une suppression explicite : la sauvegarde différée de l'assembly est abandonnée, puis l'action faite.
     *
     * @param action remet la suppression au journal ; son temps compte dans le budget
     */
    public void cancel(UUID uuid, Runnable action) {
        long start = clock.getAsLong();
        deferred.remove(uuid);
        action.run();
        spentNs += clock.getAsLong() - start;
    }

    /**
     * Fin de tick : les sauvegardes différées partent dans ce qui reste du budget, une au moins.
     *
     * @return le temps pris pendant le tick, en nanosecondes ; le compte repart de zéro
     */
    public long endTick(Consumer<T> submit) {
        boolean first = true;
        for (Iterator<T> pending = deferred.values().iterator(); pending.hasNext() && (first || !exhausted()); ) {
            long start = clock.getAsLong();
            T save = pending.next();
            pending.remove();
            submit.accept(save);
            spentNs += clock.getAsLong() - start;
            first = false;
        }
        long spent = spentNs;
        spentNs = 0;
        return spent;
    }

    /** {@return les sauvegardes différées, retirées de l'attente} À l'arrêt : elles partent toutes. */
    public List<T> drainAll() {
        List<T> all = new ArrayList<>(deferred.values());
        deferred.clear();
        return all;
    }

    /** {@return le nombre de sauvegardes différées} */
    public int deferred() {
        return deferred.size();
    }
}
