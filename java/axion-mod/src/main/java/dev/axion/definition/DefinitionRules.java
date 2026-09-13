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
 */
public record DefinitionRules(boolean vehiclesEnabled, Predicate<String> assetExists) {

    /** Refuse une règle absente. */
    public DefinitionRules {
        Objects.requireNonNull(assetExists);
    }
}
