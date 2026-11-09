package dev.axion.seat;

import dev.axion.definition.Seating;

/**
 * Les entrées d'un conducteur (12.8, ADR-158 D4). Ce qui vient du réseau n'est jamais utilisé tel quel :
 * {@link #sanitized} le borne (R-1110). Logique pure, sans Forge.
 *
 * @param throttle accélérateur, dans [0, 1]
 * @param brake frein, dans [0, 1]
 * @param steer direction, dans [-1, 1], positive à droite
 * @param clutch embrayage, dans [0, 1]
 * @param handbrake frein à main, dans [0, 1]
 * @param gear rapport engagé
 * @param flags drapeaux ; aucun n'est défini à ce jour
 */
public record VehicleInput(float throttle, float brake, float steer, float clutch, float handbrake, byte gear, int flags) {

    /** Aucune entrée : ce qu'un siège libre ou muet commande. */
    public static final VehicleInput NONE = new VehicleInput(0f, 0f, 0f, 0f, 0f, (byte) 0, 0);

    /**
     * {@return ce que le clavier du joueur commande : l'avant accélère, l'arrière freine, le saut serre le
     * frein à main} Boîte et embrayage attendent leurs touches, avec les véhicules (C-33).
     *
     * @param forward impulsion vers l'avant, dans [-1, 1]
     * @param left impulsion vers la gauche, dans [-1, 1]
     * @param jumping vrai si le joueur saute
     */
    public static VehicleInput ofKeys(float forward, float left, boolean jumping) {
        return new VehicleInput(Math.max(forward, 0f), Math.max(-forward, 0f), -left, 0f, jumping ? 1f : 0f, (byte) 0, 0);
    }

    /** {@return vrai si toutes les valeurs sont finies : une autre se journalise (R-1110)} */
    public boolean finite() {
        return Float.isFinite(throttle) && Float.isFinite(brake) && Float.isFinite(steer)
                && Float.isFinite(clutch) && Float.isFinite(handbrake);
    }

    /**
     * {@return l'entrée que le serveur retient (R-1110, INV-02) : une valeur non finie vaut zéro, chacune est
     * bornée, un contrôle que le siège ne déclare pas vaut zéro}
     *
     * @param seat le siège d'où elle vient
     */
    public VehicleInput sanitized(Seating.Seat seat) {
        return new VehicleInput(
                seat.allows(Seating.Control.THROTTLE) ? clamp(throttle, 0f) : 0f,
                seat.allows(Seating.Control.BRAKE) ? clamp(brake, 0f) : 0f,
                seat.allows(Seating.Control.STEER) ? clamp(steer, -1f) : 0f,
                seat.allows(Seating.Control.CLUTCH) ? clamp(clutch, 0f) : 0f,
                seat.allows(Seating.Control.HANDBRAKE) ? clamp(handbrake, 0f) : 0f,
                seat.allows(Seating.Control.GEAR) ? gear : 0,
                0);
    }

    private static float clamp(float value, float min) {
        return Float.isFinite(value) ? Math.max(min, Math.min(1f, value)) : 0f;
    }
}
