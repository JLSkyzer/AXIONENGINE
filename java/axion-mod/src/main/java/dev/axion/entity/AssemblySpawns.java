package dev.axion.entity;

import dev.axion.definition.Definition;
import dev.axion.definition.DefinitionRegistry;

/**
 * Décide si une demande de création d'assembly est recevable (C-50, C-71).
 *
 * <p>Décision séparée de la commande pour être vérifiable sans serveur : la
 * commande ne fait qu'appliquer ce qui est décidé ici.
 */
public final class AssemblySpawns {

    private AssemblySpawns() {}

    /**
     * Issue d'une demande.
     *
     * @param definition definition à instancier, ou {@code null} si refusée
     * @param refusal raison du refus, ou {@code null} si acceptée
     */
    public record Decision(Definition definition, String refusal) {

        /** {@return vrai si la demande est acceptée} */
        public boolean accepted() {
            return definition != null;
        }
    }

    /**
     * Examine une demande.
     *
     * @param registry definitions chargées
     * @param definitionId identifiant demandé
     * @param requested nombre d'assemblies demandées par la commande
     * @param limit {@code limits.max_spawn_per_command}
     * @return la décision
     */
    public static Decision check(
            DefinitionRegistry registry, String definitionId, int requested, long limit) {
        // R-811 : le plafond s'applique avant tout. Une commande ne crée
        // aujourd'hui qu'une assembly ; un plafond réglé à zéro interdit donc la
        // création par commande, et c'est bien ce qu'il dit.
        if (requested > limit) {
            return new Decision(null, requested + " assembly(s) demandée(s), au-delà de "
                    + "limits.max_spawn_per_command (" + limit + ")");
        }
        return registry.get(definitionId)
                .map(definition -> new Decision(definition, null))
                .orElseGet(() -> new Decision(null, "definition inconnue : " + definitionId
                        + " (" + registry.size() + " chargée(s), " + registry.refusals().size()
                        + " refusée(s))"));
    }
}
