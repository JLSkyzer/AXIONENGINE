package dev.axion.render;

/**
 * La lumière des surfaces du backend natif, synthétisée de ce que le monde fournit (§19.5, ADR-127
 * §5) — logique pure, sans Forge.
 *
 * <p><b>Lumière directe</b> : le soleil, ou la lune quand il s'est couché. Sa direction suit le ciel
 * de Minecraft, qui fait tourner le soleil autour de l'axe nord-sud : levé à l'est, au zénith à
 * midi, couché à l'ouest. Son intensité suit l'assombrissement du ciel de Minecraft
 * ({@code ClientLevel.getSkyDarken}), pluie et orage compris. La lune prend le relais sous
 * l'horizon, là où l'assombrissement éteint le soleil, et monte depuis zéro : aucun saut de lumière.
 *
 * <p><b>Ambiante</b> : un hémisphère — le ciel en haut, le sol en bas — en place de l'harmonique de
 * la sonde de C-81, qui le remplacera. Le ciel est la couleur du ciel de Minecraft ; le sol, celle du
 * brouillard renvoyée par un sol d'albédo moyen.
 *
 * <p>Les couleurs de Minecraft sont en gamma ; elles passent ici en linéaire, l'espace du calcul.
 * Les intensités sont synthétisées : choisies par le calcul, non vérifiées à l'écran, pour qu'un
 * albedo blanc non métallique face au soleil de midi sorte à peu près blanc, et ses faces latérales à
 * peu près comme celles des entités vanilla. La calibration sur le rendu vanilla revient au tone
 * mapping (C-83, R-1550).
 */
public final class WorldLight {

    /** Le soleil de plein jour, linéaire : légèrement chaud. */
    static final float[] SUN = {1.75f, 1.70f, 1.61f};

    /** La pleine lune, linéaire : froide, et faible. */
    static final float[] MOON = {0.14f, 0.155f, 0.20f};

    /** Albédo du sol de l'hémisphère ambiant. */
    static final float GROUND_ALBEDO = 0.4f;

    /**
     * Hauteur du soleil, en cosinus de son angle au zénith, sous laquelle l'assombrissement de
     * Minecraft l'a éteint : {@code clamp(2 cos θ + 0,2 ; 0 ; 1)} s'y annule.
     */
    static final float SUN_OFF = -0.1f;

    /** Montée de la lune, en cosinus, depuis la relève : de rien à toute sa lumière. */
    static final float MOON_RISE = 0.2f;

    /** Assombrissement du ciel de Minecraft en pleine nuit : le plancher de {@code getSkyDarken}. */
    static final float NIGHT_SKY_DARKEN = 0.2f;

    private static final double GAMMA = 2.2;

    private WorldLight() {}

    /**
     * Une lumière directionnelle.
     *
     * @param direction d'où elle vient, unitaire, en axes du monde
     * @param color ce qu'elle apporte, linéaire
     */
    public record Light(float[] direction, float[] color) {}

    /**
     * {@return la lumière directe du moment}
     *
     * @param timeOfDay heure du ciel, {@code Level.getTimeOfDay} : 0 à midi, 0,5 à minuit
     * @param skyDarken assombrissement du ciel, {@code ClientLevel.getSkyDarken} : de 0,2 la nuit à 1
     *     en plein jour, pluie et orage compris
     * @param moonBrightness éclat de la phase de la lune, {@code getMoonBrightness}, de 0 à 1
     * @param rainLevel pluie, {@code getRainLevel}, de 0 à 1
     * @param hasSkyLight vrai pour une dimension qui a un ciel ; sans ciel, aucune lumière directe
     */
    public static Light direct(
            float timeOfDay, float skyDarken, float moonBrightness, float rainLevel, boolean hasSkyLight) {
        double angle = timeOfDay * 2.0 * Math.PI;
        // Le ciel de Minecraft : Ry(-90°) puis Rx(angle) appliqués au zénith.
        float sunX = (float) -Math.sin(angle);
        float sunY = (float) Math.cos(angle);
        if (!hasSkyLight) {
            return new Light(new float[] {0.0f, 1.0f, 0.0f}, new float[3]);
        }
        if (sunY >= SUN_OFF) {
            float daylight = clamp((skyDarken - NIGHT_SKY_DARKEN) / (1.0f - NIGHT_SKY_DARKEN));
            return new Light(new float[] {sunX, sunY, 0.0f}, scale(SUN, daylight));
        }
        float moonY = -sunY;
        float rise = clamp((moonY + SUN_OFF) / MOON_RISE);
        float moon = moonBrightness * (1.0f - clamp(rainLevel)) * rise;
        return new Light(new float[] {-sunX, moonY, 0.0f}, scale(MOON, moon));
    }

    /**
     * {@return le ciel de l'hémisphère ambiant : la couleur du ciel de Minecraft, en linéaire}
     *
     * @param red couleur du ciel, {@code ClientLevel.getSkyColor}, en gamma
     * @param green idem
     * @param blue idem
     */
    public static float[] sky(double red, double green, double blue) {
        return new float[] {toLinear((float) red), toLinear((float) green), toLinear((float) blue)};
    }

    /**
     * {@return le sol de l'hémisphère ambiant : le brouillard, en linéaire, renvoyé par un sol
     * d'albédo {@value #GROUND_ALBEDO}}
     *
     * @param fogColor couleur du brouillard, {@code RenderSystem.getShaderFogColor}, en gamma : au moins
     *     trois composantes
     */
    public static float[] ground(float[] fogColor) {
        return new float[] {
            toLinear(fogColor[0]) * GROUND_ALBEDO, toLinear(fogColor[1]) * GROUND_ALBEDO,
            toLinear(fogColor[2]) * GROUND_ALBEDO
        };
    }

    /** {@return une composante de couleur passée du gamma au linéaire, comme le font les shaders} */
    static float toLinear(float gamma) {
        return (float) Math.pow(Math.max(gamma, 0.0f), GAMMA);
    }

    private static float[] scale(float[] color, float factor) {
        return new float[] {color[0] * factor, color[1] * factor, color[2] * factor};
    }

    private static float clamp(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }
}
