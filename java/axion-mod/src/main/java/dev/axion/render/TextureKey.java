package dev.axion.render;

import dev.axion.asset.MaterialTransfer;

/**
 * Une texture à préparer pour le rendu : son rang dans la table des textures de l'asset, et sa
 * variante (ADR-122 §7).
 *
 * <p>Une texture d'albedo sert telle quelle aux matériaux opaques et translucides. Un matériau
 * CUTOUT en veut une variante dont l'alpha est binarisé à son seuil : la coupe à 0,1 des shaders
 * d'entité vanilla tombe alors exactement au seuil du matériau. Deux matériaux CUTOUT de seuils
 * différents sur la même image font deux variantes.
 *
 * <p>L'émissive d'un matériau CUTOUT est <b>masquée</b> : le shader {@code eyes}, qui la dessine,
 * ne rejette aucun texel, et elle brillerait dans les trous de la découpe. Sa variante garde la
 * couleur de l'émissive — ou du blanc, sans texture d'émissive — là où l'albedo, binarisé au
 * seuil, est gardé, et du noir ailleurs : rien ne s'y ajoute.
 *
 * @param rank rang dans {@code MaterialTransfer.textures()} ; {@link MaterialTransfer#NO_TEXTURE}
 *     pour le blanc d'une variante masquée sans texture d'émissive
 * @param cutout vrai pour la variante à alpha binarisé
 * @param threshold seuil de la découpe ou du masque, sur l'alpha du texel ramené à
 *     {@code [0, 1]} ; {@code 0} hors des deux
 * @param mask rang de l'albedo dont l'alpha, binarisé au seuil, masque cette texture ;
 *     {@link #NO_MASK} sinon
 */
public record TextureKey(int rank, boolean cutout, float threshold, int mask) {

    /** Pas de masque. */
    public static final int NO_MASK = -1;

    /**
     * {@return la texture telle quelle}
     *
     * @param rank rang dans la table des textures
     */
    public static TextureKey plain(int rank) {
        return new TextureKey(rank, false, 0.0f, NO_MASK);
    }

    /**
     * {@return la variante à alpha binarisé au seuil donné}
     *
     * @param rank rang dans la table des textures
     * @param threshold seuil sur l'alpha du texel ; {@code +∞} coupe tout
     */
    public static TextureKey cutout(int rank, float threshold) {
        return new TextureKey(rank, true, threshold, NO_MASK);
    }

    /**
     * {@return la variante d'une émissive masquée par l'albedo d'un matériau découpé}
     *
     * @param rank rang de l'émissive, ou {@link MaterialTransfer#NO_TEXTURE} pour du blanc
     * @param mask rang de l'albedo
     * @param threshold seuil de la découpe du matériau, sur l'alpha de l'albedo
     */
    public static TextureKey masked(int rank, int mask, float threshold) {
        return new TextureKey(rank, false, threshold, mask);
    }

    /** {@return vrai pour une émissive masquée par un albedo} */
    public boolean masked() {
        return mask != NO_MASK;
    }

    /** {@return vrai si l'image est du blanc, sans texture : seule une variante masquée l'est} */
    public boolean white() {
        return rank == MaterialTransfer.NO_TEXTURE;
    }

    /**
     * {@return le rang de l'image qui donne ses dimensions à la texture} — l'émissive, ou l'albedo
     * qui masque du blanc. C'est sous ce rang qu'un refus est rapporté.
     */
    public int origin() {
        return white() ? mask : rank;
    }
}
