package dev.axion.api;

import java.util.ServiceLoader;

/**
 * Fournit le constructeur d'{@link ImpactSpec} porté par l'implémentation.
 *
 * <p>Même mécanisme que {@link AxionApiProvider} : l'implémentation déclare une
 * fabrique comme fournisseur de service, l'API la charge par {@link
 * ServiceLoader}. {@code axion-api} n'expose ainsi aucune classe concrète
 * d'impact et ne dépend pas du mod (R-1750).
 *
 * <p>Classe package-private : elle n'appartient pas au contrat public.
 */
final class ImpactSpecProvider {

    private static volatile ImpactSpecFactory factory;

    private ImpactSpecProvider() {
        throw new AssertionError("classe utilitaire, non instanciable");
    }

    static ImpactSpec.Builder newBuilder() {
        ImpactSpecFactory local = factory;
        if (local == null) {
            local = ServiceLoader.load(ImpactSpecFactory.class).findFirst().orElseThrow(
                    () -> new IllegalStateException(
                            "AXION indisponible : aucune fabrique d'impact enregistrée"));
            factory = local;
        }
        return local.newBuilder();
    }
}
