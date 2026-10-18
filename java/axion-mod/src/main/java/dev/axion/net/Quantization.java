package dev.axion.net;

/**
 * La quantification de chaque champ d'un snapshot, déclarée ici et nulle part ailleurs (R-1660,
 * ADR-150 D6).
 *
 * <p>Les deltas se calculent sur les entiers qui en sortent, jamais sur des flottants : client et serveur
 * reconstituent la même valeur au bit près.
 */
public final class Quantization {

    /** Position : pas de 1/1024 de bloc (21.5). */
    public static final int POSITION_STEPS_PER_BLOCK = 1024;

    /** Vitesse linéaire : la borne de R-180, en m/s, portée par 32 767 pas. */
    public static final float MAX_LINEAR_VELOCITY = 300f;

    /** Vitesse angulaire : la borne de R-180, en rad/s, portée par 32 767 pas. */
    public static final float MAX_ANGULAR_VELOCITY = 100f;

    /** Emprise relative à la position : pas de 1/256 de bloc (ADR-150 D6). */
    public static final int BOUNDS_STEPS_PER_BLOCK = 256;

    private static final int I16_STEPS = Short.MAX_VALUE;

    private Quantization() {
        throw new AssertionError("non instanciable");
    }

    /**
     * @param blocks coordonnée, en blocs, finie
     * @return la coordonnée quantifiée
     */
    public static long position(double blocks) {
        return Math.round(blocks * POSITION_STEPS_PER_BLOCK);
    }

    /**
     * @param steps coordonnée quantifiée
     * @return la coordonnée, en blocs
     */
    public static double position(long steps) {
        return (double) steps / POSITION_STEPS_PER_BLOCK;
    }

    /**
     * @param metresPerSecond composante de vitesse linéaire ; au-delà de la borne, elle est bornée,
     *     comme le natif la borne (R-180)
     * @return la composante quantifiée
     */
    public static short linear(float metresPerSecond) {
        return clamp(metresPerSecond * (I16_STEPS / MAX_LINEAR_VELOCITY));
    }

    /**
     * @param steps composante quantifiée
     * @return la composante, en m/s
     */
    public static float linear(short steps) {
        return steps * (MAX_LINEAR_VELOCITY / I16_STEPS);
    }

    /**
     * @param radiansPerSecond composante de vitesse angulaire, bornée au-delà de la borne
     * @return la composante quantifiée
     */
    public static short angular(float radiansPerSecond) {
        return clamp(radiansPerSecond * (I16_STEPS / MAX_ANGULAR_VELOCITY));
    }

    /**
     * @param steps composante quantifiée
     * @return la composante, en rad/s
     */
    public static float angular(short steps) {
        return steps * (MAX_ANGULAR_VELOCITY / I16_STEPS);
    }

    /**
     * Le coin minimal d'une emprise, arrondi vers le bas : quantifiée, l'emprise ne rétrécit jamais.
     *
     * @param blocks coordonnée relative à la position, en blocs
     * @return la coordonnée quantifiée, bornée à ±127,99 blocs
     */
    public static short boundsMin(float blocks) {
        return clamp((float) Math.floor(blocks * BOUNDS_STEPS_PER_BLOCK));
    }

    /**
     * Le coin maximal d'une emprise, arrondi vers le haut.
     *
     * @param blocks coordonnée relative à la position, en blocs
     * @return la coordonnée quantifiée, bornée à ±127,99 blocs
     */
    public static short boundsMax(float blocks) {
        return clamp((float) Math.ceil(blocks * BOUNDS_STEPS_PER_BLOCK));
    }

    /**
     * @param steps coordonnée d'emprise quantifiée
     * @return la coordonnée, en blocs
     */
    public static float bounds(short steps) {
        return (float) steps / BOUNDS_STEPS_PER_BLOCK;
    }

    private static short clamp(float steps) {
        return (short) Math.max(-I16_STEPS, Math.min(I16_STEPS, Math.round(steps)));
    }
}
