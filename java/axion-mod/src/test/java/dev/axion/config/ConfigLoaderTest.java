package dev.axion.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.axion.config.ConfigSchema.Option;
import dev.axion.config.ConfigSchema.Scope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** T-130..T-133 — chargement, surcharges et validation de la configuration (C-04). */
class ConfigLoaderTest {

    @TempDir
    Path configDir;

    private static Properties noProperties() {
        return new Properties();
    }

    @Test
    @DisplayName("T-130 : sans fichier ni surcharge, les défauts du schéma s'appliquent")
    void defautsAppliques() {
        AxionConfig config = ConfigLoader.load(Scope.COMMON, configDir, noProperties());

        assertTrue(config.getBoolean("general.enabled"));
        assertEquals(4L, config.getInt("sim.max_substeps"));
        assertEquals(-9.81, config.getFloat("physics.gravity"));
        assertEquals("auto", config.getString("deformation.quality"));
        assertTrue(config.issues().isEmpty(), "aucun problème attendu sans fichier");

        // Toute option déclarée est présente : une lecture ne peut pas échouer
        // faute de valeur.
        for (Option option : Scope.COMMON.options()) {
            assertTrue(
                    config.values().containsKey(option.path()),
                    () -> option.path() + " absent de la configuration résolue");
        }
    }

    @Test
    @DisplayName("T-131 : le fichier surcharge le défaut, une option absente le conserve")
    void fichierSurchargeLeDefaut() throws IOException {
        Files.writeString(
                configDir.resolve("axion-common.toml"),
                """
                [sim]
                max_substeps = 6

                [physics]
                gravity = -12.5
                """);

        AxionConfig config = ConfigLoader.load(Scope.COMMON, configDir, noProperties());

        assertEquals(6L, config.getInt("sim.max_substeps"));
        assertEquals(-12.5, config.getFloat("physics.gravity"));
        // Absente du fichier : le défaut tient. Un fichier partiel reste
        // valide, et une option ajoutée par une mise à jour ne casse rien.
        assertEquals(4, config.getInt("physics.velocity_iterations"));
        assertTrue(config.issues().isEmpty());
    }

    @Test
    @DisplayName("T-132 : une propriété -Daxion.* l'emporte sur le fichier")
    void proprieteSystemeSurchargeLeFichier() throws IOException {
        Files.writeString(
                configDir.resolve("axion-common.toml"),
                """
                [sim]
                max_substeps = 6
                """);

        Properties properties = noProperties();
        properties.setProperty("axion.sim.max_substeps", "2");
        properties.setProperty("axion.general.enabled", "false");

        AxionConfig config = ConfigLoader.load(Scope.COMMON, configDir, properties);

        assertEquals(2L, config.getInt("sim.max_substeps"));
        assertFalse(config.getBoolean("general.enabled"));
        assertTrue(config.issues().isEmpty());
    }

    @Test
    @DisplayName("T-133 : une valeur invalide est refusée, la précédente conservée et signalée")
    void valeurInvalideRefuseeEtSignalee() throws IOException {
        Files.writeString(
                configDir.resolve("axion-common.toml"),
                """
                [sim]
                max_substeps = 99

                [physics]
                gravity = 5.0

                [deformation]
                quality = "maximum"
                """);

        AxionConfig config = ConfigLoader.load(Scope.COMMON, configDir, noProperties());

        // Hors plage, hors plage, hors énumération : les trois sont refusées et
        // les défauts conservés.
        assertEquals(4L, config.getInt("sim.max_substeps"));
        assertEquals(-9.81, config.getFloat("physics.gravity"));
        assertEquals("auto", config.getString("deformation.quality"));

        assertEquals(3, config.issues().size(), () -> "problèmes relevés : " + config.issues());
        assertTrue(
                config.issues().stream().anyMatch(issue -> issue.path().equals("sim.max_substeps")),
                "sim.max_substeps devrait être signalé");
        // Le message dit ce qui était attendu, sinon il n'aide personne.
        assertTrue(
                config.issues().stream()
                        .filter(issue -> issue.path().equals("sim.max_substeps"))
                        .anyMatch(issue -> issue.message().contains("1..8")),
                () -> "le message devrait rappeler le domaine : " + config.issues());
    }

    @Test
    @DisplayName("Un type erroné est refusé comme une valeur hors plage")
    void typeErroneRefuse() {
        Properties properties = noProperties();
        properties.setProperty("axion.sim.max_substeps", "beaucoup");
        properties.setProperty("axion.general.enabled", "peut-être");
        // Un entier attendu n'accepte pas un flottant : « 4.7 » est une faute
        // de saisie, pas une valeur de 4.
        properties.setProperty("axion.physics.velocity_iterations", "4.7");

        AxionConfig config = ConfigLoader.load(Scope.COMMON, configDir, properties);

        assertEquals(4L, config.getInt("sim.max_substeps"));
        assertTrue(config.getBoolean("general.enabled"));
        assertEquals(4L, config.getInt("physics.velocity_iterations"));
        assertEquals(3, config.issues().size(), () -> "problèmes relevés : " + config.issues());
    }

    @Test
    @DisplayName("Un fichier illisible ne bloque pas le démarrage")
    void fichierIllisibleNeBloquePas() throws IOException {
        Files.writeString(configDir.resolve("axion-common.toml"), "ceci n'est pas du TOML [[[");

        AxionConfig config = ConfigLoader.load(Scope.COMMON, configDir, noProperties());

        assertEquals(4L, config.getInt("sim.max_substeps"));
        assertEquals(1, config.issues().size());
        assertTrue(config.issues().get(0).message().contains("illisible"));
    }

    @Test
    @DisplayName("Le fichier de référence est écrit une seule fois, et il est rechargeable")
    void fichierDeReferenceEcritUneFois() throws IOException {
        assertTrue(ConfigLoader.writeReferenceIfAbsent(Scope.COMMON, configDir));
        Path written = configDir.resolve("axion-common.toml");
        assertTrue(Files.exists(written));

        // Un second appel ne réécrit pas : les réglages de l'utilisateur ne
        // sont jamais écrasés.
        assertFalse(ConfigLoader.writeReferenceIfAbsent(Scope.COMMON, configDir));

        // Ce qui est écrit doit se relire sans produire le moindre problème :
        // le fichier livré est valide au regard du schéma qui l'a produit.
        AxionConfig config = ConfigLoader.load(Scope.COMMON, configDir, noProperties());
        assertTrue(config.issues().isEmpty(), () -> "problèmes relevés : " + config.issues());
        assertEquals(4L, config.getInt("sim.max_substeps"));
    }

    @Test
    @DisplayName("Les trois portées se chargent et déclarent des options")
    void lesTroisPorteesSeChargent() {
        for (Scope scope : Scope.values()) {
            AxionConfig config = ConfigLoader.load(scope, configDir, noProperties());
            assertFalse(
                    scope.options().isEmpty(),
                    () -> scope.fileName() + " ne déclare aucune option");
            assertEquals(
                    scope.options().size(),
                    config.values().size(),
                    () -> scope.fileName() + " : toutes les options devraient être résolues");
        }
    }
}
