package dev.axion.render;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Libération différée des ressources GPU (fiche 5.49, R-751, ADR-127 §3) : une ressource rendue
 * n'est détruite qu'après {@value #FRAMES_WITHOUT_USE} frames sans usage. Réutilisée entre-temps,
 * elle est gardée.
 *
 * <p>Logique pure ; le collage GL détruit ce que {@link #due} lui rend, sur le render thread.
 *
 * @param <K> ce qui désigne une ressource
 */
public final class DeferredRelease<K> {

    /** Frames sans usage avant qu'une ressource rendue soit détruite. */
    public static final int FRAMES_WITHOUT_USE = 3;

    /** Frame de la première demande, par ressource, dans l'ordre des demandes. */
    private final Map<K, Long> requested = new LinkedHashMap<>();

    /**
     * Rend une ressource ; rendue deux fois, elle compte depuis la première.
     *
     * @param key la ressource
     * @param frame frame courante
     */
    public void release(K key, long frame) {
        requested.putIfAbsent(key, frame);
    }

    /**
     * Une ressource resert : sa libération est annulée.
     *
     * @param key la ressource
     */
    public void use(K key) {
        requested.remove(key);
    }

    /** {@return vrai si la ressource attend sa destruction} */
    public boolean pending(K key) {
        return requested.containsKey(key);
    }

    /**
     * {@return les ressources à détruire maintenant, dans l'ordre où elles ont été rendues ; elles
     * sortent de la file}
     *
     * @param frame frame courante
     */
    public List<K> due(long frame) {
        List<K> due = new ArrayList<>();
        Iterator<Map.Entry<K, Long>> entries = requested.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<K, Long> entry = entries.next();
            if (frame - entry.getValue() >= FRAMES_WITHOUT_USE) {
                due.add(entry.getKey());
                entries.remove();
            }
        }
        return due;
    }

    /** {@return tout ce qui attendait, à détruire sans délai — arrêt, rechargement de ressources} */
    public List<K> drainAll() {
        List<K> all = new ArrayList<>(requested.keySet());
        requested.clear();
        return all;
    }
}
