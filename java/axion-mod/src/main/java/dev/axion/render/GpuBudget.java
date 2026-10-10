package dev.axion.render;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Budget de mémoire GPU (R-750, fiche 5.49, ADR-127 §3) : chaque asset compte ce qu'il occupe, et,
 * au-delà de {@code budgets.gpu_mem_bytes}, le premier à décharger est le moins récemment vu parmi
 * ceux qui ne le sont pas à la frame courante. Un asset visible n'est jamais déchargé.
 *
 * <p>Les leviers suivants de R-750 — LOD forcé, qualité de déformation — arrivent avec C-64 et la
 * M6 ; c'est l'appelant qui dit, au journal, qu'ils manquent.
 *
 * <p>Logique pure, render thread seul.
 *
 * @param <K> ce qui désigne un asset
 */
public final class GpuBudget<K> {

    /** Jamais vu : avant tout asset déjà dessiné. */
    private static final long NEVER = Long.MIN_VALUE;

    private final long budgetBytes;

    /** Par asset, dans l'ordre de leur premier chargement : octets, dernière frame où il a été vu. */
    private final Map<K, long[]> entries = new LinkedHashMap<>();

    /**
     * @param budgetBytes mémoire GPU permise, {@code budgets.gpu_mem_bytes}
     * @throws IllegalArgumentException si elle n'est pas positive
     */
    public GpuBudget(long budgetBytes) {
        if (budgetBytes <= 0) {
            throw new IllegalArgumentException("budget GPU de " + budgetBytes + " octets");
        }
        this.budgetBytes = budgetBytes;
    }

    /** {@return la mémoire GPU permise} */
    public long budgetBytes() {
        return budgetBytes;
    }

    /**
     * Compte une allocation d'un asset.
     *
     * @param asset l'asset
     * @param bytes octets alloués pour lui
     */
    public void charge(K asset, long bytes) {
        entries.computeIfAbsent(asset, key -> new long[] {0, NEVER})[0] += bytes;
    }

    /** Retire un asset et tout ce qu'il comptait. */
    public void discharge(K asset) {
        entries.remove(asset);
    }

    /**
     * Un asset est dessiné à cette frame. Ignoré pour un asset qui ne compte rien.
     *
     * @param asset l'asset
     * @param frame frame courante
     */
    public void seen(K asset, long frame) {
        long[] entry = entries.get(asset);
        if (entry != null) {
            entry[1] = frame;
        }
    }

    /** {@return les octets comptés, tous assets confondus} */
    public long chargedBytes() {
        long bytes = 0;
        for (long[] entry : entries.values()) {
            bytes += entry[0];
        }
        return bytes;
    }

    /** {@return les octets comptés pour un asset, zéro s'il n'en compte pas} */
    public long bytesOf(K asset) {
        long[] entry = entries.get(asset);
        return entry == null ? 0 : entry[0];
    }

    /**
     * {@return vrai si une mémoire GPU occupée dépasse le budget}
     *
     * @param gpuBytes mémoire occupée — arènes et tampons compris, pas seulement les places
     */
    public boolean exceededBy(long gpuBytes) {
        return gpuBytes > budgetBytes;
    }

    /**
     * {@return l'asset à décharger d'abord : le moins récemment vu parmi ceux qui ne le sont pas à
     * cette frame, l'ordre de chargement départageant ; {@code null} si tous le sont}
     *
     * @param frame frame courante
     */
    public K evictionCandidate(long frame) {
        K candidate = null;
        long oldest = Long.MAX_VALUE;
        for (Map.Entry<K, long[]> entry : entries.entrySet()) {
            long seen = entry.getValue()[1];
            if (seen != frame && (candidate == null || seen < oldest)) {
                candidate = entry.getKey();
                oldest = seen;
            }
        }
        return candidate;
    }
}
