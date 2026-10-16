package dev.axion.diag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R-502, ADR-133 D1, ADR-144 — l'export des métriques, du serveur comme du client. */
class MetricsExportTest {

    private static final LocalDateTime INSTANT = LocalDateTime.of(2026, 10, 16, 3, 4, 5);

    @Test
    @DisplayName("R-502 : l'export s'écrit sous axion/, nommé par son côté et son instant")
    void lExportSEcritSousAxion(@TempDir Path jeu) throws IOException {
        String json = "{\"schema\":1,\"metrics\":[]}";

        Path serveur = MetricsExport.write(json, jeu, MetricsExport.SERVER_PREFIX, INSTANT);
        Path client = MetricsExport.write(json, jeu, MetricsExport.CLIENT_PREFIX, INSTANT);

        assertEquals(jeu.resolve("axion").resolve("metrics-20261016-030405.json"), serveur);
        assertEquals(jeu.resolve("axion").resolve("metrics-client-20261016-030405.json"), client);
        assertEquals(json, Files.readString(serveur, StandardCharsets.UTF_8));
        assertEquals(json, Files.readString(client, StandardCharsets.UTF_8),
                "au même instant, l'export du client n'écrase pas celui du serveur");
    }

    @Test
    @DisplayName("R-502 : un export qui ne peut pas s'écrire échoue, il ne se tait pas")
    void unExportImpossibleEchoue(@TempDir Path jeu) throws IOException {
        // `axion` existe déjà, mais comme fichier : le répertoire ne peut pas être créé.
        Files.writeString(jeu.resolve("axion"), "occupé");
        assertThrows(IOException.class,
                () -> MetricsExport.write("{}", jeu, MetricsExport.CLIENT_PREFIX, INSTANT));
    }
}
