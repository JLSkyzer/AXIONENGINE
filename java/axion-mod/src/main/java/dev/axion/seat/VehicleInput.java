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
 * @param flags les commandes génériques tenues (ADR-163 D2), un bit par commande : le bit 0 pour la commande 1 ;
 *     {@link #IGNITION}, au-dessus des huit, est le contact du moteur (ADR-176 D1) ; les quatre bits à partir de
 *     {@link #MOTORS_SHIFT}, les moteurs électriques autorisés (ADR-182 D3) ; le natif lit ces cinq bits
 */
public record VehicleInput(float throttle, float brake, float steer, float clutch, float handbrake, byte gear, int flags) {

    /**
     * Le contact du moteur (ADR-176 D1), dans {@code flags} au-dessus des huit commandes : le serveur et le client
     * qui prédit l'y posent d'après la variable que le démarreur lit, jamais le joueur ni le réseau. Le natif le reçoit
     * dans son propre octet, à part des commandes qu'il ignore.
     */
    public static final int IGNITION = 1 << 8;

    /** Le premier bit des moteurs électriques autorisés, au-dessus du contact : un par moteur, dans l'ordre déclaré. */
    public static final int MOTORS_SHIFT = 9;

    /** Les bits des quatre moteurs électriques (ADR-182 D3). */
    private static final int MOTORS_MASK = 0xF << MOTORS_SHIFT;

    /** Aucune entrée : ce qu'un siège libre ou muet commande. */
    public static final VehicleInput NONE = new VehicleInput(0f, 0f, 0f, 0f, 0f, (byte) 0, 0);

    /**
     * {@return ce que le clavier du joueur commande : l'avant accélère, l'arrière freine, le saut serre le
     * frein à main} Au point mort, l'embrayage restant automatique (ADR-159 D6).
     *
     * @param forward impulsion vers l'avant, dans [-1, 1]
     * @param left impulsion vers la gauche, dans [-1, 1]
     * @param jumping vrai si le joueur saute
     */
    public static VehicleInput ofKeys(float forward, float left, boolean jumping) {
        return ofKeys(forward, left, jumping, (byte) 0);
    }

    /**
     * {@return ce que le clavier du joueur commande, avec le rapport que ses deux touches de boîte ont choisi}
     * L'embrayage reste automatique au clavier (ADR-159 D6).
     *
     * @param forward impulsion vers l'avant, dans [-1, 1]
     * @param left impulsion vers la gauche, dans [-1, 1]
     * @param jumping vrai si le joueur saute
     * @param gear rang du rapport demandé, voir {@link GearSelector}
     */
    public static VehicleInput ofKeys(float forward, float left, boolean jumping, byte gear) {
        return ofKeys(forward, left, jumping, gear, 0);
    }

    /**
     * {@return ce que le clavier du joueur commande, rapport et commandes génériques tenues compris (ADR-163 D2)}
     *
     * @param forward impulsion vers l'avant, dans [-1, 1]
     * @param left impulsion vers la gauche, dans [-1, 1]
     * @param jumping vrai si le joueur saute
     * @param gear rang du rapport demandé, voir {@link GearSelector}
     * @param commands les commandes tenues, un bit par commande : le bit 0 pour la commande 1
     */
    public static VehicleInput ofKeys(float forward, float left, boolean jumping, byte gear, int commands) {
        // 0 - left et non -left : sans touche latérale, un zéro positif, que l'égalité des records ne distingue pas
        // de celui d'une entrée nulle.
        return new VehicleInput(Math.max(forward, 0f), Math.max(-forward, 0f), 0f - left, 0f, jumping ? 1f : 0f, gear,
                commands);
    }

    /** {@return cette entrée, le contact mis ou coupé selon {@code on}} */
    public VehicleInput withIgnition(boolean on) {
        int next = on ? flags | IGNITION : flags & ~IGNITION;
        return next == flags ? this : new VehicleInput(throttle, brake, steer, clutch, handbrake, gear, next);
    }

    /**
     * {@return cette entrée, les moteurs électriques de {@code mask} autorisés, les autres coupés} Comme le contact, le
     * serveur et le client qui prédit les posent d'après les variables de la definition.
     *
     * @param mask un bit par moteur, le bit 0 pour le premier
     */
    public VehicleInput withMotors(int mask) {
        int next = flags & ~MOTORS_MASK | (mask << MOTORS_SHIFT) & MOTORS_MASK;
        return next == flags ? this : new VehicleInput(throttle, brake, steer, clutch, handbrake, gear, next);
    }

    /** {@return les moteurs électriques autorisés, un bit par moteur} */
    public int motors() {
        return (flags & MOTORS_MASK) >>> MOTORS_SHIFT;
    }

    /** {@return vrai si le contact est mis} */
    public boolean ignition() {
        return (flags & IGNITION) != 0;
    }

    /** {@return vrai si toutes les valeurs sont finies : une autre se journalise (R-1110)} */
    public boolean finite() {
        return Float.isFinite(throttle) && Float.isFinite(brake) && Float.isFinite(steer)
                && Float.isFinite(clutch) && Float.isFinite(handbrake);
    }

    /**
     * {@return l'entrée que le serveur retient (R-1110, INV-02) : une valeur non finie vaut zéro, chacune est
     * bornée, un contrôle que le siège ne déclare pas vaut zéro, une commande qu'il ne lie pas n'est pas tenue}
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
                flags & seat.commandMask());
    }

    private static float clamp(float value, float min) {
        return Float.isFinite(value) ? Math.max(min, Math.min(1f, value)) : 0f;
    }
}
