package dev.axion.persist;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import java.util.zip.CRC32C;

/**
 * Un enregistrement du journal latéral (R-1743, ADR-153 D6) et son format.
 *
 * <p>Un enregistrement : son type ({@code u8}), la longueur de son contenu ({@code u32}), son contenu,
 * puis le CRC-32C des trois ({@code u32}), le tout en little-endian. Un {@link Put} porte l'UUID de
 * l'assembly, sa dimension, sa position, sa definition et le blob de ses clés {@code axion:*} ; un
 * {@link Remove}, l'UUID seul.
 */
public sealed interface JournalRecord {

    /** Type d'un {@link Put}. */
    int PUT = 1;

    /** Type d'un {@link Remove}. */
    int REMOVE = 2;

    /** Type, longueur et CRC : ce qu'un enregistrement ajoute à son contenu, en octets. */
    int FRAMING = 1 + 4 + 4;

    /** {@return l'assembly concernée} */
    UUID uuid();

    /**
     * L'état d'une assembly à sa dernière sauvegarde.
     *
     * @param uuid identifiant de l'entité
     * @param dimension identifiant de sa dimension, {@code minecraft:overworld} par exemple
     * @param x position
     * @param y position
     * @param z position
     * @param definition identifiant 64 bits de sa definition ({@code axion:def})
     * @param blob ses clés {@code axion:*}, en NBT binaire
     */
    record Put(UUID uuid, String dimension, double x, double y, double z, long definition, byte[] blob)
            implements JournalRecord {

        public Put {
            Objects.requireNonNull(uuid, "uuid");
            Objects.requireNonNull(dimension, "dimension");
            Objects.requireNonNull(blob, "blob");
        }
    }

    /**
     * La suppression explicite d'une assembly : elle ne sera pas restaurée.
     *
     * @param uuid identifiant de l'entité
     */
    record Remove(UUID uuid) implements JournalRecord {

        public Remove {
            Objects.requireNonNull(uuid, "uuid");
        }
    }

    /** {@return l'enregistrement encodé, cadre et CRC compris} */
    static byte[] encode(JournalRecord record) {
        byte[] payload;
        if (record instanceof Put put) {
            byte[] dimension = put.dimension().getBytes(StandardCharsets.UTF_8);
            if (dimension.length > 0xFFFF) {
                throw new IllegalArgumentException("identifiant de dimension trop long : " + dimension.length);
            }
            payload = ByteBuffer.allocate(16 + 2 + dimension.length + 3 * 8 + 8 + 4 + put.blob().length)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putLong(put.uuid().getMostSignificantBits())
                    .putLong(put.uuid().getLeastSignificantBits())
                    .putShort((short) dimension.length)
                    .put(dimension)
                    .putDouble(put.x())
                    .putDouble(put.y())
                    .putDouble(put.z())
                    .putLong(put.definition())
                    .putInt(put.blob().length)
                    .put(put.blob())
                    .array();
        } else {
            payload = ByteBuffer.allocate(16)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putLong(record.uuid().getMostSignificantBits())
                    .putLong(record.uuid().getLeastSignificantBits())
                    .array();
        }
        ByteBuffer out = ByteBuffer.allocate(FRAMING + payload.length).order(ByteOrder.LITTLE_ENDIAN);
        out.put((byte) (record instanceof Put ? PUT : REMOVE)).putInt(payload.length).put(payload);
        out.putInt(crc(out.array(), 0, out.position()));
        return out.array();
    }

    /** {@return le CRC-32C d'une plage d'octets : type, longueur et contenu d'un enregistrement} */
    static int crc(byte[] bytes, int offset, int length) {
        CRC32C crc = new CRC32C();
        crc.update(bytes, offset, length);
        return (int) crc.getValue();
    }

    /**
     * {@return le contenu d'un enregistrement dont le cadre et le CRC ont été vérifiés}
     *
     * @throws IllegalArgumentException si le contenu ne correspond pas à son type
     */
    static JournalRecord decode(int type, ByteBuffer payload) {
        payload.order(ByteOrder.LITTLE_ENDIAN);
        try {
            UUID uuid = new UUID(payload.getLong(), payload.getLong());
            JournalRecord record;
            if (type == PUT) {
                byte[] dimension = new byte[Short.toUnsignedInt(payload.getShort())];
                payload.get(dimension);
                double x = payload.getDouble();
                double y = payload.getDouble();
                double z = payload.getDouble();
                long definition = payload.getLong();
                int length = payload.getInt();
                if (length < 0 || length != payload.remaining()) {
                    throw new IllegalArgumentException("blob de " + length + " octets pour " + payload.remaining());
                }
                byte[] blob = new byte[length];
                payload.get(blob);
                record = new Put(uuid, new String(dimension, StandardCharsets.UTF_8), x, y, z, definition, blob);
            } else if (type == REMOVE) {
                record = new Remove(uuid);
            } else {
                throw new IllegalArgumentException("type d'enregistrement inconnu : " + type);
            }
            if (payload.hasRemaining()) {
                throw new IllegalArgumentException(payload.remaining() + " octets après le contenu");
            }
            return record;
        } catch (BufferUnderflowException e) {
            throw new IllegalArgumentException("contenu tronqué", e);
        }
    }
}
