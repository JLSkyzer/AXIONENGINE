package dev.axion.definition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.axion.asset.AssetSource;
import dev.axion.definition.PhysicsMaterials.Combine;
import dev.axion.definition.PhysicsMaterials.Field;
import dev.axion.definition.PhysicsMaterials.Flag;
import dev.axion.definition.PhysicsMaterials.Material;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T-840, T-840b — DM-07 : matériaux physiques data-driven, héritage, unités (ADR-145). */
class PhysicsMaterialsTest {

    /** Matériaux simulés, par identifiant ; {@code null} pour un fichier illisible. */
    private static final class FakeSource implements AssetSource {
        private final Map<String, byte[]> files = new LinkedHashMap<>();

        FakeSource material(String id, String content) {
            int colon = id.indexOf(':');
            return put(id.substring(0, colon) + ":" + PhysicsMaterials.PREFIX + id.substring(colon + 1)
                    + PhysicsMaterials.EXTENSION, content);
        }

        FakeSource put(String location, String content) {
            files.put(location, content == null ? null : content.getBytes(StandardCharsets.UTF_8));
            return this;
        }

        @Override
        public List<String> list() {
            return List.copyOf(files.keySet());
        }

        @Override
        public byte[] read(String path) throws IOException {
            byte[] content = files.get(path);
            if (content == null) {
                throw new IOException("disparu");
            }
            return content;
        }
    }

    private static String seulRefus(PhysicsMaterials materials) {
        assertEquals(1, materials.refusals().size(), materials.refusals()::toString);
        return materials.refusals().get(0);
    }

    @Test
    @DisplayName("T-840 : un champ absent se prend au plus proche ancêtre qui le déclare, champ par champ")
    void heritageChampParChamp() {
        PhysicsMaterials materials = PhysicsMaterials.load(new FakeSource()
                .material("mymod:metal", "{\"density\": 7000, \"friction\": 0.6, \"flags\": [\"ductile\"]}")
                .material("mymod:alu", "{\"parent\": \"mymod:metal\", \"density\": 2700}")
                .material("mymod:alu_poli", "{\"parent\": \"mymod:alu\", \"friction\": 0.3,"
                        + " \"friction_combine\": \"min\"}"));
        assertTrue(materials.refusals().isEmpty(), materials.refusals()::toString);
        assertEquals(List.of("mymod:alu", "mymod:alu_poli", "mymod:metal"), materials.ids());

        Material poli = materials.get("mymod:alu_poli").orElseThrow();
        assertEquals(0.3, poli.friction(), "déclaré");
        assertEquals(2700, poli.number(Field.DENSITY), "hérité du parent");
        assertEquals(Set.of(Flag.DUCTILE), poli.flags(), "hérité du grand-parent");
        assertEquals(Combine.MIN, poli.combine(Field.FRICTION_COMBINE));

        // Ce qu'aucun ancêtre ne déclare vient de l'acier générique du moteur (R-1032).
        Material generique = PhysicsMaterials.genericSteel();
        assertEquals(generique.number(Field.STIFFNESS), poli.number(Field.STIFFNESS));
        assertEquals(generique.combine(Field.RESTITUTION_COMBINE), poli.combine(Field.RESTITUTION_COMBINE));
        assertEquals(Optional.empty(), poli.group(Field.SOUND_GROUP));
    }

    @Test
    @DisplayName("R-1032 : un matériau non déclaré se résout en acier générique")
    void materiauNonDeclare() {
        PhysicsMaterials materials = PhysicsMaterials.load(new FakeSource().material("mymod:a", "{}"));
        assertTrue(materials.knows("mymod:a"));
        assertFalse(materials.knows("mymod:absent"));
        assertSame(PhysicsMaterials.genericSteel(), materials.resolve("mymod:absent"));
        assertSame(PhysicsMaterials.genericSteel(), PhysicsMaterials.empty().resolve("axion:steel"),
                "le moteur ne connaît aucun nom de matériau (R-1030)");
    }

    @Test
    @DisplayName("R-1038 : hardness_pressure non déclaré vaut 3 × yield_strength, dit une fois par matériau")
    void relationDeTabor() {
        PhysicsMaterials materials = PhysicsMaterials.load(new FakeSource()
                .material("mymod:mou", "{\"yield_strength\": 2.0e8}")
                .material("mymod:dur", "{\"yield_strength\": 2.0e8, \"hardness_pressure\": 9.0e8}")
                .material("mymod:herite", "{\"parent\": \"mymod:dur\", \"yield_strength\": 1.0e8}"));
        assertEquals(6.0e8, materials.resolve("mymod:mou").number(Field.HARDNESS_PRESSURE));
        assertEquals(9.0e8, materials.resolve("mymod:dur").number(Field.HARDNESS_PRESSURE));
        assertEquals(9.0e8, materials.resolve("mymod:herite").number(Field.HARDNESS_PRESSURE),
                "une valeur héritée est déclarée pour la chaîne");
        assertEquals(1, materials.notes().size(), materials.notes()::toString);
        assertTrue(materials.notes().get(0).startsWith("mymod:mou : hardness_pressure non déclaré"),
                materials.notes()::toString);

        // Bornée à la plage du champ : [1e5, 1e11] Pa.
        assertEquals(1e5, PhysicsMaterials.load(new FakeSource().material("mymod:mousse",
                "{\"yield_strength\": 10}")).resolve("mymod:mousse").number(Field.HARDNESS_PRESSURE));
    }

    @Test
    @DisplayName("T-840b : chaque champ est jugé contre son unité et sa plage ; hors plage, refusé et nommé")
    void unitesEtPlages() {
        assertEquals("kg/m³", Field.DENSITY.unit());
        assertEquals("Pa", Field.STIFFNESS.unit());
        assertEquals("J/m²", Field.FRACTURE_ENERGY.unit());
        assertEquals("m", Field.INDENTER_RADIUS_REF.unit());

        for (String[] cas : new String[][] {
            {"\"friction\": 2.5", "$.friction"},
            {"\"restitution\": -0.1", "$.restitution"},
            {"\"density\": 0", "$.density"},
            {"\"stiffness\": -1", "$.stiffness"},
            {"\"deformation_resistance\": 0.001", "$.deformation_resistance"},
            {"\"spread_factor\": 9", "$.spread_factor"},
            {"\"hardness_pressure\": 1e12", "$.hardness_pressure"},
            {"\"elasticity\": 1.5", "$.elasticity"},
            {"\"density\": \"lourd\"", "$.density"},
        }) {
            String refus = seulRefus(PhysicsMaterials.load(new FakeSource()
                    .material("mymod:a", "{" + cas[0] + "}")));
            assertTrue(refus.startsWith("E-7001 mymod:axion/physics_materials/a.json — " + cas[1] + " :"),
                    refus);
        }
        assertTrue(seulRefus(PhysicsMaterials.load(new FakeSource().material("mymod:a", "{\"friction\": 2.5}")))
                .contains("[0, 2]"), "la plage est dite");
        // Les bornes elles-mêmes sont admises.
        PhysicsMaterials bornes = PhysicsMaterials.load(new FakeSource().material("mymod:a",
                "{\"friction\": 2, \"restitution\": 0, \"deformation_resistance\": 0.01,"
                        + " \"hardness_pressure\": 1e11}"));
        assertTrue(bornes.refusals().isEmpty(), bornes.refusals()::toString);
    }

    @Test
    @DisplayName("ADR-145 : JSON strict — clés de DM-07 seules, modes, drapeaux et groupes nommés")
    void jsonStrict() {
        assertTrue(seulRefus(PhysicsMaterials.load(new FakeSource().material("mymod:a", "{\"durete\": 1}")))
                .contains("$.durete : champ inconnu"));
        assertTrue(seulRefus(PhysicsMaterials.load(new FakeSource().material("mymod:a", "[]")))
                .contains("— $ : un matériau physique est un objet JSON"));
        assertTrue(seulRefus(PhysicsMaterials.load(new FakeSource().material("mymod:a",
                "{\"friction_combine\": \"moyenne\"}"))).contains("admis : average, min, max, multiply"));
        assertTrue(seulRefus(PhysicsMaterials.load(new FakeSource().material("mymod:a",
                "{\"flags\": [\"ductile\", \"mou\"]}"))).contains("$.flags[1] :"));
        assertTrue(seulRefus(PhysicsMaterials.load(new FakeSource().material("mymod:a",
                "{\"sound_group\": \"Metal\"}"))).contains("n'est pas un identifiant"));
        assertTrue(seulRefus(PhysicsMaterials.load(new FakeSource().material("mymod:a",
                "{\"parent\": \"mymod:a\"}"))).contains("n'hérite pas de lui-même"));
        assertEquals("E-7001 mymod:axion/physics_materials/a.json — illisible : disparu",
                seulRefus(PhysicsMaterials.load(new FakeSource().material("mymod:a", null))));
        assertTrue(seulRefus(PhysicsMaterials.load(new FakeSource().put("mymod:axion/autre/a.json", "{}")))
                .contains("ressource hors de <ns>:axion/physics_materials/"));

        Material groupes = PhysicsMaterials.load(new FakeSource().material("mymod:a",
                "{\"sound_group\": \"mymod:metal\", \"particle_group\": \"mymod:etincelles\"}"))
                .resolve("mymod:a");
        assertEquals(Optional.of("mymod:metal"), groupes.group(Field.SOUND_GROUP));
        assertEquals(Optional.of("mymod:etincelles"), groupes.group(Field.PARTICLE_GROUP));
    }

    @Test
    @DisplayName("R-1031 : un parent inconnu ou un cycle refusent le matériau, et ceux qui en héritent")
    void heritageImpossible() {
        PhysicsMaterials materials = PhysicsMaterials.load(new FakeSource()
                .material("mymod:a", "{\"parent\": \"mymod:absent\"}")
                .material("mymod:b", "{\"parent\": \"mymod:a\"}")
                .material("mymod:c", "{\"parent\": \"mymod:d\"}")
                .material("mymod:d", "{\"parent\": \"mymod:c\"}")
                .material("mymod:sain", "{}"));
        assertEquals(List.of("mymod:sain"), materials.ids(), materials.refusals()::toString);
        assertEquals(4, materials.refusals().size(), materials.refusals()::toString);
        assertTrue(materials.refusals().stream().anyMatch(r -> r.contains("a.json — $.parent : matériau parent « mymod:absent » inconnu")),
                materials.refusals()::toString);
        assertTrue(materials.refusals().stream().anyMatch(r -> r.contains("c.json — $.parent : héritage circulaire")),
                materials.refusals()::toString);
    }

    @Test
    @DisplayName("R-1032 : les definitions livrées ne nomment que des matériaux de la bibliothèque")
    void lesDefinitionsLivreesNommentDesMateriauxDeclares() throws IOException {
        java.nio.file.Path data = java.nio.file.Path.of(System.getProperty("axion.repo.dir"),
                "java", "axion-mod", "src", "main", "resources", "data", "axion");
        PhysicsMaterials bibliotheque = PhysicsMaterials.load(dossier(data, "axion:", PhysicsMaterials.PREFIX));
        assertTrue(bibliotheque.refusals().isEmpty(), bibliotheque.refusals()::toString);

        DefinitionRegistry definitions = DefinitionRegistry.load(
                dossier(data, "axion:", DefinitionRegistry.PREFIX),
                new DefinitionRules(true, asset -> true, true, CollisionGroupRegistry.empty(), bibliotheque));
        assertEquals(5, definitions.size(), definitions.refusals()::toString);
        assertTrue(definitions.warnings().isEmpty(), definitions.warnings()::toString);
    }

    /** {@return les fichiers JSON d'un dossier de données, comme le jeu les énumère} */
    private static AssetSource dossier(java.nio.file.Path data, String namespace, String prefix)
            throws IOException {
        Map<String, java.nio.file.Path> fichiers = new java.util.TreeMap<>();
        java.nio.file.Path racine = data.resolve(prefix);
        try (java.util.stream.Stream<java.nio.file.Path> parcours = java.nio.file.Files.walk(racine)) {
            for (java.nio.file.Path fichier : parcours.filter(java.nio.file.Files::isRegularFile).toList()) {
                fichiers.put(namespace + data.relativize(fichier).toString().replace('\\', '/'), fichier);
            }
        }
        return new AssetSource() {
            @Override
            public List<String> list() {
                return List.copyOf(fichiers.keySet());
            }

            @Override
            public byte[] read(String path) throws IOException {
                return java.nio.file.Files.readAllBytes(fichiers.get(path));
            }
        };
    }

    @Test
    @DisplayName("ADR-145 : l'empreinte suit les valeurs résolues")
    void empreinte() {
        String base = PhysicsMaterials.load(new FakeSource().material("mymod:a", "{\"friction\": 0.4}")).fingerprint();
        assertEquals(base, PhysicsMaterials.load(new FakeSource().material("mymod:a", "{\"friction\": 0.4}")).fingerprint());
        assertNotEquals(base, PhysicsMaterials.load(new FakeSource().material("mymod:a", "{\"friction\": 0.5}")).fingerprint());
        assertNotEquals(base, PhysicsMaterials.empty().fingerprint());
    }
}
