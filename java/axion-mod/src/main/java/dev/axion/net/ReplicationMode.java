package dev.axion.net;

/**
 * Mode de réplication de la déformation, choisi par le serveur au handshake (5.12bis, ADR-150 D4).
 *
 * <p>En M4, il ne change rien de visible : la déformation vient en M6. Il est décidé dès la connexion,
 * journalisé une fois et affiché par {@code /axion status}, pour que M6 trouve la négociation faite.
 */
public enum ReplicationMode {

    /** Les impacts sont rejoués localement (défaut). */
    RECONSTRUCT,

    /**
     * Le serveur envoie des instantanés de champ quantifiés. Aucune fonctionnalité n'est retirée
     * (R-514) : seul le coût réseau augmente.
     */
    SNAPSHOT;

    /**
     * Décide du mode à partir des deux empreintes : la règle de {@code ax_det::negotiate}, gardée en
     * M4 (ADR-150 D4). Le repli est préventif : il suffit d'une extrémité hors de la matrice validée,
     * ou de deux empreintes différentes.
     *
     * @param server empreinte du serveur
     * @param client empreinte du client
     * @return {@link #RECONSTRUCT} si les deux empreintes sont égales et validées, {@link #SNAPSHOT}
     *     sinon
     */
    public static ReplicationMode negotiate(DetProfile server, DetProfile client) {
        boolean same = server.profile() == client.profile();
        return same && server.inMatrix() && client.inMatrix() ? RECONSTRUCT : SNAPSHOT;
    }
}
