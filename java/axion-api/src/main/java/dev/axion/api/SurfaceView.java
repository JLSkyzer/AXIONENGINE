package dev.axion.api;

/**
 * Vue des états de surface d'une assembly (§23.2, C-47).
 *
 * <p>Les lectures rendent un canal dans {@code [0, 1]} par part. Thread :
 * autoritatif ou rendu. Coût : négligeable. Échec : aucun.
 */
@Stable
public interface SurfaceView {

    /** {@return le niveau de rayure d'une part, dans {@code [0, 1]}}. */
    float scratch(String part);

    /** {@return le niveau de salissure d'une part, dans {@code [0, 1]}}. */
    float soil(String part);

    /** {@return le niveau de brûlure d'une part, dans {@code [0, 1]}}. */
    float burn(String part);

    /** {@return le niveau de rouille d'une part, dans {@code [0, 1]}}. */
    float rust(String part);

    /**
     * Nettoie les états de surface d'une part.
     *
     * <p>Effet : remet les canaux d'usure à zéro. Thread : autoritatif (serveur)
     * uniquement ; lève {@link IllegalStateException} ailleurs (R-1755). Coût :
     * négligeable. Échec : sans effet si la part est inconnue.
     *
     * @param part nom de la part à nettoyer
     */
    void clean(String part);
}
