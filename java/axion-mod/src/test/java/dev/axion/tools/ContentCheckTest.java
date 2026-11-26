package dev.axion.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.axion.api.content.ContentKind;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ADR-166 D4 : le validateur hors jeu fait tourner le moteur, natif compris, et rend les fautes d'un addon dans les
 * mots du journal du jeu.
 */
class ContentCheckTest {

    /** Les ressources de l'addon témoin, compilé à chaque build (CAP-49). */
    private static final Path TEMOIN = Path.of(System.getProperty("axion.temoin.resources",
            "../addon-temoin/src/main/resources"));

    /**
     * Le dossier de travail du runtime : hors du dossier temporaire du test, car la bibliothèque native qu'il y pose
     * reste chargée par la JVM, et Windows refuse alors de l'effacer.
     */
    private static final Path TRAVAIL = Path.of("build", "content-check-test");

    @TempDir
    Path temp;

    private Path ecrire(String relative, String contenu) throws IOException {
        Path fichier = temp.resolve("ressources").resolve(relative);
        Files.createDirectories(fichier.getParent());
        Files.writeString(fichier, contenu, StandardCharsets.UTF_8);
        return fichier;
    }

    @Test
    @DisplayName("ADR-166 D4 : le contenu du témoin se charge comme en jeu, sans faute")
    void leTemoinPasse() {
        ContentCheck.Report report = ContentCheck.check("example_addon", TEMOIN, TRAVAIL);
        assertEquals(List.of(), report.faults(), () -> String.join("\n", report.faults()));
        assertEquals(Map.of(ContentKind.DEFINITION, 5, ContentKind.MODEL, 1), report.counts(),
                "les deux caisses sur le même modèle ; les trois buggys d'ADR-182 sur celui d'AXION");
    }

    @Test
    @DisplayName("ADR-166 D4 : chaque faute est nommée — schéma, modèle absent, modèle illisible")
    void lesFautesSontNommees() throws IOException {
        Files.copy(TEMOIN.resolve("axion/models/crate.gltf"),
                Files.createDirectories(temp.resolve("ressources/axion/models")).resolve("crate.gltf"));
        ecrire("axion/definitions/crate.json",
                "{\"schema\": 3, \"asset\": \"fautif:models/crate.gltf\", \"kind\": \"rigid_object\"}");
        // Une masse hors de ses bornes : le schéma la refuse.
        ecrire("axion/definitions/plume.json", "{\"schema\": 3, \"asset\": \"fautif:models/crate.gltf\","
                + " \"kind\": \"rigid_object\", \"physics\": {\"mass\": -5}}");
        // Un modèle que personne ne fournit.
        ecrire("axion/definitions/fantome.json",
                "{\"schema\": 3, \"asset\": \"fautif:models/absent.glb\", \"kind\": \"rigid_object\"}");
        // Un fichier de modèle qui n'en est pas un.
        ecrire("axion/models/casse.glb", "pas un modèle");
        ecrire("axion/definitions/cassee.json",
                "{\"schema\": 3, \"asset\": \"fautif:models/casse.glb\", \"kind\": \"rigid_object\"}");

        ContentCheck.Report report = ContentCheck.check("fautif", temp.resolve("ressources"), TRAVAIL);

        String fautes = String.join("\n", report.faults());
        // Au rapport du test : ce que lit l'auteur d'un addon.
        report.faults().forEach(faute -> System.out.println("FAUTE : " + faute));
        assertTrue(fautes.contains("fautif:plume") && fautes.contains("mass"), fautes);
        assertTrue(fautes.contains("fautif:fantome"), fautes);
        assertTrue(fautes.contains("fautif:axion/models/casse.glb"), fautes);
        assertTrue(report.faults().stream().noneMatch(faute -> faute.startsWith("fautif:crate ")),
                "la definition saine passe : " + fautes);
    }

    @Test
    @DisplayName("CAP-42 : un contrôleur, un widget et un paramètre d'addon ne se jugent pas hors jeu — dits non vérifiés,"
            + " pas refusés")
    void leCodeDeLAddonNEstPasJuge() throws IOException {
        // Le véhicule de test, ses contrôleurs remplacés par celui de l'addon, dont le code ne se charge pas ici.
        String vehicule = Files.readString(Path.of("../../content/test/axion/definitions/test/vehicule_controle.json"),
                        StandardCharsets.UTF_8)
                .replace("\"schema\": 4", "\"schema\": 5")
                .replace("[\"axion:test/propulsion\", \"axion:test/compteur\"]", "[\"addon:regulateur\"]")
                .replace("\"vehicle\":", "\"tuning\": {\"ranges\": {\"addon_gain\": {\"min\": 0, \"max\": 2}}},"
                        + " \"dashboard\": {\"widgets\": [\"addon:altimetre\"]}, \"vehicle\":");
        ecrire("axion/definitions/regule.json", vehicule);
        dev.axion.definition.VehicleExtensions avant = dev.axion.definition.VehicleExtensions.current();

        ContentCheck.Report report = ContentCheck.check("addon", temp.resolve("ressources"), TRAVAIL);

        assertEquals(List.of(), report.faults(), () -> String.join("\n", report.faults()));
        String avertissements = String.join("\n", report.warnings());
        report.warnings().forEach(ligne -> System.out.println("AVERTISSEMENT : " + ligne));
        assertTrue(avertissements.contains("contrôleur « addon:regulateur » non vérifié"), avertissements);
        assertTrue(avertissements.contains("widget « addon:altimetre » non vérifié"), avertissements);
        assertTrue(avertissements.contains("paramètre « addon_gain » non vérifié"), avertissements);
        assertEquals(Map.of(ContentKind.DEFINITION, 1), report.counts());
        assertSame(avant, dev.axion.definition.VehicleExtensions.current(),
                "les extensions enregistrées sont rendues après la vérification");
    }
}
