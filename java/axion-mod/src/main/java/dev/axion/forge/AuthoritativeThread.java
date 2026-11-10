package dev.axion.forge;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraftforge.common.util.LogicalSidedProvider;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.server.ServerLifecycleHooks;

/**
 * INV-03 : l'état de Minecraft ne se modifie que sur le thread autoritatif — celui du serveur pour l'état du
 * monde, le thread principal du client pour la copie qu'en tient le client (2.4 : « Server thread | Client
 * main/render thread »).
 *
 * <p>Une mutation hors de ce thread ne lève rien d'elle-même : elle croise le tick du serveur et
 * corrompt le monde au hasard. Une méthode d'AXION qui modifie l'état de Minecraft le vérifie
 * donc d'abord, plutôt que l'espérer (contrat de {@code PlatformAdapter.isAuthoritativeThread}) ;
 * T-011 vérifie sur le bytecode qu'aucun chemin vers une mutation n'évite cette vérification.
 */
public final class AuthoritativeThread {

    private AuthoritativeThread() {}

    /**
     * Vérifie que l'appel a lieu sur le thread du serveur courant.
     *
     * @throws IllegalStateException hors de ce thread, ou sans serveur : il n'y a alors aucun état
     *     autoritatif à modifier
     */
    public static void require() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || !server.isSameThread()) {
            throw new IllegalStateException("INV-03 : état de Minecraft modifié hors du thread "
                    + "autoritatif (thread « " + Thread.currentThread().getName() + " »"
                    + (server == null ? ", aucun serveur" : "") + ")");
        }
    }

    /**
     * Vérifie que l'appel a lieu sur le thread principal du client, celui qui possède l'état du client : ses
     * entités, la pose où il les dessine. Réservée au code du client — T-011 ne la reconnaît que dans
     * {@code dev.axion.forge.client} : elle n'autorise jamais à toucher l'état du serveur, même intégré.
     *
     * @throws IllegalStateException hors de ce thread
     */
    public static void requireClient() {
        BlockableEventLoop<? super TickTask> client = LogicalSidedProvider.WORKQUEUE.get(LogicalSide.CLIENT);
        if (client == null || !client.isSameThread()) {
            throw new IllegalStateException("INV-03 : état du client modifié hors de son thread principal (thread « "
                    + Thread.currentThread().getName() + " »)");
        }
    }
}
