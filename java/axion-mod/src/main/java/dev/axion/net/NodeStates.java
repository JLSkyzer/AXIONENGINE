package dev.axion.net;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * L'état des nodes d'une assembly que ses actions ont changé (ADR-174 D3, descripteur 9) : caché, émission posée,
 * teinte posée. Un état, pas un instant : il se sauvegarde ({@code axion:nodes}) et part aux joueurs avec le snapshot.
 * Seuls les nodes qu'une action a touchés y figurent, rangés par index, chacun une fois. Logique pure, sans Forge.
 *
 * @param states l'état de chaque node touché ; copie, triée par index
 */
public record NodeStates(List<State> states) {

    /** Nodes d'une assembly dont l'état se porte, au plus. */
    public static final int MAX = 256;

    /** Aucun node touché. */
    public static final NodeStates NONE = new NodeStates(List.of());

    private static final int HIDDEN = 1;
    private static final int EMISSIVE = 2;
    private static final int COLOR = 4;

    /**
     * L'état d'un node.
     *
     * @param node son index dans l'asset
     * @param hidden caché, quel que soit son canal {@code visibility}
     * @param emissive l'émission posée, qui remplace celle de son canal ; {@code NaN} sans
     * @param color la teinte posée, {@code r, g, b}, qui remplace celle de son canal ; {@code null} sans
     */
    public record State(int node, boolean hidden, float emissive, float[] color) {

        /** Refuse un index négatif, une émission ou une teinte non finie ; copie la teinte. */
        public State {
            if (node < 0) {
                throw new IllegalArgumentException("node " + node);
            }
            if (Float.isInfinite(emissive)) {
                throw new IllegalArgumentException("émission " + emissive);
            }
            if (color != null) {
                if (color.length != 3) {
                    throw new IllegalArgumentException("teinte de " + color.length + " composantes");
                }
                for (float channel : color) {
                    if (!Float.isFinite(channel)) {
                        throw new IllegalArgumentException("teinte " + Arrays.toString(color));
                    }
                }
                color = color.clone();
            }
        }

        /** {@return vrai si l'état ne change rien : ni caché, ni émission, ni teinte} */
        public boolean neutral() {
            return !hidden && Float.isNaN(emissive) && color == null;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof State that && node == that.node && hidden == that.hidden
                    && Float.floatToIntBits(emissive) == Float.floatToIntBits(that.emissive)
                    && Arrays.equals(color, that.color);
        }

        @Override
        public int hashCode() {
            return Objects.hash(node, hidden, Float.floatToIntBits(emissive), Arrays.hashCode(color));
        }
    }

    /** Copie, trie par index, refuse un node en double, un état neutre ou plus de {@link #MAX} nodes. */
    public NodeStates {
        if (states.size() > MAX) {
            throw new IllegalArgumentException(states.size() + " nodes, au-delà de " + MAX);
        }
        List<State> sorted = new ArrayList<>(states);
        sorted.sort((a, b) -> Integer.compare(a.node(), b.node()));
        for (int i = 0; i < sorted.size(); i++) {
            if (sorted.get(i).neutral()) {
                throw new IllegalArgumentException("node " + sorted.get(i).node() + " : état neutre, à retirer");
            }
            if (i > 0 && sorted.get(i).node() == sorted.get(i - 1).node()) {
                throw new IllegalArgumentException("node " + sorted.get(i).node() + " en double");
            }
        }
        states = List.copyOf(sorted);
    }

    /**
     * {@return l'état d'un node, ou {@code null} si aucune action ne l'a touché}
     *
     * @param node son index
     */
    public State of(int node) {
        for (State state : states) {
            if (state.node() == node) {
                return state;
            }
        }
        return null;
    }

    /**
     * {@return ces états, celui d'un node remplacé} Un état neutre retire le node.
     *
     * @param state le nouvel état du node
     */
    public NodeStates with(State state) {
        List<State> next = new ArrayList<>(states.size() + 1);
        for (State kept : states) {
            if (kept.node() != state.node()) {
                next.add(kept);
            }
        }
        if (!state.neutral()) {
            next.add(state);
        }
        return new NodeStates(next);
    }

    private static int varintSize(long value) {
        int size = 1;
        for (long rest = value >>> 7; rest != 0; rest >>>= 7) {
            size++;
        }
        return size;
    }

    /** {@return la taille de l'encodage, en octets} */
    public int encodedSize() {
        int size = varintSize(states.size());
        for (State state : states) {
            size += varintSize(state.node()) + 1;
            size += Float.isNaN(state.emissive()) ? 0 : 4;
            size += state.color() == null ? 0 : 12;
        }
        return size;
    }

    /**
     * Écrit les états : leur nombre, puis pour chacun son index, un octet de drapeaux, l'émission et la teinte posées.
     *
     * @param writer le flux
     */
    public void write(WireWriter writer) {
        writer.varUInt(states.size());
        for (State state : states) {
            int flags = (state.hidden() ? HIDDEN : 0) | (Float.isNaN(state.emissive()) ? 0 : EMISSIVE)
                    | (state.color() == null ? 0 : COLOR);
            writer.varUInt(state.node()).u8(flags);
            if ((flags & EMISSIVE) != 0) {
                writer.f32(state.emissive());
            }
            if ((flags & COLOR) != 0) {
                writer.f32(state.color()[0]).f32(state.color()[1]).f32(state.color()[2]);
            }
        }
    }

    /**
     * Lit des états (R-1650) : au plus {@link #MAX}, des drapeaux connus, des valeurs finies, chaque node une fois.
     *
     * @param reader le flux
     * @param handle l'entité, pour nommer la faute
     * @return les états
     * @throws MalformedPacketException s'ils sont mal formés
     */
    public static NodeStates read(WireReader reader, int handle) throws MalformedPacketException {
        int count = reader.varUInt();
        if (count > MAX) {
            throw new MalformedPacketException("entité " + handle + " : " + count + " nodes, au-delà de " + MAX);
        }
        List<State> states = new ArrayList<>(count);
        try {
            for (int i = 0; i < count; i++) {
                int node = reader.varUInt();
                int flags = reader.u8();
                if ((flags & ~(HIDDEN | EMISSIVE | COLOR)) != 0) {
                    throw new MalformedPacketException("entité " + handle + " : drapeaux de node " + flags);
                }
                float emissive = (flags & EMISSIVE) != 0 ? reader.f32() : Float.NaN;
                float[] color = (flags & COLOR) != 0 ? new float[] {reader.f32(), reader.f32(), reader.f32()} : null;
                if ((flags & EMISSIVE) != 0 && !Float.isFinite(emissive)) {
                    throw new MalformedPacketException("entité " + handle + " : émission " + emissive);
                }
                states.add(new State(node, (flags & HIDDEN) != 0, emissive, color));
            }
            return new NodeStates(states);
        } catch (IllegalArgumentException refused) {
            throw new MalformedPacketException("entité " + handle + " : " + refused.getMessage());
        }
    }
}
