package dev.axion.net;

/**
 * Le premier octet d'un message AXION, une fois ses fragments réunis (ADR-150 D1) : ce qu'il porte.
 *
 * <p>Un genre inconnu est une faute de paquet (R-1650) : la version de protocole étant exigée égale
 * des deux côtés (R-1630), aucune extrémité légitime n'en envoie.
 */
public final class MessageKinds {

    /** {@code S2C_Handshake} (21.3, ADR-150 D4). */
    public static final int SERVER_HANDSHAKE = 1;

    /** {@code C2S_Handshake}, ajouté par ADR-150 D4. */
    public static final int CLIENT_HANDSHAKE = 2;

    /** {@code S2C_Registry} (21.4, ADR-150 D5). */
    public static final int REGISTRY = 3;

    /** {@code C2S_RegistryAck}, ajouté par la tranche 3 d'ADR-150 : le registre que le client tient. */
    public static final int REGISTRY_ACK = 4;

    private MessageKinds() {
        throw new AssertionError("classe de constantes, non instanciable");
    }

    /**
     * Lit le genre d'un message et vérifie que c'est celui qu'attend le lecteur.
     *
     * @param reader lecteur placé au début du message
     * @param expected genre attendu
     * @throws MalformedPacketException si le genre diffère
     */
    static void expect(WireReader reader, int expected) throws MalformedPacketException {
        int kind = reader.u8();
        if (kind != expected) {
            throw new MalformedPacketException("message de genre " + kind + ", " + expected + " attendu");
        }
    }
}
