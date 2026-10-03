package dev.axion.render;

/**
 * Texels d'un atlas : une tuile se remplit de son image et de sa marge, puis se recopie dans sa
 * page, niveau par niveau (ADR-122 T-c).
 *
 * <p>Sur des {@link Pixels}, pour que la règle se teste sans Minecraft ; le rendu vanilla en
 * enveloppe ses {@code NativeImage}.
 */
public final class AtlasTexels {

    private AtlasTexels() {}

    /**
     * Une image, texel par texel. Les texels sont empaquetés comme les rend
     * {@code NativeImage.getPixelRGBA} ; ils sont recopiés sans être interprétés.
     */
    public interface Pixels {

        /** {@return la largeur} */
        int width();

        /** {@return la hauteur} */
        int height();

        /**
         * {@return un texel}
         *
         * @param x colonne
         * @param y rangée
         */
        int get(int x, int y);

        /**
         * Écrit un texel.
         *
         * @param x colonne
         * @param y rangée
         * @param texel texel empaqueté
         */
        void set(int x, int y, int texel);
    }

    /**
     * {@return la coordonnée, dans l'image, du texel que la texture seule lirait à cette
     * coordonnée} — enroulée en répétition, ramenée au bord en écrêtage.
     *
     * @param coordinate coordonnée relative au bord de l'image, éventuellement hors d'elle
     * @param size dimension de l'image sur cet axe
     * @param clamp écrêtage ; répétition sinon
     */
    public static int source(int coordinate, int size, boolean clamp) {
        return clamp ? Math.max(0, Math.min(size - 1, coordinate)) : Math.floorMod(coordinate, size);
    }

    /**
     * Remplit une tuile : l'image à la marge près, et tout autour ce que la texture seule
     * échantillonnerait au-delà de son bord.
     *
     * @param image l'image
     * @param tile la tuile, de la taille de son emplacement
     * @param margin marge à gauche et en haut de l'image
     * @param clamp écrêtage ; répétition sinon
     */
    public static void fill(Pixels image, Pixels tile, int margin, boolean clamp) {
        for (int y = 0; y < tile.height(); y++) {
            int sourceY = source(y - margin, image.height(), clamp);
            for (int x = 0; x < tile.width(); x++) {
                tile.set(x, y, image.get(source(x - margin, image.width(), clamp), sourceY));
            }
        }
    }

    /**
     * Recopie une image dans une plus grande.
     *
     * @param from image à recopier
     * @param to image qui la reçoit
     * @param x colonne où poser son bord gauche
     * @param y rangée où poser son bord haut
     * @throws IllegalArgumentException si elle déborde
     */
    public static void copy(Pixels from, Pixels to, int x, int y) {
        if (x < 0 || y < 0 || x + from.width() > to.width() || y + from.height() > to.height()) {
            throw new IllegalArgumentException(from.width() + "×" + from.height() + " en (" + x + ", " + y
                    + ") déborde de " + to.width() + "×" + to.height());
        }
        for (int row = 0; row < from.height(); row++) {
            for (int column = 0; column < from.width(); column++) {
                to.set(x + column, y + row, from.get(column, row));
            }
        }
    }
}
