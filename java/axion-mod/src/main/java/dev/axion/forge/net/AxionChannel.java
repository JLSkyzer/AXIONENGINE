package dev.axion.forge.net;

import dev.axion.net.NetProtocol;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.HandshakeHandler;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * Le canal {@code axion:main} (C-51, PARTIE 21.2, ADR-150).
 *
 * <p>Forge compare la version du canal des deux extrémités pendant la connexion, avec les prédicats
 * donnés ici : {@link NetProtocol#accepts} n'accepte que {@code AXION-1}. Un client sans AXION — canal
 * absent — ou un client vanilla est refusé par Forge avec un message, avant d'entrer en jeu (R-1630,
 * T-419). Le codec vit hors de Forge, dans {@code dev.axion.net} (R-401).
 */
public final class AxionChannel {

    /**
     * Le canal, créé une fois, pendant la construction du mod.
     *
     * <p>Le constructeur de {@code ResourceLocation}, déprécié par Forge 47.4, existe dans tout Forge
     * 47 ; {@code fromNamespaceAndPath} n'y est rétroporté que depuis 47.3.19, et le mod se déclare
     * compatible avec {@code [47,)} (même choix qu'{@code EntityImpactEffects}).
     */
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(NetProtocol.CHANNEL_NAMESPACE, NetProtocol.CHANNEL_PATH),
            () -> NetProtocol.VERSION,
            NetProtocol::accepts,
            NetProtocol::accepts);

    /** Discriminant du paquet de connexion, du serveur vers le client. */
    static final int LOGIN_TO_CLIENT = 0;

    /** Discriminant de la réponse du client à un paquet de connexion. */
    static final int LOGIN_TO_SERVER = 1;

    /** Discriminant d'un message en jeu, du serveur vers le client. */
    static final int PLAY_TO_CLIENT = 2;

    // Les messages s'enregistrent avec le canal, avant la première connexion : chacun ne porte
    // qu'une trame (ADR-150), que les sessions de dev.axion.net valident.
    static {
        CHANNEL.messageBuilder(ChannelFrames.LoginToClient.class, LOGIN_TO_CLIENT, NetworkDirection.LOGIN_TO_CLIENT)
                .loginIndex(ChannelFrames.LoginToClient::getAsInt, ChannelFrames.LoginToClient::setLoginIndex)
                .encoder(ChannelFrames.LoginToClient::encode)
                .decoder(ChannelFrames.LoginToClient::decode)
                .buildLoginPacketList(AxionNetwork::loginPackets)
                .consumerNetworkThread(AxionNetwork::onLoginToClient)
                .add();
        CHANNEL.messageBuilder(ChannelFrames.LoginToServer.class, LOGIN_TO_SERVER, NetworkDirection.LOGIN_TO_SERVER)
                .loginIndex(ChannelFrames.LoginToServer::getAsInt, ChannelFrames.LoginToServer::setLoginIndex)
                .encoder(ChannelFrames.LoginToServer::encode)
                .decoder(ChannelFrames.LoginToServer::decode)
                .consumerNetworkThread(HandshakeHandler.indexFirst(AxionNetwork::onLoginToServer))
                .add();
        CHANNEL.messageBuilder(ChannelFrames.PlayToClient.class, PLAY_TO_CLIENT, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(ChannelFrames.PlayToClient::encode)
                .decoder(ChannelFrames.PlayToClient::decode)
                .consumerNetworkThread(AxionNetwork::onPlayToClient)
                .add();
    }

    private AxionChannel() {
        throw new AssertionError("non instanciable");
    }

    /**
     * Crée le canal. Forge exige qu'un canal existe avant la première connexion : l'appel a lieu dans
     * le constructeur du mod.
     *
     * @return le canal
     */
    public static SimpleChannel register() {
        return CHANNEL;
    }
}
