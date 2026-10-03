package dev.axion.render;

import dev.axion.asset.GeometryTransfer;
import dev.axion.asset.MaterialTransfer;
import java.util.List;
import java.util.Map;

/**
 * Ce que le rendu reçoit d'un asset chargé : sa géométrie, l'apparence de chacun de ses meshes et
 * ses textures téléversées (ADR-119, ADR-122).
 *
 * <p>Construit sur le fil de rendu, une fois les textures téléversées : chaque mesh y reçoit la
 * texture d'albedo qu'il désigne, ou la texture neutre si elle manque — refusée, ou perdue au
 * téléversement. Lu ensuite à chaque frame, sans calcul.
 */
public final class RenderAsset {

    private final GeometryTransfer mesh;
    private final MaterialTransfer materials;
    private final List<MeshLook> looks;
    private final Map<TextureKey, TextureBinding> textures;

    /** Texture d'albedo liée de chaque mesh ; {@code null} pour la texture neutre. */
    private final TextureBinding[] albedos;

    /**
     * Assemble un asset prêt à dessiner.
     *
     * @param mesh géométrie et pose de repos
     * @param materials matériaux et textures, tels que le natif les remet
     * @param looks apparence de chaque mesh, dans l'ordre des meshes
     * @param textures textures téléversées, par clé ; une texture absente est remplacée par la
     *     texture neutre
     * @throws IllegalArgumentException si les apparences ne sont pas une par mesh
     */
    public RenderAsset(
            GeometryTransfer mesh,
            MaterialTransfer materials,
            List<MeshLook> looks,
            Map<TextureKey, TextureBinding> textures) {
        if (looks.size() != mesh.meshes().size()) {
            throw new IllegalArgumentException(
                    looks.size() + " apparences pour " + mesh.meshes().size() + " meshes");
        }
        this.mesh = mesh;
        this.materials = materials;
        this.looks = List.copyOf(looks);
        this.textures = Map.copyOf(textures);
        this.albedos = new TextureBinding[this.looks.size()];
        for (int rank = 0; rank < albedos.length; rank++) {
            albedos[rank] = texture(this.looks.get(rank).albedo());
        }
    }

    /** {@return la géométrie et la pose de repos} */
    public GeometryTransfer mesh() {
        return mesh;
    }

    /** {@return les matériaux et les textures, tels que le natif les remet} */
    public MaterialTransfer materials() {
        return materials;
    }

    /**
     * {@return l'apparence d'un mesh}
     *
     * @param rank rang du mesh
     */
    public MeshLook look(int rank) {
        return looks.get(rank);
    }

    /**
     * {@return la texture d'albedo liée d'un mesh, ou {@code null} : la texture neutre}
     *
     * @param rank rang du mesh
     */
    public TextureBinding albedo(int rank) {
        return albedos[rank];
    }

    /**
     * {@return une texture téléversée, ou {@code null} : la texture neutre la remplace}
     *
     * @param key texture et variante, ou {@code null} pour un slot vide
     */
    public TextureBinding texture(TextureKey key) {
        return key == null ? null : textures.get(key);
    }
}
