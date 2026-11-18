package dev.axion.render;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * L'apparence de chaque dessin d'une assembly (ADR-163 D3), depuis celle des nœuds que ses pistes pilotent
 * ({@link ProceduralTracks.Pose#looks}). Un nœud hérite de son parent : caché si un ancêtre l'est, son émission et sa
 * teinte multipliées par celles de ses ancêtres ; un dessin prend l'apparence du nœud qui le porte. Les deux backends
 * l'appliquent : un dessin caché n'est pas émis, sa teinte multiplie sa couleur, et son émission celle de son
 * matériau. Logique pure, sans GL ni Minecraft.
 *
 * <p>Une assembly dont aucune piste ne pilote l'apparence n'en a pas ({@code null}) : rien ne lui coûte de plus.
 */
public final class NodeLooks {

    private static final int HEADER_BYTES = 8;
    private static final int NODE_BYTES = 80;
    private static final int PARENT_AT = 8;

    private NodeLooks() {}

    /**
     * {@return le parent de chaque nœud d'une section {@code NODE}, -1 pour une racine ; {@code null} si elle ne se
     * lit pas, ou si un parent suit son enfant — comme {@link BodySkeleton}}
     *
     * @param nodes la section {@code NODE} de l'asset
     */
    public static int[] parents(byte[] nodes) {
        if (nodes == null || nodes.length < HEADER_BYTES) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.wrap(nodes).order(ByteOrder.LITTLE_ENDIAN);
        long count = Integer.toUnsignedLong(buffer.getInt(0));
        if (HEADER_BYTES + count * NODE_BYTES > nodes.length) {
            return null;
        }
        int[] parents = new int[(int) count];
        for (int node = 0; node < parents.length; node++) {
            int parent = buffer.getInt(HEADER_BYTES + node * NODE_BYTES + PARENT_AT);
            if (parent >= node || parent < -1) {
                return null;
            }
            parents[node] = parent;
        }
        return parents;
    }

    /**
     * {@return l'apparence de chaque dessin, {@link ProceduralTracks#LOOK_FLOATS} flottants par dessin dans l'ordre
     * des dessins ; {@code null} si la pose n'en a pas, ou sans table de parents}
     *
     * @param parents le parent de chaque nœud, voir {@link #parents}
     * @param pose la pose des pistes, ou {@code null}
     * @param drawNodes le nœud qui porte chaque dessin
     */
    public static float[] ofDraws(int[] parents, ProceduralTracks.Pose pose, List<Integer> drawNodes) {
        return ofDraws(parents, pose, dev.axion.net.NodeStates.NONE, drawNodes);
    }

    /**
     * {@return l'apparence de chaque dessin, pistes et état des nodes composés (ADR-174 D3) ; {@code null} si ni les
     * pistes ni les actions n'en donnent, ou sans table de parents}
     *
     * <p>Une action prime sur le canal de son node : un node caché par {@code hide_node} l'est quel que soit son canal
     * {@code visibility} ; l'émission et la teinte posées par {@code set_material_param} remplacent celles des pistes.
     * Ses enfants en héritent comme d'un canal.
     *
     * @param parents le parent de chaque nœud, voir {@link #parents}
     * @param pose la pose des pistes, ou {@code null}
     * @param states l'état des nodes que les actions ont changé
     * @param drawNodes le nœud qui porte chaque dessin
     */
    public static float[] ofDraws(int[] parents, ProceduralTracks.Pose pose, dev.axion.net.NodeStates states,
            List<Integer> drawNodes) {
        boolean tracked = pose != null && pose.looks() != null;
        if (parents == null || (!tracked && states.states().isEmpty())) {
            return null;
        }
        int width = ProceduralTracks.LOOK_FLOATS;
        float[] nodes = new float[parents.length * width];
        java.util.Arrays.fill(nodes, 1f);
        if (tracked) {
            int[] posed = pose.nodes();
            float[] own = pose.looks();
            for (int slot = 0; slot < posed.length; slot++) {
                if (posed[slot] >= 0 && posed[slot] < parents.length) {
                    System.arraycopy(own, slot * width, nodes, posed[slot] * width, width);
                }
            }
        }
        for (dev.axion.net.NodeStates.State state : states.states()) {
            int at = state.node() * width;
            if (state.node() >= parents.length) {
                continue;
            }
            if (state.hidden()) {
                nodes[at] = 0f;
            }
            if (!Float.isNaN(state.emissive())) {
                nodes[at + 1] = state.emissive();
            }
            if (state.color() != null) {
                System.arraycopy(state.color(), 0, nodes, at + 2, 3);
            }
        }
        // Un parent précède son enfant : un seul passage porte l'apparence de chaque ancêtre.
        for (int node = 0; node < parents.length; node++) {
            int parent = parents[node];
            if (parent >= 0) {
                for (int k = 0; k < width; k++) {
                    nodes[node * width + k] *= nodes[parent * width + k];
                }
            }
        }
        float[] draws = new float[drawNodes.size() * width];
        for (int draw = 0; draw < drawNodes.size(); draw++) {
            int node = drawNodes.get(draw);
            if (node >= 0 && node < parents.length) {
                System.arraycopy(nodes, node * width, draws, draw * width, width);
            } else {
                java.util.Arrays.fill(draws, draw * width, (draw + 1) * width, 1f);
            }
        }
        return draws;
    }

    /**
     * {@return vrai si ce dessin est caché}
     *
     * @param looks l'apparence des dessins, ou {@code null}
     * @param draw le rang du dessin
     */
    public static boolean hidden(float[] looks, int draw) {
        return looks != null && looks[draw * ProceduralTracks.LOOK_FLOATS] < 0.5f;
    }

    /**
     * {@return la teinte RGBA d'un dessin : sa couleur, multipliée par son émission pour la passe d'émission}
     * Blanche sans apparence.
     *
     * @param looks l'apparence des dessins, ou {@code null}
     * @param draw le rang du dessin
     * @param emission vrai pour la passe d'émission
     */
    public static float[] tint(float[] looks, int draw, boolean emission) {
        if (looks == null) {
            return new float[] {1f, 1f, 1f, 1f};
        }
        int at = draw * ProceduralTracks.LOOK_FLOATS;
        float scale = emission ? looks[at + 1] : 1f;
        return new float[] {looks[at + 2] * scale, looks[at + 3] * scale, looks[at + 4] * scale, 1f};
    }

    /**
     * {@return une couleur ARGB multipliée par la teinte d'un dessin, chaque composante bornée à 255} Pour le backend
     * vanilla, qui émet ses sommets en octets : une émission au-delà de 1 y sature.
     *
     * @param argb la couleur
     * @param looks l'apparence des dessins, ou {@code null}
     * @param draw le rang du dessin
     * @param emission vrai pour la passe d'émission
     */
    public static int tinted(int argb, float[] looks, int draw, boolean emission) {
        if (looks == null) {
            return argb;
        }
        float[] tint = tint(looks, draw, emission);
        int r = channel((argb >>> 16) & 0xFF, tint[0]);
        int g = channel((argb >>> 8) & 0xFF, tint[1]);
        int b = channel(argb & 0xFF, tint[2]);
        return (argb & 0xFF000000) | r << 16 | g << 8 | b;
    }

    private static int channel(int value, float factor) {
        return Math.max(0, Math.min(255, Math.round(value * factor)));
    }
}
