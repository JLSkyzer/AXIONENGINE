package dev.axion.forge;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.server.ServerLifecycleHooks;

/**
 * INV-03 : l'état de Minecraft ne se modifie que sur le thread autoritatif, celui du serveur.
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
}
