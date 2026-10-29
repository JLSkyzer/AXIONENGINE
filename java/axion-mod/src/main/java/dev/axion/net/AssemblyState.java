package dev.axion.net;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
 * @param bodies les corps autres que le corps racine, en poses relatives à lui (ADR-151 D8), dans
 *     l'ordre du serveur
 * @param joints l'état de chaque joint de l'assembly, dans l'ordre de la definition (ADR-151 D8)
 * @param animation la dernière commande de chaque couche d'animation (ADR-155, descripteur v3)
 */
public record AssemblyState(
        long x,
        long y,
        long z,
        int rotation,
        Velocity velocity,
        Bounds bounds,
        boolean sleeping,
        List<Body> bodies,
        JointBits joints,
        AnimationLayers animation) {

    /** Corps autres que le corps racine d'une assembly, au plus : elle en a 64 (R-190). */
    public static final int MAX_BODIES = 63;

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

    /**
     * La pose d'un corps autre que le corps racine, dans le repère du corps racine (ADR-151 D8) : elle ne
     * change pas quand l'assembly entière bouge.
     *
     * @param node node racine du corps dans l'asset
     * @param x position relative x, au 1/1024 de bloc
     * @param y position relative y, au 1/1024 de bloc
     * @param z position relative z, au 1/1024 de bloc
     * @param rotation rotation relative « smallest three » ({@link SmallestThree})
     */
    public record Body(int node, int x, int y, int z, int rotation) {

        /** Refuse un node négatif : c'est un index d'asset. */
        public Body {
            if (node < 0) {
                throw new IllegalArgumentException("node négatif : " + node);
            }
        }

        /**
         * Quantifie la pose relative d'un corps. Une position au-delà de ce que 32 bits disent, à plus de
         * deux millions de blocs du corps racine, est ramenée à cette borne.
         *
         * @param node node racine du corps
         * @param offset position relative au corps racine, dans son repère, en blocs, finie
         * @param rotation rotation relative {@code [x, y, z, w]}
         * @return la pose quantifiée
         */
        public static Body of(int node, double[] offset, float[] rotation) {
            return new Body(node, clamped(offset[0]), clamped(offset[1]), clamped(offset[2]),
                    SmallestThree.encode(rotation[0], rotation[1], rotation[2], rotation[3]));
        }

        /**
         * La pose d'un corps relative au corps racine, dans le repère de celui-ci, quantifiée : sa position
         * ramenée par l'inverse de l'orientation racine, et cet inverse composé avec son orientation.
         *
         * @param node node racine du corps
         * @param root position monde du corps racine, en blocs
         * @param rootRotation orientation monde du corps racine {@code [x, y, z, w]}, unitaire
         * @param position position monde du corps, en blocs
         * @param rotation orientation monde du corps {@code [x, y, z, w]}
         * @return la pose relative quantifiée
         */
        public static Body relative(int node, double[] root, float[] rootRotation, double[] position, float[] rotation) {
            // L'inverse d'un quaternion unitaire est son conjugué.
            double qx = -rootRotation[0];
            double qy = -rootRotation[1];
            double qz = -rootRotation[2];
            double qw = rootRotation[3];
            double vx = position[0] - root[0];
            double vy = position[1] - root[1];
            double vz = position[2] - root[2];
            // v' = v + w·t + q × t, avec t = 2·(q × v).
            double tx = 2 * (qy * vz - qz * vy);
            double ty = 2 * (qz * vx - qx * vz);
            double tz = 2 * (qx * vy - qy * vx);
            double[] offset = {
                vx + qw * tx + (qy * tz - qz * ty), vy + qw * ty + (qz * tx - qx * tz), vz + qw * tz + (qx * ty - qy * tx),
            };
            double bx = rotation[0];
            double by = rotation[1];
            double bz = rotation[2];
            double bw = rotation[3];
            float[] relative = {
                (float) (qw * bx + qx * bw + qy * bz - qz * by),
                (float) (qw * by - qx * bz + qy * bw + qz * bx),
                (float) (qw * bz + qx * by - qy * bx + qz * bw),
                (float) (qw * bw - qx * bx - qy * by - qz * bz),
            };
            return of(node, offset, relative);
        }

        private static int clamped(double blocks) {
            long steps = Quantization.position(blocks);
            return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, steps));
        }

        /** {@return la position relative au corps racine, dans son repère, en blocs} */
        public double[] offsetBlocks() {
            return new double[] {Quantization.position(x), Quantization.position(y), Quantization.position(z)};
        }

        /** {@return la rotation relative {@code (x, y, z, w)}} */
        public float[] quaternion() {
            return SmallestThree.decode(rotation);
        }
    }

    /**
     * L'état de chaque joint d'une assembly (ADR-151 D8), deux bits par joint dans l'ordre de la
     * definition : {@link #BROKEN} puis {@link #JAMMED}, les valeurs des drapeaux de
     * {@code JointState}. Le joint de rang {@code i} occupe les bits {@code 2i} et {@code 2i + 1}, et
     * les bits au-delà du dernier joint sont nuls.
     *
     * @param count nombre de joints
     * @param packed les bits, {@link #packedLength} octets ; copie
     */
    public record JointBits(int count, byte[] packed) {

        /** Drapeau : le joint est rompu. */
        public static final int BROKEN = 1;

        /** Drapeau : le joint est grippé. */
        public static final int JAMMED = 2;

        /** Joints d'une assembly, au plus : leur rang tient sur 16 bits. */
        public static final int MAX_JOINTS = 0xFFFF;

        /** Aucun joint. */
        public static final JointBits NONE = new JointBits(0, new byte[0]);

        /** Copie les bits, et refuse un nombre, une longueur ou des bits de remplissage incohérents. */
        public JointBits {
            if (count < 0 || count > MAX_JOINTS) {
                throw new IllegalArgumentException("nombre de joints " + count);
            }
            if (packed.length != packedLength(count)) {
                throw new IllegalArgumentException(packed.length + " octets pour " + count + " joints");
            }
            if (count % 4 != 0 && (packed[packed.length - 1] & 0xFF) >>> (2 * (count % 4)) != 0) {
                throw new IllegalArgumentException("bits non nuls après le dernier joint");
            }
            packed = packed.clone();
        }

        /**
         * @param count nombre de joints
         * @return la longueur de leurs bits, en octets
         */
        public static int packedLength(int count) {
            return (2 * count + 7) / 8;
        }

        /**
         * @param flags drapeaux de chaque joint, dans l'ordre de la definition ; seuls {@link #BROKEN}
         *     et {@link #JAMMED} sont gardés
         * @return leurs bits
         */
        public static JointBits of(int[] flags) {
            byte[] packed = new byte[packedLength(flags.length)];
            for (int joint = 0; joint < flags.length; joint++) {
                int bits = flags[joint] & (BROKEN | JAMMED);
                packed[joint / 4] |= (byte) (bits << (2 * (joint % 4)));
            }
            return new JointBits(flags.length, packed);
        }

        /**
         * @param joint rang du joint
         * @return ses drapeaux, {@link #BROKEN} et {@link #JAMMED}
         */
        public int flags(int joint) {
            if (joint < 0 || joint >= count) {
                throw new IndexOutOfBoundsException("joint " + joint + " sur " + count);
            }
            return (packed[joint / 4] >>> (2 * (joint % 4))) & (BROKEN | JAMMED);
        }

        /** {@return les bits, copie} */
        @Override
        public byte[] packed() {
            return packed.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof JointBits bits && count == bits.count && Arrays.equals(packed, bits.packed);
        }

        @Override
        public int hashCode() {
            return 31 * count + Arrays.hashCode(packed);
        }

        @Override
        public String toString() {
            return "JointBits[count=" + count + ", packed=" + Arrays.toString(packed) + "]";
        }
    }

    /**
     * Refuse une vitesse, des corps, des joints ou une animation absents, et des corps trop nombreux ou en
     * double.
     */
    public AssemblyState {
        if (velocity == null) {
            throw new IllegalArgumentException("vitesse absente");
        }
        if (bodies == null || joints == null || animation == null) {
            throw new IllegalArgumentException("corps, joints ou animation absents");
        }
        if (bodies.size() > MAX_BODIES) {
            throw new IllegalArgumentException(bodies.size() + " corps secondaires, au-delà de " + MAX_BODIES);
        }
        Set<Integer> nodes = new HashSet<>();
        for (Body body : bodies) {
            if (!nodes.add(body.node())) {
                throw new IllegalArgumentException("node " + body.node() + " de deux corps");
            }
        }
        bodies = List.copyOf(bodies);
    }

    /**
     * L'état d'une assembly à un seul corps, sans joint.
     *
     * @param x position x, au 1/1024 de bloc
     * @param y position y, au 1/1024 de bloc
     * @param z position z, au 1/1024 de bloc
     * @param rotation rotation « smallest three »
     * @param velocity vitesses quantifiées
     * @param bounds emprise relative à la position, ou {@code null}
     * @param sleeping le corps dort
     */
    public AssemblyState(long x, long y, long z, int rotation, Velocity velocity, Bounds bounds, boolean sleeping) {
        this(x, y, z, rotation, velocity, bounds, sleeping, List.of(), JointBits.NONE, AnimationLayers.NONE);
    }

    /**
     * L'état d'une assembly sans animation.
     *
     * @param x position x, au 1/1024 de bloc
     * @param y position y, au 1/1024 de bloc
     * @param z position z, au 1/1024 de bloc
     * @param rotation rotation « smallest three »
     * @param velocity vitesses quantifiées
     * @param bounds emprise relative à la position, ou {@code null}
     * @param sleeping le corps dort
     * @param bodies les corps autres que le corps racine
     * @param joints l'état de chaque joint
     */
    public AssemblyState(long x, long y, long z, int rotation, Velocity velocity, Bounds bounds, boolean sleeping,
            List<Body> bodies, JointBits joints) {
        this(x, y, z, rotation, velocity, bounds, sleeping, bodies, joints, AnimationLayers.NONE);
    }

    /**
     * @param otherBodies les corps autres que le corps racine, en poses relatives
     * @param jointBits l'état de chaque joint
     * @return cet état, avec ces corps et ces joints
     */
    public AssemblyState with(List<Body> otherBodies, JointBits jointBits) {
        return new AssemblyState(x, y, z, rotation, velocity, bounds, sleeping, otherBodies, jointBits, animation);
    }

    /**
     * @param layers l'état d'animation
     * @return cet état, avec cette animation
     */
    public AssemblyState withAnimation(AnimationLayers layers) {
        return new AssemblyState(x, y, z, rotation, velocity, bounds, sleeping, bodies, joints, layers);
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
