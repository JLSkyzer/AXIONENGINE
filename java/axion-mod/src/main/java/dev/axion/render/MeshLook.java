package dev.axion.render;

import dev.axion.asset.MaterialTransfer;

/**
 * L'apparence d'un mesh dans le backend vanilla : son matériau, et la couleur de ses sommets,
 * calculée une fois au chargement (ADR-122 §7).
 *
 * <p>Couleur d'un sommet = facteur d'albedo × couleur du sommet si le matériau porte
 * {@code VERTEX_COLOR}, le facteur seul sinon ; ramenée de l'espace linéaire de glTF à l'espace
 * gamma où dessine Minecraft — puissance 1/2,2 sur RVB, alpha linéaire —, puis empaquetée en ARGB,
 * huit bits par canal. Le shader la multiplie au texel, déjà en espace gamma.
 *
 * <p>L'émission, elle, est le facteur émissif seul, ramené en gamma comme l'albedo et borné à 1 :
 * une intensité supérieure ({@code KHR_materials_emissive_strength}) est plafonnée (déclaré). La
 * couleur des sommets ne la module pas (sémantique glTF).
 *
 * <p>Les tableaux sont ceux de l'instance, sans copie : le rendu les lit à chaque frame. Ils ne
 * doivent pas être modifiés.
 *
 * @param material matériau du mesh, ou le matériau par défaut si la table ne le contient pas
 * @param color couleur du facteur seul, ARGB : celle de tous les sommets sans {@code VERTEX_COLOR}
 * @param vertexColors couleur de chaque sommet, ARGB, au rang que lui donnent les indices locaux du
 *     mesh ; {@code null} sans {@code VERTEX_COLOR}
 * @param emissiveColor couleur de l'émission, ARGB opaque ; noire si le matériau n'émet pas
 * @param center centre de la boîte qui enclôt les sommets du mesh, en repère du node : la passe 4
 *     trie les surfaces translucides par sa distance (R-1580)
 */
public record MeshLook(
        MaterialTransfer.Material material, int color, int[] vertexColors, int emissiveColor, float[] center) {

    /** {@return la passe qui dessine le mesh} */
    public SurfacePass pass() {
        return SurfacePass.of(material.blendMode());
    }

    /** {@return vrai si ses deux faces se dessinent} */
    public boolean doubleSided() {
        return material.cullMode() == MaterialTransfer.CULL_NONE;
    }

    /**
     * {@return vrai s'il se dessine en pleine lumière, quelle que soit celle du monde}
     *
     * <p>Modèle sans éclairage, ou drapeau {@code FULLBRIGHT}. L'ombrage directionnel des shaders
     * d'entité demeure : approximation déclarée (ADR-122 §7).
     */
    public boolean fullbright() {
        return material.shadingModel() == MaterialTransfer.SHADING_UNLIT
                || (material.flags() & MaterialTransfer.FLAG_FULLBRIGHT) != 0;
    }

    /**
     * {@return vrai si le mesh laisse quelque chose à l'écran}
     *
     * <p>Seul un matériau découpé peut tout rejeter : quand son seuil sur l'alpha du texel dépasse
     * 1, aucun texel ne passe — pas même ceux de la texture neutre, d'alpha 1, que le shader
     * vanilla garderait.
     */
    public boolean visible() {
        return pass() != SurfacePass.CUTOUT || TexturePlan.cutoutThreshold(material) <= 1.0f;
    }

    /** {@return la texture d'albedo et sa variante, ou {@code null} : la texture neutre} */
    public TextureKey albedo() {
        return TexturePlan.albedo(material);
    }

    /** {@return vrai si le matériau émet : un dessin de plus, en passe 5} */
    public boolean emits() {
        return TexturePlan.emits(material);
    }

    /**
     * {@return la texture de l'émission et sa variante, ou {@code null} : le facteur seul, sur du
     * blanc}
     */
    public TextureKey emission() {
        return TexturePlan.emissive(material);
    }

    /** {@return la carte de normales, ou {@code null} : la normale des sommets seule} */
    public TextureKey normalMap() {
        return TexturePlan.normal(material);
    }

    /** {@return la carte ORM, ou {@code null} : les facteurs du matériau seuls} */
    public TextureKey orm() {
        return TexturePlan.orm(material);
    }

    /**
     * {@return la couleur d'un sommet, ARGB}
     *
     * @param vertex rang du sommet dans le mesh, tel que l'écrivent ses indices
     */
    public int colorOf(int vertex) {
        return vertexColors == null ? color : vertexColors[vertex];
    }
}
