package dev.axion.api.attach;

import dev.axion.api.Experimental;

/**
 * Ce qu'est devenue une demande d'attache (ADR-171 D3, D4).
 *
 * @param status l'issue
 * @param message ce qui l'a refusée, en clair ; vide si elle est posée
 */
@Experimental
public record AttachResult(Status status, String message) {

    /** L'issue d'une demande. */
    public enum Status {
        /** Posée : le joint existe dès le tick suivant. */
        ATTACHED,
        /** Un socket nommé n'existe pas. */
        UNKNOWN_SOCKET,
        /** Aucun type commun, une forme que l'autre n'épouse pas, ou un type imposé que l'un n'accepte pas. */
        INCOMPATIBLE,
        /** Un des deux sockets porte déjà une attache. */
        SOCKET_BUSY,
        /** Les deux assemblies sont déjà reliées ({@code E-8030}, R-690). */
        CYCLE,
        /** L'ensemble dépasserait {@code attachment.max_chain} attaches (R-691). */
        CHAIN,
        /** Les deux assemblies ne sont pas dans la même dimension, ou c'est la même. */
        UNREACHABLE,
        /** Le socket ne s'ancre pas au monde. */
        NOT_ANCHORABLE,
        /** Un aimant dont la variable de commande est éteinte. */
        MAGNET_OFF,
        /** Un écouteur l'a refusée ({@link AttachmentListener#allow}). */
        REFUSED
    }

    /** {@return vrai si l'attache est posée} */
    public boolean attached() {
        return status == Status.ATTACHED;
    }

    /** {@return une attache posée} */
    public static AttachResult ok() {
        return new AttachResult(Status.ATTACHED, "");
    }
}
