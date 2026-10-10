package dev.axion.render;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Optional;
import java.util.zip.CRC32;

/**
 * Un programme lié, tel que le cache binaire des shaders le garde sur disque (R-760, ADR-127 §4) —
 * logique pure.
 *
 * <pre>
 *  0  u32  magic      « AXSB »
 *  4  u16  schéma     {@value #SCHEMA}
 *  6  u16  réserve    0
 *  8  i32  format     format du binaire, que le pilote rend avec lui
 * 12  u32  longueur   du binaire, en octets
 * 16  u32  CRC-32     du binaire
 * 20  …    binaire
 * </pre>
 *
 * <p>Petit-boutiste. Toute structure persistée porte un magic, une version de schéma et un CRC
 * (obligation 4.9) : un fichier tronqué, corrompu ou d'un autre schéma n'est pas relu — le
 * programme est recompilé, et le fichier remplacé.
 */
public final class ShaderBinaryFile {

    /** « AXSB », lu petit-boutiste. */
    static final int MAGIC = 0x42535841;

    /** Version du schéma. */
    static final int SCHEMA = 1;

    /** Taille de l'en-tête. */
    static final int HEADER_BYTES = 20;

    private ShaderBinaryFile() {}

    /**
     * Un binaire de programme, et son format.
     *
     * @param format format, tel que {@code glGetProgramBinary} le rend
     * @param bytes le binaire
     */
    public record Binary(int format, byte[] bytes) {}

    /**
     * {@return le fichier d'un binaire}
     *
     * @param binary le binaire et son format
     */
    public static byte[] encode(Binary binary) {
        byte[] bytes = binary.bytes();
        ByteBuffer out = ByteBuffer.allocate(HEADER_BYTES + bytes.length).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(MAGIC);
        out.putShort((short) SCHEMA);
        out.putShort((short) 0);
        out.putInt(binary.format());
        out.putInt(bytes.length);
        out.putInt((int) crc(bytes, 0, bytes.length));
        out.put(bytes);
        return out.array();
    }

    /**
     * {@return le binaire qu'un fichier porte, ou rien s'il n'est pas relisible : trop court, autre
     * magic, autre schéma, réserve non nulle, longueur qui ne tombe pas juste, CRC faux}
     *
     * @param file le contenu du fichier
     */
    public static Optional<Binary> decode(byte[] file) {
        if (file.length < HEADER_BYTES) {
            return Optional.empty();
        }
        ByteBuffer in = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
        if (in.getInt(0) != MAGIC
                || Short.toUnsignedInt(in.getShort(4)) != SCHEMA
                || in.getShort(6) != 0) {
            return Optional.empty();
        }
        int format = in.getInt(8);
        long length = Integer.toUnsignedLong(in.getInt(12));
        if (length != file.length - HEADER_BYTES) {
            return Optional.empty();
        }
        long expected = Integer.toUnsignedLong(in.getInt(16));
        if (crc(file, HEADER_BYTES, (int) length) != expected) {
            return Optional.empty();
        }
        return Optional.of(new Binary(format, Arrays.copyOfRange(file, HEADER_BYTES, file.length)));
    }

    private static long crc(byte[] bytes, int offset, int length) {
        CRC32 crc = new CRC32();
        crc.update(bytes, offset, length);
        return crc.getValue();
    }
}
