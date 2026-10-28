package dev.axion.persist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Le journal latéral publié dans {@code docs/schema/persistence-3.md} est celui qu'écrit le code (R-1743,
 * ADR-153 D6) : un en-tête, un type ou un nom de fichier changé sans être reporté fait échouer le build.
 */
class PublishedJournalFormatTest {

    private static String published() throws IOException {
        String repo = System.getProperty("axion.repo.dir");
        assertTrue(repo != null && !repo.isBlank(), "axion.repo.dir non fourni par le build");
        Path file = Path.of(repo, "docs", "schema", "persistence-3.md");
        assertTrue(Files.isReadable(file), () -> "schéma de persistance publié absent : " + file);
        return Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static String group(String doc, String regex) {
        Matcher matcher = Pattern.compile(regex, Pattern.MULTILINE).matcher(doc);
        assertTrue(matcher.find(), () -> "le schéma publié ne dit pas : " + regex);
        return matcher.group(1);
    }

    @Test
    @DisplayName("R-1743 : en-tête, types et cadre d'un segment publiés sont ceux du code")
    void segment() throws IOException {
        String doc = published();
        assertEquals(new String(JournalStore.MAGIC, StandardCharsets.US_ASCII),
                group(doc, "magic \"([A-Z]+)\" \\(\\d+ octets ASCII\\)"));
        assertEquals(JournalStore.MAGIC.length + 2, JournalStore.HEADER, "en-tête publié : magic puis u16");
        assertEquals(JournalStore.VERSION, Integer.parseInt(group(doc, "version u16 = (\\d+)")));
        assertEquals(JournalRecord.PUT, Integer.parseInt(group(doc, "`(\\d+) : PUT`")));
        assertEquals(JournalRecord.REMOVE, Integer.parseInt(group(doc, "`(\\d+) : REMOVE`")));
        assertTrue(doc.contains("type u8 | longueur u32 | contenu | crc u32"), "cadre publié");
        assertEquals(1 + 4 + 4, JournalRecord.FRAMING, "cadre publié : type u8, longueur u32, crc u32");
        int remove = Integer.parseInt(group(doc, "Contenu d'un `REMOVE`\\*\\* : l'UUID seul, (\\d+) octets"));
        assertEquals(JournalRecord.FRAMING + remove,
                JournalRecord.encode(new JournalRecord.Remove(new UUID(1, 2))).length);
    }

    @Test
    @DisplayName("R-1743, ADR-153 D6 : emplacement, identifiant du monde et jeton publiés")
    void emplacement() throws IOException {
        String doc = published();
        assertTrue(doc.contains("/" + JournalToken.FILE + " "), "jeton publié : " + JournalToken.FILE);
        int chiffres = Integer.parseInt(group(doc, "`worldId` : les (\\d+) premiers chiffres hexadécimaux"));
        assertEquals(chiffres, WorldId.of("/mondes/essai", 42).length());
    }
}
