package dev.axion.definition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.axion.asset.AssetSource;
import dev.axion.asset.DefinitionCompiler;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-156 D3, D5 : la compilation des definitions, vue de Java, contre un faux natif. */
class DefinitionCompilationTest {

    private static final String PORTE = "mymod:axion/definitions/porte.json";
    private static final String CAISSE = "mymod:axion/definitions/caisse.json";
    private static final String MODELE = "mymod:axion/models/porte.glb";
    private static final byte[] A3D = {1, 2, 3};

    private static final DefinitionCompiler.Summary RESUME =
            new DefinitionCompiler.Summary(new int[] {0, 2}, 1, false, List.of());

    /** Le natif simulé : une issue par empreinte, OK par défaut. */
    private static final class FakeCompiler implements DefinitionCompiler {
        final Map<Long, Result> results = new HashMap<>();
        final List<Long> compiled = new ArrayList<>();
        final List<Long> removed = new ArrayList<>();
        final List<Long> assets = new ArrayList<>();

        @Override
        public Result compile(long definition, long assetId, byte[] a3d, byte[] defn) {
            compiled.add(definition);
            assets.add(assetId);
            return results.getOrDefault(definition, new Result(0, RESUME, -1, ""));
        }

        @Override
        public boolean remove(long definition) {
            removed.add(definition);
            return true;
        }
    }

    private static DefinitionRegistry registry(String... locations) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        for (String location : locations) {
            String json = location.equals(PORTE)
                    ? """
                      { "schema": 2, "asset": "mymod:models/porte.glb", "kind": "articulated",
                        "bodies": [ { "name": "cadre", "root_node": "cadre" } ],
                        "joints": [ { "name": "charniere", "type": "revolute", "a": "cadre", "b_node": "porte",
                                    "axis": [0, 1, 0] } ] }
                      """
                    : location.equals(CAISSE)
                            ? """
                              { "schema": 2, "asset": "mymod:models/porte.glb", "kind": "rigid_object" }
                              """
                            : """
                              { "schema": 2, "asset": "mymod:models/porte.glb", "kind": "articulated",
                                "bodies": [ { "name": "cadre" }, { "name": "battant" } ] }
                              """;
            files.put(location, json.getBytes(StandardCharsets.UTF_8));
        }
        AssetSource source = new AssetSource() {
            @Override
            public List<String> list() {
                return List.copyOf(files.keySet());
            }

            @Override
            public byte[] read(String path) {
                return files.get(path);
            }
        };
        return DefinitionRegistry.load(source, new DefinitionRules(true, Set.of(MODELE)::contains));
    }

    private static String id(String location) {
        return location.replace("axion/definitions/", "").replace(".json", "");
    }

    private static DefinitionCompilation.AssetStatus pret(String path) {
        return new DefinitionCompilation.AssetStatus(42L, A3D, false);
    }

    private static DefinitionCompilation.AssetStatus enCours(String path) {
        return new DefinitionCompilation.AssetStatus(42L, null, false);
    }

    @Test
    @DisplayName("ADR-156 D3 : une definition attend son asset, puis se compile contre lui")
    void attendSonAsset() {
        FakeCompiler natif = new FakeCompiler();
        DefinitionCompilation compilation = new DefinitionCompilation(natif, true, name -> 0);
        compilation.reset(registry(PORTE));

        assertFalse(compilation.pump(DefinitionCompilationTest::enCours, 0, System::nanoTime));
        assertFalse(compilation.isSettled());
        assertTrue(compilation.summary(id(PORTE)).isEmpty(), "rien tant que l'asset se compile");
        assertTrue(natif.compiled.isEmpty());

        assertTrue(compilation.pump(DefinitionCompilationTest::pret, 0, System::nanoTime));
        assertTrue(compilation.isSettled());
        assertEquals(List.of(DefinitionIds.hash(id(PORTE))), natif.compiled, "par son empreinte");
        assertEquals(List.of(42L), natif.assets, "contre son asset");
        assertEquals(0, compilation.summary(id(PORTE)).orElseThrow().rootNode());
        assertTrue(compilation.effective().get(id(PORTE)).isPresent());
    }

    @Test
    @DisplayName("R-580 : un refus du natif retire la definition, son chemin et son nom au journal")
    void refusDuNatif() {
        FakeCompiler natif = new FakeCompiler();
        natif.results.put(DefinitionIds.hash(id(PORTE)), new DefinitionCompiler.Result(
                DefinitionCompiler.E_DEFINITION_INVALID, null, 1, "aucun node de ce nom dans l'asset"));
        DefinitionCompilation compilation = new DefinitionCompilation(natif, true, name -> 0);
        compilation.reset(registry(PORTE, CAISSE));
        compilation.pump(DefinitionCompilationTest::pret, 0, System::nanoTime);

        DefinitionRegistry effective = compilation.effective();
        assertTrue(effective.get(id(PORTE)).isEmpty(), "refusée");
        assertTrue(effective.get(id(CAISSE)).isPresent(), "l'autre se compile");
        String ligne = "E-7001 " + PORTE + " — $.joints[0].b_node « porte » : aucun node de ce nom dans l'asset";
        assertTrue(effective.refusals().contains(ligne), () -> String.join("\n", effective.refusals()));
        assertEquals(List.of(ligne), compilation.drainJournal());
        assertEquals(List.of(), compilation.drainJournal(), "une fois");
    }

    @Test
    @DisplayName("R-1640 : sans son asset, la definition est désactivée (E-3001)")
    void assetIndisponible() {
        FakeCompiler natif = new FakeCompiler();
        DefinitionCompilation compilation = new DefinitionCompilation(natif, true, name -> 0);
        compilation.reset(registry(CAISSE));
        compilation.pump(path -> new DefinitionCompilation.AssetStatus(0, null, true), 0, System::nanoTime);
        assertTrue(compilation.effective().get(id(CAISSE)).isEmpty());
        assertTrue(compilation.effective().refusals().get(0).startsWith("E-3001 " + CAISSE + " — $.asset"));
        assertTrue(natif.compiled.isEmpty());
    }

    @Test
    @DisplayName("R-580 : ce que Java refuse avant le natif l'est à son chemin, sans appel natif")
    void refusDeJava() {
        String fautive = "mymod:axion/definitions/fautive.json";
        FakeCompiler natif = new FakeCompiler();
        DefinitionCompilation compilation = new DefinitionCompilation(natif, true, name -> 0);
        compilation.reset(registry(fautive));
        compilation.pump(DefinitionCompilationTest::pret, 0, System::nanoTime);
        assertTrue(natif.compiled.isEmpty());
        assertTrue(compilation.effective().refusals().get(0).startsWith("E-7001 " + fautive + " — $.bodies[1] : "));
    }

    @Test
    @DisplayName("ADR-156 D3 : un rechargement vide le registre natif, et tout attend de nouveau")
    void rechargement() {
        FakeCompiler natif = new FakeCompiler();
        DefinitionCompilation compilation = new DefinitionCompilation(natif, true, name -> 0);
        compilation.reset(registry(PORTE, CAISSE));
        compilation.pump(DefinitionCompilationTest::pret, 0, System::nanoTime);
        assertEquals(2, compilation.compiledCount());

        DefinitionRegistry suivante = registry(CAISSE);
        compilation.reset(suivante);
        assertEquals(Set.of(DefinitionIds.hash(id(PORTE)), DefinitionIds.hash(id(CAISSE))), Set.copyOf(natif.removed));
        assertEquals(0, compilation.compiledCount());
        assertFalse(compilation.isSettled());
        assertTrue(compilation.effective() == suivante, "la registry chargée, telle quelle, en attendant");
    }

    @Test
    @DisplayName("R-474 : sous le budget, une compilation au moins par appel, la suite au suivant")
    void budget() {
        FakeCompiler natif = new FakeCompiler();
        DefinitionCompilation compilation = new DefinitionCompilation(natif, true, name -> 0);
        compilation.reset(registry(PORTE, CAISSE));
        long[] maintenant = {0};
        // Chaque lecture de l'horloge avance de 10 ns ; le budget en vaut 5.
        java.util.function.LongSupplier horloge = () -> maintenant[0] += 10;
        compilation.pump(DefinitionCompilationTest::pret, 5, horloge);
        assertEquals(1, natif.compiled.size(), "une au moins, pas davantage");
        compilation.pump(DefinitionCompilationTest::pret, 5, horloge);
        assertEquals(2, natif.compiled.size());
        assertTrue(compilation.isSettled());
    }

    @Test
    @DisplayName("R-930 : les avertissements de la compilation vont au journal ; un échec de la frontière refuse")
    void avertissementsEtEchecs() {
        FakeCompiler natif = new FakeCompiler();
        natif.results.put(DefinitionIds.hash(id(PORTE)), new DefinitionCompiler.Result(0,
                new DefinitionCompiler.Summary(new int[] {0}, 0, false, List.of("racine animée (R-930)")), -1, ""));
        natif.results.put(DefinitionIds.hash(id(CAISSE)),
                new DefinitionCompiler.Result(-2002, null, -1, "tampon trop petit"));
        DefinitionCompilation compilation = new DefinitionCompilation(natif, true, name -> 0);
        compilation.reset(registry(PORTE, CAISSE));
        compilation.pump(DefinitionCompilationTest::pret, 0, System::nanoTime);
        List<String> journal = compilation.drainJournal();
        assertTrue(journal.contains("AXION : definition " + id(PORTE) + " : racine animée (R-930)"), journal::toString);
        assertTrue(journal.contains("E-2002 " + CAISSE + " — $ : compilation impossible : tampon trop petit"),
                journal::toString);
        assertTrue(compilation.effective().get(id(PORTE)).isPresent());
        assertTrue(compilation.effective().get(id(CAISSE)).isEmpty());
    }
}
