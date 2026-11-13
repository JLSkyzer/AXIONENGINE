package dev.axion.forge.net;

import dev.axion.net.ClientSession;
import dev.axion.net.PlayerSync;
import io.netty.util.AttributeKey;
import java.util.UUID;
import net.minecraft.network.Connection;

/**
 * Ce que les essais à deux processus lisent du réseau d'AXION (contenu de développement) : les corrections parties
 * vers un joueur, côté serveur ; celles reçues par la session, côté client.
 */
public final class EssaiReseau {

    /** La clé sous laquelle {@link AxionNetwork} garde la session du client : le même nom rend la même clé. */
    private static final AttributeKey<ClientSession> SESSION = AttributeKey.valueOf("axion_client_session");

    private EssaiReseau() {
        throw new AssertionError("classe utilitaire, non instanciable");
    }

    /** {@return les corrections parties vers ce joueur et celles que le débit a retenues, -1 sans synchronisation} */
    public static long[] corrections(UUID player) {
        PlayerSync sync = AxionNetwork.syncOf(player);
        return sync == null ? new long[] {-1, -1} : new long[] {sync.correctionsSent(), sync.correctionsHeld()};
    }

    /** {@return les corrections reçues par la session de cette connexion, -1 sans session} */
    public static long correctionsRecues(Connection connection) {
        ClientSession session = connection.channel().attr(SESSION).get();
        return session == null ? -1 : session.corrections();
    }
}
