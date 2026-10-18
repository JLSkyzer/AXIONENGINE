package dev.axion.net;

/**
 * Un paquet reçu ne passe pas la validation (R-1650).
 *
 * <p>Exception vérifiée : le compilateur oblige chaque lecteur à la traiter, et le récepteur du
 * canal l'attrape pour ignorer le paquet et le journaliser ({@code E-5001}). Elle ne remonte jamais
 * jusqu'au pipeline Netty.
 */
public final class MalformedPacketException extends Exception {

    private static final long serialVersionUID = 1L;

    /**
     * @param reason ce qui ne va pas, lisible dans le journal
     */
    public MalformedPacketException(String reason) {
        super(reason, null, false, false);
    }

    /**
     * @param where ce que le récepteur lisait, par exemple « registre en jeu »
     * @return la ligne de journal du paquet ignoré (R-1650)
     */
    public String report(String where) {
        return "E-5001 paquet ignoré (" + where + ") : " + getMessage();
    }
}
