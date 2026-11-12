package dev.axion.api.content;

import dev.axion.api.Experimental;
import java.util.Optional;
import java.util.Set;

/**
 * Ce qu'AXION a fait du contenu enregistré : quelles definitions sont chargées, et pourquoi une autre a été refusée
 * (ADR-166 D2).
 *
 * <p>En lecture seule : chaque méthode lit un instantané du registre des definitions. Thread : quelconque. Coût : une
 * lecture. Échec : aucun ; une definition inconnue n'est ni chargée ni refusée.
 *
 * <p>Un identifiant de definition s'écrit {@code <modId>:<chemin>} : le fichier {@code axion/definitions/crate.json}
 * du mod {@code example_addon} est la definition {@code example_addon:crate}.
 */
@Experimental
public interface ContentQuery {

    /**
     * Le contenu vu d'un fournisseur qui ne le suit pas — un double de {@link dev.axion.api.AxionApi} dans un test :
     * rien de chargé, rien de refusé.
     */
    ContentQuery NONE = new ContentQuery() {
        @Override
        public boolean isLoaded(String definitionId) {
            return false;
        }

        @Override
        public Optional<String> refusal(String definitionId) {
            return Optional.empty();
        }

        @Override
        public Set<String> definitions() {
            return Set.of();
        }
    };

    /**
     * {@return vrai si la definition est chargée et utilisable}
     *
     * @param definitionId identifiant, {@code <modId>:<chemin>}
     */
    boolean isLoaded(String definitionId);

    /**
     * {@return la cause du refus de la definition, telle que le journal du jeu l'écrit, ou vide si elle n'a pas été
     * refusée}
     *
     * @param definitionId identifiant, {@code <modId>:<chemin>}
     */
    Optional<String> refusal(String definitionId);

    /** {@return les identifiants des definitions chargées} */
    Set<String> definitions();
}
