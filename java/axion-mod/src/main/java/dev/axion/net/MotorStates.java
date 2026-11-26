package dev.axion.net;

/**
 * Le régime et la puissance des quatre moteurs électriques d'un véhicule (ADR-182 D5), dans l'ordre de sa definition.
 *
 * @param rpm0 régime du premier, en tr/min, positif dans le sens engagé
 * @param rpm1 du deuxième
 * @param rpm2 du troisième
 * @param rpm3 du quatrième
 * @param power0 puissance électrique du premier, en kW : positive tirée de sa réserve, négative rendue
 * @param power1 du deuxième
 * @param power2 du troisième
 * @param power3 du quatrième
 */
public record MotorStates(float rpm0, float rpm1, float rpm2, float rpm3, float power0, float power1, float power2,
        float power3) {

    /** Au plus ce nombre de moteurs. */
    public static final int COUNT = 4;

    /** Aucun moteur : tout à zéro. */
    public static final MotorStates NONE = new MotorStates(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f);

    /**
     * {@return le régime du moteur au rang {@code motor}, en tr/min ; 0 hors des rangs}
     *
     * @param motor le rang du moteur
     */
    public float rpm(int motor) {
        return switch (motor) {
            case 0 -> rpm0;
            case 1 -> rpm1;
            case 2 -> rpm2;
            case 3 -> rpm3;
            default -> 0f;
        };
    }

    /**
     * {@return la puissance du moteur au rang {@code motor}, en kW ; 0 hors des rangs}
     *
     * @param motor le rang du moteur
     */
    public float power(int motor) {
        return switch (motor) {
            case 0 -> power0;
            case 1 -> power1;
            case 2 -> power2;
            case 3 -> power3;
            default -> 0f;
        };
    }

    /**
     * {@return les moteurs interpolés de {@code a} vers {@code b} à la fraction {@code t}}
     *
     * @param a l'état de départ
     * @param b l'état d'arrivée
     * @param t la fraction, de 0 à 1
     */
    public static MotorStates between(MotorStates a, MotorStates b, float t) {
        return a.equals(b) ? b : new MotorStates(mix(a.rpm0, b.rpm0, t), mix(a.rpm1, b.rpm1, t), mix(a.rpm2, b.rpm2, t),
                mix(a.rpm3, b.rpm3, t), mix(a.power0, b.power0, t), mix(a.power1, b.power1, t),
                mix(a.power2, b.power2, t), mix(a.power3, b.power3, t));
    }

    private static float mix(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
