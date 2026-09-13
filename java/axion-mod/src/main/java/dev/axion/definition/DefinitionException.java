package dev.axion.definition;

/**
 * Refus d'une definition (R-580).
 *
 * <p>Porte le code de l'ANNEXE A.1 et le chemin JSON de la faute : un auteur
 * qui lit « E-7001 » sans savoir où chercher dans un fichier de trois cents
 * lignes n'a rien appris.
 */
public final class DefinitionException extends Exception {

    private static final long serialVersionUID = 1L;

    /** {@code E-7001} : definition invalide. */
    public static final int INVALID = -7001;

    /** {@code E-7003} : schéma inconnu (R-1780). */
    public static final int UNKNOWN_SCHEMA = -7003;

    private final int code;
    private final String path;

    private DefinitionException(int code, String path, String message) {
        super(message);
        this.code = code;
        this.path = path;
    }

    /**
     * {@return un refus {@code E-7001}}
     *
     * @param path chemin JSON de la faute, {@code $} pour la racine
     * @param message ce qui ne va pas, pour l'auteur
     */
    static DefinitionException invalid(String path, String message) {
        return new DefinitionException(INVALID, path, message);
    }

    /**
     * {@return un refus {@code E-7003}}
     *
     * @param path chemin JSON de la faute
     * @param message ce qui ne va pas, pour l'auteur
     */
    static DefinitionException unknownSchema(String path, String message) {
        return new DefinitionException(UNKNOWN_SCHEMA, path, message);
    }

    /** {@return le code négatif de l'annexe} */
    public int code() {
        return code;
    }

    /** {@return le code tel que l'annexe l'écrit, par exemple {@code E-7001}} */
    public String codeName() {
        return "E-" + (-code);
    }

    /** {@return le chemin JSON de la faute} */
    public String path() {
        return path;
    }
}
