package dev.axion.render;

import dev.axion.asset.GeometryTransfer;
import dev.axion.asset.MaterialTransfer;
import java.util.Map;

/**
 * Ce que le rendu reçoit d'un asset chargé : sa géométrie, ses matériaux et ses textures
 * téléversées (ADR-119, ADR-122).
 *
 * @param mesh géométrie et pose de repos
 * @param materials matériaux et textures, tels que le natif les remet
 * @param textures nom enregistré de chaque texture prête ; une texture absente est remplacée par
 *     la texture neutre
 */
public record RenderAsset(GeometryTransfer mesh, MaterialTransfer materials, Map<TextureKey, String> textures) {

    /** Copie défensive : la table est lue par le fil de rendu à chaque frame. */
    public RenderAsset {
        textures = Map.copyOf(textures);
    }

    /**
     * {@return le nom enregistré d'une texture, ou {@code null} : la texture neutre la remplace}
     *
     * @param key texture et variante, ou {@code null} pour un slot vide
     */
    public String texture(TextureKey key) {
        return key == null ? null : textures.get(key);
    }
}
