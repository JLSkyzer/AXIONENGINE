package dev.axion.net;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Écrit un message AXION : entiers en big-endian, varints non signés au codage minimal, octets et
 * chaînes UTF-8 préfixés de leur longueur. {@link WireReader} lit exactement ce qui est écrit ici.
 *
 * <p>Big-endian comme le reste du protocole de Minecraft. Ce codec ne passe pas la frontière native :
 * celle-ci, little-endian (R-271), a ses propres encodeurs.
 */
public final class WireWriter {

    private byte[] buffer;
    private int size;

    /** Un écrivain vide. */
    public WireWriter() {
        this(64);
    }

    /**
     * @param capacity capacité initiale, en octets
     */
    public WireWriter(int capacity) {
        buffer = new byte[Math.max(capacity, 16)];
    }

    /** {@return le nombre d'octets écrits} */
    public int size() {
        return size;
    }

    /** {@return une copie des octets écrits} */
    public byte[] toByteArray() {
        return Arrays.copyOf(buffer, size);
    }

    /**
     * @param value octet non signé, de 0 à 255
     * @return cet écrivain
     */
    public WireWriter u8(int value) {
        if (value < 0 || value > 0xFF) {
            throw new IllegalArgumentException("u8 hors bornes : " + value);
        }
        ensure(1);
        buffer[size++] = (byte) value;
        return this;
    }

    /**
     * @param value booléen, écrit 0 ou 1
     * @return cet écrivain
     */
    public WireWriter bool(boolean value) {
        return u8(value ? 1 : 0);
    }

    /**
     * @param value entier non signé sur 16 bits, de 0 à 65 535
     * @return cet écrivain
     */
    public WireWriter u16(int value) {
        if (value < 0 || value > 0xFFFF) {
            throw new IllegalArgumentException("u16 hors bornes : " + value);
        }
        return i16((short) value);
    }

    /**
     * @param value entier signé sur 16 bits
     * @return cet écrivain
     */
    public WireWriter i16(short value) {
        ensure(2);
        buffer[size++] = (byte) (value >>> 8);
        buffer[size++] = (byte) value;
        return this;
    }

    /**
     * @param value entier signé sur 32 bits
     * @return cet écrivain
     */
    public WireWriter i32(int value) {
        ensure(4);
        for (int shift = 24; shift >= 0; shift -= 8) {
            buffer[size++] = (byte) (value >>> shift);
        }
        return this;
    }

    /**
     * @param value entier signé sur 64 bits
     * @return cet écrivain
     */
    public WireWriter i64(long value) {
        ensure(8);
        for (int shift = 56; shift >= 0; shift -= 8) {
            buffer[size++] = (byte) (value >>> shift);
        }
        return this;
    }

    /**
     * @param value flottant IEEE 754, écrit bit pour bit
     * @return cet écrivain
     */
    public WireWriter f32(float value) {
        return i32(Float.floatToRawIntBits(value));
    }

    /**
     * Varint non signé : sept bits par octet, poids faibles d'abord, bit de poids fort pour « la
     * suite ». Codage minimal : aucun octet de poids fort nul en tête.
     *
     * @param value entier positif ou nul
     * @return cet écrivain
     */
    public WireWriter varUInt(int value) {
        if (value < 0) {
            throw new IllegalArgumentException("varint négatif : " + value);
        }
        return varULong(value);
    }

    /**
     * @param value entier positif ou nul, sur 63 bits
     * @return cet écrivain
     * @see #varUInt(int)
     */
    public WireWriter varULong(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("varint négatif : " + value);
        }
        ensure(10);
        long rest = value;
        while (rest >= 0x80) {
            buffer[size++] = (byte) ((rest & 0x7F) | 0x80);
            rest >>>= 7;
        }
        buffer[size++] = (byte) rest;
        return this;
    }

    /**
     * Octets préfixés de leur longueur (varint).
     *
     * @param bytes octets à écrire
     * @return cet écrivain
     */
    public WireWriter bytes(byte[] bytes) {
        varUInt(bytes.length);
        return raw(bytes);
    }

    /**
     * Chaîne en UTF-8, préfixée de sa longueur en octets (varint).
     *
     * @param text texte à écrire
     * @return cet écrivain
     */
    public WireWriter utf8(String text) {
        return bytes(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Octets tels quels, sans longueur.
     *
     * @param bytes octets à écrire
     * @return cet écrivain
     */
    public WireWriter raw(byte[] bytes) {
        ensure(bytes.length);
        System.arraycopy(bytes, 0, buffer, size, bytes.length);
        size += bytes.length;
        return this;
    }

    private void ensure(int more) {
        if (size + more > buffer.length) {
            buffer = Arrays.copyOf(buffer, Math.max(buffer.length * 2, size + more));
        }
    }
}
