package dev.axion.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
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

/** R-980 : les groupes réservés, et leur ordre, celui du natif. */
class CollisionFilterTest {

    @Test
    @DisplayName("R-980 : un groupe réservé a le bit de son rang ; un autre nom n'en a pas ici")
    void bitsReserves() {
        assertEquals(1, CollisionFilter.bit("world"));
        assertEquals(1 << 3, CollisionFilter.bit("debris"));
        assertEquals(1 << 7, CollisionFilter.bit("debug"));
        // Les groupes tiers se résolvent par leur registre (CollisionGroupRegistryTest).
        assertThrows(IllegalArgumentException.class, () -> CollisionFilter.bit("mymod:lourd"));
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
