package dev.axion.forge.client;

import dev.axion.config.AxionConfig;
import dev.axion.forge.AxionEntity;
import dev.axion.forge.RuntimeAccess;
import dev.axion.forge.net.AxionNetwork;
import dev.axion.lifecycle.AxionRuntime;
import dev.axion.net.Interpolation;
import dev.axion.net.RenderClock;
import dev.axion.net.SnapshotReceiver;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;

/**
 * Applique aux entités du client les états reçus par un snapshot (ADR-150 D6), à travers l'interpolation de
 * 21.7 (D7).
 *
 * <p>Le récepteur tourne sur le thread de Netty ; les entités se touchent sur celui du client : les états
 * y passent d'un bloc, dans l'ordre de leur réception, avec l'heure de leur arrivée. Une entité que le
 * client ne connaît plus — elle a disparu entre-temps — est passée.
 *
 * <p>L'horloge de rendu est celle de la connexion : posée au premier snapshot, au retard d'interpolation
 * que le serveur annonce, oubliée à la déconnexion. L'extrapolation et le seuil de téléportation sont ceux
 * de la configuration du client. Tout ici vit sur le thread du client.
 */
final class ClientSnapshots {

    private static RenderClock clock;

    private static AxionConfig config;

    private ClientSnapshots() {
        throw new AssertionError("non instanciable");
    }

    /**
     * @param states états reconstitués, depuis le thread de Netty
     */
    static void apply(List<SnapshotReceiver.Applied> states) {
        long arrival = System.nanoTime();
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            ClientLevel level = minecraft.level;
            RenderClock renderClock = clock(minecraft);
            if (level == null || renderClock == null) {
                return;
            }
            // Un snapshot porte un seul tick : ses états le partagent.
            renderClock.onSnapshot(states.get(0).serverTick(), arrival);
            for (SnapshotReceiver.Applied state : states) {
                if (level.getEntity(state.handle()) instanceof AxionEntity assembly) {
                    if (!assembly.interpolating()) {
                        assembly.startInterpolation(Interpolation.of(config), renderClock);
                    }
                    assembly.applySnapshot(state.state(), state.serverTick());
                }
            }
        });
    }

    /**
     * Une image commence : l'horloge note son heure, et si le jeu est en pause.
     *
     * @param nowNanos heure locale, en nanosecondes
     * @param paused vrai si le jeu est en pause
     */
    static void onFrame(long nowNanos, boolean paused) {
        if (clock != null) {
            clock.onFrame(nowNanos, paused);
        }
    }

    /** La connexion se ferme : la suivante aura son horloge, au retard de son serveur. */
    static void reset() {
        clock = null;
        config = null;
    }

    private static RenderClock clock(Minecraft minecraft) {
        if (clock != null) {
            return clock;
        }
        AxionRuntime runtime = RuntimeAccess.get();
        ClientPacketListener connection = minecraft.getConnection();
        if (runtime == null || connection == null) {
            return null;
        }
        config = runtime.outcome().config();
        clock = RenderClock.of(AxionNetwork.clientServer(connection.getConnection()), config);
        clock.onFrame(System.nanoTime(), minecraft.isPaused());
        return clock;
    }
}
