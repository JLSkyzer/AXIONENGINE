package dev.axion.render;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Les corps d'un asset sous une definition, pour le dessin (ADR-151 D9) : la section {@code NODE} de
 * l'asset, le node racine du corps racine que la definition déclare, et le squelette de chaque jeu de
 * corps secondaires déjà vu. Une instance par asset et par definition, sur le fil de rendu.
 */
public final class BodyRig {

    /** Jeux de corps secondaires gardés au plus : au-delà, le cache repart de zéro. */
    static final int MAX_SKELETONS = 16;

    private final byte[] nodes;
    private final int rootNode;
    private final Map<List<Integer>, BodySkeleton> skeletons = new HashMap<>();

    /**
     * @param nodes section {@code NODE} de l'asset
     * @param rootNode node racine du corps racine
     */
    public BodyRig(byte[] nodes, int rootNode) {
        this.nodes = nodes;
        this.rootNode = rootNode;
    }

    /**
     * @param secondaryRoots nodes racines des corps secondaires, dans l'ordre du snapshot
     * @return leur squelette, calculé une fois par jeu de racines ; {@code null} sans corps secondaire, ou si
     *     ces racines ne se lisent pas contre l'asset — le dessin reste alors celui d'un seul corps
     */
    public BodySkeleton skeleton(int[] secondaryRoots) {
        if (secondaryRoots.length == 0) {
            return null;
        }
        List<Integer> key = new ArrayList<>(secondaryRoots.length);
        for (int root : secondaryRoots) {
            key.add(root);
        }
        if (!skeletons.containsKey(key)) {
            if (skeletons.size() >= MAX_SKELETONS) {
                skeletons.clear();
            }
            skeletons.put(List.copyOf(key), BodySkeleton.of(nodes, rootNode, secondaryRoots));
        }
        return skeletons.get(key);
    }
}
