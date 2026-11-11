package dev.axion.api.content;

import dev.axion.api.Experimental;

/**
 * Les familles de contenu qu'un mod enregistre auprès d'AXION (ADR-162 D3).
 *
 * <p>Chacune a son dossier sous le dossier qu'un mod enregistre ({@link ContentRegistrar#registerFolder}) :
 * {@code definitions/}, {@code models/}, {@code physics_materials/}, {@code collision_groups/},
 * {@code block_materials/}, {@code tires/}. AXION les résout dans cet ordre-là, inversé pour les modèles : une
 * definition nomme son modèle, ses groupes, ses matériaux et ses pneus, qui doivent donc être connus avant
 * elle.
 */
@Experimental
public enum ContentKind {

    /** Les modèles 3D (GLB, OBJ, STL), avec leurs fichiers d'options et leurs fichiers voisins. */
    MODEL("models"),
    /** Les matériaux physiques (DM-07, ADR-145). */
    PHYSICS_MATERIAL("physics_materials"),
    /** Les groupes de collision (R-980, ADR-132). */
    COLLISION_GROUP("collision_groups"),
    /** Les correspondances bloc vers matériau physique (§11.4, ADR-145). */
    BLOCK_MATERIAL("block_materials"),
    /** Les profils de pneu (ADR-159). */
    TIRE("tires"),
    /** Les definitions (C-27). */
    DEFINITION("definitions");

    private final String folder;

    ContentKind(String folder) {
        this.folder = folder;
    }

    /** {@return le nom du dossier de cette famille, sous le dossier qu'un mod enregistre} */
    public String folder() {
        return folder;
    }
}
