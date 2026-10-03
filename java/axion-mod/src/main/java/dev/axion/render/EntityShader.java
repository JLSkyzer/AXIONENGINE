package dev.axion.render;

/**
 * Shader d'entité vanilla qui dessine une surface (ADR-122 §7, T-273).
 *
 * <p>Le backend vanilla ne compile aucun shader (R-741) : il compose ses types de rendu avec ceux
 * de Minecraft, ceux qu'un shaderpack remplace (ADR-122). Une surface opaque passe par
 * {@code entity_solid}, qui ne rejette aucun texel, quelles que soient ses faces ; une surface
 * découpée par {@code entity_cutout}, ou {@code entity_cutout_no_cull} si ses deux faces se
 * dessinent. Les deux shaders de découpe rejettent un texel d'alpha inférieur à 0,1 avant la
 * couleur de sommet. Une surface translucide passe par {@code entity_translucent_cull}, ou
 * {@code entity_translucent} si ses deux faces se dessinent. Les faces elles-mêmes sont l'affaire
 * de l'état de culling du type de rendu.
 *
 * <p>L'émission d'une surface, passe 5, se dessine par-dessus avec {@code eyes} : additif, sans
 * lightmap, sans rejet de texel.
 */
public enum EntityShader {

    /** {@code entity_solid}. */
    SOLID("entity_solid"),

    /** {@code entity_cutout}. */
    CUTOUT("entity_cutout"),

    /** {@code entity_cutout_no_cull}. */
    CUTOUT_NO_CULL("entity_cutout_no_cull"),

    /** {@code entity_translucent_cull}. */
    TRANSLUCENT_CULL("entity_translucent_cull"),

    /** {@code entity_translucent}. */
    TRANSLUCENT("entity_translucent"),

    /** {@code eyes} : l'émission, passe 5. */
    EYES("eyes");

    private final String vanillaName;

    EntityShader(String vanillaName) {
        this.vanillaName = vanillaName;
    }

    /** {@return le nom du type de rendu vanilla qui emploie ce shader} */
    public String vanillaName() {
        return vanillaName;
    }

    /**
     * {@return le shader qui dessine une surface}
     *
     * @param pass passe de la surface
     * @param doubleSided vrai si ses deux faces se dessinent
     */
    public static EntityShader of(SurfacePass pass, boolean doubleSided) {
        return switch (pass) {
            case OPAQUE -> SOLID;
            case CUTOUT -> doubleSided ? CUTOUT_NO_CULL : CUTOUT;
            case TRANSLUCENT -> doubleSided ? TRANSLUCENT : TRANSLUCENT_CULL;
        };
    }
}
