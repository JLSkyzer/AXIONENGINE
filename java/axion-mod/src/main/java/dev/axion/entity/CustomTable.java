package dev.axion.entity;

import dev.axion.api.CustomData;
import dev.axion.bridge.CborWriter;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * La table des données personnalisées d'une assembly, {@code axion:custom} (ADR-153 D7).
 *
 * <p>Une map CBOR de longueur définie : à chaque espace de noms de mod, une chaîne d'octets. Elle
 * s'encode clés triées, et ne se réencode qu'après une écriture : une table relue puis sauvegardée sans
 * changement garde ses octets, quel que soit l'encodeur qui les a produits.
 *
 * <p>Le plafond, {@code persistence.max_custom_bytes}, se lit à chaque écriture. Une écriture qui ferait
 * dépasser à la table encodée le plus grand du plafond et de sa taille actuelle est refusée, sans rien
 * changer : une table relue au-delà d'un plafond baissé depuis reste intacte, et seule une écriture qui
 * l'agrandirait est refusée.
 *
 * <p>Une table illisible reste intacte, en lecture seule : vide à la lecture, rien ne s'y écrit, et
 * l'entité ne la réécrit pas. Une table en lecture seule — celle d'une assembly inerte — se lit, mais
 * rien ne s'y écrit non plus.
 *
 * <p>Logique pure, sans Forge ; sur le thread autoritatif seulement.
 */
public final class CustomTable implements CustomData {

    /** Les règles d'un espace de noms d'identifiant de ressource. */
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]+");

    private static final int MAJOR_MASK = 0xE0;
    private static final int MAJOR_BYTES = 0x40;
    private static final int MAJOR_TEXT = 0x60;
    private static final int MAJOR_MAP = 0xA0;

    private final TreeMap<String, byte[]> entries;
    private final LongSupplier limit;
    private final boolean readOnly;

    /** Ce que la lecture a trouvé d'anormal, ou {@code null}. */
    private final String problem;

    /** Taille de la table encodée, en octets : celle qui a été lue, puis celle de chaque écriture. */
    private long encodedSize;

    /** Encodage de la dernière écriture ; {@code null} tant que rien n'a changé depuis la lecture. */
    private byte[] written;

    private boolean changed;

    private CustomTable(
            TreeMap<String, byte[]> entries, long encodedSize, LongSupplier limit, boolean readOnly, String problem) {
        this.entries = entries;
        this.encodedSize = encodedSize;
        this.limit = limit;
        this.readOnly = readOnly;
        this.problem = problem;
    }

    /**
     * Lit une table persistée.
     *
     * @param encoded la table encodée, ou {@code null} si l'assembly n'en a pas
     * @param limit le plafond en vigueur, en octets
     * @param readOnly vrai pour une table qui se lit sans s'écrire
     * @return la table ; illisible, en lecture seule, si les octets ne sont pas une table valide
     */
    public static CustomTable read(byte[] encoded, LongSupplier limit, boolean readOnly) {
        if (encoded == null) {
            return new CustomTable(new TreeMap<>(), 0, limit, readOnly, null);
        }
        TreeMap<String, byte[]> entries;
        try {
            entries = decode(encoded);
        } catch (Malformed malformed) {
            return unreadable(malformed.getMessage());
        }
        long cap = limit.getAsLong();
        String problem = encoded.length > cap
                ? "table de " + encoded.length + " octets, au-delà du plafond de " + cap
                        + " : gardée, mais rien ne l'agrandira"
                : null;
        return new CustomTable(entries, encoded.length, limit, readOnly, problem);
    }

    /**
     * {@return une table illisible : gardée intacte, vide à la lecture, rien ne s'y écrit}
     *
     * @param reason ce qui la rend illisible
     */
    public static CustomTable unreadable(String reason) {
        return new CustomTable(new TreeMap<>(), 0, () -> 0, true, "table illisible, gardée telle quelle : " + reason);
    }

    /** {@return ce que la lecture a trouvé d'anormal — table illisible, ou au-delà du plafond} */
    public Optional<String> problem() {
        return Optional.ofNullable(problem);
    }

    /** {@return vrai si une écriture a changé la table depuis sa lecture} */
    public boolean changed() {
        return changed;
    }

    /**
     * {@return l'encodage de la table, ou vide si elle n'a plus rien} Pour une table qui a {@linkplain
     * #changed changé} seulement : sinon, ce sont les octets lus qui font foi.
     *
     * @throws IllegalStateException si la table n'a pas changé
     */
    public Optional<byte[]> encoded() {
        if (!changed) {
            throw new IllegalStateException("table inchangée : ses octets lus font foi");
        }
        return Optional.ofNullable(written).map(byte[]::clone);
    }

    @Override
    public Set<String> namespaces() {
        return Collections.unmodifiableSortedSet(new TreeSet<>(entries.keySet()));
    }

    @Override
    public Optional<byte[]> get(String namespace) {
        checkNamespace(namespace);
        return Optional.ofNullable(entries.get(namespace)).map(byte[]::clone);
    }

    @Override
    public boolean put(String namespace, byte[] value) {
        checkNamespace(namespace);
        Objects.requireNonNull(value, "value");
        if (readOnly) {
            return false;
        }
        TreeMap<String, byte[]> candidate = new TreeMap<>(entries);
        candidate.put(namespace, value.clone());
        byte[] encoded = encode(candidate);
        if (encoded.length > Math.max(limit.getAsLong(), encodedSize)) {
            return false;
        }
        entries.put(namespace, candidate.get(namespace));
        commit(encoded);
        return true;
    }

    @Override
    public void remove(String namespace) {
        checkNamespace(namespace);
        if (readOnly || entries.remove(namespace) == null) {
            return;
        }
        commit(entries.isEmpty() ? null : encode(entries));
    }

    private void commit(byte[] encoded) {
        written = encoded;
        encodedSize = encoded == null ? 0 : encoded.length;
        changed = true;
    }

    private static void checkNamespace(String namespace) {
        Objects.requireNonNull(namespace, "namespace");
        if (!NAMESPACE.matcher(namespace).matches()) {
            throw new IllegalArgumentException("espace de noms invalide, attendu [a-z0-9_.-]+ : " + namespace);
        }
    }

    private static byte[] encode(TreeMap<String, byte[]> entries) {
        return CborWriter.encodeMap(new LinkedHashMap<>(entries));
    }

    /**
     * Décode une table : une map de longueur définie, des clés texte UTF-8 qui sont des espaces de noms
     * valides, sans doublon, des valeurs en chaînes d'octets de longueur définie, et rien après la map.
     */
    private static TreeMap<String, byte[]> decode(byte[] in) throws Malformed {
        Reader reader = new Reader(in);
        long count = reader.head(MAJOR_MAP, "la table");
        TreeMap<String, byte[]> entries = new TreeMap<>();
        for (long index = 0; index < count; index++) {
            // Un octet qui n'est pas de l'UTF-8 devient un caractère de remplacement, que les règles
            // d'un espace de noms refusent.
            String key = new String(reader.bytes(reader.head(MAJOR_TEXT, "une clé")), StandardCharsets.UTF_8);
            if (!NAMESPACE.matcher(key).matches()) {
                throw new Malformed("espace de noms invalide : " + key);
            }
            byte[] value = reader.bytes(reader.head(MAJOR_BYTES, "la valeur de " + key));
            if (entries.put(key, value) != null) {
                throw new Malformed("espace de noms en double : " + key);
            }
        }
        if (reader.position != in.length) {
            throw new Malformed((in.length - reader.position) + " octets après la table");
        }
        return entries;
    }

    /** Lecture big-endian (RFC 8949), bornée par le tableau. */
    private static final class Reader {

        private final byte[] in;
        private int position;

        Reader(byte[] in) {
            this.in = in;
        }

        /** {@return la longueur que porte l'élément suivant, qui doit être du type majeur donné} */
        long head(int major, String what) throws Malformed {
            int head = u8();
            if ((head & MAJOR_MASK) != major) {
                throw new Malformed(what + " n'est pas du type CBOR attendu (octet 0x"
                        + Integer.toHexString(head) + ")");
            }
            int info = head & 0x1F;
            if (info < 24) {
                return info;
            }
            return switch (info) {
                case 24 -> u8();
                case 25 -> bigEndian(2);
                case 26 -> bigEndian(4);
                case 27 -> {
                    long value = bigEndian(8);
                    if (value < 0) {
                        throw new Malformed("longueur hors de portée pour " + what);
                    }
                    yield value;
                }
                default -> throw new Malformed("longueur indéfinie ou réservée pour " + what);
            };
        }

        byte[] bytes(long length) throws Malformed {
            if (length > in.length - position) {
                throw new Malformed("chaîne de " + length + " octets au-delà de la fin");
            }
            byte[] out = new byte[(int) length];
            System.arraycopy(in, position, out, 0, out.length);
            position += out.length;
            return out;
        }

        private int u8() throws Malformed {
            if (position >= in.length) {
                throw new Malformed("table tronquée");
            }
            return in[position++] & 0xFF;
        }

        private long bigEndian(int bytes) throws Malformed {
            long value = 0;
            for (int index = 0; index < bytes; index++) {
                value = (value << 8) | u8();
            }
            return value;
        }
    }

    /** Une table qui n'est pas lisible. */
    private static final class Malformed extends Exception {

        private static final long serialVersionUID = 1L;

        Malformed(String message) {
            super(message);
        }
    }
}
