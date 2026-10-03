package dev.axion.render;

/**
 * Où un mesh lit sa texture : la texture liée, et la place qu'y occupe son image (ADR-122 §7,
 * T-c).
 *
 * <p>Une texture individuelle occupe toute sa texture liée ; une texture rangée dans un atlas n'en
 * occupe qu'une tuile. Ses coordonnées de texture, dans {@code [0, 1]}, s'y ramènent par
 * {@code u' = uOffset + u × uScale} et {@code v' = vOffset + v × vScale}, à l'émission des
 * sommets.
 *
 * @param binding texture liée, celle que le type de rendu lie
 * @param uOffset début de l'image dans la texture liée, en U
 * @param vOffset début de l'image dans la texture liée, en V
 * @param uScale étendue de l'image dans la texture liée, en U
 * @param vScale étendue de l'image dans la texture liée, en V
 */
public record TextureRegion(TextureBinding binding, float uOffset, float vOffset, float uScale, float vScale) {

    /**
     * {@return une texture qui occupe toute sa texture liée}
     *
     * @param binding texture liée
     */
    public static TextureRegion whole(TextureBinding binding) {
        return new TextureRegion(binding, 0.0f, 0.0f, 1.0f, 1.0f);
    }

    /**
     * {@return la coordonnée U dans la texture liée}
     *
     * @param u coordonnée U du sommet, dans l'image
     */
    public float u(float u) {
        return uOffset + u * uScale;
    }

    /**
     * {@return la coordonnée V dans la texture liée}
     *
     * @param v coordonnée V du sommet, dans l'image
     */
    public float v(float v) {
        return vOffset + v * vScale;
    }
}
