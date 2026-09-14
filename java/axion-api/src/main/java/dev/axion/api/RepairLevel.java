package dev.axion.api;

/**
 * Profondeur d'une réparation (§23.2 {@link DamageService#repair}, C-46).
 *
 * <p>Chaque niveau englobe les précédents : {@link #FULL} rétablit tout, {@link
 * #VISUAL} ne touche qu'à la surface.
 */
@Stable
public enum RepairLevel {
    /** Surface seule : rayures, salissures, brûlures, décalques. */
    VISUAL,
    /** Champ de déformation : la géométrie retrouve sa forme. */
    DEFORM,
    /** Santé et état d'une part. */
    PART,
    /** Intégrité structurelle : liaisons rompues rétablies. */
    STRUCTURAL,
    /** Tout, jusqu'à l'état neuf. */
    FULL,
}
