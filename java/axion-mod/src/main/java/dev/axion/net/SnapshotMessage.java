package dev.axion.net;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * {@code S2C_Snapshot} (21.5, ADR-150 D6) : les états des assemblies qu'un joueur suit, chacun complet ou
 * en delta sur une base qu'il nomme.
 *
 * <pre>
 * genre u8 = 5 | séquence varuint | tick serveur varulong | descripteur u8 | n varuint | n entrées
 * entrée : handle varuint | drapeaux u8 | [base varuint] | position | rotation i32
 *          | [vitesses 6 × i16] | [emprise 6 × i16] | [corps] | [joints]
 * position : 3 × i64 absolus (entrée complète) ou 3 × i32 de delta, au 1/1024 de bloc
 * corps : n varuint | n × (node varuint | position 3 × i32 au 1/1024 de bloc | rotation i32)
 * joints : n varuint | ⌈2n / 8⌉ octets, deux bits par joint
 * </pre>
 *
 * <p>Une entrée en delta nomme la séquence du snapshot qui portait sa base : le client vérifie qu'il la
 * tient, et redemande l'entrée complète sinon, plutôt que d'appliquer un delta à la mauvaise base. Une
 * vitesse ou une emprise absentes d'un delta sont celles de la base ; absentes d'une entrée complète, la
 * vitesse est nulle et l'emprise inconnue.
 *
 * <p>Les corps autres que le corps racine, en poses relatives à lui, et les bits de chaque joint suivent
 * (descripteur 2, ADR-151 D8). Chacun part entier quand il a changé depuis la base, et pas du tout
 * sinon : absents d'un delta, ce sont ceux de la base ; absents d'une entrée complète, l'assembly n'en a
 * pas.
 *
 * @param sequence numéro du snapshot chez l'émetteur, à partir de 1
 * @param serverTick tick du serveur dont il porte les états
 * @param descriptor version du descripteur d'états procéduraux
 * @param entries entrées, une par assembly au plus
 */
public record SnapshotMessage(int sequence, long serverTick, int descriptor, List<Entry> entries) {

    /**
     * Le descripteur d'états procéduraux (ADR-150 D6) : 2 depuis ADR-151 D8, qui y ajoute les corps
     * autres que le corps racine et les joints.
     */
    public static final int DESCRIPTOR_VERSION = 2;

    /** Nombre maximal d'entrées d'un snapshot (R-1691). */
    static final int MAX_ENTRIES = 8192;

    static final int FLAG_FULL = 1;
    static final int FLAG_VELOCITY = 2;
    static final int FLAG_BOUNDS = 4;
    static final int FLAG_SLEEPING = 8;
    static final int FLAG_BODIES = 16;
    static final int FLAG_JOINTS = 32;
    private static final int KNOWN_FLAGS =
            FLAG_FULL | FLAG_VELOCITY | FLAG_BOUNDS | FLAG_SLEEPING | FLAG_BODIES | FLAG_JOINTS;

    /** Copie les entrées. */
    public SnapshotMessage {
        entries = List.copyOf(entries);
    }

    /**
     * L'entrée d'une assembly.
     *
     * @param handle identifiant réseau de l'entité de l'assembly
     * @param base séquence du snapshot qui portait la base du delta, {@code 0} pour une entrée complète
     * @param x position x absolue (entrée complète) ou delta
     * @param y position y absolue ou delta
     * @param z position z absolue ou delta
     * @param rotation rotation « smallest three »
     * @param velocity vitesses, ou {@code null} : celles de la base, nulles pour une entrée complète
     * @param bounds emprise, ou {@code null} : celle de la base, inconnue pour une entrée complète
     * @param sleeping le corps dort
     * @param bodies corps autres que le corps racine, ou {@code null} : ceux de la base, aucun pour une
     *     entrée complète
     * @param joints bits des joints, ou {@code null} : ceux de la base, aucun pour une entrée complète
     */
    public record Entry(
            int handle,
            int base,
            long x,
            long y,
            long z,
            int rotation,
            AssemblyState.Velocity velocity,
            AssemblyState.Bounds bounds,
            boolean sleeping,
            List<AssemblyState.Body> bodies,
            AssemblyState.JointBits joints) {

        /** Copie les corps. */
        public Entry {
            bodies = bodies == null ? null : List.copyOf(bodies);
        }

        /** {@return vrai pour une entrée complète} */
        public boolean full() {
            return base == 0;
        }

        /**
         * @param handle identifiant réseau de l'entité
         * @param state état de l'assembly
         * @return l'entrée complète
         */
        public static Entry full(int handle, AssemblyState state) {
            AssemblyState.Velocity velocity =
                    AssemblyState.Velocity.ZERO.equals(state.velocity()) ? null : state.velocity();
            return new Entry(handle, 0, state.x(), state.y(), state.z(), state.rotation(), velocity,
                    state.bounds(), state.sleeping(), state.bodies().isEmpty() ? null : state.bodies(),
                    state.joints().count() == 0 ? null : state.joints());
        }

        /**
         * L'entrée en delta sur une base, ou complète si un delta ne peut pas la dire : un écart qui ne
         * tient pas sur 32 bits, ou une emprise disparue depuis la base.
         *
         * @param handle identifiant réseau de l'entité
         * @param baseSequence séquence du snapshot qui portait la base, à partir de 1
         * @param base état de la base, tel que le client le tient
         * @param state état courant
         * @return l'entrée
         */
        public static Entry delta(int handle, int baseSequence, AssemblyState base, AssemblyState state) {
            long dx = state.x() - base.x();
            long dy = state.y() - base.y();
            long dz = state.z() - base.z();
            if (!fitsInt(dx) || !fitsInt(dy) || !fitsInt(dz) || (state.bounds() == null && base.bounds() != null)) {
                return full(handle, state);
            }
            AssemblyState.Velocity velocity = state.velocity().equals(base.velocity()) ? null : state.velocity();
            AssemblyState.Bounds bounds = Objects.equals(state.bounds(), base.bounds()) ? null : state.bounds();
            List<AssemblyState.Body> bodies = state.bodies().equals(base.bodies()) ? null : state.bodies();
            AssemblyState.JointBits joints = state.joints().equals(base.joints()) ? null : state.joints();
            return new Entry(handle, baseSequence, dx, dy, dz, state.rotation(), velocity, bounds, state.sleeping(),
                    bodies, joints);
        }

        /**
         * @param base état de la base nommée par l'entrée ; ignoré pour une entrée complète
         * @return l'état que l'entrée décrit
         */
        public AssemblyState resolve(AssemblyState base) {
            if (full()) {
                return new AssemblyState(x, y, z, rotation,
                        velocity == null ? AssemblyState.Velocity.ZERO : velocity, bounds, sleeping,
                        bodies == null ? List.of() : bodies, joints == null ? AssemblyState.JointBits.NONE : joints);
            }
            return new AssemblyState(base.x() + x, base.y() + y, base.z() + z, rotation,
                    velocity == null ? base.velocity() : velocity, bounds == null ? base.bounds() : bounds, sleeping,
                    bodies == null ? base.bodies() : bodies, joints == null ? base.joints() : joints);
        }

        private static boolean fitsInt(long value) {
            return value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE;
        }
    }

    /**
     * @param entry entrée
     * @return sa taille sur le fil, en octets : celle qu'{@link #encode} lui donne
     */
    static int encodedSize(Entry entry) {
        return varintSize(entry.handle()) + 1
                + (entry.full() ? 3 * Long.BYTES : varintSize(entry.base()) + 3 * Integer.BYTES)
                + Integer.BYTES
                + (entry.velocity() != null ? 6 * Short.BYTES : 0)
                + (entry.bounds() != null ? 6 * Short.BYTES : 0)
                + (entry.bodies() != null ? bodiesSize(entry.bodies()) : 0)
                + (entry.joints() != null
                        ? varintSize(entry.joints().count())
                                + AssemblyState.JointBits.packedLength(entry.joints().count())
                        : 0);
    }

    private static int bodiesSize(List<AssemblyState.Body> bodies) {
        int size = varintSize(bodies.size());
        for (AssemblyState.Body body : bodies) {
            size += varintSize(body.node()) + 4 * Integer.BYTES;
        }
        return size;
    }

    /**
     * @param sequence séquence du snapshot
     * @param serverTick tick du serveur
     * @param count nombre d'entrées
     * @return la taille de l'en-tête sur le fil, en octets
     */
    static int headerSize(int sequence, long serverTick, int count) {
        return 1 + varintSize(sequence) + varintSize(serverTick) + 1 + varintSize(count);
    }

    private static int varintSize(long value) {
        int size = 1;
        for (long rest = value >>> 7; rest != 0; rest >>>= 7) {
            size++;
        }
        return size;
    }

    /** {@return le message, genre compris} */
    public byte[] encode() {
        WireWriter writer = new WireWriter(32 + 48 * entries.size())
                .u8(MessageKinds.SNAPSHOT)
                .varUInt(sequence)
                .varULong(serverTick)
                .u8(descriptor)
                .varUInt(entries.size());
        for (Entry entry : entries) {
            int flags = (entry.full() ? FLAG_FULL : 0)
                    | (entry.velocity() != null ? FLAG_VELOCITY : 0)
                    | (entry.bounds() != null ? FLAG_BOUNDS : 0)
                    | (entry.sleeping() ? FLAG_SLEEPING : 0)
                    | (entry.bodies() != null ? FLAG_BODIES : 0)
                    | (entry.joints() != null ? FLAG_JOINTS : 0);
            writer.varUInt(entry.handle()).u8(flags);
            if (entry.full()) {
                writer.i64(entry.x()).i64(entry.y()).i64(entry.z());
            } else {
                writer.varUInt(entry.base()).i32((int) entry.x()).i32((int) entry.y()).i32((int) entry.z());
            }
            writer.i32(entry.rotation());
            if (entry.velocity() != null) {
                AssemblyState.Velocity v = entry.velocity();
                writer.i16(v.lx()).i16(v.ly()).i16(v.lz()).i16(v.ax()).i16(v.ay()).i16(v.az());
            }
            if (entry.bounds() != null) {
                AssemblyState.Bounds b = entry.bounds();
                writer.i16(b.minX()).i16(b.minY()).i16(b.minZ()).i16(b.maxX()).i16(b.maxY()).i16(b.maxZ());
            }
            if (entry.bodies() != null) {
                writer.varUInt(entry.bodies().size());
                for (AssemblyState.Body body : entry.bodies()) {
                    writer.varUInt(body.node()).i32(body.x()).i32(body.y()).i32(body.z()).i32(body.rotation());
                }
            }
            if (entry.joints() != null) {
                writer.varUInt(entry.joints().count()).raw(entry.joints().packed());
            }
        }
        return writer.toByteArray();
    }

    /**
     * @param message message reçu, genre compris
     * @return le snapshot lu
     * @throws MalformedPacketException si le message est mal formé : séquence nulle, descripteur
     *     inconnu, trop d'entrées, drapeau inconnu, base hors de l'historique, entité en double, octets
     *     en trop ou en moins
     */
    public static SnapshotMessage decode(byte[] message) throws MalformedPacketException {
        WireReader reader = new WireReader(message);
        MessageKinds.expect(reader, MessageKinds.SNAPSHOT);
        int sequence = reader.varUInt();
        if (sequence < 1) {
            throw new MalformedPacketException("séquence de snapshot " + sequence);
        }
        long serverTick = reader.varULong();
        int descriptor = reader.u8();
        if (descriptor != DESCRIPTOR_VERSION) {
            throw new MalformedPacketException("descripteur d'états " + descriptor + ", " + DESCRIPTOR_VERSION
                    + " attendu");
        }
        int count = reader.varUInt();
        if (count > MAX_ENTRIES) {
            throw new MalformedPacketException("snapshot de " + count + " entrées, au-delà de " + MAX_ENTRIES);
        }
        List<Entry> entries = new ArrayList<>(Math.min(count, reader.remaining()));
        Set<Integer> handles = new HashSet<>();
        for (int index = 0; index < count; index++) {
            int handle = reader.varUInt();
            if (!handles.add(handle)) {
                throw new MalformedPacketException("entité " + handle + " en double dans le snapshot");
            }
            int flags = reader.u8();
            if ((flags & ~KNOWN_FLAGS) != 0) {
                throw new MalformedPacketException("drapeaux d'entrée inconnus : " + flags);
            }
            int base = 0;
            long x;
            long y;
            long z;
            if ((flags & FLAG_FULL) != 0) {
                x = reader.i64();
                y = reader.i64();
                z = reader.i64();
            } else {
                base = reader.varUInt();
                if (base < 1 || base >= sequence) {
                    throw new MalformedPacketException("base " + base + " d'un delta du snapshot " + sequence);
                }
                x = reader.i32();
                y = reader.i32();
                z = reader.i32();
            }
            int rotation = reader.i32();
            AssemblyState.Velocity velocity = (flags & FLAG_VELOCITY) == 0 ? null : new AssemblyState.Velocity(
                    reader.i16(), reader.i16(), reader.i16(), reader.i16(), reader.i16(), reader.i16());
            AssemblyState.Bounds bounds = (flags & FLAG_BOUNDS) == 0 ? null : new AssemblyState.Bounds(
                    reader.i16(), reader.i16(), reader.i16(), reader.i16(), reader.i16(), reader.i16());
            List<AssemblyState.Body> bodies = (flags & FLAG_BODIES) == 0 ? null : readBodies(reader, handle);
            AssemblyState.JointBits joints = (flags & FLAG_JOINTS) == 0 ? null : readJoints(reader, handle);
            entries.add(new Entry(handle, base, x, y, z, rotation, velocity, bounds, (flags & FLAG_SLEEPING) != 0,
                    bodies, joints));
        }
        reader.expectEnd();
        return new SnapshotMessage(sequence, serverTick, descriptor, entries);
    }

    /** Les corps d'une entrée : au plus {@link AssemblyState#MAX_BODIES}, chacun à un node distinct. */
    private static List<AssemblyState.Body> readBodies(WireReader reader, int handle) throws MalformedPacketException {
        int count = reader.varUInt();
        if (count > AssemblyState.MAX_BODIES) {
            throw new MalformedPacketException("entité " + handle + " : " + count + " corps secondaires, au-delà de "
                    + AssemblyState.MAX_BODIES);
        }
        List<AssemblyState.Body> bodies = new ArrayList<>(count);
        Set<Integer> nodes = new HashSet<>();
        for (int index = 0; index < count; index++) {
            int node = reader.varUInt();
            if (!nodes.add(node)) {
                throw new MalformedPacketException("entité " + handle + " : node " + node + " de deux corps");
            }
            bodies.add(new AssemblyState.Body(node, reader.i32(), reader.i32(), reader.i32(), reader.i32()));
        }
        return bodies;
    }

    /** Les bits des joints d'une entrée : au plus 16 Kio, chaque octet lu dans ce que le message couvre. */
    private static AssemblyState.JointBits readJoints(WireReader reader, int handle) throws MalformedPacketException {
        int count = reader.varUInt();
        if (count > AssemblyState.JointBits.MAX_JOINTS) {
            throw new MalformedPacketException("entité " + handle + " : " + count + " joints, au-delà de "
                    + AssemblyState.JointBits.MAX_JOINTS);
        }
        byte[] packed = new byte[AssemblyState.JointBits.packedLength(count)];
        for (int index = 0; index < packed.length; index++) {
            packed[index] = (byte) reader.u8();
        }
        try {
            return new AssemblyState.JointBits(count, packed);
        } catch (IllegalArgumentException padding) {
            throw new MalformedPacketException("entité " + handle + " : " + padding.getMessage());
        }
    }
}
