package dev.axion.net;

/**
 * {@code C2S_RegistryAck}, ajouté par la tranche 3 d'ADR-150 : les empreintes du registre que le client
 * tient, renvoyées en réponse au dernier fragment de {@code S2C_Registry}.
 *
 * <p>Le serveur bâtit la liste des paquets de connexion dès l'arrivée du client, bien avant son entrée en
 * jeu : un rechargement des données entre les deux lui ferait tenir un registre périmé. À l'entrée en jeu,
 * le serveur compare cet acquittement à son registre courant et renvoie celui-ci s'ils diffèrent — ou si
 * le client n'a rien pu appliquer, ce que dit {@link #NONE}.
 *
 * @param definitionsHash empreinte des definitions et des groupes de collision tenus
 * @param materialsHash empreinte des matériaux physiques tenus
 */
public record RegistryAck(long definitionsHash, long materialsHash) {

    /** Aucun registre tenu : celui d'un registre vide. */
    public static final RegistryAck NONE =
            new RegistryAck(RegistryContent.EMPTY_DIGEST, RegistryContent.EMPTY_DIGEST);

    /**
     * @param registry registre appliqué
     * @return l'acquittement de ce registre
     */
    public static RegistryAck of(RegistryContent registry) {
        return new RegistryAck(registry.definitionsHash(), registry.materialsHash());
    }

    /**
     * @param registry registre à comparer
     * @return vrai si l'acquittement est celui de ce registre
     */
    public boolean matches(RegistryContent registry) {
        return definitionsHash == registry.definitionsHash() && materialsHash == registry.materialsHash();
    }

    /** {@return le message, genre compris} */
    public byte[] encode() {
        return new WireWriter(17)
                .u8(MessageKinds.REGISTRY_ACK)
                .i64(definitionsHash)
                .i64(materialsHash)
                .toByteArray();
    }

    /**
     * @param message message reçu, genre compris
     * @return l'acquittement lu
     * @throws MalformedPacketException si le message est mal formé
     */
    public static RegistryAck decode(byte[] message) throws MalformedPacketException {
        WireReader reader = new WireReader(message);
        MessageKinds.expect(reader, MessageKinds.REGISTRY_ACK);
        long definitions = reader.i64();
        long materials = reader.i64();
        reader.expectEnd();
        return new RegistryAck(definitions, materials);
    }
}
