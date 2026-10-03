package dev.axion.render;

/**
 * Règles qu'une image suit avant de devenir une texture (C-26, R-532, R-570, ADR-122 §7).
 */
public final class TextureRules {

    /** Côté maximal d'une texture, en pixels (R-570). */
    public static final int MAX_SIDE = 4096;

    /** Les huit octets qui ouvrent tout fichier PNG. */
    private static final byte[] PNG_SIGNATURE = {
        (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A
    };

    private TextureRules() {}

    /**
     * {@return vrai si les octets commencent par la signature PNG}
     *
     * <p>Le décodeur de Minecraft lit aussi JPEG, BMP ou GIF : la règle « PNG uniquement » de
     * R-532 se vérifie donc ici, avant lui (ADR-122, constat 3).
     *
     * @param bytes octets du fichier
     */
    public static boolean isPng(byte[] bytes) {
        if (bytes.length < PNG_SIGNATURE.length) {
            return false;
        }
        for (int at = 0; at < PNG_SIGNATURE.length; at++) {
            if (bytes[at] != PNG_SIGNATURE[at]) {
                return false;
            }
        }
        return true;
    }

    /**
     * {@return pourquoi une image de ces dimensions est refusée, ou {@code null} si elle est
     * admise}
     *
     * @param width largeur, en pixels
     * @param height hauteur, en pixels
     */
    public static String sizeRefusal(int width, int height) {
        if (width < 1 || height < 1) {
            return "E-3006 : image de " + width + "×" + height + ", vide";
        }
        if (width > MAX_SIDE || height > MAX_SIDE) {
            return "E-3006 : image de " + width + "×" + height + ", au-delà de " + MAX_SIDE
                    + " par côté (R-570)";
        }
        return null;
    }

    /**
     * {@return le nombre de niveaux de mipmaps à produire, base exclue}
     *
     * <p>Le réglage vanilla, mais jamais plus que ce que l'image permet : chaque niveau divise
     * ses côtés par deux, et le plus petit côté ne descend pas sous un pixel.
     *
     * @param vanillaLevels réglage {@code mipmapLevels} du jeu (0 à 4)
     * @param width largeur de l'image
     * @param height hauteur de l'image
     */
    public static int mipLevels(int vanillaLevels, int width, int height) {
        int smallest = Math.min(width, height);
        int fit = smallest < 1 ? 0 : 31 - Integer.numberOfLeadingZeros(smallest);
        return Math.max(0, Math.min(vanillaLevels, fit));
    }

    /**
     * {@return l'alpha binarisé d'un texel : 255 s'il atteint le seuil, 0 sinon}
     *
     * @param alpha alpha du texel, de 0 à 255
     * @param threshold seuil sur l'alpha ramené à {@code [0, 1]} ; {@code +∞} coupe tout
     */
    public static int cutoutAlpha(int alpha, float threshold) {
        return alpha / 255.0 >= threshold ? 255 : 0;
    }
}
