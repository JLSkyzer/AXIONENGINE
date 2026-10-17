package dev.axion.definition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.axion.asset.AssetSource;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PhysicsMaterialTableTest {

    /** Des matériaux de pack, par identifiant. */
    private static PhysicsMaterials registry(Map<String, String> materials) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        materials.forEach((id, json) -> {
            int colon = id.indexOf(':');
            files.put(id.substring(0, colon) + ":" + PhysicsMaterials.PREFIX + id.substring(colon + 1)
                    + PhysicsMaterials.EXTENSION, json.getBytes(StandardCharsets.UTF_8));
        });
        return PhysicsMaterials.load(new AssetSource() {
            @Override
            public List<String> list() {
                return List.copyOf(files.keySet());
            }

            @Override
            public byte[] read(String path) {
                return files.get(path);
            }
        });
    }

    private static ByteBuffer entry(PhysicsMaterialTable.Encoded table, int index) {
        return ByteBuffer.wrap(table.entries(), index * PhysicsMaterialTable.ENTRY_BYTES,
                        PhysicsMaterialTable.ENTRY_BYTES)
                .slice()
                .order(ByteOrder.LITTLE_ENDIAN);
    }

    @Test
    @DisplayName("ADR-149 D1 : l'acier générique à 0, puis chaque matériau garde son index pour la session")
    void lesIndexSontStablesPourLaSession() {
        PhysicsMaterialTable table = new PhysicsMaterialTable();
        assertEquals(1, table.size(), "l'acier générique, seul");
        table.update(registry(Map.of("mymod:wood", "{}", "mymod:ice", "{}")));
        // Dans l'ordre des identifiants.
        assertEquals(1, table.indexOf("mymod:ice"));
        assertEquals(2, table.indexOf("mymod:wood"));
        assertEquals(PhysicsMaterialTable.GENERIC_STEEL, table.indexOf("mymod:absent"), "non déclaré : R-1032");
        assertEquals(PhysicsMaterialTable.GENERIC_STEEL, table.indexOf(null));

        // Un rechargement : un nouveau prend la suite, un retiré garde son entrée.
        table.update(registry(Map.of("mymod:wood", "{}", "mymod:amber", "{}")));
        assertEquals(2, table.indexOf("mymod:wood"), "index gardé");
        assertEquals(3, table.indexOf("mymod:amber"), "nouveau : à la suite");
        assertEquals(PhysicsMaterialTable.GENERIC_STEEL, table.indexOf("mymod:ice"),
                "retiré, il n'est plus déclaré : qui le nomme prend l'acier générique");
        assertEquals(4, table.size(), "l'entrée du retiré reste, pour les corps qui la portent");
        ByteBuffer retire = entry(table.takeIfChanged(), 1);
        assertEquals(DefinitionIds.hash("mymod:ice"), retire.getLong(0));
        assertEquals((float) PhysicsMaterials.genericSteel().friction(), retire.getFloat(8),
                "l'entrée du retiré vaut l'acier générique");
    }

    @Test
    @DisplayName("ADR-149 D1 : la table ne repart que si elle a changé, ou pour une session neuve")
    void laTableNeRepartQueSiElleAChange() {
        PhysicsMaterialTable table = new PhysicsMaterialTable();
        assertNotNull(table.takeIfChanged(), "première émission");
        assertNull(table.takeIfChanged(), "rien de neuf");
        table.update(registry(Map.of("mymod:wood", "{}")));
        assertEquals(2, table.takeIfChanged().count());
        assertNull(table.takeIfChanged());
        table.markChanged();
        assertEquals(2, table.takeIfChanged().count(), "session native neuve");
    }

    @Test
    @DisplayName("ADR-149 : chaque entrée est un DM-07 de 120 octets, modes aux valeurs du solveur")
    void lEncodageSuitDm07() {
        PhysicsMaterialTable table = new PhysicsMaterialTable();
        table.update(registry(Map.of("mymod:balle", "{\"friction\": 0.4, \"restitution\": 0.8,"
                + " \"rolling_friction\": 0.02, \"friction_combine\": \"multiply\","
                + " \"restitution_combine\": \"max\", \"density\": 1100,"
                + " \"sound_group\": \"mymod:caoutchouc\", \"flags\": [\"soft\", \"non_deformable\"]}")));
        PhysicsMaterialTable.Encoded encoded = table.takeIfChanged();
        assertEquals(2 * PhysicsMaterialTable.ENTRY_BYTES, encoded.entries().length);

        ByteBuffer acier = entry(encoded, 0);
        assertEquals(0L, acier.getLong(0), "l'acier générique n'a pas de nom");
        assertEquals((float) PhysicsMaterials.genericSteel().friction(), acier.getFloat(8));

        ByteBuffer balle = entry(encoded, 1);
        assertEquals(DefinitionIds.hash("mymod:balle"), balle.getLong(0));
        assertEquals(0.4f, balle.getFloat(8), "friction");
        assertEquals(0.8f, balle.getFloat(12), "restitution");
        assertEquals(0.02f, balle.getFloat(16), "rolling_friction");
        // MULTIPLY vaut 2 et MAX 3 sur la frontière, quel que soit l'ordre de l'énumération Java.
        assertEquals(2, balle.get(20), "friction_combine");
        assertEquals(3, balle.get(21), "restitution_combine");
        assertEquals(0, balle.getShort(22), "_pad0");
        assertEquals(1100f, balle.getFloat(24), "density");
        assertEquals((int) DefinitionIds.hash("mymod:caoutchouc"), balle.getInt(104), "sound_group");
        assertEquals(0, balle.getInt(108), "sans groupe de particules");
        assertEquals((1 << 2) | (1 << 5), balle.getInt(112), "SOFT | NON_DEFORMABLE");
        assertEquals(0, balle.getInt(116), "alignement");
    }
}
