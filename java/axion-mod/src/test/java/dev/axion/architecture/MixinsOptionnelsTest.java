package dev.axion.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * R-1921, ADR-177 D3 : aucune injection Mixin n'est obligatoire au démarrage, et (T-024, R-1920) toutes les cibles sont du
 * code Minecraft. {@code axion.mixins.json} ne déclare donc ni {@code required}, ni {@code defaultRequire} : un échec
 * d'injection dégrade, il n'arrête pas le jeu (T-656).
 */
class MixinsOptionnelsTest {

    private static JsonObject configuration() throws IOException {
        try (InputStream in = MixinsOptionnelsTest.class.getResourceAsStream("/axion.mixins.json")) {
            assertNotNull(in, "axion.mixins.json est dans les ressources du mod");
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    @Test
    @DisplayName("R-1921 : la configuration des Mixin n'exige aucune injection")
    void aucuneInjectionObligatoire() throws IOException {
        JsonObject config = configuration();
        assertFalse(config.get("required").getAsBoolean(), "un Mixin qui échoue ne doit pas arrêter le démarrage");
        assertEquals(0, config.getAsJsonObject("injectors").get("defaultRequire").getAsInt(),
                "aucune injection n'est obligatoire par défaut");
    }

    @Test
    @DisplayName("T-024 : aucune cible Mixin hors du code Minecraft (R-1920)")
    void ciblesDeMinecraftSeulement() throws IOException {
        Path sources = Path.of(System.getProperty("user.dir"), "src", "main", "java", "dev", "axion", "mixin");
        if (!Files.isDirectory(sources)) {
            // Aucune cible n'est encore ajoutée (ADR-177 D3) : la règle se lit sur les classes de ce paquet, quand il existe.
            return;
        }
        Pattern cible = Pattern.compile("@Mixin\\s*\\(([^)]*)\\)");
        try (Stream<Path> files = Files.walk(sources)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                Matcher m = cible.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (m.find()) {
                    assertFalse(m.group(1).contains("dev.axion") || m.group(1).contains("AxionEntity"),
                            file + " : une cible Mixin doit être du code Minecraft, pas du code de mod");
                    assertTrue(m.group(1).contains("net.minecraft") || m.group(1).contains(".class")
                            || m.group(1).contains("targets"), file + " : cible illisible " + m.group(1));
                }
            }
        }
    }
}
