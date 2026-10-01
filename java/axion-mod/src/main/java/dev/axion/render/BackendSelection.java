package dev.axion.render;

/**
 * Choix du backend de rendu (R-1490, ADR-118) — logique pure, sans Forge.
 *
 * <p>Deux backends (§19.2) : {@link Kind#NATIVE} (pipeline OpenGL dédié, C-60) et
 * {@link Kind#VANILLA} (rendu à travers Minecraft, C-61). Le backend vanilla est le repli
 * universel (R-1491) et il est <b>obligatoire</b> sous shaderpack (fiche 5.48) : un
 * shaderpack écrit son propre G-buffer, que le backend natif ne peut pas alimenter (§19.1).
 */
public final class BackendSelection {

    /** Backend effectivement retenu. */
    public enum Kind {
        /** Pipeline OpenGL dédié (C-60). */
        NATIVE,
        /** Rendu à travers {@code RenderType}/{@code VertexConsumer} de Minecraft (C-61). */
        VANILLA
    }

    /**
     * Résultat d'un choix.
     *
     * @param kind backend retenu
     * @param reason pourquoi, tel que le journal l'affichera (R-1493 : entrée unique au
     *     démarrage du backend)
     */
    public record Selection(Kind kind, String reason) {}

    private BackendSelection() {}

    /**
     * Choisit le backend.
     *
     * @param requested valeur de {@code render.backend} : {@code auto}, {@code native} ou
     *     {@code vanilla} ; toute autre valeur est traitée comme {@code auto}
     * @param nativeAvailable vrai si le backend natif est livré et initialisable
     * @param shaderpackActive vrai si un mod de shaders est actif
     * @return le backend retenu et sa raison
     */
    public static Selection select(String requested, boolean nativeAvailable, boolean shaderpackActive) {
        if (shaderpackActive) {
            return new Selection(Kind.VANILLA, "mod de shaders actif : backend vanilla obligatoire");
        }
        if ("vanilla".equals(requested)) {
            return new Selection(Kind.VANILLA, "render.backend = vanilla");
        }
        String asked = "native".equals(requested) ? "native" : "auto";
        if (nativeAvailable) {
            return new Selection(Kind.NATIVE, "render.backend = " + asked);
        }
        return new Selection(
                Kind.VANILLA, "render.backend = " + asked + ", backend natif indisponible : repli vanilla");
    }
}
