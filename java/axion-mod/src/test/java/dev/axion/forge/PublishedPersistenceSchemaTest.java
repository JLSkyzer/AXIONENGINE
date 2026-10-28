package dev.axion.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.axion.entity.AssemblyBinding;
import dev.axion.entity.CustomTable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Le NBT publié dans {@code docs/schema/persistence-3.md} est celui qu'écrit le code (PARTIE 22.2,
 * ADR-153 D2) : une clé, une taille ou une version changée sans être reportée fait échouer le build.
 */
class PublishedPersistenceSchemaTest {

    /** Une ligne du bloc NBT publié : la clé, puis son type. */
    private static final Pattern KEY_LINE = Pattern.compile("^(axion:[a-z_]+)\\s", Pattern.MULTILINE);

    static String published() throws IOException {
        String repo = System.getProperty("axion.repo.dir");
        assertTrue(repo != null && !repo.isBlank(), "axion.repo.dir non fourni par le build");
        Path file = Path.of(repo, "docs", "schema", "persistence-" + AssemblyBinding.NBT_SCHEMA + ".md");
        assertTrue(Files.isReadable(file), () -> "schéma de persistance publié absent : " + file);
        // Git peut convertir les fins de ligne à l'extraction : seul le contenu compte.
        return Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    static long number(String doc, String regex) {
        Matcher matcher = Pattern.compile(regex, Pattern.MULTILINE).matcher(doc);
        assertTrue(matcher.find(), () -> "le schéma publié ne dit pas : " + regex);
        return Long.parseLong(matcher.group(1).replaceAll("\\s", ""));
    }

    @Test
    @DisplayName("ADR-153 D2 : le bloc publié porte exactement les clés qu'écrit une assembly")
    void clesPubliees() throws IOException {
        CompoundTag stored = AssemblyTags.bound(new CompoundTag(), 0x1234_5678_9abc_def0L);
        new PersistedState(
                        new double[] {1.5, 64.0, -3.25},
                        new float[] {0f, 0f, 0f, 1f},
                        new float[] {0.5f, -2.0f, 0.125f},
                        new float[] {0f, 1.25f, -0.75f},
                        true,
                        List.of(new PersistedState.Body(7, 2.5, 63.0, -3.0, new float[] {0f, 0f, 0f, 1f},
                                new float[] {1f, 2f, 3f}, new float[] {-1f, -2f, -3f})),
                        List.of(new PersistedState.Joint(true, 0.5f)))
                .write(stored);
        CustomTable custom = CustomTable.read(null, () -> 16_384, false);
        assertTrue(custom.put("demo", new byte[] {1, 2, 3}));
        AssemblyTags.putCustom(stored, custom);

        Set<String> publiees = new TreeSet<>();
        Matcher matcher = KEY_LINE.matcher(published());
        while (matcher.find()) {
            publiees.add(matcher.group(1));
        }
        assertEquals(new TreeSet<>(stored.getAllKeys()), publiees);
    }

    @Test
    @DisplayName("ADR-153 D2 et D5 : version, tailles et drapeaux publiés sont ceux du code")
    void valeursPubliees() throws IOException {
        String doc = published();
        assertEquals(AssemblyBinding.NBT_SCHEMA, number(doc, "^axion:v\\s+int\\s+(\\d+)$"));
        assertEquals(PersistedState.Body.BYTES, number(doc, "^axion:bodies\\s+byte\\[\\]\\s+(\\d+) octets"));
        assertEquals(PersistedState.Joint.BYTES, number(doc, "^axion:joints\\s+byte\\[\\]\\s+(\\d+) octets"));
        assertEquals(PersistedState.SLEEPING, 1L << number(doc, "^axion:flags\\s+int\\s+bit (\\d+) : endormie"));
        assertEquals(PersistedState.Joint.BROKEN, 1L << number(doc, "`flags u8` \\(bit (\\d+) : rompu"));
    }

    @Test
    @DisplayName("R-1711, ADR-153 D6 : registre du monde et plancher de compactage publiés")
    void journalCoteForge() throws IOException {
        String doc = published();
        assertTrue(doc.contains("`data/" + AxionRegistryData.NAME + ".dat`"),
                "le SavedData publié n'est pas " + AxionRegistryData.NAME);
        assertEquals(JournalBridge.COMPACTION_FLOOR, number(doc, "plancher de ([\\d\\s]+) octets"));
    }
}
