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
     * ne monte que sur une rupture (R-1631) : 2 avec le descripteur d'états 2 (ADR-151 D8), 3 avec
     * {@code C2S_Input} (ADR-158 D4), 4 avec la famille des pneus du registre (ADR-159 Q1), 5 avec le
     * descripteur d'états 4, les roues (ADR-159 D9), 6 avec le descripteur d'états 5, l'état du véhicule et
     * les drapeaux d'entrée sur 16 bits (ADR-163 D1), 7 avec les addons exigés du handshake (ADR-165 D2), 8 avec le
     * descripteur d'états 6, les modificateurs des paramètres d'une assembly (ADR-167 D8), 9 avec la famille des
     * correspondances bloc → matériau du registre (ADR-168 D6), 10 avec l'octet de cadence de {@code C2S_Input} et la
     * profondeur de la file dans {@code S2C_Correction} (ADR-168 D3, révisé le 2026-11-14), 11 avec le descripteur
     * d'états 7, les attaches d'une assembly (ADR-171 D7), 12 avec le descripteur d'états 8, les réglages des attaches
     * et la correction de l'ensemble (ADR-171 Q8), 13 avec {@code S2C_Event}, les événements de contenu, et le
     * descripteur d'états 9, l'état des nodes (ADR-174 D2, D3), 14 avec le descripteur d'états 13, le carburant et la
     * batterie du véhicule (ADR-176 D3), 15 avec le descripteur d'états 14, l'usure du pneu de chaque roue (ADR-176 D4), 16 avec le descripteur d'états 15, le mouillé du véhicule (ADR-176 D5), 17 avec le descripteur
     * d'états 16, les 4 réserves d'énergie du véhicule (ADR-182), 18 avec le descripteur d'états 17, le régime et la
     * puissance des moteurs électriques (ADR-182 D5).
     */
    public static final String VERSION = "AXION-18";

    /** {@code protocol_version} du handshake (21.3) : le numéro de {@link #VERSION}. */
    public static final int PROTOCOL_VERSION = 18;

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
