package dev.axion.net;

/**
 * L'état d'une assembly tel qu'un snapshot le porte : quantifié, champ par champ (R-1660, ADR-150 D6).
 *
 * <p>Ce sont ces entiers que serveur et client comparent et retranchent : deux états égaux le sont au
 * bit près, et un delta appliqué à sa base redonne exactement l'état d'origine.
 *
 * @param x position x, au 1/1024 de bloc
 * @param y position y, au 1/1024 de bloc
 * @param z position z, au 1/1024 de bloc
 * @param rotation rotation « smallest three » ({@link SmallestThree})
 * @param velocity vitesses quantifiées
 * @param bounds emprise relative à la position, ou {@code null} si le corps n'en a pas encore
 * @param sleeping le corps dort
 */
public record AssemblyState(
        long x, long y, long z, int rotation, Velocity velocity, Bounds bounds, boolean sleeping) {

    /**
     * Les vitesses, en pas de {@link Quantization}.
     *
     * @param lx vitesse linéaire x
     * @param ly vitesse linéaire y
     * @param lz vitesse linéaire z
     * @param ax vitesse angulaire x
     * @param ay vitesse angulaire y
     * @param az vitesse angulaire z
     */
    public record Velocity(short lx, short ly, short lz, short ax, short ay, short az) {

        /** Au repos. */
        public static final Velocity ZERO = new Velocity((short) 0, (short) 0, (short) 0, (short) 0, (short) 0, (short) 0);
    }

    /**
     * L'emprise, relative à la position, en pas de {@link Quantization#BOUNDS_STEPS_PER_BLOCK}.
     *
     * @param minX coin minimal x
     * @param minY coin minimal y
     * @param minZ coin minimal z
     * @param maxX coin maximal x
     * @param maxY coin maximal y
     * @param maxZ coin maximal z
     */
    public record Bounds(short minX, short minY, short minZ, short maxX, short maxY, short maxZ) {}

    /** Refuse une vitesse absente. */
    public AssemblyState {
        if (velocity == null) {
            throw new IllegalArgumentException("vitesse absente");
        }
    }

    /**
     * Quantifie l'état d'un corps.
     *
     * @param position position monde {@code [x, y, z]}, en blocs
     * @param rotation quaternion {@code [x, y, z, w]}
     * @param linear vitesse linéaire {@code [x, y, z]}, en m/s
     * @param angular vitesse angulaire {@code [x, y, z]}, en rad/s
     * @param boundsMin coin minimal de l'emprise, relatif à la position, ou {@code null}
     * @param boundsMax coin maximal de l'emprise, relatif à la position, ou {@code null}
     * @param sleeping le corps dort
     * @return l'état quantifié
     * @throws IllegalArgumentException si la position n'est pas finie : elle ne se quantifie pas
     */
    public static AssemblyState of(
            double[] position,
            float[] rotation,
            float[] linear,
            float[] angular,
            float[] boundsMin,
            float[] boundsMax,
            boolean sleeping) {
        for (double coordinate : position) {
            if (!Double.isFinite(coordinate)) {
                throw new IllegalArgumentException("position non finie");
            }
        }
        Velocity velocity = new Velocity(
                Quantization.linear(linear[0]), Quantization.linear(linear[1]), Quantization.linear(linear[2]),
                Quantization.angular(angular[0]), Quantization.angular(angular[1]), Quantization.angular(angular[2]));
        Bounds bounds = boundsMin == null || boundsMax == null ? null : new Bounds(
                Quantization.boundsMin(boundsMin[0]), Quantization.boundsMin(boundsMin[1]),
                Quantization.boundsMin(boundsMin[2]), Quantization.boundsMax(boundsMax[0]),
                Quantization.boundsMax(boundsMax[1]), Quantization.boundsMax(boundsMax[2]));
        return new AssemblyState(
                Quantization.position(position[0]),
                Quantization.position(position[1]),
                Quantization.position(position[2]),
                SmallestThree.encode(rotation[0], rotation[1], rotation[2], rotation[3]),
                velocity,
                bounds,
                sleeping);
    }

    /** {@return la position monde, en blocs} */
    public double[] positionBlocks() {
        return new double[] {Quantization.position(x), Quantization.position(y), Quantization.position(z)};
    }

    /** {@return le quaternion {@code (x, y, z, w)}} */
    public float[] quaternion() {
        return SmallestThree.decode(rotation);
    }

    /** {@return la vitesse linéaire, en m/s} */
    public float[] linearVelocity() {
        return new float[] {
            Quantization.linear(velocity.lx()), Quantization.linear(velocity.ly()), Quantization.linear(velocity.lz()),
        };
    }

    /** {@return la vitesse angulaire, en rad/s} */
    public float[] angularVelocity() {
        return new float[] {
            Quantization.angular(velocity.ax()), Quantization.angular(velocity.ay()), Quantization.angular(velocity.az()),
        };
    }

    /** {@return le coin minimal de l'emprise, relatif à la position, ou {@code null}} */
    public float[] boundsMin() {
        return bounds == null ? null : new float[] {
            Quantization.bounds(bounds.minX()), Quantization.bounds(bounds.minY()), Quantization.bounds(bounds.minZ()),
        };
    }

    /** {@return le coin maximal de l'emprise, relatif à la position, ou {@code null}} */
    public float[] boundsMax() {
        return bounds == null ? null : new float[] {
            Quantization.bounds(bounds.maxX()), Quantization.bounds(bounds.maxY()), Quantization.bounds(bounds.maxZ()),
        };
    }
}
