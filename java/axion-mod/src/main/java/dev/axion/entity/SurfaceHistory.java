package dev.axion.entity;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Les matériaux sous les roues, pas à pas, au client qui prédit (ADR-181). La vue d'un contrôleur prend ceux du pas
 * d'avant ; après une correction, ceux du pas qu'elle acquitte, et non les plus récents : le bloc d'état du serveur ne
 * les porte pas, et la prédiction, une dizaine de ticks en avance, en verrait un autre sol. L'histoire couvre celle de
 * la boucle de prédiction.
 */
public final class SurfaceHistory {

    /** Les pas gardés, comme les variables prédites : l'historique de la boucle, 64 entrées, et sa marge. */
    static final int KEPT = PredictedVariables.KEPT;

    private final Map<Long, String[]> kept = new LinkedHashMap<>(KEPT, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, String[]> eldest) {
            return size() > KEPT;
        }
    };

    private String[] current;

    /** {@return les matériaux que la prochaine vue lit, par roue ; {@code null} sans} */
    public String[] current() {
        return current;
    }

    /**
     * Un pas prédit, neuf ou rejoué.
     *
     * @param sequence la séquence de son entrée
     * @param seen les matériaux sous les roues, sa collecte comprise
     */
    public void step(long sequence, String[] seen) {
        current = seen;
        kept.remove(sequence);
        kept.put(sequence, seen);
    }

    /**
     * Avant un rejeu : les matériaux du pas acquitté reviennent, s'ils sont encore gardés.
     *
     * @param sequence la séquence acquittée, -1 sans entrée appliquée
     */
    public void restore(long sequence) {
        String[] seen = kept.get(sequence);
        if (seen != null) {
            current = seen;
        }
    }

    /** Oublie tout : le véhicule piloté change, ou la prédiction s'arrête. */
    public void clear() {
        kept.clear();
        current = null;
    }
}
