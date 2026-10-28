package dev.axion.render;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;

/**
 * Le regroupement de C-65 (ADR-154 D6) : une suite de dessins découpée en séries consécutives de même
 * clé. Une série d'au moins {@code render.instancing_threshold} dessins se dessine d'un seul appel
 * instancié ; une plus courte, dessin par dessin — le chemin non instancié reste exercé (R-781).
 * Logique pure.
 */
public final class InstanceRuns {

    /**
     * Une série de dessins consécutifs de même clé.
     *
     * @param start rang de son premier dessin
     * @param count nombre de dessins
     * @param instanced vrai si elle se dessine d'un seul appel instancié
     */
    public record Run(int start, int count, boolean instanced) {}

    private InstanceRuns() {}

    /**
     * {@return les séries d'une suite de dessins, dans l'ordre}
     *
     * @param size nombre de dessins
     * @param sameKey vrai si deux dessins, par leur rang, ont la même clé de regroupement
     * @param threshold {@code render.instancing_threshold} : taille minimale d'une série instanciée ;
     *     0 ou 1, toute série l'est
     */
    public static List<Run> of(int size, BiPredicate<Integer, Integer> sameKey, int threshold) {
        List<Run> runs = new ArrayList<>();
        int start = 0;
        while (start < size) {
            int end = start + 1;
            while (end < size && sameKey.test(start, end)) {
                end++;
            }
            int count = end - start;
            runs.add(new Run(start, count, count >= Math.max(1, threshold)));
            start = end;
        }
        return runs;
    }
}
