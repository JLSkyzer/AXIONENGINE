package dev.axion.asset;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.axion.asset.AssetCompiler.CompileStatus;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** T-210..T-214 — orchestration des assets (C-20). */
class AssetRegistryTest {

    private static final int COMPILER_VERSION = 1;

    /** Sources simulées, que le test compose à sa guise. */
    private static final class FakeSource implements AssetSource {
        private final Map<String, byte[]> files = new LinkedHashMap<>();
        private final List<String> illisibles = new ArrayList<>();

        FakeSource put(String path, String content) {
            files.put(path, content.getBytes(StandardCharsets.UTF_8));
            return this;
        }

        @Override
        public List<String> list() {
            return List.copyOf(files.keySet());
        }

        @Override
        public byte[] read(String path) throws IOException {
            if (illisibles.contains(path)) {
                throw new IOException("disparue entre l'énumération et la lecture");
            }
            return files.get(path);
        }
    }

    /**
     * Compilateur simulé : chaque travail aboutit après un nombre de sondages
     * fixé, ou échoue.
     */
    private static final class FakeCompiler implements AssetCompiler {
        private final List<Long> submitted = new ArrayList<>();
        private final Map<Integer, Integer> restants = new LinkedHashMap<>();
        private int nextJob = 1;
        private int delay;
        private int refuseSubmit;
        private boolean failCompile;

        @Override
        public int submit(long assetId, int format, byte[] source) {
            if (refuseSubmit != 0) {
                return refuseSubmit;
            }
            submitted.add(assetId);
            int job = nextJob++;
            restants.put(job, delay);
            return job;
        }

        @Override
        public CompileStatus poll(int jobId) {
            int reste = restants.getOrDefault(jobId, 0);
            if (reste > 0) {
                restants.put(jobId, reste - 1);
                return CompileStatus.pending();
            }
            restants.remove(jobId);
            return failCompile ? CompileStatus.failed(-3050) : CompileStatus.compiled(new byte[1024]);
        }
    }

    /** Horloge simulée : chaque lecture avance d'un pas fixe. */
    private static final class FakeClock {
        private long now;
        private long step;

        long tick() {
            long value = now;
            now += step;
            return value;
        }
    }

    private static AssetRegistry registry(FakeSource source, FakeCompiler compiler) {
        return new AssetRegistry(source, compiler, COMPILER_VERSION, () -> 0L);
    }

    @Test
    @DisplayName("T-210 : les sources reconnues sont découvertes, les autres ignorées")
    void decouverteDesSources() {
        FakeSource source = new FakeSource()
                .put("axion/models/voiture.obj", "v 0 0 0")
                .put("axion/models/roue.glb", "glTF...")
                .put("axion/models/deja.a3d", "A3D")
                .put("axion/models/notes.txt", "bonjour");

        AssetRegistry registry = registry(source, new FakeCompiler());
        assertEquals(2, registry.discover());

        // Un `.a3d` est déjà le résultat d'une compilation ; le repasser au
        // compilateur serait compiler une sortie.
        assertEquals(2, registry.entries().size());
        assertEquals(SourceFormats.OBJ, registry.entry("axion/models/voiture.obj").format());
        assertEquals(SourceFormats.GLB, registry.entry("axion/models/roue.glb").format());
    }

    @Test
    @DisplayName("T-211 : une compilation traverse tous les états de SM-01")
    void cycleCompletDUnAsset() {
        FakeSource source = new FakeSource().put("m.obj", "v 0 0 0");
        FakeCompiler compiler = new FakeCompiler();
        compiler.delay = 2;

        AssetRegistry registry = registry(source, compiler);
        registry.discover();
        AssetEntry entry = registry.entry("m.obj");
        assertEquals(AssetState.DISCOVERED, entry.state());

        assertEquals(1, registry.pump(0).submitted());
        assertEquals(AssetState.COMPILING, entry.state());
        assertFalse(registry.isSettled());

        registry.pump(0);
        assertEquals(AssetState.COMPILING, entry.state(), "aboutie trop tôt");
        registry.pump(0);
        assertEquals(AssetState.COMPILING, entry.state());

        assertEquals(1, registry.pump(0).completed());
        assertEquals(AssetState.COMPILED, entry.state());
        assertEquals(1024, entry.compiledSize());
        assertTrue(registry.isSettled());
    }

    @Test
    @DisplayName("T-212 : R-520, seuls les assets dont la clé a changé sont refaits")
    void seulsLesAssetsModifiesSontRefaits() {
        FakeSource source = new FakeSource().put("a.obj", "v 0 0 0").put("b.obj", "v 1 1 1");
        FakeCompiler compiler = new FakeCompiler();

        AssetRegistry registry = registry(source, compiler);
        assertEquals(2, registry.discover());
        registry.pump(0);
        registry.pump(0);
        assertTrue(registry.isSettled());

        // Rechargement sans modification : rien à refaire.
        assertEquals(0, registry.discover());
        assertEquals(AssetState.COMPILED, registry.entry("a.obj").state());

        // Une seule source change.
        source.put("b.obj", "v 2 2 2");
        assertEquals(1, registry.discover());
        assertEquals(AssetState.COMPILED, registry.entry("a.obj").state(), "a.obj refait à tort");
        assertEquals(AssetState.DISCOVERED, registry.entry("b.obj").state());
    }

    @Test
    @DisplayName("T-212 : une source disparue quitte le registre")
    void uneSourceDisparueQuitteLeRegistre() {
        FakeSource source = new FakeSource().put("a.obj", "v 0 0 0").put("b.obj", "v 1 1 1");
        AssetRegistry registry = registry(source, new FakeCompiler());
        registry.discover();
        assertEquals(2, registry.entries().size());

        source.files.remove("b.obj");
        registry.discover();
        // La retirer est ce qui distingue un pack rechargé d'un pack qui
        // grossit sans fin.
        assertEquals(1, registry.entries().size());
        assertEquals("a.obj", registry.entries().get(0).path());
    }

    @Test
    @DisplayName("T-213 : R-521, le budget borne le travail d'un tick")
    void leBudgetBorneLeTravailDUnTick() {
        FakeSource source = new FakeSource();
        for (int index = 0; index < 10; index++) {
            source.put("m" + index + ".obj", "v " + index + " 0 0");
        }
        FakeClock clock = new FakeClock();
        clock.step = 100;

        FakeCompiler compiler = new FakeCompiler();
        AssetRegistry registry =
                new AssetRegistry(source, compiler, COMPILER_VERSION, clock::tick);
        registry.discover();

        // Budget de 250 ns, pas de 100 ns : le tick s'arrête avant d'avoir tout
        // soumis.
        AssetRegistry.PumpResult result = registry.pump(250);
        assertTrue(result.submitted() < 10, "le budget n'a rien borné");
        assertTrue(result.submitted() > 0, "le tick n'a rien fait");
        assertFalse(registry.isSettled());
    }

    @Test
    @DisplayName("T-213 : un budget nul lève toute limite")
    void unBudgetNulLeveTouteLimite() {
        FakeSource source = new FakeSource();
        for (int index = 0; index < 10; index++) {
            source.put("m" + index + ".obj", "v " + index + " 0 0");
        }
        FakeClock clock = new FakeClock();
        clock.step = 1_000_000;

        AssetRegistry registry =
                new AssetRegistry(source, new FakeCompiler(), COMPILER_VERSION, clock::tick);
        registry.discover();

        // C'est ce qu'emploie la barrière de démarrage de R-521.
        assertEquals(10, registry.pump(0).submitted());
    }

    @Test
    @DisplayName("T-213 : un dépassement de budget est marqué, jamais tu")
    void unDepassementEstMarque() {
        FakeSource source = new FakeSource().put("a.obj", "v 0 0 0").put("b.obj", "v 1 1 1");
        FakeClock clock = new FakeClock();
        clock.step = 500;

        AssetRegistry registry =
                new AssetRegistry(source, new FakeCompiler(), COMPILER_VERSION, clock::tick);
        registry.discover();

        AssetRegistry.PumpResult result = registry.pump(100);
        // Abandonner une soumission à moitié faite laisserait un travail en vol
        // que personne ne sonderait plus : le tick finit ce qu'il a commencé,
        // et le dit.
        assertTrue(result.overBudget(), "dépassement passé sous silence");
        assertTrue(result.elapsedNanos() > 100);
    }

    @Test
    @DisplayName("T-214 : R-522, un asset refusé est journalisé une fois et remplacé")
    void unAssetRefuseEstJournaliseUneFois() {
        FakeSource source = new FakeSource().put("casse.obj", "n'importe quoi");
        FakeCompiler compiler = new FakeCompiler();
        compiler.failCompile = true;

        AssetRegistry registry = registry(source, compiler);
        registry.discover();
        registry.pump(0);
        registry.pump(0);

        AssetEntry entry = registry.entry("casse.obj");
        assertEquals(AssetState.FAILED, entry.state());
        assertEquals(1, registry.diagnostics().size(), () -> registry.diagnostics().toString());
        assertTrue(registry.diagnostics().get(0).contains(AssetRegistry.FALLBACK_ID));

        // Répéter le message à chaque tick noierait tout le reste.
        registry.pump(0);
        registry.pump(0);
        assertEquals(1, registry.diagnostics().size());
    }

    @Test
    @DisplayName("T-214 : un asset refusé rend l'asset de secours")
    void unAssetRefuseRendLeSecours() {
        FakeSource source = new FakeSource().put("casse.obj", "x").put("bon.obj", "v 0 0 0");
        FakeCompiler compiler = new FakeCompiler();
        compiler.failCompile = true;

        AssetRegistry registry = registry(source, compiler);
        registry.discover();
        registry.pump(0);
        registry.pump(0);

        // Le monde se charge, la pièce manquante se voit, et rien ne s'arrête.
        assertEquals(AssetRegistry.FALLBACK_ID, registry.resolve("casse.obj"));
        assertEquals(AssetRegistry.FALLBACK_ID, registry.resolve("inconnu.obj"));
    }

    @Test
    @DisplayName("T-214 : une soumission refusée est un échec, pas un blocage")
    void uneSoumissionRefuseeEstUnEchec() {
        FakeSource source = new FakeSource().put("m.obj", "v 0 0 0");
        FakeCompiler compiler = new FakeCompiler();
        compiler.refuseSubmit = -2001;

        AssetRegistry registry = registry(source, compiler);
        registry.discover();
        registry.pump(0);

        assertEquals(AssetState.FAILED, registry.entry("m.obj").state());
        assertTrue(registry.isSettled(), "un refus laisse l'asset en attente");
        assertTrue(registry.diagnostics().get(0).contains("-2001"));
    }

    @Test
    @DisplayName("T-214 : une source illisible ne fait pas échouer la découverte")
    void uneSourceIllisibleNeBloquePas() {
        FakeSource source = new FakeSource().put("a.obj", "v 0 0 0").put("b.obj", "v 1 1 1");
        source.illisibles.add("b.obj");

        AssetRegistry registry = registry(source, new FakeCompiler());
        registry.discover();

        assertEquals(AssetState.FAILED, registry.entry("b.obj").state());
        assertEquals(AssetState.DISCOVERED, registry.entry("a.obj").state());
        assertEquals(1, registry.diagnostics().size());
    }

    @Test
    @DisplayName("T-212 : une source corrigée reprend sa chance")
    void uneSourceCorrigeeReprendSaChance() {
        FakeSource source = new FakeSource().put("m.obj", "casse");
        FakeCompiler compiler = new FakeCompiler();
        compiler.failCompile = true;

        AssetRegistry registry = registry(source, compiler);
        registry.discover();
        registry.pump(0);
        registry.pump(0);
        assertEquals(AssetState.FAILED, registry.entry("m.obj").state());

        // L'auteur corrige : la clé change, l'asset repart de la découverte.
        compiler.failCompile = false;
        source.put("m.obj", "v 0 0 0");
        assertEquals(1, registry.discover());
        assertEquals(AssetState.DISCOVERED, registry.entry("m.obj").state());

        registry.pump(0);
        registry.pump(0);
        assertEquals(AssetState.COMPILED, registry.entry("m.obj").state());
    }

    @Test
    @DisplayName("La clé dépend du contenu, des options et de la version du compilateur")
    void laCleDependDeSesTroisTermes() {
        byte[] contenu = "v 0 0 0".getBytes(StandardCharsets.UTF_8);
        AssetKey reference = AssetKey.of(contenu, "", 1, 1);

        assertEquals(reference, AssetKey.of(contenu, "", 1, 1));
        assertNotEquals(
                reference, AssetKey.of("v 1 1 1".getBytes(StandardCharsets.UTF_8), "", 1, 1));
        assertNotEquals(reference, AssetKey.of(contenu, "lod=2", 1, 1));
        // Sans ce terme, une correction du compilateur n'atteindrait jamais les
        // assets déjà compilés (R-562).
        assertNotEquals(reference, AssetKey.of(contenu, "", 2, 1));

        assertEquals(64, reference.hex().length());
        assertEquals(reference.hex().substring(0, 2), reference.shard());
    }

    @Test
    @DisplayName("Une transition hors de SM-01 est refusée")
    void uneTransitionHorsMachineEstRefusee() {
        FakeSource source = new FakeSource().put("m.obj", "v 0 0 0");
        AssetRegistry registry = registry(source, new FakeCompiler());
        registry.discover();
        AssetEntry entry = registry.entry("m.obj");

        // Sauter jusqu'au chargement sauterait la validation de C-22, et
        // personne ne s'en apercevrait avant une lecture de données non
        // contrôlées.
        assertFalse(entry.transitionTo(AssetState.LOADED));
        assertEquals(AssetState.DISCOVERED, entry.state());
        assertTrue(entry.transitionTo(AssetState.QUEUED));
    }

    @Test
    @DisplayName("ADR-119 : un asset compilé est publié, avec son identité et sa clé")
    void unAssetCompileEstPublie() {
        FakeSource source = new FakeSource().put("m.obj", "v 0 0 0");
        AssetRegistry registry = registry(source, new FakeCompiler());
        registry.discover();
        assertNull(registry.published("m.obj"), "publié avant d'être compilé");

        registry.pump(0);
        registry.pump(0);
        AssetEntry entry = registry.entry("m.obj");
        AssetRegistry.Published published = registry.published("m.obj");
        assertNotNull(published);
        assertEquals(entry.assetId(), published.assetId());
        assertEquals(entry.key(), published.key());
        assertSame(entry.compiled(), published.a3d());
    }

    @Test
    @DisplayName("ADR-119 : un rechargement sans changement garde la même publication")
    void unRechargementSansChangementGardeLaPublication() {
        FakeSource source = new FakeSource().put("m.obj", "v 0 0 0");
        AssetRegistry registry = registry(source, new FakeCompiler());
        registry.discover();
        registry.pump(0);
        registry.pump(0);
        AssetRegistry.Published avant = registry.published("m.obj");

        assertEquals(0, registry.discover());
        assertSame(avant, registry.published("m.obj"));
    }

    @Test
    @DisplayName("ADR-119 : un contenu périmé, refusé ou disparu cesse d'être publié")
    void unContenuQuiNEstPlusUtilisableCesseDEtrePublie() {
        FakeSource source = new FakeSource().put("a.obj", "v 0 0 0").put("b.obj", "v 1 1 1");
        FakeCompiler compiler = new FakeCompiler();
        AssetRegistry registry = registry(source, compiler);
        registry.discover();
        registry.pump(0);
        registry.pump(0);
        assertNotNull(registry.published("a.obj"));
        assertNotNull(registry.published("b.obj"));

        // La source change : l'ancien conteneur n'est plus le sien.
        source.put("a.obj", "v 2 2 2");
        registry.discover();
        assertNull(registry.published("a.obj"), "contenu périmé encore servi");

        // La nouvelle compilation échoue : rien n'est servi (R-522, secours).
        compiler.failCompile = true;
        registry.pump(0);
        registry.pump(0);
        assertEquals(AssetState.FAILED, registry.entry("a.obj").state());
        assertNull(registry.published("a.obj"));

        // La source disparaît : sa publication aussi.
        source.files.remove("b.obj");
        registry.discover();
        assertNull(registry.published("b.obj"));
    }

    @Test
    @DisplayName("ADR-119 : une recompilation forcée retire les publications")
    void uneRecompilationForceeRetireLesPublications() {
        FakeSource source = new FakeSource().put("m.obj", "v 0 0 0");
        AssetRegistry registry = registry(source, new FakeCompiler());
        registry.discover();
        registry.pump(0);
        registry.pump(0);
        assertNotNull(registry.published("m.obj"));

        registry.forceRecompile();
        assertNull(registry.published("m.obj"), "conteneur servi pendant sa recompilation");
        registry.pump(0);
        registry.pump(0);
        assertNotNull(registry.published("m.obj"));
    }

    @Test
    @DisplayName("ADR-119 : un asset repris du cache est publié dès la découverte")
    void unAssetReprisDuCacheEstPublieDesLaDecouverte(@TempDir Path dossier) {
        FakeSource source = new FakeSource().put("m.obj", "v 0 0 0");
        AssetCache cache = new AssetCache(dossier, 1 << 20);
        AssetRegistry premier =
                new AssetRegistry(source, new FakeCompiler(), COMPILER_VERSION, 1, () -> 0L, cache);
        premier.discover();
        premier.pump(0);
        premier.pump(0);

        // Un nouveau registre — le rechargement suivant — reprend le cache sans compiler.
        AssetRegistry second =
                new AssetRegistry(source, new FakeCompiler(), COMPILER_VERSION, 1, () -> 0L, cache);
        assertEquals(0, second.discover());
        assertEquals(AssetState.CACHED, second.entry("m.obj").state());
        AssetRegistry.Published published = second.published("m.obj");
        assertNotNull(published);
        assertEquals(premier.published("m.obj").key(), published.key());
        assertArrayEquals(premier.published("m.obj").a3d(), published.a3d());
    }
}
