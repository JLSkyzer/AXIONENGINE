package dev.axion.forge.client;

import dev.axion.forge.AxionEntity;
import dev.axion.net.SnapshotReceiver;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * Applique aux entités du client les états reçus par un snapshot (ADR-150 D6).
 *
 * <p>Le récepteur tourne sur le thread de Netty ; les entités se touchent sur celui du client : les états
 * y passent d'un bloc, dans l'ordre de leur réception. Une entité que le client ne connaît plus — elle a
 * disparu entre-temps — est passée.
 */
final class ClientSnapshots {

    private ClientSnapshots() {
        throw new AssertionError("non instanciable");
    }

    /**
     * @param states états reconstitués, depuis le thread de Netty
     */
    static void apply(List<SnapshotReceiver.Applied> states) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            ClientLevel level = minecraft.level;
            if (level == null) {
                return;
            }
            for (SnapshotReceiver.Applied state : states) {
                if (level.getEntity(state.handle()) instanceof AxionEntity assembly) {
                    assembly.applySnapshot(state.state());
                }
            }
        });
    }
}
