package dev.axion.render;

/**
 * Une texture téléversée, telle qu'un type de rendu la lie (ADR-122 §7).
 *
 * <p>Minecraft réapplique le filtrage d'une texture à chaque dessin, depuis son type de rendu : le
 * type doit donc reprendre celui du téléversement — linéaire ou au plus proche, avec ou sans
 * mipmaps —, sans quoi il l'écraserait.
 *
 * @param location nom enregistré auprès du gestionnaire de textures
 * @param blur filtrage linéaire ; au plus proche sinon
 * @param mipmap la texture a des niveaux de mipmaps
 */
public record TextureBinding(String location, boolean blur, boolean mipmap) {}
