package dev.axion.api.hud;

import dev.axion.api.Experimental;

/**
 * La toile d'un widget du tableau de bord (ADR-170 D6) : son rectangle à l'écran, en pixels de l'interface de
 * Minecraft, origine en haut à gauche, à l'échelle que le joueur a choisie ({@code hud.dashboard_scale}). Tout ce qui
 * sort du rectangle est coupé. Les couleurs sont en {@code 0xAARRGGBB}.
 *
 * <p>AXION la fournit ; un addon ne l'implémente pas. La même toile servira aux écrans du monde (CAP-54).
 */
@Experimental
public interface DashboardCanvas {

    /** {@return la largeur de la toile} */
    int width();

    /** {@return sa hauteur} */
    int height();

    /**
     * Remplit un rectangle.
     *
     * @param x son bord gauche
     * @param y son bord haut
     * @param width sa largeur
     * @param height sa hauteur
     * @param argb sa couleur
     */
    void fill(int x, int y, int width, int height, int argb);

    /**
     * Trace un segment, d'un pixel d'épaisseur : une aiguille, une graduation.
     *
     * @param x1 son départ
     * @param y1 son départ
     * @param x2 son arrivée
     * @param y2 son arrivée
     * @param argb sa couleur
     */
    void line(double x1, double y1, double x2, double y2, int argb);

    /**
     * Écrit un texte tel quel, dans la police de Minecraft, avec son ombre.
     *
     * @param text le texte
     * @param x son bord gauche
     * @param y son bord haut
     * @param argb sa couleur
     */
    void text(String text, int x, int y, int argb);

    /**
     * Écrit un texte traduit, dans la langue du joueur.
     *
     * @param key sa clé de traduction, celle d'un fichier de langue du mod
     * @param x son bord gauche
     * @param y son bord haut
     * @param argb sa couleur
     * @param arguments les valeurs que la traduction insère
     */
    void translated(String key, int x, int y, int argb, Object... arguments);

    /**
     * {@return la largeur d'un texte écrit tel quel, en pixels}
     *
     * @param text le texte
     */
    int textWidth(String text);

    /** {@return la hauteur d'une ligne de texte, en pixels} */
    int lineHeight();

    /**
     * Dessine une texture du mod, étirée au rectangle.
     *
     * @param location son emplacement, {@code <modId>:textures/…png}
     * @param x son bord gauche
     * @param y son bord haut
     * @param width sa largeur
     * @param height sa hauteur
     */
    void texture(String location, int x, int y, int width, int height);
}
