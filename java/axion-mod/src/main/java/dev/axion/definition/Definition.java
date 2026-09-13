package dev.axion.definition;

import com.google.gson.JsonObject;
import java.util.Objects;

/**
 * Definition acceptée.
 *
 * <p>Seuls les champs obligatoires du schéma 1 sont décodés ici. Le reste du
 * document est gardé tel que l'auteur l'a écrit : l'absence d'un champ
 * optionnel est une information — le système consommateur applique alors son
 * défaut (ADR-109, point 6) — qu'un défaut rempli à la lecture effacerait.
 *
 * @param id identifiant, {@code <ns>:<chemin>} sous {@code axion/definitions}
 * @param location ressource d'origine
 * @param schema version de schéma, {@code 1}
 * @param asset modèle désigné, sous la forme {@code <ns>:axion/<chemin>}
 * @param kind genre d'assembly
 * @param root document complet
 * @param sha256 empreinte du fichier, en hexadécimal
 */
public record Definition(
        String id,
        String location,
        int schema,
        String asset,
        AssemblyKind kind,
        JsonObject root,
        String sha256) {

    /** Garde une copie : un arbre Gson est modifiable, une definition acceptée non. */
    public Definition {
        Objects.requireNonNull(id);
        Objects.requireNonNull(location);
        Objects.requireNonNull(asset);
        Objects.requireNonNull(kind);
        Objects.requireNonNull(sha256);
        root = root.deepCopy();
    }

    /** {@return une copie du document} */
    @Override
    public JsonObject root() {
        return root.deepCopy();
    }
}
