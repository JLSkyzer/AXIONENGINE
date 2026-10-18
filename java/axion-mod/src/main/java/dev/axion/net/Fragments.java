package dev.axion.net;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Fragmentation ordonnée des messages AXION (R-1651, ADR-150).
 *
 * <p>Une trame ne dépasse jamais {@link NetProtocol#MAX_FRAME_BYTES}. Elle commence par un octet de
 * drapeaux :
 *
 * <pre>
 * trame entière    : flags = 0 | message
 * trame fragmentée : flags = 1 | sequence varint | index varint | count varint | morceau
 * </pre>
 *
 * <p>Les fragments d'un message partent à la suite, dans l'ordre, sans autre trame entre eux :
 * Minecraft passe par TCP, qui garde l'ordre, et une trame qui arrive autrement est une faute, pas
 * un réordonnancement à faire. {@code count} va de 2 à {@link #MAX_FRAGMENTS} : un message tient
 * donc en {@link #MAX_MESSAGE_BYTES} au plus, borne de ce qu'un récepteur accumule (R-1691).
 */
public final class Fragments {

    /** Drapeau d'une trame qui porte un fragment. */
    static final int FLAG_FRAGMENT = 1;

    /** Nombre maximal de fragments d'un message. */
    public static final int MAX_FRAGMENTS = 256;

    /** En-tête d'un fragment au plus long : drapeaux, puis trois varints de cinq octets au plus. */
    static final int MAX_FRAGMENT_HEADER_BYTES = 1 + 3 * 5;

    /** Octets de message portés par un fragment. */
    public static final int FRAGMENT_PAYLOAD_BYTES =
            NetProtocol.MAX_FRAME_BYTES - MAX_FRAGMENT_HEADER_BYTES;

    /** Taille maximale d'un message, en octets. */
    public static final int MAX_MESSAGE_BYTES = MAX_FRAGMENTS * FRAGMENT_PAYLOAD_BYTES;

    private Fragments() {
        throw new AssertionError("non instanciable");
    }

    /**
     * Découpe un message en trames.
     *
     * @param message message entier, non vide
     * @param sequence numéro du message chez l'émetteur, positif ou nul ; il ne sert qu'à un message
     *     fragmenté
     * @return les trames à envoyer, dans l'ordre
     * @throws IllegalArgumentException si le message est vide ou dépasse {@link #MAX_MESSAGE_BYTES} :
     *     c'est une faute de l'émetteur, pas une donnée reçue
     */
    public static List<byte[]> split(byte[] message, int sequence) {
        if (message.length == 0) {
            throw new IllegalArgumentException("message vide");
        }
        if (message.length > MAX_MESSAGE_BYTES) {
            throw new IllegalArgumentException(
                    "message de " + message.length + " octets, au-delà de " + MAX_MESSAGE_BYTES);
        }
        if (sequence < 0) {
            throw new IllegalArgumentException("séquence négative : " + sequence);
        }
        if (message.length < NetProtocol.MAX_FRAME_BYTES) {
            byte[] frame = new byte[message.length + 1];
            System.arraycopy(message, 0, frame, 1, message.length);
            return List.of(frame);
        }
        int count = (message.length + FRAGMENT_PAYLOAD_BYTES - 1) / FRAGMENT_PAYLOAD_BYTES;
        List<byte[]> frames = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            int from = index * FRAGMENT_PAYLOAD_BYTES;
            int to = Math.min(from + FRAGMENT_PAYLOAD_BYTES, message.length);
            frames.add(new WireWriter(MAX_FRAGMENT_HEADER_BYTES + to - from)
                    .u8(FLAG_FRAGMENT)
                    .varUInt(sequence)
                    .varUInt(index)
                    .varUInt(count)
                    .raw(Arrays.copyOfRange(message, from, to))
                    .toByteArray());
        }
        return frames;
    }

    /**
     * Reconstitue les messages d'une connexion, trame après trame.
     *
     * <p>Une instance par connexion et par sens, utilisée depuis un seul thread. Après une trame
     * fautive, le message en cours est abandonné et la suivante repart de zéro : une faute ne
     * contamine pas le message qui la suit.
     */
    public static final class Reassembler {

        private int sequence;
        private int count;
        private int expected;
        private byte[] buffer;
        private int size;

        /**
         * @param frame trame reçue
         * @return le message complet, ou {@code null} si la trame en est un fragment non final
         * @throws MalformedPacketException si la trame est fautive ; le message en cours est abandonné
         */
        public byte[] accept(byte[] frame) throws MalformedPacketException {
            try {
                return read(frame);
            } catch (MalformedPacketException error) {
                reset();
                throw error;
            }
        }

        /** {@return vrai si un message fragmenté attend sa suite} */
        public boolean inProgress() {
            return buffer != null;
        }

        private byte[] read(byte[] frame) throws MalformedPacketException {
            if (frame.length > NetProtocol.MAX_FRAME_BYTES) {
                throw new MalformedPacketException("trame de " + frame.length + " octets, au-delà de "
                        + NetProtocol.MAX_FRAME_BYTES);
            }
            WireReader reader = new WireReader(frame);
            int flags = reader.u8();
            if (flags == 0) {
                if (inProgress()) {
                    throw new MalformedPacketException("trame entière au milieu d'un message fragmenté");
                }
                byte[] message = reader.rest();
                if (message.length == 0) {
                    throw new MalformedPacketException("message vide");
                }
                return message;
            }
            if (flags != FLAG_FRAGMENT) {
                throw new MalformedPacketException("drapeaux de trame inconnus : " + flags);
            }
            int frameSequence = reader.varUInt();
            int index = reader.varUInt();
            int frameCount = reader.varUInt();
            if (frameCount < 2 || frameCount > MAX_FRAGMENTS) {
                throw new MalformedPacketException("nombre de fragments hors de [2, " + MAX_FRAGMENTS
                        + "] : " + frameCount);
            }
            byte[] piece = reader.rest();
            if (piece.length == 0) {
                throw new MalformedPacketException("fragment vide");
            }
            if (index == 0) {
                if (inProgress()) {
                    throw new MalformedPacketException(
                            "message " + sequence + " interrompu par le début du message " + frameSequence);
                }
                sequence = frameSequence;
                count = frameCount;
                expected = 0;
                buffer = new byte[Math.min(frameCount * FRAGMENT_PAYLOAD_BYTES, 4 * FRAGMENT_PAYLOAD_BYTES)];
                size = 0;
            } else if (!inProgress()) {
                throw new MalformedPacketException("fragment " + index + " sans début de message");
            } else if (frameSequence != sequence || frameCount != count) {
                throw new MalformedPacketException("fragment du message " + frameSequence + " (" + frameCount
                        + ") au milieu du message " + sequence + " (" + count + ")");
            }
            if (index != expected) {
                throw new MalformedPacketException(
                        "fragment " + index + " reçu, " + expected + " attendu");
            }
            append(piece);
            expected++;
            if (expected < count) {
                return null;
            }
            byte[] message = Arrays.copyOf(buffer, size);
            reset();
            return message;
        }

        private void append(byte[] piece) throws MalformedPacketException {
            if (size + piece.length > MAX_MESSAGE_BYTES) {
                throw new MalformedPacketException("message au-delà de " + MAX_MESSAGE_BYTES + " octets");
            }
            if (size + piece.length > buffer.length) {
                buffer = Arrays.copyOf(buffer, Math.min(MAX_MESSAGE_BYTES,
                        Math.max(buffer.length * 2, size + piece.length)));
            }
            System.arraycopy(piece, 0, buffer, size, piece.length);
            size += piece.length;
        }

        private void reset() {
            buffer = null;
            size = 0;
            expected = 0;
            count = 0;
        }
    }
}
