package dev.axion.net;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code C2S_Ack} (21.4, ADR-150 D1) : le dernier snapshot que le client a reçu, et les entités dont il
 * redemande une entrée complète — un delta dont il ne tenait pas la base.
 *
 * <pre>
 * genre u8 = 6 | dernière séquence varuint | n varuint | n handles varuint
 * </pre>
 *
 * <p>Minecraft passe par TCP : recevoir un snapshot, c'est avoir reçu tous ceux qui l'ont précédé. Les
 * empreintes de champ de 21.4 viennent avec la déformation, en M6.
 *
 * @param lastSequence séquence du dernier snapshot reçu, {@code 0} si aucun
 * @param needFull handles des entités à renvoyer en entier
 */
public record AckMessage(int lastSequence, List<Integer> needFull) {

    /** Nombre maximal d'entités redemandées par acquittement (R-1691). */
    static final int MAX_FULL_REQUESTS = 64;

    /** Copie la liste. */
    public AckMessage {
        needFull = List.copyOf(needFull);
    }

    /** {@return le message, genre compris} */
    public byte[] encode() {
        WireWriter writer = new WireWriter(8 + 5 * needFull.size())
                .u8(MessageKinds.ACK)
                .varUInt(lastSequence)
                .varUInt(needFull.size());
        needFull.forEach(writer::varUInt);
        return writer.toByteArray();
    }

    /**
     * @param message message reçu, genre compris
     * @return l'acquittement lu
     * @throws MalformedPacketException si le message est mal formé ou redemande trop d'entités
     */
    public static AckMessage decode(byte[] message) throws MalformedPacketException {
        WireReader reader = new WireReader(message);
        MessageKinds.expect(reader, MessageKinds.ACK);
        int last = reader.varUInt();
        int count = reader.varUInt();
        if (count > MAX_FULL_REQUESTS) {
            throw new MalformedPacketException(count + " entités redemandées, au-delà de " + MAX_FULL_REQUESTS);
        }
        List<Integer> handles = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            handles.add(reader.varUInt());
        }
        reader.expectEnd();
        return new AckMessage(last, handles);
    }
}
