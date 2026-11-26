package dev.axion.net;

/**
 * {@code C2S_Carried} (ADR-185 D1, protocole 21) : la place que le joueur porté se connaît sur l'assembly, qu'il envoie à
 * la fin de chaque tick où il est porté. Le serveur la vérifie contre la position qu'il a jugée avant de la diffuser aux
 * autres (D2).
 *
 * <pre>
 * genre u8 | identifiant réseau de l'assembly varuint | corps u8 | place 3 × varint zigzag au 1/256 de bloc
 * </pre>
 *
 * @param place l'assembly ({@code entity}), le corps et la place dans son repère, quantifiée comme dans les snapshots
 */
public record CarriedMessage(AssemblyState.CarriedEntity place) {

    /**
     * Quantifie la place du joueur porté.
     *
     * @param assembly identifiant réseau de l'assembly
     * @param body rang du corps
     * @param local place dans le repère du corps, en blocs
     * @return le message
     * @throws IllegalArgumentException si l'identifiant, le corps ou la place ne se disent pas
     */
    public static CarriedMessage of(int assembly, int body, double[] local) {
        return new CarriedMessage(AssemblyState.CarriedEntity.of(assembly, body, local));
    }

    /** {@return le message, genre compris} */
    public byte[] encode() {
        return new WireWriter(16).u8(MessageKinds.CARRIED).varUInt(place.entity()).u8(place.body())
                .varInt(place.x()).varInt(place.y()).varInt(place.z()).toByteArray();
    }

    /**
     * @param message message reçu, genre compris
     * @return la place lue
     * @throws MalformedPacketException si le message est mal formé (R-1650)
     */
    public static CarriedMessage decode(byte[] message) throws MalformedPacketException {
        WireReader reader = new WireReader(message);
        MessageKinds.expect(reader, MessageKinds.CARRIED);
        int assembly = reader.varUInt();
        int body = reader.u8();
        int x = reader.varInt();
        int y = reader.varInt();
        int z = reader.varInt();
        reader.expectEnd();
        try {
            return new CarriedMessage(new AssemblyState.CarriedEntity(assembly, body, x, y, z));
        } catch (IllegalArgumentException refused) {
            throw new MalformedPacketException("place portée : " + refused.getMessage());
        }
    }
}
