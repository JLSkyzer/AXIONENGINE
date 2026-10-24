package dev.axion.net;

/**
 * Constantes du protocole réseau d'AXION (C-51, PARTIE 21.2, ADR-150).
 *
 * <p>Ce paquet est du Java pur : ni Forge (R-401, T-020) ni Minecraft. Le codec s'y teste sans
 * démarrer le jeu ; la colle avec le canal de Forge vit dans {@code dev.axion.forge.net}.
 */
public final class NetProtocol {

    /** Espace de noms du canal {@code axion:main}. */
    public static final String CHANNEL_NAMESPACE = "axion";

    /** Chemin du canal {@code axion:main}. */
    public static final String CHANNEL_PATH = "main";

    /**
     * Version de protocole négociée par Forge (21.2). Elle est indépendante de la version du mod et
     * ne monte que sur une rupture (R-1631) : 2 avec le descripteur d'états 2 (ADR-151 D8).
     */
    public static final String VERSION = "AXION-2";

    /** {@code protocol_version} du handshake (21.3) : le numéro de {@link #VERSION}. */
    public static final int PROTOCOL_VERSION = 2;

    /**
     * Taille maximale d'une trame AXION, en-tête de fragment compris, dans les deux sens.
     *
     * <p>Sous les 32 KiB de R-1651, et sous les 32 767 octets que Minecraft 1.20.1 accepte d'un paquet
     * personnalisé venu du client ({@code ServerboundCustomPayloadPacket.MAX_PAYLOAD_SIZE}), avec de la
     * place pour l'octet discriminant du canal de Forge. Au-delà, un message se fragmente
     * ({@link Fragments}).
     */
    public static final int MAX_FRAME_BYTES = 32_000;

    private NetProtocol() {
        throw new AssertionError("classe de constantes, non instanciable");
    }

    /**
     * {@return vrai si la version annoncée par l'autre extrémité est celle d'AXION}
     *
     * <p>R-1630 : AXION est requis des deux côtés. Forge présente ici la version du canal distant,
     * ou ses marqueurs de canal absent et de client vanilla : seule l'égalité exacte est acceptée, et
     * Forge refuse alors la connexion avec un message. Accepter un client sans AXION produirait des
     * entités qu'il ne peut ni représenter ni simuler.
     *
     * @param remote version annoncée par l'autre extrémité, ou marqueur de Forge
     */
    public static boolean accepts(String remote) {
        return VERSION.equals(remote);
    }
}
