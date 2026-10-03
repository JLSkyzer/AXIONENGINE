package dev.axion.render;

/**
 * Une texture à préparer pour le rendu : son rang dans la table des textures de l'asset, et sa
 * variante (ADR-122 §7).
 *
 * <p>Une texture d'albedo sert telle quelle aux matériaux opaques et translucides. Un matériau
 * CUTOUT en veut une variante dont l'alpha est binarisé à son seuil : la coupe à 0,1 des shaders
 * d'entité vanilla tombe alors exactement au seuil du matériau. Deux matériaux CUTOUT de seuils
 * différents sur la même image font deux variantes.
 *
 * @param rank rang dans {@code MaterialTransfer.textures()}
 * @param cutout vrai pour la variante à alpha binarisé
 * @param threshold seuil de cette variante, sur l'alpha du texel ramené à {@code [0, 1]} ;
 *     {@code 0} hors découpe
 */
public record TextureKey(int rank, boolean cutout, float threshold) {

    /**
     * {@return la texture telle quelle}
     *
     * @param rank rang dans la table des textures
     */
    public static TextureKey plain(int rank) {
        return new TextureKey(rank, false, 0.0f);
    }

    /**
     * {@return la variante à alpha binarisé au seuil donné}
     *
     * @param rank rang dans la table des textures
     * @param threshold seuil sur l'alpha du texel ; {@code +∞} coupe tout
     */
    public static TextureKey cutout(int rank, float threshold) {
        return new TextureKey(rank, true, threshold);
    }
}
