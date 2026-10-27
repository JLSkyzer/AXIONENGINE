package dev.axion.persist;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Le jeton qui lie un journal latéral à son monde (ADR-153 D6). */
class JournalTokenTest {

    @TempDir
    Path racine;

    private final List<String> messages = new ArrayList<>();

    private Path journal() {
        return racine.resolve("0123456789abcdef");
    }

    private String jeton() throws IOException {
        return Files.readString(journal().resolve(JournalToken.FILE), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("Un monde sans journal : le dossier naît avec le jeton du monde")
    void premierDemarrage() throws IOException {
        JournalToken.reconcile(journal(), "jeton-a", 1, messages::add);
        assertEquals("jeton-a", jeton());
        assertEquals(List.of(), messages);
    }

    @Test
    @DisplayName("Un journal du même monde est gardé tel quel")
    void memeMonde() throws IOException {
        JournalToken.reconcile(journal(), "jeton-a", 1, messages::add);
        Files.write(journal().resolve("assemblies.0.journal"), new byte[] {1, 2, 3});
        JournalToken.reconcile(journal(), "jeton-a", 2, messages::add);
        assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(journal().resolve("assemblies.0.journal")));
        assertEquals(List.of(), messages);
    }

    @Test
    @DisplayName("R-1747 : le journal d'un autre monde de même chemin et de même graine est mis de côté, intact, et signalé")
    void autreMonde() throws IOException {
        JournalToken.reconcile(journal(), "jeton-a", 1, messages::add);
        Files.write(journal().resolve("assemblies.0.journal"), new byte[] {1, 2, 3});
        JournalToken.reconcile(journal(), "jeton-b", 42, messages::add);
        Path ecarte = racine.resolve("0123456789abcdef.ecarte-42");
        assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(ecarte.resolve("assemblies.0.journal")));
        assertFalse(Files.exists(journal().resolve("assemblies.0.journal")), "le monde neuf repart d'un journal vide");
        assertEquals("jeton-b", jeton());
        assertTrue(messages.stream().anyMatch(m -> m.contains("autre monde") && m.contains("ecarte-42")), messages.toString());
    }

    @Test
    @DisplayName("Un journal sans jeton, d'avant ce mécanisme, adopte celui du monde")
    void journalSansJeton() throws IOException {
        Files.createDirectories(journal());
        Files.write(journal().resolve("assemblies.0.journal"), new byte[] {9});
        JournalToken.reconcile(journal(), "jeton-a", 1, messages::add);
        assertEquals("jeton-a", jeton());
        assertArrayEquals(new byte[] {9}, Files.readAllBytes(journal().resolve("assemblies.0.journal")));
        assertEquals(List.of(), messages);
    }
}
