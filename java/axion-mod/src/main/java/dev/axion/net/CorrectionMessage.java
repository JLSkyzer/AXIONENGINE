package dev.axion.net;

import dev.axion.physics.SimCommandStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * {@code S2C_Correction} (ADR-168 D4) : ce que le serveur a calculé du véhicule qu'un joueur conduit, à un tick. Il
 * part au seul conducteur, à chaque tick où son véhicule a avancé ; le client qui prédit le compare à ce qu'il avait
 * prédit, et rejoue ce qu'il a envoyé après la séquence acquittée (D5).
 *
 * <pre>
 * genre u8 = 8 | tick varulong | drapeaux u8 | [séquence appliquée u32] | entité varuint | tick des paramètres varulong
 *   | variables : n varuint | ⌈n / 8⌉ octets, un bit par variable présente | f64 par variable présente, par rang
 *   | états : m varuint, puis par assembly : entité varuint | octets préfixés de leur longueur
 * </pre>
 *
 * <ul>
 *   <li>Le drapeau {@link #FLAG_APPLIED} dit qu'une entrée du conducteur a été appliquée ; sans lui, la séquence est
 *       absente.</li>
 *   <li>Les variables sont celles que le conducteur reçoit ({@code replicate} autre que {@code none}), en pleine
 *       précision : le rejeu en repart.</li>
 *   <li>Le tick des paramètres date les modificateurs que le natif appliquait ({@link AssemblyState.Modifiers#tick()}) ;
 *       les snapshots les portent.</li>
 *   <li>Chaque état est le bloc opaque du natif ({@link dev.axion.physics.AssemblyStateReport}) : le véhicule, puis
 *       l'ensemble qui lui est attaché (D7). Le client le rend tel quel à son natif ; Java ne le lit pas.</li>
 * </ul>
 *
 * @param serverTick le tick du serveur dont l'état est le résultat
 * @param appliedSequence la séquence de la dernière entrée appliquée du conducteur, non signée ; -1 sans
 * @param entity identifiant réseau du véhicule
 * @param parametersTick le tick des modificateurs de paramètres en vigueur, 0 sans
 * @param variables les variables du véhicule
 * @param states l'état complet de chaque assembly de l'ensemble, le véhicule d'abord
 */
public record CorrectionMessage(long serverTick, long appliedSequence, int entity, long parametersTick,
        Variables variables, List<State> states) {

    /** Drapeau : une entrée du conducteur a été appliquée, sa séquence suit. */
    public static final int FLAG_APPLIED = 1;

    /** Octets d'un état au moins sur le fil : l'entité, la longueur, huit octets. */
    private static final int MIN_STATE_BYTES = 1 + 1 + 8;

    /** Refuse un message que le serveur n'enverrait pas. */
    public CorrectionMessage {
        if (serverTick < 0 || parametersTick < 0) {
            throw new IllegalArgumentException("tick " + serverTick + ", paramètres du tick " + parametersTick);
        }
        if (appliedSequence < -1 || appliedSequence > 0xFFFF_FFFFL) {
            throw new IllegalArgumentException("séquence appliquée " + appliedSequence);
        }
        if (entity < 0) {
            throw new IllegalArgumentException("entité " + entity);
        }
        if (states.isEmpty()) {
            throw new IllegalArgumentException("aucun état : le véhicule au moins");
        }
        states = List.copyOf(states);
    }

    /**
     * Les variables d'un véhicule, telles que son conducteur les reçoit.
     *
     * @param count nombre de variables de la definition, au plus {@link AssemblyState.VariableValues#MAX_VARIABLES}
     * @param mask les variables présentes, un bit par rang
     * @param values la valeur de chaque variable, 0 pour une absente ; copie
     */
    public record Variables(int count, long mask, double[] values) {

        /** Aucune variable. */
        public static final Variables NONE = new Variables(0, 0L, new double[0]);

        /** Copie les valeurs, et refuse un nombre, un masque ou une valeur incohérents. */
        public Variables {
            if (count < 0 || count > AssemblyState.VariableValues.MAX_VARIABLES || values.length != count) {
                throw new IllegalArgumentException(values.length + " valeurs pour " + count + " variables");
            }
            if (count < 64 && mask >>> count != 0) {
                throw new IllegalArgumentException("bits de variables au-delà de la dernière");
            }
            for (int i = 0; i < count; i++) {
                boolean present = (mask >>> i & 1L) != 0;
                if (!Double.isFinite(values[i]) || (!present && Double.doubleToRawLongBits(values[i]) != 0)) {
                    throw new IllegalArgumentException("variable " + i + " : valeur " + values[i]);
                }
            }
            values = values.clone();
        }

        /**
         * {@return les variables présentes selon le masque, les autres à zéro}
         *
         * @param values la valeur de chaque variable, dans l'ordre de la definition
         * @param mask celles qui partent
         */
        public static Variables of(double[] values, long mask) {
            double[] kept = new double[values.length];
            long valid = values.length == 64 ? mask : mask & ((1L << values.length) - 1);
            for (int i = 0; i < values.length; i++) {
                if ((valid >>> i & 1L) != 0) {
                    kept[i] = values[i];
                }
            }
            return new Variables(values.length, valid, kept);
        }

        /** {@return vrai si la variable de ce rang est présente} */
        public boolean has(int index) {
            return (mask >>> index & 1L) != 0;
        }

        /** {@return la valeur de la variable de ce rang, 0 si elle est absente} */
        public double value(int index) {
            return values[index];
        }

        @Override
        public double[] values() {
            return values.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Variables that && count == that.count && mask == that.mask
                    && Arrays.equals(values, that.values);
        }

        @Override
        public int hashCode() {
            return 31 * (31 * count + Long.hashCode(mask)) + Arrays.hashCode(values);
        }

        @Override
        public String toString() {
            return "Variables[count=" + count + ", mask=" + Long.toBinaryString(mask) + ", values="
                    + Arrays.toString(values) + "]";
        }
    }

    /**
     * L'état complet d'une assembly de l'ensemble.
     *
     * @param entity identifiant réseau de son entité
     * @param bytes le bloc opaque du natif : non vide, multiple de 8 octets, au plus
     *     {@link SimCommandStream#MAX_ASSEMBLY_STATE_BYTES} ; copie
     */
    public record State(int entity, byte[] bytes) {

        /** Copie le bloc, et refuse ce que le natif ne relirait pas. */
        public State {
            if (entity < 0) {
                throw new IllegalArgumentException("entité " + entity);
            }
            if (bytes.length == 0 || bytes.length % 8 != 0 || bytes.length > SimCommandStream.MAX_ASSEMBLY_STATE_BYTES) {
                throw new IllegalArgumentException("bloc d'état de " + bytes.length + " octets : non vide, multiple de"
                        + " 8, au plus " + SimCommandStream.MAX_ASSEMBLY_STATE_BYTES);
            }
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof State that && entity == that.entity && Arrays.equals(bytes, that.bytes);
        }

        @Override
        public int hashCode() {
            return 31 * entity + Arrays.hashCode(bytes);
        }

        @Override
        public String toString() {
            return "State[entity=" + entity + ", " + bytes.length + " octets]";
        }
    }

    /** {@return vrai si une entrée du conducteur a été appliquée} */
    public boolean applied() {
        return appliedSequence >= 0;
    }

    /** {@return le message, genre compris} */
    public byte[] encode() {
        int size = 32 + 8 * variables.count();
        for (State state : states) {
            size += 10 + state.bytes.length;
        }
        WireWriter writer = new WireWriter(size)
                .u8(MessageKinds.CORRECTION)
                .varULong(serverTick)
                .u8(applied() ? FLAG_APPLIED : 0);
        if (applied()) {
            writer.i32((int) appliedSequence);
        }
        writer.varUInt(entity).varULong(parametersTick);
        int count = variables.count();
        writer.varUInt(count);
        for (int octet = 0; octet < (count + 7) / 8; octet++) {
            writer.u8((int) (variables.mask() >>> (8 * octet) & 0xFF));
        }
        for (int index = 0; index < count; index++) {
            if (variables.has(index)) {
                writer.f64(variables.value(index));
            }
        }
        writer.varUInt(states.size());
        for (State state : states) {
            writer.varUInt(state.entity()).bytes(state.bytes);
        }
        return writer.toByteArray();
    }

    /**
     * @param message message reçu, genre compris
     * @return la correction lue, ses bornes vérifiées (R-1650)
     * @throws MalformedPacketException si le message est mal formé
     */
    public static CorrectionMessage decode(byte[] message) throws MalformedPacketException {
        WireReader reader = new WireReader(message);
        MessageKinds.expect(reader, MessageKinds.CORRECTION);
        long serverTick = reader.varULong();
        int flags = reader.u8();
        if ((flags & ~FLAG_APPLIED) != 0) {
            throw new MalformedPacketException("correction : drapeaux inconnus " + flags);
        }
        long applied = (flags & FLAG_APPLIED) != 0 ? Integer.toUnsignedLong(reader.i32()) : -1;
        int entity = reader.varUInt();
        long parametersTick = reader.varULong();
        int count = reader.varUInt();
        if (count > AssemblyState.VariableValues.MAX_VARIABLES) {
            throw new MalformedPacketException("correction : " + count + " variables, au-delà de "
                    + AssemblyState.VariableValues.MAX_VARIABLES);
        }
        long mask = 0L;
        for (int octet = 0; octet < (count + 7) / 8; octet++) {
            mask |= (long) reader.u8() << (8 * octet);
        }
        double[] values = new double[count];
        for (int index = 0; index < count; index++) {
            if ((mask >>> index & 1L) != 0) {
                values[index] = reader.f64();
            }
        }
        int stateCount = reader.varUInt();
        if (stateCount > reader.remaining() / MIN_STATE_BYTES) {
            throw new MalformedPacketException("correction : " + stateCount + " états dans " + reader.remaining()
                    + " octets");
        }
        List<State> states = new ArrayList<>(stateCount);
        for (int index = 0; index < stateCount; index++) {
            int owner = reader.varUInt();
            byte[] bytes = reader.bytes(SimCommandStream.MAX_ASSEMBLY_STATE_BYTES);
            try {
                states.add(new State(owner, bytes));
            } catch (IllegalArgumentException refused) {
                throw new MalformedPacketException("correction : " + refused.getMessage());
            }
        }
        reader.expectEnd();
        try {
            return new CorrectionMessage(serverTick, applied, entity, parametersTick,
                    new Variables(count, mask, values), states);
        } catch (IllegalArgumentException refused) {
            throw new MalformedPacketException("correction : " + refused.getMessage());
        }
    }
}
