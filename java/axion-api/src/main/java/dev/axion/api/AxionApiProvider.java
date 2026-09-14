package dev.axion.api;

import java.util.Optional;
import java.util.ServiceLoader;

/**
 * Résout l'implémentation d'{@link AxionApi} sans que l'API dépende du mod.
 *
 * <p>L'implémentation (le mod) se déclare comme fournisseur de service Java
 * ({@code META-INF/services/dev.axion.api.AxionApi}) ; l'API la charge par
 * {@link ServiceLoader}. Ainsi {@code axion-api} reste publié seul, sans aucune
 * dépendance à l'implémentation (R-1750), et aucun point d'entrée mutant
 * n'apparaît dans sa surface publique.
 *
 * <p>Classe package-private : elle n'appartient pas au contrat public.
 */
final class AxionApiProvider {

    private static volatile AxionApi instance;

    private AxionApiProvider() {
        throw new AssertionError("classe utilitaire, non instanciable");
    }

    static Optional<AxionApi> get() {
        AxionApi local = instance;
        if (local == null) {
            local = ServiceLoader.load(AxionApi.class).findFirst().orElse(null);
            instance = local;
        }
        return Optional.ofNullable(local);
    }

    static AxionApi require() {
        return get().orElseThrow(() -> new IllegalStateException(
                "AXION indisponible : aucun fournisseur d'API n'est enregistré "
                        + "(le mod est absent ou désactivé)"));
    }
}
