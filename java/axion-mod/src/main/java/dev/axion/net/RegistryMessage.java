package dev.axion.net;

/**
 * {@code S2C_Registry} (21.4, ADR-150 D5) : les empreintes annoncées, puis le registre compressé par le
 * zstd du natif.
 *
 * <p>Le client recalcule les empreintes sur ce qu'il a reçu et refuse un registre qui ne les donne pas
 * (T-420) : un registre corrompu ou tronqué en chemin ne se fait pas passer pour celui du serveur.
 */
public final class RegistryMessage {

    /** Taille maximale du registre décompressé, en octets (R-1691). */
    static final int MAX_CONTENT_BYTES = 16 << 20;

    private RegistryMessage() {
        throw new AssertionError("non instanciable");
    }

    /**
     * @param content registre du serveur
     * @param compression compression des charges utiles réseau
     * @return le message, genre compris
     */
    public static byte[] encode(RegistryContent content, NetCompression compression) {
        byte[] frame = compression.compress(content.encode());
        return new WireWriter(frame.length + 32)
                .u8(MessageKinds.REGISTRY)
                .i64(content.definitionsHash())
                .i64(content.materialsHash())
                .bytes(frame)
                .toByteArray();
    }

    /**
     * @param message message reçu, genre compris
     * @param compression compression des charges utiles réseau
     * @return le registre reçu, empreintes vérifiées
     * @throws MalformedPacketException si le message est mal formé, la trame refusée, le registre
     *     invalide, ou ses empreintes différentes de celles annoncées
     */
    public static RegistryContent decode(byte[] message, NetCompression compression)
            throws MalformedPacketException {
        WireReader reader = new WireReader(message);
        MessageKinds.expect(reader, MessageKinds.REGISTRY);
        long definitionsHash = reader.i64();
        long materialsHash = reader.i64();
        byte[] frame = reader.bytes(Fragments.MAX_MESSAGE_BYTES);
        reader.expectEnd();
        RegistryContent content = RegistryContent.decode(compression.decompress(frame, MAX_CONTENT_BYTES));
        if (content.definitionsHash() != definitionsHash || content.materialsHash() != materialsHash) {
            throw new MalformedPacketException(String.format(
                    "empreintes du registre reçu %016x / %016x, annoncées %016x / %016x",
                    content.definitionsHash(), content.materialsHash(), definitionsHash, materialsHash));
        }
        return content;
    }
}
