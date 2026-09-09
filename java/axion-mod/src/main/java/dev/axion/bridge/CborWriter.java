package dev.axion.bridge;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Encodeur CBOR minimal, suffisant pour la configuration (C-04, IF-01).
 *
 * <p>La configuration traverse la frontière encodée en CBOR, que le natif
 * décode avec {@code ciborium}. Côté Java, aucune bibliothèque CBOR n'est
 * disponible : Minecraft n'en fournit pas, et {@code validateJar} interdit
 * d'embarquer une dépendance shadée. Cet encodeur couvre donc exactement les
 * quatre types du modèle de configuration — booléen, entier, flottant, chaîne —
 * et rien de plus.
 *
 * <p>Ne pas confondre les ordres d'octets : CBOR est <strong>big-endian</strong>
 * (RFC 8949), alors que les tampons de transfert sont en little-endian (R-271).
 * Les deux se croisent dans ce paquet sans se ressembler.
 *
 * <p>Types non couverts, délibérément : tableaux, maps imbriquées, entiers
 * arbitrairement grands, dates, octets bruts. Le décodeur les refuse, et rien
 * dans la configuration n'en a besoin.
 */
public final class CborWriter {

    /** Entier non signé. */
    private static final int MAJOR_UNSIGNED = 0x00;

    /** Entier négatif, encodé comme {@code -1 - valeur}. */
    private static final int MAJOR_NEGATIVE = 0x20;

    /** Chaîne UTF-8. */
    private static final int MAJOR_TEXT = 0x60;

    /** Map. */
    private static final int MAJOR_MAP = 0xA0;

    private static final int FALSE = 0xF4;
    private static final int TRUE = 0xF5;
    private static final int FLOAT64 = 0xFB;

    private CborWriter() {
        throw new AssertionError("classe utilitaire, non instanciable");
    }

    /**
     * Encode une map de chemins d'option vers leurs valeurs.
     *
     * <p>L'ordre d'itération de la map est conservé. Il n'a pas d'importance
     * pour le décodeur, mais un ordre stable rend deux encodages successifs
     * comparables octet pour octet, ce qui aide au diagnostic.
     *
     * @param values valeurs à encoder, dont les types doivent être
     *     {@link Boolean}, {@link Long}, {@link Integer}, {@link Double},
     *     {@link Float} ou {@link String}
     * @return la représentation CBOR
     * @throws IllegalArgumentException si une valeur n'est pas d'un type
     *     supporté — c'est une erreur de programmation, pas une donnée douteuse
     */
    public static byte[] encodeMap(Map<String, Object> values) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 + values.size() * 32);
        writeHead(out, MAJOR_MAP, values.size());
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            writeText(out, entry.getKey());
            writeValue(out, entry.getKey(), entry.getValue());
        }
        return out.toByteArray();
    }

    private static void writeValue(ByteArrayOutputStream out, String path, Object value) {
        if (value instanceof Boolean flag) {
            out.write(flag ? TRUE : FALSE);
        } else if (value instanceof Long number) {
            writeInteger(out, number);
        } else if (value instanceof Integer number) {
            writeInteger(out, number.longValue());
        } else if (value instanceof Double number) {
            writeDouble(out, number);
        } else if (value instanceof Float number) {
            writeDouble(out, number.doubleValue());
        } else if (value instanceof String text) {
            writeText(out, text);
        } else {
            throw new IllegalArgumentException(
                    "type non encodable pour " + path + " : "
                            + (value == null ? "null" : value.getClass().getName()));
        }
    }

    private static void writeInteger(ByteArrayOutputStream out, long value) {
        if (value >= 0) {
            writeHead(out, MAJOR_UNSIGNED, value);
        } else {
            // CBOR encode un négatif comme -1 - n : la valeur écrite est donc
            // toujours positive, et -1 s'écrit 0.
            writeHead(out, MAJOR_NEGATIVE, -1 - value);
        }
    }

    private static void writeDouble(ByteArrayOutputStream out, double value) {
        out.write(FLOAT64);
        long bits = Double.doubleToRawLongBits(value);
        // Big-endian, comme tout le reste de CBOR.
        for (int shift = 56; shift >= 0; shift -= 8) {
            out.write((int) ((bits >>> shift) & 0xFF));
        }
    }

    private static void writeText(ByteArrayOutputStream out, String text) {
        byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
        writeHead(out, MAJOR_TEXT, utf8.length);
        out.write(utf8, 0, utf8.length);
    }

    /**
     * Écrit l'octet de tête d'un élément, suivi de sa longueur si nécessaire.
     *
     * <p>CBOR encode les petites valeurs dans l'octet de tête lui-même, puis
     * bascule sur 1, 2, 4 ou 8 octets supplémentaires. Choisir la forme la plus
     * courte n'est pas une optimisation gratuite : c'est ce que produit tout
     * encodeur conforme, et ce qui rend deux encodages comparables.
     */
    private static void writeHead(ByteArrayOutputStream out, int major, long value) {
        if (value < 0) {
            throw new IllegalArgumentException("longueur négative : " + value);
        }
        if (value < 24) {
            out.write(major | (int) value);
        } else if (value <= 0xFF) {
            out.write(major | 24);
            out.write((int) value);
        } else if (value <= 0xFFFF) {
            out.write(major | 25);
            writeBigEndian(out, value, 2);
        } else if (value <= 0xFFFF_FFFFL) {
            out.write(major | 26);
            writeBigEndian(out, value, 4);
        } else {
            out.write(major | 27);
            writeBigEndian(out, value, 8);
        }
    }

    private static void writeBigEndian(ByteArrayOutputStream out, long value, int bytes) {
        for (int index = bytes - 1; index >= 0; index--) {
            out.write((int) ((value >>> (index * 8)) & 0xFF));
        }
    }
}
