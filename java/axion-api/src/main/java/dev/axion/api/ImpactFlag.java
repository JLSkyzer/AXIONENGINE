package dev.axion.api;

/**
 * Nature d'un impact (§23.2, {@link ImpactSpec.Builder#flags}).
 *
 * <p>Les drapeaux se combinent : un impact peut être à la fois {@link #SHARP} et
 * {@link #CONTINUOUS}. Ils orientent la répartition d'énergie et le type de
 * dommage, sans jamais court-circuiter la validation de l'impact.
 */
@Stable
public enum ImpactFlag {
    /** Contact tranchant : concentre l'énergie, favorise la coupe et l'éraflure. */
    SHARP,
    /** Contact contondant : répartit l'énergie, favorise l'enfoncement. */
    BLUNT,
    /** Apport thermique : brûlure, ramollissement. */
    THERMAL,
    /** Sollicitation continue plutôt qu'un choc unique (frottement, écrasement). */
    CONTINUOUS,
}
