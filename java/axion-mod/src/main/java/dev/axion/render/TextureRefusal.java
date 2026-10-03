package dev.axion.render;

/**
 * Une texture refusée : la texture neutre la remplace, et la raison est rapportée une fois
 * (ADR-122 §7).
 */
public final class TextureRefusal extends Exception {

    private static final long serialVersionUID = 1L;

    /**
     * Crée un refus.
     *
     * @param reason raison, telle qu'elle sera rapportée
     */
    public TextureRefusal(String reason) {
        super(reason);
    }

    /**
     * Crée un refus causé par une erreur de lecture ou de décodage.
     *
     * @param reason raison, telle qu'elle sera rapportée
     * @param cause erreur d'origine
     */
    public TextureRefusal(String reason, Throwable cause) {
        super(reason, cause);
    }
}
