package dev.axion.render;

import dev.axion.asset.MaterialTransfer;

/**
 * Filtrage et répétition d'une texture, tels que le rendu vanilla sait les appliquer (ADR-122
 * §7, décision 4).
 *
 * <p>Le filtrage vient de la source : l'échantillonneur glTF, ou le {@code blur} du
 * {@code .mcmeta} d'une ressource ; à défaut, le plus proche, comme Minecraft. Le téléversement
 * vanilla n'écrête que les deux axes à la fois : un échantillonneur qui n'en écrête qu'un est
 * rendu en répétition, et signalé.
 *
 * @param blur filtrage linéaire ; au plus proche sinon
 * @param clamp coordonnées écrêtées au bord ; répétées sinon
 * @param mixedClamp un seul axe était écrêté : rendu en répétition, à signaler
 */
public record TextureSampling(boolean blur, boolean clamp, boolean mixedClamp) {

    /**
     * {@return le filtrage et la répétition d'une texture}
     *
     * @param sampler bits de l'échantillonneur (ADR-122 §2)
     * @param metadataBlur {@code blur} du {@code .mcmeta} d'une ressource, ou {@code null}
     * @param metadataClamp {@code clamp} du {@code .mcmeta} d'une ressource, ou {@code null}
     */
    public static TextureSampling of(int sampler, Boolean metadataBlur, Boolean metadataClamp) {
        int filter = sampler & MaterialTransfer.SAMPLER_FILTER_MASK;
        boolean blur = filter == MaterialTransfer.SAMPLER_FILTER_LINEAR
                || (filter == MaterialTransfer.SAMPLER_FILTER_UNDECLARED
                        && Boolean.TRUE.equals(metadataBlur));
        boolean clampU = (sampler & MaterialTransfer.SAMPLER_CLAMP_U) != 0;
        boolean clampV = (sampler & MaterialTransfer.SAMPLER_CLAMP_V) != 0;
        boolean clamp = (clampU && clampV) || Boolean.TRUE.equals(metadataClamp);
        return new TextureSampling(blur, clamp, clampU != clampV && !clamp);
    }
}
