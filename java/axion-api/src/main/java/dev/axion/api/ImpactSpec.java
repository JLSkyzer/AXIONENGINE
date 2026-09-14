package dev.axion.api;

import net.minecraft.world.phys.Vec3;

/**
 * Description d'un impact physique (§23.2).
 *
 * <p>Un {@code ImpactSpec} se construit par son {@link Builder}, dont {@link
 * Builder#build()} <strong>valide et borne</strong> toutes les valeurs (R-1762) :
 * un mod tiers ne peut pas injecter une énergie aberrante.
 */
@Stable
public interface ImpactSpec {

    /**
     * {@return un constructeur d'impact vierge}
     *
     * <p>Effet : alloue un constructeur. Thread : quelconque. Coût : négligeable.
     * Échec : lève {@link IllegalStateException} si AXION est indisponible.
     */
    static Builder builder() {
        return ImpactSpecProvider.newBuilder();
    }

    /**
     * Constructeur d'un {@link ImpactSpec}.
     *
     * <p>Chaque méthode rend le constructeur pour l'enchaînement. Thread :
     * quelconque jusqu'à {@link #build()}. Coût : négligeable.
     */
    interface Builder {

        /**
         * @param worldPoint point d'impact monde
         * @return ce constructeur
         */
        Builder point(Vec3 worldPoint);

        /**
         * @param worldNormal normale de contact monde
         * @return ce constructeur
         */
        Builder normal(Vec3 worldNormal);

        /**
         * @param joules énergie de l'impact, en joules
         * @return ce constructeur
         */
        Builder energy(float joules);

        /**
         * @param squareMeters aire de contact, en mètres carrés
         * @return ce constructeur
         */
        Builder area(float squareMeters);

        /**
         * @param kg masse effective, en kilogrammes
         * @return ce constructeur
         */
        Builder effectiveMass(float kg);

        /**
         * @param flags nature de l'impact
         * @return ce constructeur
         */
        Builder flags(ImpactFlag... flags);

        /**
         * @param src origine de l'impact
         * @return ce constructeur
         */
        Builder source(ImpactSource src);

        /**
         * {@return l'impact construit, validé et borné}
         *
         * <p>Effet : fige l'impact. Thread : quelconque. Coût : négligeable.
         * Échec : lève {@link IllegalArgumentException} si une valeur est absente
         * ou hors bornes (R-1762).
         */
        ImpactSpec build();
    }
}
