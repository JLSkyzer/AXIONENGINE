package dev.axion.render;

import dev.axion.asset.MaterialTransfer;

/**
 * Passe qui dessine une surface, selon le mode de mélange de son matériau (DM-05, §19.10,
 * R-1570).
 */
public enum SurfacePass {

    /** Passe 1 : profondeur écrite, sans mélange ; stage {@code AFTER_ENTITIES}. */
    OPAQUE,

    /** Passe 2 : comme l'opaque, texels rejetés sous le seuil du matériau ; {@code AFTER_ENTITIES}. */
    CUTOUT,

    /** Passe 4 : mélangée, triée de l'arrière vers l'avant ; {@code AFTER_TRANSLUCENT_BLOCKS}. */
    TRANSLUCENT;

    /**
     * {@return la passe d'un mode de mélange}
     *
     * @param blendMode mode de mélange d'un matériau, borné par la lecture de la table
     * @throws IllegalArgumentException si le mode est inconnu
     */
    public static SurfacePass of(int blendMode) {
        return switch (blendMode) {
            case MaterialTransfer.BLEND_OPAQUE -> OPAQUE;
            case MaterialTransfer.BLEND_CUTOUT -> CUTOUT;
            case MaterialTransfer.BLEND_TRANSLUCENT -> TRANSLUCENT;
            default -> throw new IllegalArgumentException("mode de mélange inconnu : " + blendMode);
        };
    }
}
