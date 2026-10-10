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
 * <p><b>Ambiante</b> : la lightmap de Minecraft en porte l'intensité et la teinte — l'heure, le ciel
 * visible, la lumière des blocs (R-1511) ; un hémisphère — le ciel en haut, le sol en bas — n'en donne
 * que la répartition selon la normale, en place de l'harmonique de la sonde de C-81, qui le
 * remplacera. Le ciel pèse la luminance de la couleur du ciel de Minecraft ; le sol, celle du
 * brouillard renvoyée par un sol d'albédo moyen ; l'ensemble est ramené à une moyenne fixe. Prendre
 * ces couleurs pour une lumière assombrissait la nuit deux fois — elles le sont déjà, comme la
 * lightmap — et teintait de bleu un métal blanc (banc de rendu du 2026-10-10, ADR-127 §5).
 *
 * <p>Les couleurs de Minecraft sont en gamma ; elles passent ici en linéaire, l'espace du calcul.
 * Les intensités sont synthétisées : choisies par le calcul, non vérifiées à l'écran, pour qu'un
 * albedo blanc non métallique face au soleil de midi sorte à peu près blanc, et ses faces latérales à
 * peu près comme celles des entités vanilla. La calibration sur le rendu vanilla revient au tone
 * mapping (C-83, R-1550).
 */
public final class WorldLight {

    /** Le soleil de plein jour, linéaire : légèrement chaud. */
    static final float[] SUN = {2.0f, 1.94f, 1.84f};

    /**
     * Part de la lumière de Minecraft qui arrive en ambiante, en moyenne sur l'hémisphère : le reste
     * d'une face au soleil de midi vient du soleil.
     */
    static final float AMBIENT = 0.35f;

    /** En deçà, ciel et brouillard sont noirs : l'hémisphère n'a plus de direction. */
    static final float MIN_LUMINANCE = 1e-4f;

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
     * L'hémisphère ambiant : ce que le ciel et le sol apportent, en part de la lumière de Minecraft
     * que la lightmap porte — gris, sans teinte ; leur moyenne vaut {@value #AMBIENT}.
     *
     * @param sky ce qu'apporte une surface tournée vers le ciel
     * @param ground ce qu'apporte une surface tournée vers le sol
     */
    public record Hemisphere(float sky, float ground) {}

    /**
     * {@return l'hémisphère ambiant du moment}
     *
     * @param skyRed couleur du ciel, {@code ClientLevel.getSkyColor}, en gamma
     * @param skyGreen idem
     * @param skyBlue idem
     * @param fogColor couleur du brouillard, {@code RenderSystem.getShaderFogColor}, en gamma : au moins
     *     trois composantes
     */
    public static Hemisphere hemisphere(double skyRed, double skyGreen, double skyBlue, float[] fogColor) {
        float sky = luminance(toLinear((float) skyRed), toLinear((float) skyGreen), toLinear((float) skyBlue));
        float ground = luminance(toLinear(fogColor[0]), toLinear(fogColor[1]), toLinear(fogColor[2])) * GROUND_ALBEDO;
        float mean = (sky + ground) / 2.0f;
        if (!(mean > MIN_LUMINANCE)) {
            return new Hemisphere(AMBIENT, AMBIENT);
        }
        return new Hemisphere(AMBIENT * sky / mean, AMBIENT * ground / mean);
    }

    /** {@return une composante de couleur passée du gamma au linéaire, comme le font les shaders} */
    static float toLinear(float gamma) {
        return (float) Math.pow(Math.max(gamma, 0.0f), GAMMA);
    }

    /** {@return la luminance d'une couleur linéaire, coefficients de la Rec. 709} */
    static float luminance(float red, float green, float blue) {
        return 0.2126f * red + 0.7152f * green + 0.0722f * blue;
    }

    private static float[] scale(float[] color, float factor) {
        return new float[] {color[0] * factor, color[1] * factor, color[2] * factor};
    }

    private static float clamp(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }
}
