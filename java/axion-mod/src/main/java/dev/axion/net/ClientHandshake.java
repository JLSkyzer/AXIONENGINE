package dev.axion.net;

/**
 * {@code C2S_Handshake}, la réponse du client au handshake du serveur (ADR-150 D4).
 *
 * <p>21.3 ne prévoyait aucune réponse ; 5.12bis exige pourtant que le serveur connaisse l'empreinte
 * déterministe du client pour choisir le mode de réplication. Le client ne dit pas les empreintes de
 * ses registres : un client distant ne lit jamais les données du serveur, et le registre part donc à
 * toute connexion distante (précision de mise en œuvre d'ADR-150).
 *
 * @param protocolVersion {@code protocol_version} du client : {@link NetProtocol#PROTOCOL_VERSION}
 * @param det empreinte de configuration déterministe du client (5.12bis)
 */
public record ClientHandshake(int protocolVersion, DetProfile det) {

    /** {@return le message, genre compris} */
    public byte[] encode() {
        return new WireWriter(24)
                .u8(MessageKinds.CLIENT_HANDSHAKE)
                .u16(protocolVersion)
                .i32(det.kernelVersion())
                .bool(det.inMatrix())
                .i64(det.profile())
                .toByteArray();
    }

    /**
     * @param message message reçu, genre compris
     * @return la réponse lue
     * @throws MalformedPacketException si le message est mal formé ou d'une autre version de protocole
     */
    public static ClientHandshake decode(byte[] message) throws MalformedPacketException {
        WireReader reader = new WireReader(message);
        MessageKinds.expect(reader, MessageKinds.CLIENT_HANDSHAKE);
        int protocol = reader.u16();
        if (protocol != NetProtocol.PROTOCOL_VERSION) {
            throw new MalformedPacketException(
                    "version de protocole " + protocol + ", " + NetProtocol.PROTOCOL_VERSION + " attendue");
        }
        int kernel = reader.i32();
        boolean inMatrix = reader.bool();
        long profile = reader.i64();
        reader.expectEnd();
        return new ClientHandshake(protocol, new DetProfile(profile, kernel, inMatrix));
    }
}
