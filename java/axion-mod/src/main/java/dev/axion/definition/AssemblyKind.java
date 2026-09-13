package dev.axion.definition;

import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Genre d'une assembly, champ {@code kind} d'une definition.
 *
 * <p>Les valeurs sont celles d'{@code AssemblyKind} au DM ; leur graphie JSON,
 * que le cahier des charges ne donne que pour {@code vehicle}, est fixée par
 * l'ADR-109 : le {@code snake_case} minuscule du nom du DM.
 */
public enum AssemblyKind {
    /** Assembly immobile. */
    STATIC("static"),
    /** Objet rigide. */
    RIGID_OBJECT("rigid_object"),
    /** Véhicule (C-33). */
    VEHICLE("vehicle"),
    /** Assembly articulée par des joints. */
    ARTICULATED("articulated"),
    /** Personnage. */
    CHARACTER("character"),
    /** Assembly purement visuelle. */
    VISUAL("visual"),
    /** Structure, variante d'articulée (R-191). */
    STRUCTURE("structure"),
    /** Machine, variante d'articulée (R-191). */
    MACHINE("machine"),
    /** Objet souple. */
    SOFT_OBJECT("soft_object");

    private final String json;

    AssemblyKind(String json) {
        this.json = json;
    }

    /** {@return la graphie JSON} */
    public String json() {
        return json;
    }

    /**
     * {@return le genre d'une graphie JSON, ou vide}
     *
     * <p>La correspondance est exacte : {@code VEHICLE} n'est pas
     * {@code vehicle} (ADR-109, point 1).
     *
     * @param value valeur lue
     */
    public static Optional<AssemblyKind> fromJson(String value) {
        return Arrays.stream(values()).filter(kind -> kind.json.equals(value)).findFirst();
    }

    /** {@return les graphies admises, pour un message d'erreur} */
    static String allowed() {
        return Arrays.stream(values()).map(AssemblyKind::json).collect(Collectors.joining(", "));
    }
}
