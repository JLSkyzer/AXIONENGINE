package dev.axion.api;

import net.minecraft.resources.ResourceLocation;

/**
 * Référence à une definition data-driven (C-27), par son identifiant.
 *
 * <p>L'identifiant est une {@link ResourceLocation} {@code <ns>:<chemin>}, le
 * même que celui du fichier {@code data/<ns>/axion/definitions/<chemin>.json}.
 *
 * @param id identifiant de la definition
 */
@Stable
public record DefinitionRef(ResourceLocation id) {}
