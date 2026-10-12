package dev.axion.physics;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** R-980 : les groupes de collision qu'une definition nomme, écrits sur ses colliders. */
class CollisionFilterTest {

    private static JsonObject definition(String physics) {
        return JsonParser.parseString("{\"physics\": " + physics + "}").getAsJsonObject();
    }

    /** Une section PHYS de {@code colliders} colliders, {@code points} points et {@code children} enfants. */
    private static byte[] phys(int colliders, int points, int children) {
        ByteBuffer out = ByteBuffer.allocate(4 + colliders * 104 + 4 + points * 12 + 4 + children * 104)
                .order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(0, colliders);
        out.putInt(4 + colliders * 104, points);
        out.putInt(4 + colliders * 104 + 4 + points * 12, children);
        return out.array();
    }

    private static int intAt(byte[] bytes, int at) {
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(at);
    }

    @Test
    @DisplayName("R-980 : sans déclaration, membre d'assembly et heurte tout")
    void defauts() {
        JsonObject none = JsonParser.parseString("{}").getAsJsonObject();
        assertEquals(1 << 1, CollisionFilter.membership(none));
        assertEquals(CollisionFilter.ALL, CollisionFilter.filter(none));
        assertEquals(1 << 1, CollisionFilter.membership(definition("{\"mass\": 2.0}")));
    }

    @Test
    @DisplayName("R-980 : les noms déclarés deviennent des bits ; une liste vide ne heurte rien")
    void nomsDeclares() {
        JsonObject debris = definition(
                "{\"collision_group\": \"debris\", \"collides_with\": [\"world\", \"entity_proxy\"]}");
        assertEquals(1 << 3, CollisionFilter.membership(debris));
        assertEquals((1 << 0) | (1 << 4), CollisionFilter.filter(debris));
        assertEquals(0, CollisionFilter.filter(definition("{\"collides_with\": []}")));
        assertThrows(IllegalArgumentException.class, () -> CollisionFilter.bit("lourd"));
    }

    @Test
    @DisplayName("R-980 : chaque collider de PHYS, enfants compris, porte groupe et masque")
    void ecritureDansPhys() {
        byte[] phys = phys(2, 3, 1);
        byte[] filtered = CollisionFilter.apply(phys, 1 << 3, 1);
        int childrenAt = 4 + 2 * 104 + 4 + 3 * 12 + 4;
        for (int at : new int[] {4, 4 + 104, childrenAt}) {
            assertEquals(1 << 3, intAt(filtered, at + 72), "groupe à l'octet 72");
            assertEquals(1, intAt(filtered, at + 76), "masque à l'octet 76");
        }
        // L'entrée n'est pas modifiée ; le reste de la section non plus.
        assertEquals(0, intAt(phys, 4 + 72));
        assertEquals(3, intAt(filtered, 4 + 2 * 104));
    }

    @Test
    @DisplayName("R-980 : une section incohérente est rendue inchangée, le natif la juge")
    void sectionIncoherente() {
        byte[] phys = phys(1, 0, 0);
        ByteBuffer.wrap(phys).order(ByteOrder.LITTLE_ENDIAN).putInt(0, 9);
        assertArrayEquals(phys, CollisionFilter.apply(phys, 2, -1));
    }

    @Test
    @DisplayName("R-980 : l'ordre des groupes réservés est celui de ReservedGroup côté natif")
    void pariteAvecLeNatif() throws IOException {
        Path source = Path.of(System.getProperty("axion.crates.dir"), "ax-physics", "src", "groups.rs");
        String rust = Files.readString(source, StandardCharsets.UTF_8);
        Map<String, Integer> bits = new TreeMap<>();
        Map<String, String> names = new TreeMap<>();
        Matcher bit = Pattern.compile("ReservedGroup::(\\w+) => (\\d+),").matcher(rust);
        while (bit.find()) {
            bits.put(bit.group(1), Integer.parseInt(bit.group(2)));
        }
        Matcher name = Pattern.compile("ReservedGroup::(\\w+) => \"(\\w+)\",").matcher(rust);
        while (name.find()) {
            names.put(name.group(1), name.group(2));
        }
        assertEquals(8, bits.size(), () -> "bits lus : " + bits);
        assertEquals(bits.keySet(), names.keySet());
        List<String> byBit = new ArrayList<>(Arrays.asList(new String[8]));
        bits.forEach((variant, index) -> byBit.set(index, names.get(variant)));
        assertEquals(CollisionFilter.RESERVED, byBit);
        assertTrue(rust.contains("memberships: 1 << ReservedGroup::World.bit()"),
                "les tuiles du monde sont membres de `world`");
    }
}
