package dev.axion.net;

import dev.axion.seat.VehicleInput;

/**
 * {@code C2S_Input} (21.4, ADR-158 D4) : les entrées d'un joueur assis, une fois par tick client.
 *
 * <pre>
 * genre u8 = 7 | séquence u32 | entité varuint | siège u8
 *   | throttle, brake, steer, clutch, handbrake f32 | gear i8 | flags u8 | cadence u8
 * </pre>
 *
 * <p>L'octet de cadence (ADR-168 D3, révisé le 2026-11-14) : {@link #PACED}, le client règle la profondeur de la file
 * de son siège au serveur ; aucun autre bit n'est défini.
 *
 * <p>La séquence croît par connexion : la prédiction des véhicules s'y appuiera (C-33). Rien de ce message
 * n'est cru : {@link PlayerSync} écarte une séquence qui ne croît pas, le serveur vérifie que le joueur occupe
 * ce siège, et borne les valeurs (R-1110, INV-02).
 *
 * @param sequence séquence, non signée
 * @param entity identifiant réseau de l'assembly
 * @param seat rang du siège dans la definition
 * @param input les entrées, telles que le client les a envoyées
 * @param paced vrai si le client règle la profondeur de la file de son siège (ADR-168 D3)
 */
public record InputMessage(int sequence, int entity, int seat, VehicleInput input, boolean paced) {

    /** Bit de l'octet de cadence : le client règle la file de son siège. */
    public static final int PACED = 1;

    /**
     * Une entrée d'un client qui ne règle pas la file.
     *
     * @param sequence séquence, non signée
     * @param entity identifiant réseau de l'assembly
     * @param seat rang du siège
     * @param input les entrées
     */
    public InputMessage(int sequence, int entity, int seat, VehicleInput input) {
        this(sequence, entity, seat, input, false);
    }

    /** {@return le message, genre compris} */
    public byte[] encode() {
        return new WireWriter(35)
                .u8(MessageKinds.INPUT)
                .i32(sequence)
                .varUInt(entity)
                .u8(seat)
                .f32(input.throttle())
                .f32(input.brake())
                .f32(input.steer())
                .f32(input.clutch())
                .f32(input.handbrake())
                .u8(input.gear() & 0xFF)
                .u8(input.flags())
                .u8(paced ? PACED : 0)
                .toByteArray();
    }

    /**
     * @param message message reçu, genre compris
     * @return le message lu ; ses valeurs restent à borner
     * @throws MalformedPacketException si le message est mal formé
     */
    public static InputMessage decode(byte[] message) throws MalformedPacketException {
        WireReader reader = new WireReader(message);
        MessageKinds.expect(reader, MessageKinds.INPUT);
        int sequence = reader.i32();
        int entity = reader.varUInt();
        int seat = reader.u8();
        VehicleInput input = new VehicleInput(reader.f32(), reader.f32(), reader.f32(), reader.f32(), reader.f32(),
                (byte) reader.u8(), reader.u8());
        int cadence = reader.u8();
        if ((cadence & ~PACED) != 0) {
            throw new MalformedPacketException("entrées : octet de cadence inconnu " + cadence);
        }
        reader.expectEnd();
        return new InputMessage(sequence, entity, seat, input, (cadence & PACED) != 0);
    }
}
