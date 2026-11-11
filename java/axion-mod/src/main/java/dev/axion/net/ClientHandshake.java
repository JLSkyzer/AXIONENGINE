package dev.axion.net;

import java.util.ArrayList;
import java.util.List;

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
 * @param versions la version que le client a de chaque addon que le serveur exige, dans l'ordre du handshake du
 *     serveur ; vide pour un addon qu'il n'a pas (ADR-165 D2)
 */
public record ClientHandshake(int protocolVersion, DetProfile det, List<String> versions) {

    /** Garde une copie figée de la liste. */
    public ClientHandshake {
        versions = List.copyOf(versions);
    }

    /** {@return le message, genre compris} */
    public byte[] encode() {
        WireWriter writer = new WireWriter(24 + 16 * versions.size())
                .u8(MessageKinds.CLIENT_HANDSHAKE)
                .u16(protocolVersion)
                .i32(det.kernelVersion())
                .bool(det.inMatrix())
                .i64(det.profile())
                .u16(versions.size());
        for (String version : versions) {
            writer.utf8(version);
        }
        return writer.toByteArray();
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
        int count = reader.u16();
        if (count > RequiredMod.MAX_MODS) {
            throw new MalformedPacketException(count + " versions d'addons, " + RequiredMod.MAX_MODS + " au plus");
        }
        List<String> versions = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            versions.add(reader.utf8(RequiredMod.MAX_VERSION_BYTES));
        }
        reader.expectEnd();
        return new ClientHandshake(protocol, new DetProfile(profile, kernel, inMatrix), versions);
    }
}
