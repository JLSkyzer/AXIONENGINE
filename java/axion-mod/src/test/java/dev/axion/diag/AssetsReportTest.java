package dev.axion.diag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.axion.asset.AssetCompiler;
import dev.axion.asset.AssetRegistry;
import dev.axion.asset.AssetSource;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T-560..T-562 — rapports des commandes d'assets (C-71). */
class AssetsReportTest {

    /** Sources simulées. */
    private static final class FakeSource implements AssetSource {
        private final Map<String, byte[]> files = new LinkedHashMap<>();

        FakeSource put(String path, String content) {
            files.put(path, content.getBytes(StandardCharsets.UTF_8));
            return this;
        }

        @Override
        public List<String> list() {
            return List.copyOf(files.keySet());
        }

        @Override
        public byte[] read(String path) {
            return files.get(path);
        }
    }

    /** Compilateur simulé, qui aboutit ou échoue selon ce qu'on lui demande. */
    private static final class FakeCompiler implements AssetCompiler {
        private final List<Integer> jobs = new ArrayList<>();
        private boolean fail;

        @Override
        public int submit(long assetId, int format, byte[] source) {
            jobs.add(jobs.size() + 1);
            return jobs.size();
        }

        @Override
        public CompileStatus poll(int jobId) {
            return fail ? CompileStatus.failed(-3050) : CompileStatus.compiled(new byte[256]);
        }
    }

    private static String joined(List<String> lines) {
        return String.join("\n", lines);
    }

    private static AssetRegistry registry(FakeSource source, FakeCompiler compiler) {
        AssetRegistry registry = new AssetRegistry(source, compiler, 1, () -> 0L);
        registry.discover();
        registry.pump(0);
        registry.pump(0);
        return registry;
    }

    @Test
    @DisplayName("T-560 : la liste groupe les assets par état")
    void laListeGroupeParEtat() {
        FakeSource source = new FakeSource().put("a.obj", "v 0 0 0").put("b.obj", "v 1 1 1");
        String report = joined(AssetsReport.list(registry(source, new FakeCompiler())));

        assertTrue(report.contains("assets (2)"), report);
        assertTrue(report.contains("COMPILED : 2"), report);
    }

    @Test
    @DisplayName("T-560 : les assets refusés sont nommés, ce sont eux qu'on cherche")
    void lesAssetsRefusesSontNommes() {
        FakeSource source = new FakeSource().put("casse.obj", "x");
        FakeCompiler compiler = new FakeCompiler();
        compiler.fail = true;

        String report = joined(AssetsReport.list(registry(source, compiler)));
        assertTrue(report.contains("FAILED : 1"), report);
        assertTrue(report.contains("refusé : casse.obj"), report);
    }

    @Test
    @DisplayName("T-560 : sans registre, la liste le dit plutôt que de rester vide")
    void sansRegistreLaListeLeDit() {
        String report = joined(AssetsReport.list(null));
        // Une liste vide et un chargement qui n'a pas eu lieu se ressemblent ;
        // seule la seconde se corrige.
        assertTrue(report.contains("aucun asset découvert"), report);
    }

    @Test
    @DisplayName("T-561 : le détail d'un asset dit son état, sa clé et ses tailles")
    void leDetailDitToutCeQuiSert() {
        FakeSource source = new FakeSource().put("voiture.obj", "v 0 0 0");
        String report = joined(AssetsReport.info(registry(source, new FakeCompiler()), "voiture.obj"));

        assertTrue(report.contains("voiture.obj"), report);
        assertTrue(report.contains("état : COMPILED"), report);
        assertTrue(report.contains("format : obj"), report);
        assertTrue(report.contains("clé : "), report);
        assertTrue(report.contains("source : 7 octets"), report);
        assertTrue(report.contains("compilé : 256 octets"), report);
    }

    @Test
    @DisplayName("T-561 : un asset refusé dit par quoi il est remplacé")
    void unAssetRefuseDitSonRemplacant() {
        FakeSource source = new FakeSource().put("casse.obj", "x");
        FakeCompiler compiler = new FakeCompiler();
        compiler.fail = true;

        String report = joined(AssetsReport.info(registry(source, compiler), "casse.obj"));
        assertTrue(report.contains(AssetRegistry.FALLBACK_ID), report);
    }

    @Test
    @DisplayName("T-561 : un chemin inconnu est nommé, pas simplement « introuvable »")
    void unCheminInconnuEstNomme() {
        FakeSource source = new FakeSource().put("a.obj", "v 0 0 0");
        String report = joined(AssetsReport.info(registry(source, new FakeCompiler()), "b.obj"));

        // Sur un pack fourni, c'est presque toujours une faute de frappe.
        assertTrue(report.contains("b.obj"), report);
        assertFalse(report.contains("état :"), report);
    }

    @Test
    @DisplayName("T-562 : une recompilation forcée ramène tout à la découverte")
    void uneRecompilationForceeRamemeTout() {
        FakeSource source = new FakeSource().put("a.obj", "v 0 0 0").put("b.obj", "v 1 1 1");
        AssetRegistry registry = registry(source, new FakeCompiler());
        assertTrue(registry.isSettled());

        assertEquals(2, registry.forceRecompile());
        assertFalse(registry.isSettled());
        String report = joined(AssetsReport.list(registry));
        assertTrue(report.contains("DISCOVERED : 2"), report);
    }

    @Test
    @DisplayName("T-562 : après une recompilation forcée, un échec se redit")
    void unEchecSeReditApresRecompilation() {
        FakeSource source = new FakeSource().put("casse.obj", "x");
        FakeCompiler compiler = new FakeCompiler();
        compiler.fail = true;
        AssetRegistry registry = registry(source, compiler);
        assertEquals(1, registry.diagnostics().size());

        registry.forceRecompile();
        registry.pump(0);
        registry.pump(0);

        // Une recompilation demandée expressément ne doit pas rester muette sur
        // ce qui cloche.
        assertEquals(2, registry.diagnostics().size(), () -> registry.diagnostics().toString());
    }
}
