package dev.axion.diag;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.axion.bootstrap.BootstrapOutcome;
import dev.axion.bootstrap.BootstrapOutcome.Phase;
import dev.axion.bootstrap.BootstrapOutcome.Reason;
import dev.axion.config.ConfigLoader;
import dev.axion.config.ConfigSchema.Scope;
import dev.axion.lifecycle.AxionRuntime;
import dev.axion.platform.PlatformAdapter;
import dev.axion.render.BackendSelection;
import dev.axion.render.RenderCapabilities;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** T-140 : le rapport d'etat dit ce qui se passe, et pourquoi (C-05). */
class StatusReportTest {

    @TempDir
    Path dir;

    private record FakePlatform(Path dir) implements PlatformAdapter {
        @Override
        public String platformName() {
            return "test-1";
        }

        @Override
        public String minecraftVersion() {
            return "1.20.1";
        }

        @Override
        public boolean isModLoaded(String modid) {
            return false;
        }

        @Override
        public boolean isAuthoritativeThread() {
            return true;
        }

        @Override
        public boolean isClient() {
            return false;
        }

        @Override
        public long currentTick() {
            return 0;
        }

        @Override
        public void runOnAuthoritativeThread(Runnable work) {
            work.run();
        }

        @Override
        public Path gameDir() {
            return dir;
        }

        @Override
        public Path configDir() {
            return dir;
        }
    }

    private BootstrapOutcome outcome(Phase phase, Reason reason, String detail, long ffi) {
        return new BootstrapOutcome(
                phase,
                phase == Phase.READY ? 7L : 0L,
                ConfigLoader.load(Scope.COMMON, dir, new Properties()),
                reason,
                detail,
                ffi,
                List.of("bibliotheque native chargee"));
    }

    private static String joined(List<String> lines) {
        return String.join("\n", lines);
    }

    @Test
    @DisplayName("T-140 : avant tout demarrage, le rapport le dit sans rien inventer")
    void avantDemarrage() {
        String report = joined(StatusReport.of(new AxionRuntime((p, q) -> null)));

        assertTrue(report.contains("inactif"), report);
        assertTrue(report.contains("UNLOADED"), report);
        assertTrue(report.contains("pas encore"), report);
    }

    @Test
    @DisplayName("T-140 : runtime pret, le cout FFI mesure est affiche")
    void runtimePret() {
        AxionRuntime runtime =
                new AxionRuntime((p, q) -> outcome(Phase.READY, null, "", 137L));
        runtime.onConstructed(new FakePlatform(dir));
        runtime.onSetup(new Properties());
        runtime.onLoadComplete();
        runtime.onServerStarting();

        String report = joined(StatusReport.of(runtime));

        assertTrue(report.contains("actif"), report);
        assertTrue(report.contains("RUNNING_SERVER"), report);
        // La valeur mesuree doit apparaitre : c'est elle qui dimensionne les
        // lots, et la voir permet de constater une machine anormalement lente.
        assertTrue(report.contains("137 ns"), report);
        assertTrue(report.contains("tous actifs"), report);
    }

    @Test
    @DisplayName("T-141 : runtime inactif, le rapport dit pourquoi")
    void runtimeInactifExplique() {
        AxionRuntime runtime = new AxionRuntime(
                (p, q) -> outcome(
                        Phase.DISABLED,
                        Reason.ABI_MISMATCH,
                        "ABI 2 cote natif, 1 attendue",
                        -1L));
        runtime.onConstructed(new FakePlatform(dir));
        runtime.onSetup(new Properties());

        String report = joined(StatusReport.of(runtime));

        // Un moteur silencieux sans explication est indiscernable d'un moteur
        // en panne : la cause doit etre lisible.
        assertTrue(report.contains("ABI_MISMATCH"), report);
        assertTrue(report.contains("ABI 2 cote natif"), report);
        assertTrue(report.contains("bibliotheque native chargee"), report);
    }

    @Test
    @DisplayName("Un hook desactive reste visible dans le rapport")
    void hookDesactiveVisible() {
        AxionRuntime runtime = new AxionRuntime((p, q) -> {
            throw new IllegalStateException("demarrage fautif");
        });
        // Cinq cycles complets : la transition vers SETUP n'est admise qu'une
        // fois par cycle, et c'est bien un demarrage repete qui condamne le
        // hook, pas un appel repete hors sequence.
        for (int index = 0; index < 5; index++) {
            runtime.onConstructed(new FakePlatform(dir));
            runtime.onSetup(new Properties());
            runtime.onGameShuttingDown();
        }

        String report = joined(StatusReport.of(runtime));

        assertTrue(report.contains("E-1010"), report);
        assertTrue(report.contains("setup"), report);
    }

    @Test
    @DisplayName("R-1493 : sans backend de rendu client, la section rendu le dit")
    void renduSansBackendClient() {
        String report = joined(StatusReport.of(new AxionRuntime((p, q) -> null)));

        assertTrue(report.contains("rendu : aucun backend client"), report);
    }

    @Test
    @DisplayName("R-1493 : la section rendu donne le backend du client et ce qu'il ne sait pas faire")
    void renduDuClient() {
        AxionRuntime runtime = new AxionRuntime((p, q) -> null);
        runtime.setRenderCapabilities(RenderCapabilities.of(new BackendSelection.Selection(
                BackendSelection.Kind.VANILLA, "render.backend = vanilla")));

        String report = joined(StatusReport.of(runtime));

        assertTrue(report.contains("  rendu : backend VANILLA (render.backend = vanilla)"), report);
        assertTrue(report.contains("    indisponibles dans ce backend : "), report);
        assertTrue(report.contains("    pas encore livrées : "), report);
    }

    @Test
    @DisplayName("Le rapport ne divulgue aucune donnee de joueur ni de monde")
    void aucuneDonneeSensible() {
        AxionRuntime runtime =
                new AxionRuntime((p, q) -> outcome(Phase.READY, null, "", 100L));
        runtime.onConstructed(new FakePlatform(dir));
        runtime.onSetup(new Properties());

        String report = joined(StatusReport.of(runtime)).toLowerCase(java.util.Locale.ROOT);

        // R-442 : rien du monde, du chat ou des joueurs.
        for (String interdit : List.of("player", "joueur", "uuid", "chat", "seed")) {
            assertTrue(!report.contains(interdit), "le rapport mentionne " + interdit);
        }
    }
}
