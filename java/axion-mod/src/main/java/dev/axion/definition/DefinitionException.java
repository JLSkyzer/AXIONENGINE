package dev.axion.definition;

import java.util.List;

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

    /** {@code E-3050} : plafond d'assembly dépassé (R-190). */
    public static final int LIMIT = -3050;

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

    /**
     * {@return un refus qui rassemble toutes les fautes d'une definition}
     *
     * <p>Le code et le chemin sont ceux de la première faute ; le message les
     * énumère toutes, pour qu'un seul rechargement suffise à les corriger.
     *
     * @param issues fautes relevées, au moins une
     */
    static DefinitionException fromIssues(List<DefinitionChecker.Issue> issues) {
        DefinitionChecker.Issue first = issues.get(0);
        StringBuilder message = new StringBuilder(first.message());
        if (issues.size() > 1) {
            message.append(" ; et ").append(issues.size() - 1).append(" autre(s) faute(s) : ");
            for (int index = 1; index < issues.size(); index++) {
                DefinitionChecker.Issue issue = issues.get(index);
                if (index > 1) {
                    message.append(" ; ");
                }
                message.append(issue.path()).append(" : ").append(issue.message());
            }
        }
        return new DefinitionException(first.code(), first.path(), message.toString());
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
