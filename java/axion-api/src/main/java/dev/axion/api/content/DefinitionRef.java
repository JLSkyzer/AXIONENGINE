package dev.axion.api.content;

import dev.axion.api.Stable;

import net.minecraft.resources.ResourceLocation;

/**
 * Référence à une definition data-driven (C-27), par son identifiant.
 *
 * <p>L'identifiant est une {@link ResourceLocation} {@code <ns>:<chemin>}, le
 * même que celui du fichier {@code axion/definitions/<chemin>.json} que le mod {@code <ns>} enregistre
 * ({@link ContentRegistrar#registerFolder}).
 *
 * @param id identifiant de la definition
 */
@Stable
public record DefinitionRef(ResourceLocation id) {}
