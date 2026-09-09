package dev.axion.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.axion.bootstrap.BootstrapOutcome;
import dev.axion.bootstrap.BootstrapOutcome.Phase;
import dev.axion.bootstrap.BootstrapOutcome.Reason;
import dev.axion.config.ConfigLoader;
import dev.axion.config.ConfigSchema.Scope;
import dev.axion.platform.PlatformAdapter;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** T-100..T-103 — cycle de vie du mod, indépendamment de la plateforme (C-01). */
class AxionRuntimeTest {

    @TempDir
    Path gameDir;

    /** Plateforme simulée : c'est tout ce que le moteur voit de son hôte. */
    private record FakePlatform(Path dir, boolean client) implements PlatformAdapter {
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
            return client;
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

    /** Surface native simulée, qui note les contextes qu'on lui demande de fermer. */
    private static class RecordingNative implements dev.axion.bootstrap.NativeApi {
        private final List<Long> closed = new java.util.ArrayList<>();

        @Override
        public int abiVersion() {
            return 1;
        }

        @Override
        public long initialize(byte[] configCbor) {
            return 1L;
        }

        @Override
        public int close(long context) {
            closed.add(context);
            return 0;
        }

        @Override
        public java.nio.ByteBuffer acquire(long context, int kind, long minCapacity) {
            return java.nio.ByteBuffer.allocateDirect((int) minCapacity);
        }

        @Override
        public int release(long context, int kind, int generation) {
            return 0;
        }

        @Override
        public String lastErrorMessage(long context) {
            return "";
        }
    }

    private BootstrapOutcome ready() {
        return new BootstrapOutcome(
                Phase.READY,
                42L,
                ConfigLoader.load(Scope.COMMON, gameDir, new Properties()),
                null,
                "",
                120L,
                List.of());
    }

    private BootstrapOutcome disabled() {
        return new BootstrapOutcome(
                Phase.DISABLED,
                0L,
                ConfigLoader.load(Scope.COMMON, gameDir, new Properties()),
                Reason.NATIVE_UNAVAILABLE,
                "natif absent",
                -1L,
                List.of());
    }

    @Test
    @DisplayName("T-100 : cycle de vie nominal d'un serveur")
    void cycleNominalServeur() {
        RecordingNative api = new RecordingNative();
        AxionRuntime runtime = new AxionRuntime((platform, properties) -> ready(), api);
        assertEquals(LifecyclePhase.UNLOADED, runtime.phase());

        runtime.onConstructed(new FakePlatform(gameDir, false));
        assertEquals(LifecyclePhase.CONSTRUCTED, runtime.phase());

        runtime.onSetup(new Properties());
        assertEquals(LifecyclePhase.SETUP, runtime.phase());
        assertNotNull(runtime.outcome());
        // Rien ne tourne encore : le démarrage a réussi, mais aucun monde
        // n'existe.
        assertFalse(runtime.isOperational());

        runtime.onLoadComplete();
        runtime.onServerStarting();
        assertEquals(LifecyclePhase.RUNNING_SERVER, runtime.phase());
        assertTrue(runtime.isOperational());

        runtime.onTick();
        runtime.onTick();

        runtime.onServerStopping();
        assertEquals(LifecyclePhase.UNLOADED, runtime.phase());
        assertNull(runtime.outcome(), "l'issue devrait être relâchée à l'arrêt");
        assertFalse(runtime.isOperational());
        // Le contexte natif doit être rendu, et une seule fois : c'est lui qui
        // porte le bilan des allocations (R-322).
        assertEquals(List.of(42L), api.closed, "contexte natif non fermé à l'arrêt");
    }

    @Test
    @DisplayName("Une fermeture native qui échoue n'empêche pas l'arrêt d'aboutir")
    void fermetureNativeQuiEchoue() {
        dev.axion.bootstrap.NativeApi fautif = new RecordingNative() {
            @Override
            public int close(long context) {
                throw new UnsatisfiedLinkError("bibliothèque déjà déchargée");
            }
        };
        AxionRuntime runtime = new AxionRuntime((platform, properties) -> ready(), fautif);

        runtime.onConstructed(new FakePlatform(gameDir, false));
        runtime.onSetup(new Properties());
        runtime.onLoadComplete();
        runtime.onServerStarting();
        runtime.onServerStopping();

        // Rester bloqué en STOPPING empêcherait tout redémarrage propre.
        assertEquals(LifecyclePhase.UNLOADED, runtime.phase());
        assertTrue(
                runtime.transitions().stream().anyMatch(line -> line.contains("impossible")),
                () -> "échec de fermeture non consigné : " + runtime.transitions());
    }

    @Test
    @DisplayName("T-101 : un démarrage désactivé laisse le cycle se dérouler")
    void cycleAvecDemarrageDesactive() {
        AxionRuntime runtime = new AxionRuntime((platform, properties) -> disabled());

        runtime.onConstructed(new FakePlatform(gameDir, false));
        runtime.onSetup(new Properties());
        runtime.onLoadComplete();
        runtime.onServerStarting();

        // Le mod suit son cycle normalement : c'est le jeu qui doit rester
        // jouable, pas AXION qui doit fonctionner à tout prix.
        assertEquals(LifecyclePhase.RUNNING_SERVER, runtime.phase());
        assertFalse(runtime.isOperational(), "opérationnel malgré un natif absent");

        runtime.onTick();
        runtime.onServerStopping();
        assertEquals(LifecyclePhase.UNLOADED, runtime.phase());
    }

    @Test
    @DisplayName("T-102 : une transition inattendue est constatée, pas subie")
    void transitionInattendueIgnoree() {
        AxionRuntime runtime = new AxionRuntime((platform, properties) -> ready());

        // Un tick avant toute construction : rien ne doit se produire, et
        // surtout rien ne doit remonter.
        runtime.onTick();
        assertEquals(LifecyclePhase.UNLOADED, runtime.phase());

        // Un setup sans construction préalable ne fait pas avancer le cycle.
        runtime.onSetup(new Properties());
        assertEquals(LifecyclePhase.UNLOADED, runtime.phase());
        assertNull(runtime.outcome(), "le démarrage a eu lieu hors séquence");

        assertTrue(
                runtime.transitions().stream().anyMatch(line -> line.contains("ignorée")),
                () -> "transition non signalée : " + runtime.transitions());

        // Le cycle reprend normalement dès que l'ordre est respecté.
        runtime.onConstructed(new FakePlatform(gameDir, false));
        runtime.onSetup(new Properties());
        assertEquals(LifecyclePhase.SETUP, runtime.phase());
    }

    @Test
    @DisplayName("T-103 : un démarrage qui échoue n'interrompt pas le cycle")
    void demarrageQuiLeveUneException() {
        AtomicInteger appels = new AtomicInteger();
        AxionRuntime runtime = new AxionRuntime((platform, properties) -> {
            appels.incrementAndGet();
            throw new IllegalStateException("démarrage fautif");
        });

        runtime.onConstructed(new FakePlatform(gameDir, false));
        // Ne doit rien lever : le garde absorbe, et le mod reste chargé.
        runtime.onSetup(new Properties());

        assertEquals(1, appels.get());
        assertNull(runtime.outcome());
        assertFalse(runtime.isOperational());
        // Le hook a bien enregistré l'incident.
        assertEquals(1, runtime.guards().get("setup").consecutiveFailures());
        assertFalse(runtime.guards().get("setup").isDisabled(), "désactivé dès le premier échec");

        // La suite du cycle continue de se dérouler normalement : Forge émet
        // LoadComplete puis ServerStarting quoi qu'il soit arrivé au setup.
        runtime.onLoadComplete();
        runtime.onServerStarting();
        assertEquals(LifecyclePhase.RUNNING_SERVER, runtime.phase());
    }

    @Test
    @DisplayName("Un client peut ouvrir un monde local : la transition est admise")
    void clientPuisServeurIntegre() {
        AxionRuntime runtime = new AxionRuntime((platform, properties) -> ready());

        runtime.onConstructed(new FakePlatform(gameDir, true));
        runtime.onSetup(new Properties());
        runtime.onLoadComplete();
        runtime.onClientTick();
        assertEquals(LifecyclePhase.RUNNING_CLIENT, runtime.phase());

        // Ouvrir un monde solo démarre un serveur intégré sans quitter le
        // client.
        runtime.onServerStarting();
        assertEquals(LifecyclePhase.RUNNING_SERVER, runtime.phase());
        assertTrue(runtime.isOperational());
    }


    @Test
    @DisplayName("Revenir au menu principal ne ferme pas le runtime natif")
    void retourAuMenuPrincipal() {
        RecordingNative api = new RecordingNative();
        AxionRuntime runtime = new AxionRuntime((platform, properties) -> ready(), api);

        runtime.onConstructed(new FakePlatform(gameDir, true));
        runtime.onSetup(new Properties());
        runtime.onLoadComplete();
        runtime.onClientTick();
        runtime.onServerStarting();
        assertTrue(runtime.isOperational());

        // Quitter un monde solo arrête le serveur intégré. Le jeu, lui,
        // continue de tourner : fermer le contexte natif ici laissait AXION
        // mort pour tout le reste de la session, sans qu'aucune erreur ne le
        // signale. Le premier lancement réel du client l'a montré.
        runtime.onServerStopping();
        assertEquals(LifecyclePhase.RUNNING_CLIENT, runtime.phase());
        assertNotNull(runtime.outcome(), "l'issue du démarrage a été relâchée");
        assertEquals(List.of(), api.closed, "contexte natif fermé au retour au menu");

        // Le monde suivant retrouve un moteur opérationnel.
        runtime.onServerStarting();
        assertEquals(LifecyclePhase.RUNNING_SERVER, runtime.phase());
        assertTrue(runtime.isOperational(), "AXION est resté inactif au second monde");

        // Et c'est bien l'arrêt du jeu qui rend le contexte, une seule fois.
        runtime.onGameShuttingDown();
        runtime.onServerStopping();
        assertEquals(LifecyclePhase.UNLOADED, runtime.phase());
        assertEquals(List.of(42L), api.closed, "contexte natif non rendu à l'arrêt du jeu");
    }

    @Test
    @DisplayName("Quitter le jeu depuis le menu principal rend le contexte natif")
    void arretDepuisLeMenuPrincipal() {
        RecordingNative api = new RecordingNative();
        AxionRuntime runtime = new AxionRuntime((platform, properties) -> ready(), api);

        runtime.onConstructed(new FakePlatform(gameDir, true));
        runtime.onSetup(new Properties());
        runtime.onLoadComplete();
        runtime.onClientTick();

        // Aucun serveur n'a jamais tourné : rien ne viendra après, la
        // fermeture a lieu tout de suite.
        runtime.onGameShuttingDown();
        assertEquals(LifecyclePhase.UNLOADED, runtime.phase());
        assertEquals(List.of(42L), api.closed, "contexte natif non rendu");
    }

    @Test
    @DisplayName("L'ordre d'arrêt d'un serveur dédié ne signale aucune anomalie")
    void ordreDArretDuServeurDedie() {
        RecordingNative api = new RecordingNative();
        AxionRuntime runtime = new AxionRuntime((platform, properties) -> ready(), api);

        runtime.onConstructed(new FakePlatform(gameDir, false));
        runtime.onSetup(new Properties());
        runtime.onLoadComplete();
        runtime.onServerStarting();

        // Forge émet ServerStopping puis GameShuttingDown sur un serveur
        // dédié : c'est l'ordre courant, constaté au lancement réel.
        runtime.onServerStopping();
        runtime.onGameShuttingDown();

        assertEquals(LifecyclePhase.UNLOADED, runtime.phase());
        assertEquals(List.of(42L), api.closed, "contexte natif fermé plusieurs fois");
        // Journaliser une « transition ignorée » ici inquiéterait pour rien :
        // il ne s'est rien passé d'inattendu.
        assertTrue(
                runtime.transitions().stream().noneMatch(line -> line.contains("ignorée")),
                () -> "arrêt nominal signalé comme anomalie : " + runtime.transitions());
    }

    @Test
    @DisplayName("Le contexte natif n'est rendu qu'une fois")
    void fermetureIdempotente() {
        RecordingNative api = new RecordingNative();
        AxionRuntime runtime = new AxionRuntime((platform, properties) -> ready(), api);

        runtime.onConstructed(new FakePlatform(gameDir, false));
        runtime.onSetup(new Properties());
        runtime.onLoadComplete();
        runtime.onServerStarting();

        runtime.onServerStopping();
        runtime.onServerStopping();

        // Fermer deux fois le même contexte serait un appel natif sur un jeton
        // périmé : le cycle doit l'empêcher, pas la bibliothèque.
        assertEquals(List.of(42L), api.closed, "contexte natif fermé plusieurs fois");
        assertEquals(LifecyclePhase.UNLOADED, runtime.phase());
    }

    @Test
    @DisplayName("Les ticks du client ne remplissent pas le journal des transitions")
    void ticksClientSilencieuxApresLePremier() {
        AxionRuntime runtime = new AxionRuntime((platform, properties) -> ready());

        runtime.onConstructed(new FakePlatform(gameDir, true));
        runtime.onSetup(new Properties());
        runtime.onLoadComplete();
        runtime.onClientTick();

        int apresLePremier = runtime.transitions().size();
        for (int index = 0; index < 100; index++) {
            runtime.onClientTick();
        }

        // Une transition refusée par tick ferait grossir le journal sans fin.
        assertEquals(apresLePremier, runtime.transitions().size(),
                () -> "journal grossi par les ticks : " + runtime.transitions());
    }

    @Test
    @DisplayName("La plateforme est consignée dès la construction")
    void plateformeConsignee() {
        AxionRuntime runtime = new AxionRuntime((platform, properties) -> ready());
        runtime.onConstructed(new FakePlatform(gameDir, false));

        assertTrue(
                runtime.transitions().stream().anyMatch(line -> line.contains("test-1")),
                () -> "plateforme non consignée : " + runtime.transitions());
        assertTrue(
                runtime.transitions().stream().anyMatch(line -> line.contains("1.20.1")),
                () -> "version de Minecraft non consignée : " + runtime.transitions());
    }
}
