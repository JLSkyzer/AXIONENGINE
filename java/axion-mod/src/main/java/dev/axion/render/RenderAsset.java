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
 * téléversement —, avec la place qu'y occupe son image : toute la texture, ou une tuile de l'atlas
 * de l'asset (T-c). Lu ensuite à chaque frame, sans calcul.
 *
 * <p>L'émission suit la fiche C-26 : sans texture d'émissive, le facteur seul, sur du blanc
 * (sémantique glTF) ; texture d'émissive manquante, la neutre est noire — le mesh n'émet pas,
 * plutôt que de rayonner partout, ou dans les trous de sa découpe.
 */
public final class RenderAsset {

    private final GeometryTransfer mesh;
    private final MaterialTransfer materials;
    private final List<MeshLook> looks;
    private final Map<TextureKey, TextureRegion> textures;

    /** Texture d'albedo de chaque mesh ; {@code null} pour la texture neutre. */
    private final TextureRegion[] albedos;

    /** Texture d'émission de chaque mesh ; {@code null} pour du blanc. */
    private final TextureRegion[] emissions;

    /** Vrai pour un mesh qui émet, et dont l'émission est là. */
    private final boolean[] emitting;

    /**
     * Assemble un asset prêt à dessiner.
     *
     * @param mesh géométrie et pose de repos
     * @param materials matériaux et textures, tels que le natif les remet
     * @param looks apparence de chaque mesh, dans l'ordre des meshes
     * @param textures textures téléversées, par clé, et la place qu'y occupe chaque image ; une
     *     texture absente est remplacée par la texture neutre
     * @throws IllegalArgumentException si les apparences ne sont pas une par mesh
     */
    public RenderAsset(
            GeometryTransfer mesh,
            MaterialTransfer materials,
            List<MeshLook> looks,
            Map<TextureKey, TextureRegion> textures) {
        if (looks.size() != mesh.meshes().size()) {
            throw new IllegalArgumentException(
                    looks.size() + " apparences pour " + mesh.meshes().size() + " meshes");
        }
        this.mesh = mesh;
        this.materials = materials;
        this.looks = List.copyOf(looks);
        this.textures = Map.copyOf(textures);
        this.albedos = new TextureRegion[this.looks.size()];
        this.emissions = new TextureRegion[this.looks.size()];
        this.emitting = new boolean[this.looks.size()];
        for (int rank = 0; rank < albedos.length; rank++) {
            MeshLook look = this.looks.get(rank);
            albedos[rank] = texture(look.albedo());
            TextureKey emission = look.emission();
            emissions[rank] = texture(emission);
            emitting[rank] = look.emits() && (emission == null || emissions[rank] != null);
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
     * {@return la texture d'albedo d'un mesh, ou {@code null} : la texture neutre}
     *
     * @param rank rang du mesh
     */
    public TextureRegion albedo(int rank) {
        return albedos[rank];
    }

    /**
     * {@return vrai si un mesh émet, et que son émission est là}
     *
     * @param rank rang du mesh
     */
    public boolean emits(int rank) {
        return emitting[rank];
    }

    /**
     * {@return la texture d'émission d'un mesh qui émet, ou {@code null} : du blanc}
     *
     * @param rank rang du mesh
     */
    public TextureRegion emission(int rank) {
        return emissions[rank];
    }

    /**
     * {@return une texture téléversée, ou {@code null} : la texture neutre la remplace}
     *
     * @param key texture et variante, ou {@code null} pour un slot vide
     */
    public TextureRegion texture(TextureKey key) {
        return key == null ? null : textures.get(key);
    }
}
