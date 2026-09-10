package dev.axion.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.axion.bootstrap.BootstrapOutcome.Phase;
import dev.axion.bootstrap.BootstrapOutcome.Reason;
import dev.axion.bridge.NativeBridge;
import dev.axion.config.ConfigSchema.Scope;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** T-110..T-114 — séquence de démarrage et chemins de désactivation (C-02). */
class AxionBootstrapTest {

    @TempDir
    Path gameDir;

    @TempDir
    Path configDir;

    /** Surface native simulée, dont chaque étape peut être mise en échec. */
    private static final class FakeNative implements NativeApi {
        private int abi = NativeBridge.EXPECTED_ABI_VERSION;
        private long initResult = 0x4158_494F_0000_0001L;
        private boolean acquireSucceeds = true;
        private final List<Long> closed = new ArrayList<>();
        private final List<Integer> released = new ArrayList<>();
        private final List<Integer> sides = new ArrayList<>();
        private int abiCalls;

        @Override
        public int abiVersion() {
            abiCalls++;
            return abi;
        }

        @Override
        public long initialize(byte[] configCbor, int side) {
            sides.add(side);
            // Le natif reçoit toujours quelque chose à décoder : la
            // configuration résolue, jamais un tableau vide.
            assertNotNull(configCbor);
            assertTrue(configCbor.length > 0, "configuration CBOR vide");
            return initResult;
        }

        @Override
        public int close(long context) {
            closed.add(context);
            return 0;
        }

        @Override
        public ByteBuffer acquire(long context, int kind, long minCapacity) {
            if (!acquireSucceeds) {
                return null;
            }
            return ByteBuffer.allocateDirect((int) minCapacity + 32).order(ByteOrder.LITTLE_ENDIAN);
        }

        @Override
        public int release(long context, int kind, int generation) {
            released.add(kind);
            return 0;
        }

        @Override
        public String lastErrorMessage(long context) {
            return "message simulé";
        }

        @Override
        public String metricsJson(long context) {
            return "{}";
        }
    }

    private static Function<Path, NativeLoadResult> loaderThatSucceeds() {
        return dir -> new NativeLoadResult.Loaded(dir.resolve("axion_native.dll"), "0".repeat(64));
    }

    private static Function<Path, NativeLoadResult> loaderThatFails(
            NativeLoadResult.Reason reason) {
        return dir -> new NativeLoadResult.Failed(reason, "échec simulé");
    }

    private BootstrapOutcome start(
            Function<Path, NativeLoadResult> loader, NativeApi api, Properties properties) {
        return AxionBootstrap.start(Scope.SERVER, gameDir, configDir, properties, loader, api);
    }

    @Test
    @DisplayName("T-110 : démarrage nominal, contexte ouvert et coût FFI mesuré")
    void demarrageNominal() {
        FakeNative api = new FakeNative();
        BootstrapOutcome outcome = start(loaderThatSucceeds(), api, new Properties());

        assertEquals(Phase.READY, outcome.phase(), outcome::summary);
        assertTrue(outcome.isReady());
        assertNull(outcome.reason());
        assertTrue(outcome.context() > 0, "contexte non ouvert");

        // R-330 : la valeur est mesurée, pas supposée. On ne vérifie pas un
        // seuil — ce serait inventer un chiffre de performance — seulement
        // qu'une mesure a réellement eu lieu.
        assertTrue(outcome.ffiRoundtripNanos() >= 0, "calibration non effectuée");
        assertTrue(api.abiCalls > 10_000, "la calibration n'a pas appelé le natif");

        // Les fichiers de référence sont créés au premier démarrage.
        assertTrue(Files.exists(configDir.resolve("axion-common.toml")));
        assertTrue(Files.exists(configDir.resolve("axion-server.toml")));
        assertTrue(api.closed.isEmpty(), "le contexte ne doit pas être refermé");

        // Non-regression : le tampon de controle etait acquis et jamais rendu,
        // ce qui faussait le bilan d'allocations de l'arret (R-322). Le defaut
        // n'est apparu qu'au premier demarrage reel du serveur.
        assertEquals(
                List.of(dev.axion.bridge.BufferKinds.SIM_OUT),
                api.released,
                "le tampon de contrôle doit être rendu");

        // R-471 : le côté est transmis au natif, qui en déduit le plafond de
        // workers. Le déduire des clés reçues créerait un couplage implicite.
        assertEquals(
                List.of(dev.axion.bridge.NativeBridge.SIDE_SERVER),
                api.sides,
                "le côté n'a pas été transmis");
    }

    @Test
    @DisplayName("T-111 : general.enabled=false désactive sans rien charger")
    void desactivationParConfiguration() {
        Properties properties = new Properties();
        properties.setProperty("axion.general.enabled", "false");

        List<Path> chargements = new ArrayList<>();
        Function<Path, NativeLoadResult> loader = dir -> {
            chargements.add(dir);
            return loaderThatSucceeds().apply(dir);
        };

        BootstrapOutcome outcome = start(loader, new FakeNative(), properties);

        assertEquals(Phase.DISABLED, outcome.phase());
        assertEquals(Reason.CONFIGURATION, outcome.reason());
        assertEquals(0L, outcome.context());
        // Désactivé veut dire désactivé : la bibliothèque n'est même pas
        // extraite.
        assertTrue(chargements.isEmpty(), "le natif a été chargé malgré la désactivation");
        assertFalse(outcome.isReady());
    }

    @Test
    @DisplayName("T-112 : natif absent, le mod reste chargé et le jeu jouable")
    void natifAbsent() {
        BootstrapOutcome outcome = start(
                loaderThatFails(NativeLoadResult.Reason.RESOURCE_MISSING),
                new FakeNative(),
                new Properties());

        assertEquals(Phase.DISABLED, outcome.phase());
        assertEquals(Reason.NATIVE_UNAVAILABLE, outcome.reason());
        assertEquals(0L, outcome.context());
        // La configuration reste exploitable : c'est elle qui pilote le
        // comportement inerte des assemblies (R-410).
        assertNotNull(outcome.config());
        assertTrue(outcome.config().getBoolean("general.enabled"));
        assertTrue(outcome.detail().contains("RESOURCE_MISSING"), outcome.detail());
    }

    @Test
    @DisplayName("T-112 : une bibliothèque qui refuse de se lier ne fait pas remonter d'erreur")
    void chargeurQuiLeveUneErreur() {
        Function<Path, NativeLoadResult> loader = dir -> {
            throw new UnsatisfiedLinkError("liaison impossible");
        };

        BootstrapOutcome outcome = start(loader, new FakeNative(), new Properties());

        assertEquals(Phase.DISABLED, outcome.phase());
        assertEquals(Reason.NATIVE_UNAVAILABLE, outcome.reason());
    }

    @Test
    @DisplayName("T-113 : une ABI incompatible désactive avec un message actionnable")
    void abiIncompatible() {
        FakeNative api = new FakeNative();
        api.abi = NativeBridge.EXPECTED_ABI_VERSION + 1;

        BootstrapOutcome outcome = start(loaderThatSucceeds(), api, new Properties());

        assertEquals(Phase.DISABLED, outcome.phase());
        assertEquals(Reason.ABI_MISMATCH, outcome.reason());
        // Le message doit dire quoi faire : une ABI qui ne correspond pas
        // signale un JAR partiellement remplacé.
        assertTrue(outcome.detail().contains("réinstaller"), outcome.detail());
        assertEquals(0L, outcome.context());
    }

    @Test
    @DisplayName("T-114 : un axion_init refusé désactive sans laisser de contexte")
    void initialisationRefusee() {
        FakeNative api = new FakeNative();
        api.initResult = -1006; // configuration refusée côté natif

        BootstrapOutcome outcome = start(loaderThatSucceeds(), api, new Properties());

        assertEquals(Phase.DISABLED, outcome.phase());
        assertEquals(Reason.INIT_REFUSED, outcome.reason());
        assertTrue(outcome.detail().contains("-1006"), outcome.detail());
        assertEquals(0L, outcome.context());
    }

    @Test
    @DisplayName("T-114 : un tampon inacquérable referme le contexte ouvert")
    void tamponsIndisponibles() {
        FakeNative api = new FakeNative();
        api.acquireSucceeds = false;

        BootstrapOutcome outcome = start(loaderThatSucceeds(), api, new Properties());

        assertEquals(Phase.DISABLED, outcome.phase());
        assertEquals(Reason.BUFFERS_UNAVAILABLE, outcome.reason());
        // Rien ne doit rester ouvert derrière un démarrage qui n'aboutit pas.
        assertEquals(List.of(0x4158_494FL << 32 | 1L), api.closed, "contexte non refermé");
        assertEquals(0L, outcome.context());
    }

    @Test
    @DisplayName("Une configuration invalide n'empêche pas le démarrage, elle est signalée")
    void configurationInvalideSignaleeSansBloquer() {
        Properties properties = new Properties();
        properties.setProperty("axion.sim.max_substeps", "99");

        BootstrapOutcome outcome = start(loaderThatSucceeds(), new FakeNative(), properties);

        assertEquals(Phase.READY, outcome.phase(), outcome::summary);
        assertEquals(4L, outcome.config().getInt("sim.max_substeps"), "le défaut n'a pas tenu");
        assertTrue(
                outcome.diagnostics().stream().anyMatch(d -> d.contains("sim.max_substeps")),
                () -> "problème non signalé : " + outcome.diagnostics());
    }

    @Test
    @DisplayName("Un répertoire de configuration illisible ne bloque pas le démarrage")
    void repertoireDeConfigurationInutilisable() {
        // Un chemin qui désigne un fichier, pas un répertoire : l'écriture des
        // fichiers de référence échouera.
        Path fichier = gameDir.resolve("pas-un-repertoire");
        BootstrapOutcome outcome = AxionBootstrap.start(
                Scope.SERVER,
                gameDir,
                fichier,
                new Properties(),
                loaderThatSucceeds(),
                new FakeNative());

        // Les défauts compilés suffisent à démarrer.
        assertEquals(Phase.READY, outcome.phase(), outcome::summary);
        assertEquals(4L, outcome.config().getInt("sim.max_substeps"));
    }

    @Test
    @DisplayName("Le résumé dit toujours quelque chose d'exploitable")
    void resumeToujoursRenseigne() {
        BootstrapOutcome pret = start(loaderThatSucceeds(), new FakeNative(), new Properties());
        assertTrue(pret.summary().contains("prêt"), pret.summary());

        BootstrapOutcome inactif = start(
                loaderThatFails(NativeLoadResult.Reason.PLATFORM_UNSUPPORTED),
                new FakeNative(),
                new Properties());
        assertTrue(inactif.summary().contains("inactif"), inactif.summary());
        assertTrue(inactif.summary().contains("PLATFORM_UNSUPPORTED"), inactif.summary());
    }
}
