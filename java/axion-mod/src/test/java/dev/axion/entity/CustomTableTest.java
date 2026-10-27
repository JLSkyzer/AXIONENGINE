package dev.axion.entity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-432, côté logique pure : la table des données personnalisées d'une assembly (ADR-153 D7) — son
 * encodage, son plafond, et ce qu'elle fait d'octets qu'elle ne sait pas lire.
 */
class CustomTableTest {

    private static final long PLAFOND = 16384;

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int index = 0; index < values.length; index++) {
            out[index] = (byte) values[index];
        }
        return out;
    }

    private static CustomTable vide(long plafond) {
        return CustomTable.read(null, () -> plafond, false);
    }

    @Test
    @DisplayName("T-432 : une table écrite, encodée puis relue rend les mêmes espaces de noms et les mêmes octets")
    void allerRetour() {
        CustomTable table = vide(PLAFOND);
        assertTrue(table.put("mod_b", bytes(9, 8, 7)));
        assertTrue(table.put("mod.a", new byte[0]));
        byte[] encodee = table.encoded().orElseThrow();

        CustomTable relue = CustomTable.read(encodee, () -> PLAFOND, false);
        assertEquals(Optional.empty(), relue.problem());
        assertEquals(List.of("mod.a", "mod_b"), List.copyOf(relue.namespaces()));
        assertArrayEquals(bytes(9, 8, 7), relue.get("mod_b").orElseThrow());
        assertArrayEquals(new byte[0], relue.get("mod.a").orElseThrow());
        assertFalse(relue.changed(), "une table relue n'a pas changé");
    }

    @Test
    @DisplayName("ADR-153 D7 : la table s'encode en map CBOR, clés triées, valeurs en chaînes d'octets")
    void encodage() {
        CustomTable table = vide(PLAFOND);
        assertTrue(table.put("b", bytes(2)));
        assertTrue(table.put("a", bytes(1, 2)));
        // A2 : map de deux entrées ; 61 'a' ; 42 01 02 ; 61 'b' ; 41 02.
        assertArrayEquals(
                bytes(0xA2, 0x61, 'a', 0x42, 0x01, 0x02, 0x61, 'b', 0x41, 0x02),
                table.encoded().orElseThrow());
    }

    @Test
    @DisplayName("T-432 : une écriture au-delà du plafond est refusée, la table inchangée")
    void auDelaDuPlafond() {
        // {"m": h'01'} s'encode en 5 octets ; {"m": h'0102'} en 6.
        CustomTable table = vide(5);
        assertTrue(table.put("m", bytes(1)), "exactement au plafond : acceptée");
        assertFalse(table.put("m", bytes(1, 2)), "un octet au-delà : refusée");
        assertArrayEquals(bytes(1), table.get("m").orElseThrow());
        assertArrayEquals(bytes(0xA1, 0x61, 'm', 0x41, 0x01), table.encoded().orElseThrow());

        CustomTable neuve = vide(4);
        assertFalse(neuve.put("m", bytes(1)));
        assertEquals(Set.of(), neuve.namespaces());
        assertFalse(neuve.changed(), "un refus ne change rien, pas même l'état « changée »");
    }

    @Test
    @DisplayName("ADR-153 D7 : une table relue au-delà d'un plafond baissé reste intacte ; seule une écriture qui l'agrandit est refusée")
    void plafondBaisse() {
        CustomTable source = vide(PLAFOND);
        source.put("m", bytes(1, 2, 3));
        byte[] encodee = source.encoded().orElseThrow();

        CustomTable relue = CustomTable.read(encodee, () -> 2, false);
        assertTrue(relue.problem().isPresent(), "une table au-delà du plafond se signale");
        assertArrayEquals(bytes(1, 2, 3), relue.get("m").orElseThrow());
        assertFalse(relue.put("m", bytes(1, 2, 3, 4)), "elle grandirait");
        assertFalse(relue.changed());
        assertTrue(relue.put("m", bytes(4, 5, 6)), "même taille : acceptée");
        assertTrue(relue.put("m", bytes(7)), "plus petite : acceptée");
        assertFalse(relue.put("m", bytes(7, 8)), "la taille actuelle est désormais la plus petite");
        relue.remove("m");
        assertEquals(Optional.empty(), relue.encoded(), "vide : la clé disparaît");
    }

    @Test
    @DisplayName("ADR-153 D7 : une table illisible reste intacte, vide à la lecture, et rien ne s'y écrit")
    void illisible() {
        List<byte[]> illisibles = List.of(
                new byte[0], // rien
                bytes(0xA1, 0x61, 'm'), // tronquée
                bytes(0xA0, 0x00), // un octet après la table
                bytes(0xA2, 0x61, 'm', 0x40, 0x61, 'm', 0x40), // doublon
                bytes(0xA1, 0x61, 'M', 0x40), // espace de noms invalide
                bytes(0xA1, 0x61, 0xFF, 0x40), // clé qui n'est pas de l'UTF-8, donc pas un espace de noms
                bytes(0xBF, 0xFF), // map de longueur indéfinie
                bytes(0xA1, 0x61, 'm', 0x61, 'x'), // valeur texte, pas octets
                bytes(0xA1, 0x61, 'm', 0x5A, 0x7F, 0xFF, 0xFF, 0xFF), // chaîne au-delà de la fin
                bytes(0xBB, 0x7F, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF), // compte démesuré
                bytes(0x80)); // un tableau, pas une map
        for (byte[] octets : illisibles) {
            CustomTable table = CustomTable.read(octets, () -> PLAFOND, false);
            assertTrue(table.problem().isPresent(), () -> "lue sans problème : " + HexFormat.of().formatHex(octets));
            assertEquals(Set.of(), table.namespaces());
            assertFalse(table.put("m", bytes(1)), "rien ne s'écrit dans une table illisible");
            table.remove("m");
            assertFalse(table.changed(), "l'entité ne la réécrit pas");
        }
    }

    @Test
    @DisplayName("ADR-153 D7 : une table valide mais non canonique se lit, et compte sa taille lue")
    void nonCanonique() {
        // Longueur de la valeur sur un octet (58 01) là où l'en-tête suffisait : 6 octets au lieu de 5.
        byte[] octets = bytes(0xA1, 0x61, 'm', 0x58, 0x01, 0x07);
        CustomTable table = CustomTable.read(octets, () -> 5, false);
        assertTrue(table.problem().isPresent(), "6 octets lus pour un plafond de 5");
        assertArrayEquals(bytes(7), table.get("m").orElseThrow());
        assertTrue(table.put("m", bytes(7, 8)), "6 octets encodés : pas plus grande que la table lue");
    }

    @Test
    @DisplayName("ADR-153 D7 : la table d'une assembly inerte se lit, rien ne s'y écrit")
    void lectureSeule() {
        CustomTable source = vide(PLAFOND);
        source.put("m", bytes(1));
        CustomTable inerte = CustomTable.read(source.encoded().orElseThrow(), () -> PLAFOND, true);
        assertArrayEquals(bytes(1), inerte.get("m").orElseThrow());
        assertFalse(inerte.put("m", bytes(2)));
        inerte.remove("m");
        assertArrayEquals(bytes(1), inerte.get("m").orElseThrow());
        assertFalse(inerte.changed());
        assertThrows(IllegalStateException.class, inerte::encoded, "inchangée : ses octets lus font foi");
    }

    @Test
    @DisplayName("CustomData : un espace de noms suit les règles d'un identifiant de ressource ; les octets sont copiés")
    void contrat() {
        CustomTable table = vide(PLAFOND);
        for (String invalide : List.of("", "Mod", "a:b", "a/b", "é")) {
            assertThrows(IllegalArgumentException.class, () -> table.put(invalide, bytes(1)), invalide);
            assertThrows(IllegalArgumentException.class, () -> table.get(invalide), invalide);
            assertThrows(IllegalArgumentException.class, () -> table.remove(invalide), invalide);
        }
        assertThrows(NullPointerException.class, () -> table.put("m", null));

        byte[] valeur = bytes(1, 2);
        assertTrue(table.put("m", valeur));
        valeur[0] = 9;
        assertArrayEquals(bytes(1, 2), table.get("m").orElseThrow(), "put copie la valeur");
        table.get("m").orElseThrow()[0] = 9;
        assertArrayEquals(bytes(1, 2), table.get("m").orElseThrow(), "get rend une copie");
        assertThrows(UnsupportedOperationException.class, () -> table.namespaces().clear());
        assertEquals(Optional.empty(), table.get("autre"));
        table.remove("autre");
    }
}
