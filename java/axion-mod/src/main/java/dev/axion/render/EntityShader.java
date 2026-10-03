package dev.axion.render;

/**
 * Shader d'entité vanilla qui dessine une surface des passes 1 et 2 (ADR-122 §7, T-273).
 *
 * <p>Le backend vanilla ne compile aucun shader (R-741) : il compose ses types de rendu avec ceux
 * de Minecraft, qu'un shaderpack sait remplacer. Une surface opaque passe par
 * {@code entity_solid}, qui ne rejette aucun texel, quelles que soient ses faces ; une surface
 * découpée par {@code entity_cutout}, ou {@code entity_cutout_no_cull} si ses deux faces se
 * dessinent. Les deux shaders de découpe rejettent un texel d'alpha inférieur à 0,1 avant la
 * couleur de sommet. Les faces elles-mêmes sont l'affaire de l'état de culling du type de rendu.
 */
public enum EntityShader {

    /** {@code entity_solid}. */
    SOLID("entity_solid"),

    /** {@code entity_cutout}. */
    CUTOUT("entity_cutout"),

    /** {@code entity_cutout_no_cull}. */
    CUTOUT_NO_CULL("entity_cutout_no_cull");

    private final String vanillaName;

    EntityShader(String vanillaName) {
        this.vanillaName = vanillaName;
    }

    /** {@return le nom du type de rendu vanilla qui emploie ce shader} */
    public String vanillaName() {
        return vanillaName;
    }

    /**
     * {@return le shader d'une surface des passes 1 et 2}
     *
     * @param pass passe de la surface
     * @param doubleSided vrai si ses deux faces se dessinent
     * @throws IllegalArgumentException pour une surface translucide : la passe 4 a ses propres
     *     shaders, {@code entity_translucent_cull} et {@code entity_translucent}
     */
    public static EntityShader of(SurfacePass pass, boolean doubleSided) {
        return switch (pass) {
            case OPAQUE -> SOLID;
            case CUTOUT -> doubleSided ? CUTOUT_NO_CULL : CUTOUT;
            case TRANSLUCENT -> throw new IllegalArgumentException(
                    "surface translucide : hors des passes 1 et 2");
        };
    }
}
