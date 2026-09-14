package dev.axion.api;

/**
 * Fabrique de constructeurs d'{@link ImpactSpec}, fournie par l'implémentation.
 *
 * <p>Déclarée comme service Java par le mod ; l'API la charge pour honorer {@link
 * ImpactSpec#builder()} sans exposer de classe concrète (R-1750). Un mod tiers
 * n'implémente pas cette interface : il appelle {@link ImpactSpec#builder()}.
 */
@Stable
public interface ImpactSpecFactory {

    /**
     * {@return un nouveau constructeur d'impact}
     *
     * <p>Effet : alloue un constructeur. Thread : quelconque. Coût : négligeable.
     * Échec : aucun.
     */
    ImpactSpec.Builder newBuilder();
}
