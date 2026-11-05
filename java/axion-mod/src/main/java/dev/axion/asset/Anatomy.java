package dev.axion.asset;

import java.nio.ByteBuffer;

/**
 * Les nodes d'un asset sous une definition compilée, tels que le natif les rend (ADR-158 D2, D3) :
 * le parent de chaque node, le corps qui le porte, sa pose de repos dans le repère de ce corps, et le
 * node porteur de chaque collider de chaque corps.
 *
 * <p>Java pose un siège en composant la pose monde de son corps avec la pose du node dans ce corps,
 * et ramène le collider qu'un rayon a touché à son node, puis aux ancêtres de celui-ci. Logique pure,
 * sans Forge.
 */
public final class Anatomy {

    /** Aucun node : le résumé d'une definition sans siège, pour les tests. */
    public static final Anatomy NONE = new Anatomy(new int[0], new int[0], new float[0], new int[0][]);

    /** Taille d'un node dans le résumé, en octets : parent, corps, translation, rotation. */
    static final int NODE_BYTES = 36;

    private final int[] parents;
    private final int[] bodies;
    /** Sept valeurs par node : translation {@code x, y, z}, rotation {@code x, y, z, w}. */
    private final float[] poses;
    private final int[][] carriers;

    private Anatomy(int[] parents, int[] bodies, float[] poses, int[][] carriers) {
        this.parents = parents;
        this.bodies = bodies;
        this.poses = poses;
        this.carriers = carriers;
    }

    /**
     * {@return une anatomie donnée en clair}
     *
     * @param parents le parent de chaque node, {@code -1} pour une racine ; un parent précède son enfant
     * @param bodies le corps de chaque node
     * @param poses sept valeurs par node : translation, puis rotation {@code x, y, z, w}
     * @param carriers pour chaque corps, le node porteur de chacun de ses colliders
     * @throws IllegalArgumentException si les tailles ne concordent pas, ou si un parent suit son enfant
     */
    public static Anatomy of(int[] parents, int[] bodies, float[] poses, int[][] carriers) {
        if (bodies.length != parents.length || poses.length != parents.length * 7) {
            throw new IllegalArgumentException("un corps et sept valeurs de pose par node");
        }
        for (int node = 0; node < parents.length; node++) {
            if (parents[node] != -1 && (parents[node] < 0 || parents[node] >= node)) {
                throw new IllegalArgumentException("le parent du node " + node + " ne le précède pas");
            }
        }
        int[][] copy = new int[carriers.length][];
        for (int body = 0; body < carriers.length; body++) {
            copy[body] = carriers[body].clone();
        }
        return new Anatomy(parents.clone(), bodies.clone(), poses.clone(), copy);
    }

    /**
     * Lit les nodes, puis les porteurs des colliders de {@code bodyCount} corps.
     *
     * @param in le résumé, placé sur le nombre de nodes
     * @param bodyCount nombre de corps de l'assembly
     * @return l'anatomie, ou {@code null} si elle ne se lit pas
     * @throws java.nio.BufferUnderflowException si le résumé est tronqué
     */
    static Anatomy read(ByteBuffer in, int bodyCount) {
        int count = in.getInt();
        if (count < 0 || count > in.remaining() / NODE_BYTES) {
            return null;
        }
        int[] parents = new int[count];
        int[] bodies = new int[count];
        float[] poses = new float[count * 7];
        for (int node = 0; node < count; node++) {
            parents[node] = in.getInt();
            bodies[node] = in.getInt();
            for (int k = 0; k < 7; k++) {
                poses[node * 7 + k] = in.getFloat();
            }
            if (parents[node] != -1 && (parents[node] < 0 || parents[node] >= node)) {
                return null;
            }
        }
        int[][] carriers = new int[bodyCount][];
        for (int body = 0; body < bodyCount; body++) {
            int colliders = in.getInt();
            if (colliders < 0 || colliders > in.remaining() / Integer.BYTES) {
                return null;
            }
            carriers[body] = new int[colliders];
            for (int k = 0; k < colliders; k++) {
                carriers[body][k] = in.getInt();
            }
        }
        return new Anatomy(parents, bodies, poses, carriers);
    }

    /** {@return le nombre de nodes de l'asset} */
    public int size() {
        return parents.length;
    }

    /**
     * {@return le parent d'un node, {@code -1} pour une racine ou un node hors de la table}
     *
     * @param node index du node
     */
    public int parent(int node) {
        return node >= 0 && node < parents.length ? parents[node] : -1;
    }

    /**
     * {@return le rang du corps qui porte un node, {@code -1} s'il n'a pas de pose — une chaîne
     * qu'une échelle non uniforme rompt — ou s'il est hors de la table}
     *
     * @param node index du node
     */
    public int body(int node) {
        return node >= 0 && node < bodies.length ? bodies[node] : -1;
    }

    /**
     * {@return la position de repos d'un node dans le repère de son corps, en blocs}
     *
     * @param node index d'un node de la table
     */
    public float[] translation(int node) {
        return new float[] {poses[node * 7], poses[node * 7 + 1], poses[node * 7 + 2]};
    }

    /**
     * {@return la rotation de repos d'un node dans le repère de son corps, {@code x, y, z, w}}
     *
     * @param node index d'un node de la table
     */
    public float[] rotation(int node) {
        return new float[] {poses[node * 7 + 3], poses[node * 7 + 4], poses[node * 7 + 5], poses[node * 7 + 6]};
    }

    /**
     * {@return le node qui porte un collider, {@code -1} pour le collider de l'asset entier ou hors
     * de la table}
     *
     * @param body rang du corps dans l'assembly
     * @param collider rang du collider dans ce corps
     */
    public int carrier(int body, int collider) {
        if (body < 0 || body >= carriers.length || collider < 0 || collider >= carriers[body].length) {
            return -1;
        }
        return carriers[body][collider];
    }
}
