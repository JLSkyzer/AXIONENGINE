package dev.axion.physics;

import dev.axion.bridge.NativeBridge;
import java.util.List;

/**
 * Résultat d'un {@code collect} d'IF-03 : les compteurs du tick et les données
 * décodées de {@code SIM_OUT}/{@code EVENTS}.
 *
 * <p>{@code code} est le code de retour natif : {@link NativeBridge#OK} en
 * succès, un code négatif sinon (les listes sont alors vides). {@code flags}
 * porte {@link NativeBridge#SIM_INCOMPLETE} / {@link NativeBridge#SIM_DEGRADED} —
 * un collect incomplet (R-281) reste un succès, pas un abandon.
 *
 * @param code code de retour natif du collect
 * @param stateCount nombre de {@link BodyState} déposés
 * @param eventCount nombre de {@link PhysicsEvent} déposés
 * @param deformPageCount pages de déformation modifiées (M6)
 * @param refitCount colliders refités ce tick (M6)
 * @param detachCount détachements décidés (M7)
 * @param netBytes octets réseau prêts à émettre (M4)
 * @param flags drapeaux du collect
 * @param bodies états décodés, dans l'ordre déterministe du natif (R-1020)
 * @param events événements décodés, dans l'ordre d'émission
 */
public record CollectResult(
        int code,
        int stateCount,
        int eventCount,
        int deformPageCount,
        int refitCount,
        int detachCount,
        int netBytes,
        int flags,
        List<BodyState> bodies,
        List<PhysicsEvent> events) {

    /** Résultat d'un collect en échec (code négatif), sans donnée. */
    static CollectResult failed(int code) {
        return new CollectResult(code, 0, 0, 0, 0, 0, 0, 0, List.of(), List.of());
    }

    /** {@return vrai si le collect a abouti} */
    public boolean ok() {
        return code == NativeBridge.OK;
    }

    /** {@return vrai si tout n'a pas été produit dans le délai (R-281)} */
    public boolean incomplete() {
        return (flags & NativeBridge.SIM_INCOMPLETE) != 0;
    }

    /** {@return vrai si la simulation tourne en qualité dégradée} */
    public boolean degraded() {
        return (flags & NativeBridge.SIM_DEGRADED) != 0;
    }
}
