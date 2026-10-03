package dev.axion.render;

import java.util.Arrays;
import java.util.Comparator;

/**
 * L'ordre de la passe 4 : les surfaces translucides du plus loin au plus près de la caméra (R-150,
 * R-1580, ADR-122 §7).
 *
 * <p>Un mesh se range à la distance de son centre, au carré ; ses quads, eux, sont triés au sein
 * de leur lot par Minecraft. Aucun tri entre les triangles d'un même mesh n'est promis (R-1581).
 */
public final class DepthOrder {

    private DepthOrder() {}

    /**
     * {@return les rangs des surfaces, de la plus lointaine à la plus proche ; à distance égale,
     * dans l'ordre donné, pour qu'une frame ressemble à la précédente}
     *
     * @param squaredDistances distance de chaque surface à la caméra, au carré
     */
    public static int[] farToNear(double[] squaredDistances) {
        Integer[] ranks = new Integer[squaredDistances.length];
        for (int rank = 0; rank < ranks.length; rank++) {
            ranks[rank] = rank;
        }
        // Le tri des objets de Java est stable : les égalités gardent leur ordre.
        Arrays.sort(ranks, Comparator.comparingDouble((Integer rank) -> squaredDistances[rank]).reversed());
        int[] order = new int[ranks.length];
        for (int at = 0; at < order.length; at++) {
            order[at] = ranks[at];
        }
        return order;
    }
}
