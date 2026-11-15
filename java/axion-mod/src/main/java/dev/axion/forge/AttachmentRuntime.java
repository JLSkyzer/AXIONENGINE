package dev.axion.forge;

import dev.axion.api.assembly.Assembly;
import dev.axion.api.attach.AttachResult;
import dev.axion.api.attach.AttachResult.Status;
import dev.axion.api.attach.Attachments;
import dev.axion.attach.AttachmentGraph;
import dev.axion.content.ContentRegistry;
import dev.axion.definition.SocketTable;
import dev.axion.definition.SocketTable.Spec;
import dev.axion.entity.AssemblyVariables;
import dev.axion.physics.AttachmentReport;
import dev.axion.physics.CollectResult;
import dev.axion.physics.PhysicsEvent;
import dev.axion.physics.PhysicsEventCodes;
import dev.axion.physics.SimCommandProvider;
import dev.axion.physics.SimCommandStream;
import dev.axion.seat.SeatPlacement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Les attaches du serveur (C-48, ADR-171 D3 à D5) : le graphe, les commandes au natif, et ce qui vit d'un tick à
 * l'autre — le treuil qui suit sa variable, l'aimant qui s'éteint, la prise de force, la rupture par désalignement.
 *
 * <p>Thread : autoritatif. Une instance par serveur ({@link #current()}), créée au démarrage, oubliée à l'arrêt ; les
 * attaches ne se sauvegardent pas encore (tranche 8d).
 */
public final class AttachmentRuntime implements SimCommandProvider, Consumer<CollectResult> {

    private static final Logger LOGGER = LoggerFactory.getLogger(AttachmentRuntime.class);

    /** Ticks de désalignement au-delà du seuil après lesquels une attache rompt (ADR-171 D1) : une seconde. */
    static final int MISALIGNED_TICKS = 20;

    /** Le cône d'une rotule dont aucun socket ne déclare le sien, en degrés. */
    static final double DEFAULT_CONE = 45.0;

    /** Les bornes d'un treuil qui n'en déclare pas, en blocs, et sa vitesse, en blocs par seconde. */
    static final double DEFAULT_WINCH_MIN = 0.5;
    static final double DEFAULT_WINCH_MAX = 30.0;
    static final double DEFAULT_WINCH_SPEED = 1.0;

    /** Qui écrit les variables de la prise de force (ADR-171 D5). */
    static final String POWER_WRITER = "axion:attach";

    private static volatile List<ContentRegistry.AttachmentListenerEntry> listeners = List.of();
    private static volatile AttachmentRuntime current;

    /** Une attache vivante. */
    private static final class Live {
        final int id;
        final AxionEntity a;
        final Spec aSocket;
        final AxionEntity b;
        final Spec bSocket;
        final BlockPos anchor;
        final String kind;
        final String owner;
        final double misalignment;
        final double winchMin;
        final double winchMax;
        double winchTarget = Double.NaN;
        boolean winchByApi;
        double winchCommand = Double.NaN;
        int misalignedTicks;
        AttachmentReport last;

        Live(int id, AxionEntity a, Spec aSocket, AxionEntity b, Spec bSocket, BlockPos anchor, String kind,
                String owner, double misalignment, double winchMin, double winchMax) {
            this.id = id;
            this.a = a;
            this.aSocket = aSocket;
            this.b = b;
            this.bSocket = bSocket;
            this.anchor = anchor;
            this.kind = kind;
            this.owner = owner;
            this.misalignment = misalignment;
            this.winchMin = winchMin;
            this.winchMax = winchMax;
        }
    }

    private final AttachmentGraph graph = new AttachmentGraph();
    private final Map<Integer, Live> live = new TreeMap<>();
    /**
     * Ce que le prochain flux de commandes portera, en données : la demande d'un geste ne touche pas la simulation, que
     * seul le lot du tick pilote (R-701).
     */
    private sealed interface Pending permits PendingAttach, PendingDetach, PendingLength {}

    /** Une attache à créer : les champs d'{@code ATTACH}. */
    private record PendingAttach(int id, int kind, int aIndex, int aNode, float[] aFrame, int bIndex, int bNode,
            float[] bFrame, double[] world, float maxForce, float maxTorque, float cone, float length,
            float minLength, float maxLength, float speed) implements Pending {}

    /** Une attache à défaire. */
    private record PendingDetach(int id) implements Pending {}

    /** La longueur cible d'un treuil. */
    private record PendingLength(int id, float target) implements Pending {}

    private final List<Pending> pending = new ArrayList<>();
    private final int maxChain;
    private final double snapDistance;
    private int nextId = 1;

    /**
     * @param maxChain {@code attachment.max_chain}
     * @param snapDistance {@code attachment.snap_distance}
     */
    public AttachmentRuntime(int maxChain, double snapDistance) {
        this.maxChain = maxChain;
        this.snapDistance = snapDistance;
    }

    /**
     * Installe l'instance du serveur, ou l'oublie avec {@code null}.
     *
     * @param runtime l'instance
     */
    public static void install(AttachmentRuntime runtime) {
        current = runtime;
    }

    /** {@return l'instance du serveur, ou {@code null} hors d'un serveur} */
    public static AttachmentRuntime current() {
        return current;
    }

    /**
     * Publie les écouteurs enregistrés par les mods (ADR-171 D4).
     *
     * @param registered les écouteurs, dans l'ordre
     */
    public static void publishListeners(List<ContentRegistry.AttachmentListenerEntry> registered) {
        listeners = List.copyOf(registered);
    }

    /** {@return la distance où l'interaction {@code attach} cherche l'autre socket, sans la sienne} */
    public double snapDistance() {
        return snapDistance;
    }

    // Poser.

    /**
     * Attache le socket {@code aName} de {@code a} au socket {@code bName} de {@code b}.
     *
     * @param a l'assembly d'où part l'attache
     * @param aName son socket
     * @param b l'autre
     * @param bName son socket
     * @param allowed les types permis, dans l'ordre canonique ; vide : tous
     * @param owner le mod qui la pose
     * @return le résultat
     */
    public AttachResult attach(AxionEntity a, String aName, AxionEntity b, String bName, Set<String> allowed,
            String owner) {
        AuthoritativeThread.require();
        Optional<Spec> aSocket = a.sockets().named(aName);
        Optional<Spec> bSocket = b.sockets().named(bName);
        if (aSocket.isEmpty() || bSocket.isEmpty()) {
            return refused(Status.UNKNOWN_SOCKET, "socket inconnu : " + (aSocket.isEmpty() ? aName : bName));
        }
        if (a == b || a.level() != b.level() || a.isRemoved() || b.isRemoved()) {
            return refused(Status.UNREACHABLE, "les deux assemblies ne sont pas deux, dans une même dimension");
        }
        Spec sa = aSocket.get();
        Spec sb = bSocket.get();
        if (!sa.mates(sb) || !sb.mates(sa)) {
            return refused(Status.INCOMPATIBLE, "les formes de « " + aName + " » et « " + bName + " » ne s'épousent pas");
        }
        String kind = null;
        for (String candidate : SocketTable.KINDS) {
            if (sa.accepts().contains(candidate) && sb.accepts().contains(candidate)
                    && (allowed.isEmpty() || allowed.contains(candidate))) {
                kind = candidate;
                break;
            }
        }
        if (kind == null) {
            return refused(Status.INCOMPATIBLE, "aucun type que « " + aName + " » et « " + bName + " » acceptent");
        }
        return place(a, sa, b, sb, null, kind, owner);
    }

    /**
     * Ancre le socket {@code aName} de {@code a} au bloc {@code pos}.
     *
     * @param a l'assembly
     * @param aName son socket
     * @param pos le bloc
     * @param allowed les types permis ; vide : tous
     * @param owner le mod qui l'ancre
     * @return le résultat
     */
    public AttachResult anchor(AxionEntity a, String aName, BlockPos pos, Set<String> allowed, String owner) {
        AuthoritativeThread.require();
        Optional<Spec> socket = a.sockets().named(aName);
        if (socket.isEmpty()) {
            return refused(Status.UNKNOWN_SOCKET, "socket inconnu : " + aName);
        }
        if (!socket.get().anchor()) {
            return refused(Status.NOT_ANCHORABLE, "le socket « " + aName + " » ne s'ancre pas au monde");
        }
        String kind = null;
        for (String candidate : SocketTable.KINDS) {
            if (socket.get().accepts().contains(candidate) && (allowed.isEmpty() || allowed.contains(candidate))) {
                kind = candidate;
                break;
            }
        }
        if (kind == null) {
            return refused(Status.INCOMPATIBLE, "aucun type permis que « " + aName + " » accepte");
        }
        return place(a, socket.get(), null, null, pos.immutable(), kind, owner);
    }

    private AttachResult place(AxionEntity a, Spec sa, AxionEntity b, Spec sb, BlockPos anchor, String kind,
            String owner) {
        if ("magnet".equals(kind) && !magnetOn(a, sa, b, sb)) {
            return refused(Status.MAGNET_OFF, "l'aimant est éteint : sa variable de commande est fausse");
        }
        int id = nextId;
        AttachmentGraph.Link link = new AttachmentGraph.Link(id, a.getUUID(), sa.name(),
                b == null ? null : b.getUUID(), sb == null ? null : sb.name());
        Optional<AttachmentGraph.Refusal> refusal = graph.check(link, maxChain);
        if (refusal.isPresent()) {
            return switch (refusal.get()) {
                case SOCKET_BUSY -> refused(Status.SOCKET_BUSY, "un des deux sockets est déjà attaché");
                case CYCLE -> refused(Status.CYCLE, "E-8030 : ces deux assemblies sont déjà reliées (R-690)");
                case CHAIN -> refused(Status.CHAIN, "la chaîne dépasserait " + maxChain + " attaches (R-691)");
                default -> refused(Status.UNREACHABLE, "attache impossible : " + refusal.get());
            };
        }
        float[] aFrame = a.socketFrame(sa.node());
        float[] bFrame = b == null ? new float[8] : b.socketFrame(sb.node());
        if (aFrame == null || bFrame == null) {
            return refused(Status.UNREACHABLE, "la definition d'une extrémité n'est pas encore compilée");
        }
        Assembly aView = ForgeAxionApi.view(a);
        Optional<Assembly> bView = b == null ? Optional.empty() : Optional.of(ForgeAxionApi.view(b));
        for (ContentRegistry.AttachmentListenerEntry entry : listeners) {
            try {
                if (!entry.listener().allow(aView, sa.name(), bView, sb == null ? null : sb.name(), kind)) {
                    return refused(Status.REFUSED, "refusée par " + entry.modId());
                }
            } catch (RuntimeException | LinkageError failure) {
                LOGGER.error("AXION : l'écouteur d'attaches de {} a levé ; l'attache n'est pas refusée",
                        entry.modId(), failure);
            }
        }
        nextId++;
        graph.add(link, maxChain);
        double misalignment = Math.min(orInfinity(sa.misalignment()), sb == null ? Double.POSITIVE_INFINITY
                : orInfinity(sb.misalignment()));
        double winchMin = first(sa.winchMin(), sb == null ? Double.NaN : sb.winchMin(), DEFAULT_WINCH_MIN);
        double winchMax = Math.max(winchMin, first(sa.winchMax(), sb == null ? Double.NaN : sb.winchMax(),
                DEFAULT_WINCH_MAX));
        Live attached = new Live(id, a, sa, b, sb, anchor, kind, owner, misalignment, winchMin, winchMax);
        live.put(id, attached);
        double[] world = anchor == null ? new double[3]
                : new double[] {anchor.getX() + 0.5, anchor.getY() + 0.5, anchor.getZ() + 0.5};
        double distance = currentDistance(a, sa, b, sb, world);
        double length = switch (kind) {
            case "rope" -> Math.max(0.1, first(sa.ropeLength(), sb == null ? Double.NaN : sb.ropeLength(),
                    distance));
            case "winch" -> Math.max(winchMin, Math.min(winchMax, distance));
            default -> 0.0;
        };
        float maxForce = (float) Math.min(sa.maxForce(), sb == null ? Double.POSITIVE_INFINITY : sb.maxForce());
        float maxTorque = (float) Math.min(sa.maxTorque(), sb == null ? Double.POSITIVE_INFINITY : sb.maxTorque());
        double cone = first(sa.cone(), sb == null ? Double.NaN : sb.cone(), DEFAULT_CONE);
        double speed = first(sa.winchSpeed(), sb == null ? Double.NaN : sb.winchSpeed(), DEFAULT_WINCH_SPEED);
        pending.add(new PendingAttach(id, SocketTable.KINDS.indexOf(kind), AssemblyRuntime.handleIndexOf(a),
                (int) aFrame[0], tail(aFrame), b == null ? -1 : AssemblyRuntime.handleIndexOf(b), (int) bFrame[0],
                tail(bFrame), world, maxForce, maxTorque, (float) Math.toRadians(cone), (float) length,
                (float) winchMin, (float) winchMax, (float) speed));
        for (ContentRegistry.AttachmentListenerEntry entry : listeners) {
            try {
                entry.listener().attached(aView, sa.name(), bView, sb == null ? null : sb.name(), kind);
            } catch (RuntimeException | LinkageError failure) {
                LOGGER.error("AXION : l'écouteur d'attaches de {} a levé", entry.modId(), failure);
            }
        }
        return AttachResult.ok();
    }

    // Défaire.

    /**
     * Défait l'attache d'un socket.
     *
     * @param a l'assembly
     * @param socket son socket
     * @param cause la cause, pour les écouteurs
     * @return vrai si une attache le tenait
     */
    public boolean detach(AxionEntity a, String socket, String cause) {
        AuthoritativeThread.require();
        Optional<AttachmentGraph.Link> link = graph.socketLink(a.getUUID(), socket);
        link.ifPresent(found -> drop(found.id(), cause, true));
        return link.isPresent();
    }

    /** Retire une attache du graphe ; {@code native} demande au natif de défaire son joint. */
    private void drop(int id, String cause, boolean nativeToo) {
        graph.remove(id);
        Live gone = live.remove(id);
        if (gone == null) {
            return;
        }
        if (nativeToo) {
            pending.add(new PendingDetach(id));
        }
        Assembly aView = ForgeAxionApi.view(gone.a);
        Optional<Assembly> bView = gone.b == null || gone.b.isRemoved() ? Optional.empty()
                : Optional.of(ForgeAxionApi.view(gone.b));
        for (ContentRegistry.AttachmentListenerEntry entry : listeners) {
            try {
                entry.listener().detached(aView, gone.aSocket.name(), bView,
                        gone.bSocket == null ? null : gone.bSocket.name(), cause);
            } catch (RuntimeException | LinkageError failure) {
                LOGGER.error("AXION : l'écouteur d'attaches de {} a levé", entry.modId(), failure);
            }
        }
    }

    // Lire et piloter.

    /**
     * {@return les attaches d'une assembly, dans l'ordre où elles ont été posées}
     *
     * @param assembly l'assembly
     */
    public List<Attachments.Attachment> list(AxionEntity assembly) {
        List<Attachments.Attachment> found = new ArrayList<>();
        for (AttachmentGraph.Link link : graph.linksOf(assembly.getUUID())) {
            Live attached = live.get(link.id());
            if (attached == null) {
                continue;
            }
            boolean fromA = attached.a == assembly;
            AxionEntity other = fromA ? attached.b : attached.a;
            Spec mine = fromA ? attached.aSocket : attached.bSocket;
            Spec theirs = fromA ? attached.bSocket : attached.aSocket;
            AttachmentReport report = attached.last;
            found.add(new Attachments.Attachment(mine.name(),
                    other == null ? Optional.empty() : Optional.of(other.getUUID()),
                    theirs == null ? null : theirs.name(),
                    attached.anchor == null ? Optional.empty()
                            : Optional.of(new int[] {attached.anchor.getX(), attached.anchor.getY(),
                                attached.anchor.getZ()}),
                    attached.kind, report == null ? 0.0 : report.length(), report == null ? 0.0 : report.distance(),
                    report == null ? 0.0 : report.force(), attached.owner));
        }
        return found;
    }

    /**
     * La longueur cible du treuil d'un socket, jusqu'à ce que sa variable de commande change.
     *
     * @param a l'assembly
     * @param socket son socket
     * @param length la longueur, en blocs
     * @return vrai si le socket tient un treuil
     */
    public boolean setWinchLength(AxionEntity a, String socket, double length) {
        AuthoritativeThread.require();
        Optional<AttachmentGraph.Link> link = graph.socketLink(a.getUUID(), socket);
        Live attached = link.map(found -> live.get(found.id())).orElse(null);
        if (attached == null || !"winch".equals(attached.kind) || !Double.isFinite(length)) {
            return false;
        }
        attached.winchTarget = Math.max(attached.winchMin, Math.min(attached.winchMax, length));
        attached.winchByApi = true;
        float target = (float) attached.winchTarget;
        pending.add(new PendingLength(attached.id, target));
        return true;
    }

    /** {@return l'ensemble d'une assembly : elle et celles qui lui sont attachées, de proche en proche (D7)} */
    public Set<UUID> ensemble(AxionEntity assembly) {
        return graph.ensemble(assembly.getUUID());
    }

    @Override
    public SimCommandStream commandsForTick(long tick) {
        SimCommandStream stream = new SimCommandStream();
        for (Live attached : List.copyOf(live.values())) {
            if (attached.a.isRemoved() || (attached.b != null
                    && (attached.b.isRemoved() || attached.b.level() != attached.a.level()))) {
                drop(attached.id, "removed", true);
                continue;
            }
            if ("magnet".equals(attached.kind)
                    && !magnetOn(attached.a, attached.aSocket, attached.b, attached.bSocket)) {
                drop(attached.id, "magnet_off", true);
                continue;
            }
            if ("winch".equals(attached.kind)) {
                steerWinch(attached);
            }
            if (attached.b != null) {
                transferPower(attached.a, attached.aSocket, attached.b);
                transferPower(attached.b, attached.bSocket, attached.a);
            }
        }
        for (Pending command : pending) {
            if (command instanceof PendingAttach at) {
                stream.attach(new SimCommandStream.AttachRequest(at.id(), at.kind(), at.aIndex(),
                        AssemblyRuntime.GENERATION, at.aNode(), at.aFrame(), Math.max(0, at.bIndex()),
                        at.bIndex() < 0 ? 0 : AssemblyRuntime.GENERATION, at.bNode(), at.bFrame(), at.world(),
                        at.maxForce(), at.maxTorque(), at.cone(), at.length(), at.minLength(), at.maxLength(),
                        at.speed()));
            } else if (command instanceof PendingDetach detach) {
                stream.detach(detach.id());
            } else if (command instanceof PendingLength length) {
                stream.setAttachmentLength(length.id(), length.target());
            }
        }
        pending.clear();
        return stream;
    }

    /**
     * Le treuil suit sa variable de commande (ADR-171 D5) : négative, il enroule jusqu'à sa plus courte longueur ;
     * positive, il déroule jusqu'à la plus longue ; nulle, il tient la longueur qu'il a. Un changement de la variable
     * reprend la main sur une longueur posée par l'API.
     */
    private void steerWinch(Live attached) {
        String variable = attached.aSocket.winchVariable() != null ? attached.aSocket.winchVariable()
                : attached.bSocket == null ? null : attached.bSocket.winchVariable();
        AxionEntity owner = attached.aSocket.winchVariable() != null ? attached.a : attached.b;
        double command = variable == null || owner == null ? Double.NaN : value(owner, variable);
        if (Double.isNaN(command) || command == attached.winchCommand) {
            return;
        }
        boolean first = Double.isNaN(attached.winchCommand);
        attached.winchCommand = command;
        if (first && command == 0.0) {
            return;
        }
        attached.winchByApi = false;
        double target = command < 0 ? attached.winchMin : command > 0 ? attached.winchMax
                : attached.last == null ? attached.winchTarget : attached.last.length();
        if (Double.isFinite(target)) {
            attached.winchTarget = target;
            float sent = (float) target;
            pending.add(new PendingLength(attached.id, sent));
        }
    }

    /** La prise de force (ADR-171 D5) : les variables de {@code from} recopiées dans celles de {@code to}. */
    private static void transferPower(AxionEntity from, Spec socket, AxionEntity to) {
        if (socket == null || socket.power().isEmpty()) {
            return;
        }
        AssemblyVariables source = from.variables();
        AssemblyVariables target = to.variables();
        if (source == null || target == null) {
            return;
        }
        for (Map.Entry<String, String> copy : socket.power().entrySet()) {
            int read = source.declared().indexOf(copy.getKey());
            int write = target.declared().indexOf(copy.getValue());
            if (read < 0 || write < 0 || source.get(read) == target.get(write)) {
                continue;
            }
            try {
                target.set(write, source.get(read), POWER_WRITER);
            } catch (IllegalArgumentException outOfBounds) {
                // La variable de l'autre côté ne prend pas cette valeur : la prise de force ne la force pas.
            }
        }
    }

    @Override
    public void accept(CollectResult result) {
        for (PhysicsEvent event : result.events()) {
            if (event.kind() == PhysicsEventCodes.Kind.DETACH_ATTACHMENT
                    && event.nodeA() == PhysicsEventCodes.Data.DETACH_REFUSED && live.containsKey(event.data())) {
                LOGGER.warn("AXION : le natif a refusé l'attache {} — une extrémité inconnue ou un réglage invalide",
                        event.data());
                drop(event.data(), "removed", false);
            }
        }
        for (AttachmentReport report : result.attachments()) {
            Live attached = live.get(report.id());
            if (attached == null) {
                continue;
            }
            attached.last = report;
            if (report.broken()) {
                drop(report.id(), "broken", false);
                continue;
            }
            // Le désalignement ne vaut que pour ce qui tient deux sockets ensemble : une corde les écarte par nature.
            boolean held = !"rope".equals(attached.kind) && !"winch".equals(attached.kind);
            if (held && report.distance() > attached.misalignment) {
                if (++attached.misalignedTicks >= MISALIGNED_TICKS) {
                    drop(report.id(), "misaligned", true);
                }
            } else {
                attached.misalignedTicks = 0;
            }
        }
    }

    // L'interaction (ADR-171 D4).

    /**
     * L'interaction {@code attach} d'un socket : le socket compatible et libre le plus proche d'une autre assembly,
     * dans {@code distance} ; à défaut, le bloc visé, si le socket s'ancre.
     *
     * @param a l'assembly
     * @param socketName son socket
     * @param allowed les types permis ; vide : tous
     * @param distance la distance de recherche, en blocs ; {@code NaN} : {@link #snapDistance()}
     * @param target le bloc que vise le joueur, ou {@code null}
     * @return le résultat
     */
    public AttachResult interact(AxionEntity a, String socketName, Set<String> allowed, double distance,
            BlockPos target) {
        Optional<Spec> socket = a.sockets().named(socketName);
        if (socket.isEmpty()) {
            return refused(Status.UNKNOWN_SOCKET, "socket inconnu : " + socketName);
        }
        SeatPlacement.Pose here = a.nodePose(socket.get().node());
        double reach = Double.isNaN(distance) ? snapDistance : distance;
        AxionEntity best = null;
        String bestSocket = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        if (here != null) {
            AABB around = new AABB(here.x() - reach - 8, here.y() - reach - 8, here.z() - reach - 8,
                    here.x() + reach + 8, here.y() + reach + 8, here.z() + reach + 8);
            for (AxionEntity other : a.level().getEntitiesOfClass(AxionEntity.class, around)) {
                if (other == a) {
                    continue;
                }
                for (Spec candidate : other.sockets().sockets()) {
                    if (graph.socketLink(other.getUUID(), candidate.name()).isPresent()) {
                        continue;
                    }
                    SeatPlacement.Pose there = other.nodePose(candidate.node());
                    if (there == null) {
                        continue;
                    }
                    double d = Math.sqrt(square(there.x() - here.x()) + square(there.y() - here.y())
                            + square(there.z() - here.z()));
                    if (d <= reach && d < bestDistance && compatible(socket.get(), candidate, allowed)) {
                        best = other;
                        bestSocket = candidate.name();
                        bestDistance = d;
                    }
                }
            }
        }
        if (best != null) {
            return attach(a, socketName, best, bestSocket, allowed, "axion");
        }
        if (target != null && socket.get().anchor()) {
            return anchor(a, socketName, target, allowed, "axion");
        }
        return refused(Status.INCOMPATIBLE, "aucun socket compatible et libre à " + reach + " blocs");
    }

    private static boolean compatible(Spec a, Spec b, Set<String> allowed) {
        if (!a.mates(b) || !b.mates(a)) {
            return false;
        }
        for (String kind : a.accepts()) {
            if (b.accepts().contains(kind) && (allowed.isEmpty() || allowed.contains(kind))) {
                return true;
            }
        }
        return false;
    }

    // Aides.

    private static boolean magnetOn(AxionEntity a, Spec sa, AxionEntity b, Spec sb) {
        if (sa.magnetVariable() != null) {
            return value(a, sa.magnetVariable()) > 0.5;
        }
        if (sb != null && sb.magnetVariable() != null && b != null) {
            return value(b, sb.magnetVariable()) > 0.5;
        }
        return true;
    }

    private static double value(AxionEntity assembly, String variable) {
        AssemblyVariables variables = assembly.variables();
        if (variables == null) {
            return Double.NaN;
        }
        int index = variables.declared().indexOf(variable);
        return index < 0 ? Double.NaN : variables.get(index);
    }

    private static double currentDistance(AxionEntity a, Spec sa, AxionEntity b, Spec sb, double[] world) {
        SeatPlacement.Pose here = a.nodePose(sa.node());
        if (here == null) {
            return 1.0;
        }
        double x;
        double y;
        double z;
        if (b == null) {
            x = world[0];
            y = world[1];
            z = world[2];
        } else {
            SeatPlacement.Pose there = b.nodePose(sb.node());
            if (there == null) {
                return 1.0;
            }
            x = there.x();
            y = there.y();
            z = there.z();
        }
        return Math.sqrt(square(x - here.x()) + square(y - here.y()) + square(z - here.z()));
    }

    private static float[] tail(float[] frame) {
        float[] rest = new float[7];
        System.arraycopy(frame, 1, rest, 0, 7);
        if (rest[3] == 0 && rest[4] == 0 && rest[5] == 0 && rest[6] == 0) {
            rest[6] = 1;
        }
        return rest;
    }

    private static double first(double preferred, double fallback, double last) {
        return !Double.isNaN(preferred) ? preferred : !Double.isNaN(fallback) ? fallback : last;
    }

    private static double orInfinity(double value) {
        return Double.isNaN(value) ? Double.POSITIVE_INFINITY : value;
    }

    private static double square(double value) {
        return value * value;
    }

    private static AttachResult refused(Status status, String message) {
        return new AttachResult(status, message);
    }

    /** Le graphe, pour les tests et les commandes. */
    AttachmentGraph graph() {
        return graph;
    }

    /** Oublie tout : le serveur s'arrête. */
    public void clear() {
        live.clear();
        pending.clear();
        for (AttachmentGraph.Link link : graph.all()) {
            graph.remove(link.id());
        }
        Optional.ofNullable(current).filter(installed -> installed == this).ifPresent(installed -> install(null));
    }
}
