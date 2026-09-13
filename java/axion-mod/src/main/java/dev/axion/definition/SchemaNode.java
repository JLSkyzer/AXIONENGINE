package dev.axion.definition;

import java.util.Map;
import java.util.Set;

/**
 * Description déclarative d'un schéma de definition.
 *
 * <p>Le schéma est une donnée, pas un enchaînement de tests écrits à la main :
 * le même arbre sert à vérifier une definition ({@link DefinitionChecker}) et à
 * publier le schéma JSON que R-1783 exige. Deux transcriptions séparées du même
 * format finiraient par diverger.
 */
sealed interface SchemaNode {

    /**
     * Objet aux clés connues ; toute autre clé est refusée (ADR-109, point 4).
     *
     * @param fields champs, dans l'ordre de la documentation
     */
    record Obj(Map<String, Field> fields) implements SchemaNode {}

    /**
     * Champ d'un objet.
     *
     * @param node forme de la valeur
     * @param required le champ doit être présent
     */
    record Field(SchemaNode node, boolean required) {}

    /**
     * Tableau.
     *
     * @param item forme de chaque élément
     * @param min nombre minimal d'éléments
     * @param max nombre maximal d'éléments
     * @param limit le maximum est un plafond d'assembly (R-190, C-22), dont le
     *     dépassement porte {@code E-3050} plutôt que {@code E-7001}
     */
    record Arr(SchemaNode item, int min, int max, boolean limit) implements SchemaNode {}

    /**
     * Objet dont les clés sont des données : animations, étapes, variables.
     *
     * @param key règle portée par chaque clé
     * @param value forme de chaque valeur
     * @param max nombre maximal de clés, plafond d'assembly
     */
    record Dict(TextRule key, SchemaNode value, int max) implements SchemaNode {}

    /**
     * Chaîne.
     *
     * @param rule ce que la chaîne doit être
     */
    record Text(TextRule rule) implements SchemaNode {}

    /**
     * Nombre fini.
     *
     * @param min borne inférieure
     * @param max borne supérieure, incluse
     * @param exclusiveMin la borne inférieure est exclue
     * @param integer seuls les entiers sont admis
     */
    record Num(double min, double max, boolean exclusiveMin, boolean integer)
            implements SchemaNode {}

    /** Booléen. */
    record Bool() implements SchemaNode {}

    /** Valeur JSON quelconque : {@code custom}, arguments sans forme écrite. */
    record Any() implements SchemaNode {}

    /**
     * Valeur qui peut être {@code null}.
     *
     * @param node forme de la valeur non nulle
     */
    record Nullable(SchemaNode node) implements SchemaNode {}

    /** Ce qu'une chaîne doit être. */
    sealed interface TextRule {

        /** Texte libre. */
        record Free() implements TextRule {}

        /**
         * Valeur d'une liste fermée.
         *
         * @param values valeurs admises, dans l'ordre de la documentation
         */
        record OneOf(Set<String> values) implements TextRule {}

        /**
         * Nom qui déclare un élément.
         *
         * @param category catégorie du nom
         */
        record Declares(NameCategory category) implements TextRule {}

        /**
         * Nom qui désigne un élément.
         *
         * @param category catégorie du nom désigné
         */
        record Refers(NameCategory category) implements TextRule {}

        /** Identifiant de ressource {@code <ns>:<chemin>}. */
        record Id() implements TextRule {}

        /** Source procédurale de l'ANNEXE A.4. */
        record Source() implements TextRule {}
    }
}
