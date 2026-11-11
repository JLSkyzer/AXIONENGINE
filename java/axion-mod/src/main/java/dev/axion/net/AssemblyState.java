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
 * @param wheels l'état de chaque roue, dans l'ordre de la definition (ADR-159 D9, descripteur 4)
 * @param vehicle l'état du véhicule, {@link Vehicle#NONE} si l'assembly n'en est pas un (ADR-163 D1, descripteur 5)
 * @param variables les variables de contenu, {@link VariableValues#NONE} sans (ADR-163 D2, descripteur 5)
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
        AnimationLayers animation,
        Wheels wheels,
        Vehicle vehicle,
        VariableValues variables) {

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
     * L'état de chaque roue d'un véhicule (ADR-159 D9), quantifié : par roue, compression i8 en fraction du
     * débattement dans {@code [-2, 2]}, vitesse de rotation i16 au 1/8 rad/s, braquage i8 dans
     * {@code [-π/2, π/2]} ; puis un bit {@code on_ground} par roue. {@code -128} n'est pas une valeur de
     * compression ni de braquage : un entier à bornes symétriques, relues à l'identique.
     *
     * @param count nombre de roues
     * @param packed les valeurs, {@link #packedLength} octets ; copie
     */
    public record Wheels(int count, byte[] packed) {

        /** Roues d'un véhicule, au plus (R-1052). */
        public static final int MAX_WHEELS = 32;

        /** Aucune roue. */
        public static final Wheels NONE = new Wheels(0, new byte[0]);

        private static final int RECORD_BYTES = 4;
        private static final float COMPRESSION_STEPS = 127f / 2f;
        private static final float SPIN_STEPS = 8f;
        private static final float STEER_STEPS = (float) (127.0 / (Math.PI / 2.0));

        /** Copie les valeurs, et refuse un nombre, une longueur, une borne ou des bits de remplissage incohérents. */
        public Wheels {
            if (count < 0 || count > MAX_WHEELS) {
                throw new IllegalArgumentException("nombre de roues " + count);
            }
            if (packed.length != packedLength(count)) {
                throw new IllegalArgumentException(packed.length + " octets pour " + count + " roues");
            }
            for (int wheel = 0; wheel < count; wheel++) {
                if (packed[wheel * RECORD_BYTES] == Byte.MIN_VALUE || packed[wheel * RECORD_BYTES + 3] == Byte.MIN_VALUE) {
                    throw new IllegalArgumentException("roue " + wheel + " hors de ses bornes");
                }
            }
            int spare = count % 8;
            if (spare != 0 && (packed[packed.length - 1] & 0xFF) >>> spare != 0) {
                throw new IllegalArgumentException("bits non nuls après la dernière roue");
            }
            packed = packed.clone();
        }

        /**
         * @param count nombre de roues
         * @return la longueur de leurs valeurs, en octets
         */
        public static int packedLength(int count) {
            return count * RECORD_BYTES + (count + 7) / 8;
        }

        /**
         * Quantifie l'état des roues. Une valeur non finie compte pour zéro ; une valeur hors bornes est
         * ramenée à sa borne.
         *
         * @param compression compression de chaque roue, en fraction du débattement
         * @param spin vitesse de rotation de chaque roue, en rad/s
         * @param steer braquage de chaque roue, en radians
         * @param onGround la roue touche le sol
         * @return leur état
         */
        public static Wheels of(float[] compression, float[] spin, float[] steer, boolean[] onGround) {
            int count = compression.length;
            byte[] packed = new byte[packedLength(count)];
            int grounds = count * RECORD_BYTES;
            for (int wheel = 0; wheel < count; wheel++) {
                int at = wheel * RECORD_BYTES;
                short speed = (short) bounded(spin[wheel] * SPIN_STEPS, Short.MAX_VALUE);
                packed[at] = (byte) bounded(compression[wheel] * COMPRESSION_STEPS, Byte.MAX_VALUE);
                packed[at + 1] = (byte) (speed >> 8);
                packed[at + 2] = (byte) speed;
                packed[at + 3] = (byte) bounded(steer[wheel] * STEER_STEPS, Byte.MAX_VALUE);
                if (onGround[wheel]) {
                    packed[grounds + wheel / 8] |= (byte) (1 << (wheel % 8));
                }
            }
            return new Wheels(count, packed);
        }

        private static int bounded(float steps, int limit) {
            if (!Float.isFinite(steps)) {
                return 0;
            }
            return Math.max(-limit, Math.min(limit, Math.round(steps)));
        }

        /**
         * @param wheel rang de la roue
         * @return sa compression, en fraction du débattement
         */
        public float compression(int wheel) {
            return packed[at(wheel)] / COMPRESSION_STEPS;
        }

        /**
         * @param wheel rang de la roue
         * @return sa vitesse de rotation, en rad/s
         */
        public float spin(int wheel) {
            int at = at(wheel);
            return (short) ((packed[at + 1] & 0xFF) << 8 | packed[at + 2] & 0xFF) / SPIN_STEPS;
        }

        /**
         * @param wheel rang de la roue
         * @return son braquage, en radians
         */
        public float steer(int wheel) {
            return packed[at(wheel) + 3] / STEER_STEPS;
        }

        /**
         * @param wheel rang de la roue
         * @return vrai si elle touche le sol
         */
        public boolean onGround(int wheel) {
            at(wheel);
            return (packed[count * RECORD_BYTES + wheel / 8] >>> (wheel % 8) & 1) != 0;
        }

        private int at(int wheel) {
            if (wheel < 0 || wheel >= count) {
                throw new IndexOutOfBoundsException("roue " + wheel + " sur " + count);
            }
            return wheel * RECORD_BYTES;
        }

        /** {@return les valeurs, copie} */
        @Override
        public byte[] packed() {
            return packed.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Wheels wheels && count == wheels.count && Arrays.equals(packed, wheels.packed);
        }

        @Override
        public int hashCode() {
            return 31 * count + Arrays.hashCode(packed);
        }

        @Override
        public String toString() {
            return "Wheels[count=" + count + ", packed=" + Arrays.toString(packed) + "]";
        }
    }

    /**
     * L'état d'un véhicule (ADR-163 D1), quantifié sur {@link #BYTES} octets : régime i16 par pas de
     * {@value #RPM_STEP} tr/min, charge u8 au 1/255, rapport i8, drapeaux u8, puis les entrées répliquées —
     * accélérateur u8 au 1/255, frein u8 au 1/255, direction i8 au 1/127 dans {@code [-1, 1]}, frein à main u8 au
     * 1/255. Le régime est d'abord l'octet fort. {@code -32768} n'est pas un régime ni {@code -128} une direction :
     * des bornes symétriques, relues à l'identique.
     *
     * <p>Tout à zéro, c'est {@link #NONE} : une assembly qui n'est pas un véhicule, et qu'un snapshot ne porte pas.
     * Un véhicule à motopropulseur a toujours {@link #RUNNING} : il n'est jamais {@code NONE}.
     *
     * @param packed les valeurs, {@link #BYTES} octets ; copie
     */
    public record Vehicle(byte[] packed) {

        /** Taille de l'état, en octets. */
        public static final int BYTES = 9;

        /** Pas du régime, en tr/min. */
        public static final int RPM_STEP = 2;

        /** Drapeau : un changement de rapport est en cours. */
        public static final int SHIFTING = 1;

        /** Drapeau : le moteur tourne. */
        public static final int RUNNING = 2;

        /** Drapeau : le siège {@code driver} est occupé. */
        public static final int DRIVER = 4;

        private static final int KNOWN_FLAGS = SHIFTING | RUNNING | DRIVER;

        private static final float UNIT_STEPS = 255f;

        private static final float STEER_STEPS = 127f;

        /** Aucun véhicule. */
        public static final Vehicle NONE = new Vehicle(new byte[BYTES]);

        /** Copie les valeurs, et refuse une longueur, une borne ou un drapeau incohérents. */
        public Vehicle {
            if (packed.length != BYTES) {
                throw new IllegalArgumentException(packed.length + " octets pour l'état d'un véhicule");
            }
            if (packed[0] == Byte.MIN_VALUE && packed[1] == 0) {
                throw new IllegalArgumentException("régime hors de ses bornes");
            }
            if ((packed[4] & 0xFF & ~KNOWN_FLAGS) != 0) {
                throw new IllegalArgumentException("drapeaux de véhicule inconnus : " + (packed[4] & 0xFF));
            }
            if (packed[7] == Byte.MIN_VALUE) {
                throw new IllegalArgumentException("direction hors de ses bornes");
            }
            packed = packed.clone();
        }

        /**
         * Quantifie l'état d'un véhicule. Une valeur non finie compte pour zéro ; une valeur hors bornes est
         * ramenée à sa borne.
         *
         * @param rpm régime, en tr/min
         * @param load charge du moteur, dans [0, 1]
         * @param gear rapport engagé, négatif en marche arrière
         * @param flags {@link #SHIFTING}, {@link #RUNNING}, {@link #DRIVER} ; un autre bit est ignoré
         * @param throttle accélérateur, dans [0, 1]
         * @param brake frein, dans [0, 1]
         * @param steer direction, dans [-1, 1]
         * @param handbrake frein à main, dans [0, 1]
         * @return son état
         */
        public static Vehicle of(float rpm, float load, int gear, int flags, float throttle, float brake, float steer,
                float handbrake) {
            short steps = (short) bounded(rpm / RPM_STEP, -Short.MAX_VALUE, Short.MAX_VALUE);
            return new Vehicle(new byte[] {
                (byte) (steps >> 8),
                (byte) steps,
                (byte) bounded(load * UNIT_STEPS, 0, 255),
                (byte) Math.max(Byte.MIN_VALUE, Math.min(Byte.MAX_VALUE, gear)),
                (byte) (flags & KNOWN_FLAGS),
                (byte) bounded(throttle * UNIT_STEPS, 0, 255),
                (byte) bounded(brake * UNIT_STEPS, 0, 255),
                (byte) bounded(steer * STEER_STEPS, -Byte.MAX_VALUE, Byte.MAX_VALUE),
                (byte) bounded(handbrake * UNIT_STEPS, 0, 255),
            });
        }

        private static int bounded(float steps, int low, int high) {
            if (!Float.isFinite(steps)) {
                return 0;
            }
            return Math.max(low, Math.min(high, Math.round(steps)));
        }

        /** {@return le régime, en tr/min} */
        public float rpm() {
            return (short) ((packed[0] & 0xFF) << 8 | packed[1] & 0xFF) * (float) RPM_STEP;
        }

        /** {@return la charge du moteur, dans [0, 1]} */
        public float load() {
            return (packed[2] & 0xFF) / UNIT_STEPS;
        }

        /** {@return le rapport engagé, négatif en marche arrière, 0 au point mort} */
        public int gear() {
            return packed[3];
        }

        /** {@return les drapeaux} */
        public int flags() {
            return packed[4] & 0xFF;
        }

        /** {@return l'accélérateur, dans [0, 1]} */
        public float throttle() {
            return (packed[5] & 0xFF) / UNIT_STEPS;
        }

        /** {@return le frein, dans [0, 1]} */
        public float brake() {
            return (packed[6] & 0xFF) / UNIT_STEPS;
        }

        /** {@return la direction, dans [-1, 1], positive à droite} */
        public float steer() {
            return packed[7] / STEER_STEPS;
        }

        /** {@return le frein à main, dans [0, 1]} */
        public float handbrake() {
            return (packed[8] & 0xFF) / UNIT_STEPS;
        }

        /** {@return les valeurs, copie} */
        @Override
        public byte[] packed() {
            return packed.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Vehicle vehicle && Arrays.equals(packed, vehicle.packed);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(packed);
        }

        @Override
        public String toString() {
            return "Vehicle[packed=" + Arrays.toString(packed) + "]";
        }
    }

    /**
     * Les variables de contenu d'une assembly (ADR-163 D2), telles qu'un joueur les reçoit : celles que le masque
     * désigne, chacune en {@code f32} — un booléen ou un entier de moins de 2<sup>24</sup> y tient exactement. Le
     * serveur ne met dans le masque que les variables que ce joueur doit recevoir ({@code replicate}).
     *
     * @param count nombre de variables de la definition, au plus {@link #MAX_VARIABLES}
     * @param mask les variables présentes, un bit par rang
     * @param values la valeur de chaque variable, 0 pour une absente ; copie
     */
    public record VariableValues(int count, long mask, float[] values) {

        /** Variables d'une definition, au plus (ADR-163 D2). */
        public static final int MAX_VARIABLES = 64;

        /** Aucune variable. */
        public static final VariableValues NONE = new VariableValues(0, 0L, new float[0]);

        /** Copie les valeurs, et refuse un nombre, un masque ou une valeur incohérents. */
        public VariableValues {
            if (count < 0 || count > MAX_VARIABLES || values.length != count) {
                throw new IllegalArgumentException(values.length + " valeurs pour " + count + " variables");
            }
            if (count < 64 && mask >>> count != 0) {
                throw new IllegalArgumentException("bits de variables au-delà de la dernière");
            }
            for (int i = 0; i < count; i++) {
                boolean present = (mask >>> i & 1L) != 0;
                if (!Float.isFinite(values[i]) || (!present && Float.floatToRawIntBits(values[i]) != 0)) {
                    throw new IllegalArgumentException("variable " + i + " : valeur " + values[i]);
                }
            }
            values = values.clone();
        }

        /**
         * {@return les variables d'une assembly, toutes présentes}
         *
         * @param values la valeur de chacune, dans l'ordre de la definition
         */
        public static VariableValues of(double[] values) {
            float[] floats = new float[values.length];
            long mask = 0L;
            for (int i = 0; i < values.length; i++) {
                floats[i] = (float) values[i];
                mask |= 1L << i;
            }
            return new VariableValues(values.length, mask, floats);
        }

        /**
         * {@return ces variables, réduites à celles du masque}
         *
         * @param keep les variables à garder, un bit par rang
         */
        public VariableValues only(long keep) {
            long kept = mask & keep;
            if (kept == mask) {
                return this;
            }
            float[] filtered = new float[count];
            for (int i = 0; i < count; i++) {
                if ((kept >>> i & 1L) != 0) {
                    filtered[i] = values[i];
                }
            }
            return new VariableValues(count, kept, filtered);
        }

        /**
         * {@return vrai si cette variable est présente}
         *
         * @param index son rang
         */
        public boolean has(int index) {
            return index >= 0 && index < count && (mask >>> index & 1L) != 0;
        }

        /**
         * {@return la valeur d'une variable présente, 0 sinon}
         *
         * @param index son rang
         */
        public float value(int index) {
            return has(index) ? values[index] : 0f;
        }

        /** {@return les valeurs, copie} */
        @Override
        public float[] values() {
            return values.clone();
        }

        /** {@return le nombre de variables présentes} */
        public int present() {
            return Long.bitCount(mask);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof VariableValues that && count == that.count && mask == that.mask
                    && Arrays.equals(values, that.values);
        }

        @Override
        public int hashCode() {
            return 31 * (31 * count + Long.hashCode(mask)) + Arrays.hashCode(values);
        }

        @Override
        public String toString() {
            return "VariableValues[count=" + count + ", mask=" + Long.toBinaryString(mask) + ", values="
                    + Arrays.toString(values) + "]";
        }
    }

    /**
     * Refuse une vitesse, des corps, des joints, une animation, des roues ou un véhicule absents, et des corps
     * trop nombreux ou en double.
     */
    public AssemblyState {
        if (velocity == null) {
            throw new IllegalArgumentException("vitesse absente");
        }
        if (bodies == null || joints == null || animation == null || wheels == null || vehicle == null
                || variables == null) {
            throw new IllegalArgumentException("corps, joints, animation, roues, véhicule ou variables absents");
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
        this(x, y, z, rotation, velocity, bounds, sleeping, List.of(), JointBits.NONE, AnimationLayers.NONE, Wheels.NONE,
                Vehicle.NONE, VariableValues.NONE);
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
        this(x, y, z, rotation, velocity, bounds, sleeping, bodies, joints, AnimationLayers.NONE, Wheels.NONE,
                Vehicle.NONE, VariableValues.NONE);
    }

    /**
     * L'état d'une assembly sans roues.
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
     * @param animation l'état d'animation
     */
    public AssemblyState(long x, long y, long z, int rotation, Velocity velocity, Bounds bounds, boolean sleeping,
            List<Body> bodies, JointBits joints, AnimationLayers animation) {
        this(x, y, z, rotation, velocity, bounds, sleeping, bodies, joints, animation, Wheels.NONE, Vehicle.NONE,
                VariableValues.NONE);
    }

    /**
     * L'état d'une assembly qui n'est pas un véhicule.
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
     * @param animation l'état d'animation
     * @param wheels l'état de chaque roue
     */
    public AssemblyState(long x, long y, long z, int rotation, Velocity velocity, Bounds bounds, boolean sleeping,
            List<Body> bodies, JointBits joints, AnimationLayers animation, Wheels wheels) {
        this(x, y, z, rotation, velocity, bounds, sleeping, bodies, joints, animation, wheels, Vehicle.NONE,
                VariableValues.NONE);
    }

    /**
     * L'état d'une assembly sans variables de contenu.
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
     * @param animation l'état d'animation
     * @param wheels l'état de chaque roue
     * @param vehicle l'état du véhicule
     */
    public AssemblyState(long x, long y, long z, int rotation, Velocity velocity, Bounds bounds, boolean sleeping,
            List<Body> bodies, JointBits joints, AnimationLayers animation, Wheels wheels, Vehicle vehicle) {
        this(x, y, z, rotation, velocity, bounds, sleeping, bodies, joints, animation, wheels, vehicle,
                VariableValues.NONE);
    }

    /**
     * @param otherBodies les corps autres que le corps racine, en poses relatives
     * @param jointBits l'état de chaque joint
     * @return cet état, avec ces corps et ces joints
     */
    public AssemblyState with(List<Body> otherBodies, JointBits jointBits) {
        return new AssemblyState(x, y, z, rotation, velocity, bounds, sleeping, otherBodies, jointBits, animation,
                wheels, vehicle, variables);
    }

    /**
     * @param layers l'état d'animation
     * @return cet état, avec cette animation
     */
    public AssemblyState withAnimation(AnimationLayers layers) {
        return new AssemblyState(
                x, y, z, rotation, velocity, bounds, sleeping, bodies, joints, layers, wheels, vehicle, variables);
    }

    /**
     * @param states l'état des roues
     * @return cet état, avec ces roues
     */
    public AssemblyState withWheels(Wheels states) {
        return new AssemblyState(
                x, y, z, rotation, velocity, bounds, sleeping, bodies, joints, animation, states, vehicle, variables);
    }

    /**
     * @param state l'état du véhicule
     * @return cet état, avec ce véhicule
     */
    public AssemblyState withVehicle(Vehicle state) {
        return new AssemblyState(
                x, y, z, rotation, velocity, bounds, sleeping, bodies, joints, animation, wheels, state, variables);
    }

    /**
     * @param values les variables de contenu
     * @return cet état, avec ces variables
     */
    public AssemblyState withVariables(VariableValues values) {
        return new AssemblyState(
                x, y, z, rotation, velocity, bounds, sleeping, bodies, joints, animation, wheels, vehicle, values);
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
