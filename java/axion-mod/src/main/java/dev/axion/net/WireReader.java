package dev.axion.net;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Lit un message AXION écrit par {@link WireWriter}, en validant chaque lecture (R-1650).
 *
 * <p>Toute lecture qui dépasse la fin, toute valeur hors de son domaine et tout codage non canonique
 * lèvent {@link MalformedPacketException} : un octet venu du réseau est une donnée hostile jusqu'à
 * preuve du contraire. Une longueur annoncée n'alloue rien avant d'avoir été comparée à ce qui reste
 * et à la borne de l'appelant (R-1691).
 */
public final class WireReader {

    private final byte[] data;
    private final int end;
    private int position;

    /**
     * @param data octets à lire, en entier
     */
    public WireReader(byte[] data) {
        this(data, 0, data.length);
    }

    /**
     * @param data octets
     * @param offset début de la lecture
     * @param length nombre d'octets lisibles
     */
    public WireReader(byte[] data, int offset, int length) {
        if (offset < 0 || length < 0 || offset > data.length - length) {
            throw new IllegalArgumentException("plage hors du tableau");
        }
        this.data = data;
        this.position = offset;
        this.end = offset + length;
    }

    /** {@return le nombre d'octets qui restent à lire} */
    public int remaining() {
        return end - position;
    }

    /**
     * @return un octet non signé, de 0 à 255
     * @throws MalformedPacketException si le message est épuisé
     */
    public int u8() throws MalformedPacketException {
        need(1, "octet");
        return data[position++] & 0xFF;
    }

    /**
     * @return un booléen écrit 0 ou 1
     * @throws MalformedPacketException si le message est épuisé, ou si l'octet n'est ni 0 ni 1
     */
    public boolean bool() throws MalformedPacketException {
        int value = u8();
        if (value > 1) {
            throw new MalformedPacketException("booléen invalide : " + value);
        }
        return value == 1;
    }

    /**
     * @return un entier non signé sur 16 bits
     * @throws MalformedPacketException si le message est épuisé
     */
    public int u16() throws MalformedPacketException {
        return i16() & 0xFFFF;
    }

    /**
     * @return un entier signé sur 16 bits
     * @throws MalformedPacketException si le message est épuisé
     */
    public short i16() throws MalformedPacketException {
        need(2, "entier 16 bits");
        int value = ((data[position] & 0xFF) << 8) | (data[position + 1] & 0xFF);
        position += 2;
        return (short) value;
    }

    /**
     * @return un entier signé sur 32 bits
     * @throws MalformedPacketException si le message est épuisé
     */
    public int i32() throws MalformedPacketException {
        need(4, "entier 32 bits");
        int value = 0;
        for (int index = 0; index < 4; index++) {
            value = (value << 8) | (data[position++] & 0xFF);
        }
        return value;
    }

    /**
     * @return un entier signé sur 64 bits
     * @throws MalformedPacketException si le message est épuisé
     */
    public long i64() throws MalformedPacketException {
        need(8, "entier 64 bits");
        long value = 0;
        for (int index = 0; index < 8; index++) {
            value = (value << 8) | (data[position++] & 0xFF);
        }
        return value;
    }

    /**
     * @return un flottant IEEE 754 lu bit pour bit ; {@code NaN} et les infinis passent, au lecteur
     *     de les refuser là où ils n'ont pas de sens
     * @throws MalformedPacketException si le message est épuisé
     */
    public float f32() throws MalformedPacketException {
        return Float.intBitsToFloat(i32());
    }

    /**
     * @return un varint non signé, de 0 à {@link Integer#MAX_VALUE}
     * @throws MalformedPacketException si le message est épuisé, si le codage n'est pas minimal, ou
     *     si la valeur dépasse 31 bits
     */
    public int varUInt() throws MalformedPacketException {
        long value = varint(5);
        if (value > Integer.MAX_VALUE) {
            throw new MalformedPacketException("varint hors des 31 bits : " + value);
        }
        return (int) value;
    }

    /**
     * @return un varint non signé, de 0 à {@link Long#MAX_VALUE}
     * @throws MalformedPacketException si le message est épuisé, si le codage n'est pas minimal, ou
     *     si la valeur dépasse 63 bits
     */
    public long varULong() throws MalformedPacketException {
        return varint(9);
    }

    /**
     * Octets préfixés de leur longueur.
     *
     * @param max longueur maximale admise
     * @return les octets lus
     * @throws MalformedPacketException si la longueur dépasse {@code max} ou ce qui reste
     */
    public byte[] bytes(int max) throws MalformedPacketException {
        int length = varUInt();
        if (length > max) {
            throw new MalformedPacketException("longueur " + length + " au-delà de la borne " + max);
        }
        need(length, "octets");
        byte[] bytes = Arrays.copyOfRange(data, position, position + length);
        position += length;
        return bytes;
    }

    /**
     * Chaîne UTF-8 préfixée de sa longueur en octets. Un UTF-8 mal formé est refusé, jamais remplacé.
     *
     * @param maxBytes longueur maximale admise, en octets
     * @return la chaîne lue
     * @throws MalformedPacketException si la longueur dépasse la borne, ou si l'UTF-8 est mal formé
     */
    public String utf8(int maxBytes) throws MalformedPacketException {
        byte[] bytes = bytes(maxBytes);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException error) {
            throw new MalformedPacketException("UTF-8 mal formé");
        }
    }

    /**
     * Octets tels quels : tout ce qui reste.
     *
     * @return les octets restants
     */
    public byte[] rest() {
        byte[] bytes = Arrays.copyOfRange(data, position, end);
        position = end;
        return bytes;
    }

    /**
     * Exige que le message soit entièrement lu : un octet de trop est une faute, pas un ajout ignoré.
     *
     * @throws MalformedPacketException s'il reste des octets
     */
    public void expectEnd() throws MalformedPacketException {
        if (position != end) {
            throw new MalformedPacketException(remaining() + " octet(s) en trop");
        }
    }

    private long varint(int maxBytes) throws MalformedPacketException {
        long value = 0;
        for (int index = 0; index < maxBytes; index++) {
            need(1, "varint");
            int current = data[position++] & 0xFF;
            value |= (long) (current & 0x7F) << (7 * index);
            if ((current & 0x80) == 0) {
                if (current == 0 && index > 0) {
                    throw new MalformedPacketException("varint au codage non minimal");
                }
                return value;
            }
        }
        throw new MalformedPacketException("varint de plus de " + maxBytes + " octets");
    }

    private void need(int bytes, String what) throws MalformedPacketException {
        if (bytes > end - position) {
            throw new MalformedPacketException(what + " tronqué : " + bytes + " octet(s) attendus, "
                    + (end - position) + " restants");
        }
    }
}
