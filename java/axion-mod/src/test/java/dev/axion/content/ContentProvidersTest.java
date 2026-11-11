package dev.axion.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.axion.api.content.ContentKind;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** ADR-162 D2, D9 : les fournisseurs gardés, et rappelés par {@code /axion dev reload}. */
class ContentProvidersTest {

    @TempDir
    Path temp;

    /** Chaque mod a son dossier sous {@code temp}, comme un mod qui tourne depuis ses sources. */
    private BiFunction<String, String, Path> sources() {
        return (mod, relative) -> {
            Path path = temp.resolve(mod).resolve(relative);
            return Files.exists(path) ? path : null;
        };
    }

    private void write(String mod, String relative, String content) throws IOException {
        Path path = temp.resolve(mod).resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private static Map<String, String> definitions(ContentRegistry registry) {
        Map<String, String> out = new java.util.TreeMap<>();
        for (ContentRegistry.Entry entry : registry.resolve().get(ContentKind.DEFINITION)) {
            out.put(entry.key(), new String(entry.bytes(), StandardCharsets.UTF_8));
        }
        return out;
    }

    @Test
    @DisplayName("ADR-162 D9 : rappelés, les fournisseurs relisent leur dossier depuis le disque")
    void rappelesIlsRelisentLeDisque() throws IOException {
        write("policemod", "axion/definitions/patrouille.json", "{\"v\":1}");
        ContentProviders providers = new ContentProviders(List.of(
                new ContentProviders.Provided("policemod", registrar -> registrar.registerFolder("axion/"))));
        List<String> failures = new ArrayList<>();

        ContentRegistry first = providers.registerAll(new ContentRegistry(Long.MAX_VALUE), sources(),
                (mod, failure) -> failures.add(mod));
        assertEquals(Map.of("policemod:axion/definitions/patrouille.json", "{\"v\":1}"), definitions(first));

        // Le développeur modifie un fichier, en ajoute un autre, et recharge.
        write("policemod", "axion/definitions/patrouille.json", "{\"v\":2}");
        write("policemod", "axion/definitions/depanneuse.json", "{\"v\":1}");
        ContentRegistry second = providers.registerAll(new ContentRegistry(Long.MAX_VALUE), sources(),
                (mod, failure) -> failures.add(mod));
        assertEquals(Map.of(
                "policemod:axion/definitions/depanneuse.json", "{\"v\":1}",
                "policemod:axion/definitions/patrouille.json", "{\"v\":2}"), definitions(second));

        // Un fichier retiré disparaît du contenu.
        Files.delete(temp.resolve("policemod/axion/definitions/depanneuse.json"));
        ContentRegistry third = providers.registerAll(new ContentRegistry(Long.MAX_VALUE), sources(),
                (mod, failure) -> failures.add(mod));
        assertEquals(Map.of("policemod:axion/definitions/patrouille.json", "{\"v\":2}"), definitions(third));
        assertEquals(List.of(), failures);
    }

    @Test
    @DisplayName("ADR-162 D2 : appelés dans l'ordre des modId ; un fournisseur qui échoue n'arrête pas les suivants")
    void ordreEtEchec() {
        List<String> calls = new ArrayList<>();
        ContentProviders providers = new ContentProviders(List.of(
                new ContentProviders.Provided("tracteurs", registrar -> calls.add("tracteurs")),
                new ContentProviders.Provided("bateaux", registrar -> {
                    calls.add("bateaux");
                    throw new IllegalStateException("panne du fournisseur");
                }),
                new ContentProviders.Provided("avions", registrar -> calls.add("avions"))));
        List<String> failures = new ArrayList<>();

        providers.registerAll(new ContentRegistry(Long.MAX_VALUE), sources(),
                (mod, failure) -> failures.add(mod + " : " + failure.getMessage()));

        assertEquals(List.of("avions", "bateaux", "tracteurs"), calls);
        assertEquals(List.of("bateaux : panne du fournisseur"), failures);
        assertEquals(List.of("avions", "bateaux", "tracteurs"), providers.modIds());
    }

    @Test
    @DisplayName("ADR-162 D3 : rappelé, un fournisseur reçoit un registraire neuf, dans son espace de noms")
    void unRegistraireNeufAChaqueAppel() {
        List<String> namespaces = new ArrayList<>();
        ContentProviders providers = new ContentProviders(List.of(
                new ContentProviders.Provided("policemod", registrar -> namespaces.add(registrar.modId()))));

        providers.registerAll(new ContentRegistry(Long.MAX_VALUE), sources(), (mod, failure) -> { });
        providers.registerAll(new ContentRegistry(Long.MAX_VALUE), sources(), (mod, failure) -> { });

        assertEquals(List.of("policemod", "policemod"), namespaces);
        assertTrue(providers.modIds().contains("policemod"));
    }
}
