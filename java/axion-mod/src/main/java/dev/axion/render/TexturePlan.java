package dev.axion.render;

import dev.axion.asset.MaterialTransfer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Les textures qu'un asset doit charger pour le backend vanilla (ADR-122 §7).
 *
 * <p>Deux slots seulement y servent : l'albedo, sous la variante qu'exige le mode de mélange, et
 * l'émissive, quand le matériau émet — masquée par l'albedo d'un matériau découpé. Normal, ORM,
 * height et damage sont sans effet dans ce backend (R-1513) : les charger coûterait de la mémoire
 * pour rien.
 */
public final class TexturePlan {

    private TexturePlan() {}

    /**
     * {@return la texture d'albedo d'un matériau, ou {@code null} s'il n'en a pas}
     *
     * <p>Un matériau CUTOUT veut la variante binarisée à son seuil (voir {@link #cutoutThreshold}).
     *
     * @param material matériau
     */
    public static TextureKey albedo(MaterialTransfer.Material material) {
        int slot = material.albedoTexture();
        if (slot == MaterialTransfer.NO_TEXTURE) {
            return null;
        }
        return material.blendMode() == MaterialTransfer.BLEND_CUTOUT
                ? TextureKey.cutout(slot, cutoutThreshold(material))
                : TextureKey.plain(slot);
    }

    /**
     * {@return la texture émissive d'un matériau, ou {@code null} : il n'émet pas, ou il émet son
     * facteur seul, sur du blanc (sémantique glTF)}
     *
     * <p>Un matériau CUTOUT texturé veut la variante masquée par son albedo (voir
     * {@link TextureKey}) — même sans texture d'émissive : c'est alors du blanc qui est masqué.
     *
     * @param material matériau
     */
    public static TextureKey emissive(MaterialTransfer.Material material) {
        if (!emits(material)) {
            return null;
        }
        int slot = material.emissiveTexture();
        int albedo = material.albedoTexture();
        if (material.blendMode() == MaterialTransfer.BLEND_CUTOUT && albedo != MaterialTransfer.NO_TEXTURE) {
            return TextureKey.masked(slot, albedo, cutoutThreshold(material));
        }
        return slot == MaterialTransfer.NO_TEXTURE ? null : TextureKey.plain(slot);
    }

    /**
     * {@return vrai si le matériau émet : une composante de son facteur émissif est positive}
     *
     * @param material matériau
     */
    public static boolean emits(MaterialTransfer.Material material) {
        for (float channel : material.emissiveFactor()) {
            if (channel > 0.0f) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@return les textures distinctes à charger, dans l'ordre des matériaux}
     *
     * @param materials table des matériaux et des textures
     */
    public static List<TextureKey> keys(MaterialTransfer materials) {
        Set<TextureKey> keys = new LinkedHashSet<>();
        for (MaterialTransfer.Material material : materials.materials()) {
            TextureKey albedo = albedo(material);
            if (albedo != null) {
                keys.add(albedo);
            }
            TextureKey emissive = emissive(material);
            if (emissive != null) {
                keys.add(emissive);
            }
        }
        return new ArrayList<>(keys);
    }

    /**
     * {@return le seuil de découpe d'un matériau, sur l'alpha du texel}
     *
     * <p>Le matériau garde un texel quand {@code alpha × albedo.a ≥ alphaCutoff}, donc quand
     * {@code alpha ≥ alphaCutoff / albedo.a}. Un albedo d'alpha nul ne garde rien, sauf à un
     * seuil nul, qui garde tout.
     *
     * @param material matériau
     */
    public static float cutoutThreshold(MaterialTransfer.Material material) {
        float alpha = material.albedoFactor()[3];
        float cutoff = material.alphaCutoff();
        if (alpha > 0.0f) {
            return cutoff / alpha;
        }
        return cutoff <= 0.0f ? 0.0f : Float.POSITIVE_INFINITY;
    }
}
