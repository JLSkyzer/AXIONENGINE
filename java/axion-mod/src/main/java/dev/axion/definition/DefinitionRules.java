package dev.axion.definition;

import java.util.Objects;
import java.util.function.Predicate;

/**
 * Ce que la validation d'une definition consulte hors du document.
 *
 * <p>Réuni ici pour que la registry se vérifie sans monde chargé ni
 * configuration réelle : un test compose ses règles comme il l'entend.
 *
 * @param vehiclesEnabled le module {@code vehicles} est actif (§24.1)
 * @param assetExists indique si un modèle, désigné par
 *     {@code <ns>:axion/<chemin>}, a été découvert
 * @param examplesEnabled les definitions de démonstration se chargent
 *     ({@code content.examples}, R-1790, ADR-134)
 * @param collisionGroups les groupes de collision tiers qu'une definition peut
 *     nommer (R-980, ADR-132 A1), chargés avant elle
 * @param physicsMaterials les matériaux physiques qu'une definition peut nommer
 *     (R-171, ADR-145), chargés avant elle ; un nom inconnu avertit (R-1032)
 * @param tires les pneus qu'une definition peut nommer (ADR-159 Q1), chargés avant elle ; un nom
 *     inconnu avertit
 * @param extensions les contrôleurs de véhicule et les paramètres d'addon que les mods ont enregistrés (ADR-167 D9),
 *     avant les definitions ; un contrôleur inconnu refuse la definition
 */
public record DefinitionRules(
        boolean vehiclesEnabled,
        Predicate<String> assetExists,
        boolean examplesEnabled,
        CollisionGroupRegistry collisionGroups,
        PhysicsMaterials physicsMaterials,
        TireProfiles tires,
        VehicleExtensions extensions) {

    /** Refuse une règle absente. */
    public DefinitionRules {
        Objects.requireNonNull(assetExists);
        Objects.requireNonNull(collisionGroups);
        Objects.requireNonNull(physicsMaterials);
        Objects.requireNonNull(tires);
        Objects.requireNonNull(extensions);
    }

    /**
     * Des règles sans contrôleur ni paramètre d'addon enregistrés : une definition qui nomme un contrôleur est refusée.
     *
     * @param vehiclesEnabled le module {@code vehicles} est actif (§24.1)
     * @param assetExists indique si un modèle a été découvert
     * @param examplesEnabled les definitions de démonstration se chargent
     * @param collisionGroups les groupes de collision tiers
     * @param physicsMaterials les matériaux physiques
     * @param tires les pneus
     */
    public DefinitionRules(
            boolean vehiclesEnabled,
            Predicate<String> assetExists,
            boolean examplesEnabled,
            CollisionGroupRegistry collisionGroups,
            PhysicsMaterials physicsMaterials,
            TireProfiles tires) {
        this(vehiclesEnabled, assetExists, examplesEnabled, collisionGroups, physicsMaterials, tires,
                VehicleExtensions.NONE);
    }

    /**
     * Des règles sans pneu déclaré : tout nom de pneu avertit.
     *
     * @param vehiclesEnabled le module {@code vehicles} est actif (§24.1)
     * @param assetExists indique si un modèle a été découvert
     * @param examplesEnabled les definitions de démonstration se chargent
     * @param collisionGroups les groupes de collision tiers
     * @param physicsMaterials les matériaux physiques
     */
    public DefinitionRules(
            boolean vehiclesEnabled,
            Predicate<String> assetExists,
            boolean examplesEnabled,
            CollisionGroupRegistry collisionGroups,
            PhysicsMaterials physicsMaterials) {
        this(vehiclesEnabled, assetExists, examplesEnabled, collisionGroups, physicsMaterials, TireProfiles.empty());
    }

    /**
     * Des règles sans matériau physique déclaré : tout nom de matériau avertit.
     *
     * @param vehiclesEnabled le module {@code vehicles} est actif (§24.1)
     * @param assetExists indique si un modèle a été découvert
     * @param examplesEnabled les definitions de démonstration se chargent
     * @param collisionGroups les groupes de collision tiers
     */
    public DefinitionRules(
            boolean vehiclesEnabled,
            Predicate<String> assetExists,
            boolean examplesEnabled,
            CollisionGroupRegistry collisionGroups) {
        this(vehiclesEnabled, assetExists, examplesEnabled, collisionGroups, PhysicsMaterials.empty());
    }

    /**
     * Des règles sans groupe de collision tiers : les réservés seuls.
     *
     * @param vehiclesEnabled le module {@code vehicles} est actif (§24.1)
     * @param assetExists indique si un modèle a été découvert
     * @param examplesEnabled les definitions de démonstration se chargent
     */
    public DefinitionRules(
            boolean vehiclesEnabled, Predicate<String> assetExists, boolean examplesEnabled) {
        this(vehiclesEnabled, assetExists, examplesEnabled, CollisionGroupRegistry.empty());
    }

    /**
     * Des règles sans les definitions de démonstration : le défaut de la release
     * (R-1790).
     *
     * @param vehiclesEnabled le module {@code vehicles} est actif (§24.1)
     * @param assetExists indique si un modèle a été découvert
     */
    public DefinitionRules(boolean vehiclesEnabled, Predicate<String> assetExists) {
        this(vehiclesEnabled, assetExists, false);
    }
}
