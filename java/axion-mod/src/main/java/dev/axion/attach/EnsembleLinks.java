package dev.axion.attach;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * L'ensemble d'une assembly tel que le client le lit des attaches qu'il a reçues (ADR-171 D7, CAP-37) : chaque assembly
 * ne réplique que les attaches qu'elle a posées, vers l'identifiant réseau de l'autre ; l'ensemble les suit dans les
 * deux sens, de proche en proche. Un ancrage au monde n'y relie rien. Logique pure.
 */
public final class EnsembleLinks {

    private EnsembleLinks() {}

    /**
     * {@return les identifiants réseau de l'ensemble de {@code start}, lui compris, en ordre croissant}
     *
     * @param start l'identifiant réseau de l'assembly
     * @param posed pour chaque assembly, les identifiants réseau des autres extrémités des attaches qu'elle a posées
     */
    public static Set<Integer> members(int start, Map<Integer, List<Integer>> posed) {
        Set<Integer> found = new TreeSet<>();
        Deque<Integer> next = new ArrayDeque<>();
        found.add(start);
        next.add(start);
        while (!next.isEmpty()) {
            int current = next.poll();
            for (Map.Entry<Integer, List<Integer>> origin : posed.entrySet()) {
                int from = origin.getKey();
                for (int to : origin.getValue()) {
                    int other = from == current ? to : to == current ? from : current;
                    if (other != current && found.add(other)) {
                        next.add(other);
                    }
                }
            }
        }
        return found;
    }
}
