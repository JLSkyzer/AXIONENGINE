package dev.axion.forge;

import dev.axion.api.assembly.Assembly;
import dev.axion.api.attach.AttachResult;
import dev.axion.api.attach.AttachResult.Status;
import dev.axion.api.attach.Attachments;
import dev.axion.attach.AttachmentGraph;
import dev.axion.attach.AttachmentRecord;
import dev.axion.attach.EnsembleHold;
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
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
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
        /** La longueur de la corde au dernier tick, gardée à la sauvegarde ; NaN avant le premier rapport. */
        double lastLength = Double.NaN;
        /** L'écart des ancrages et la tension au dernier tick, pour les joueurs (D7). */
        double lastDistance;
        double lastTension;
        /** La commande qui l'a posée : ses réglages partent aux joueurs (ADR-171 Q8). */
        PendingAttach command;

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
     * La durée de vie d'un ticket d'ensemble, en ticks (ADR-171 Q9) : renouvelé à chaque tick tant que l'ensemble est
     * tenu, il expire deux secondes après — tous ceux d'un ensemble au même tick, et ses chunks se déchargent ensemble.
     */
    private static final int ENSEMBLE_TICKET_TICKS = 40;

    /** Le ticket qui garde chargés les chunks d'un ensemble tenu (ADR-171 Q9, CAP-37). */
    private static final TicketType<ChunkPos> ENSEMBLE_TICKET =
            TicketType.create("axion_ensemble", Comparator.comparingLong(ChunkPos::toLong), ENSEMBLE_TICKET_TICKS);

    /**
     * Le rayon du ticket : 2, pour que le chunk du membre soit au niveau où ses entités tournent (31 = 33 − 2), comme
     * sous un joueur.
     */
    private static final int ENSEMBLE_TICKET_RADIUS = 2;

    /** Les chunks que des ensembles ont tenus au dernier tick, pour les essais. */
    private final List<ChunkPos> heldChunks = new ArrayList<>();
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

    /** La cause d'une attache dont une extrémité est déchargée : gardée, rétablie à son retour (D7). */
    static final String SUSPENDED = "suspended";

    /** Les assemblies dont une attache gardée attend d'être rétablie (D7, R-692), dans l'ordre d'arrivée. */
    private final java.util.Set<AxionEntity> restoring = new java.util.LinkedHashSet<>();

    /** Les attaches gardées qu'on ne peut plus rétablir, déjà dites au journal : une fois chacune. */
    private final java.util.Set<String> unrestorable = new java.util.HashSet<>();
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

    /** {@return les écouteurs publiés ; un essai les remet après avoir posé les siens} */
    static List<ContentRegistry.AttachmentListenerEntry> publishedListeners() {
        return listeners;
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
        return place(a, sa, b, sb, null, kind, owner, Double.NaN, false);
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
        return place(a, socket.get(), null, null, pos.immutable(), kind, owner, Double.NaN, false);
    }

    /**
     * Pose une attache. {@code restoring} : elle est rétablie depuis la sauvegarde (D7) — les écouteurs l'ont déjà jugée
     * et suivie, sa longueur gardée vaut, et l'assembly la tient déjà.
     */
    private AttachResult place(AxionEntity a, Spec sa, AxionEntity b, Spec sb, BlockPos anchor, String kind,
            String owner, double savedLength, boolean restoring) {
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
        String ownName = sa.name();
        String otherName = sb == null ? null : sb.name();
        String asked = kind;
        for (ContentRegistry.AttachmentListenerEntry entry : restoring ? List.<ContentRegistry.AttachmentListenerEntry>of()
                : listeners) {
            // ADR-174 D1 : un écouteur qui lève ne refuse rien, et trop de fautes de suite le désactivent (E-1012).
            if (!ContentListeners.call(entry.listener(), entry.modId(), "l'attache de " + ownName + " à juger",
                    () -> entry.listener().allow(aView, ownName, bView, otherName, asked), true)) {
                return refused(Status.REFUSED, "refusée par " + entry.modId());
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
            case "rope" -> Math.max(0.1, Double.isFinite(savedLength) ? savedLength
                    : first(sa.ropeLength(), sb == null ? Double.NaN : sb.ropeLength(), distance));
            case "winch" -> Math.max(winchMin, Math.min(winchMax, Double.isFinite(savedLength) ? savedLength
                    : distance));
            default -> 0.0;
        };
        if (!restoring) {
            a.attachmentRecords().add(new AttachmentRecord(sa.name(), b == null ? null : b.getUUID(),
                    sb == null ? null : sb.name(),
                    anchor == null ? null : new int[] {anchor.getX(), anchor.getY(), anchor.getZ()}, kind,
                    "rope".equals(kind) || "winch".equals(kind) ? length : Double.NaN, owner,
                    b == null ? 0 : b.definitionRevision()));
        }
        float maxForce = (float) Math.min(sa.maxForce(), sb == null ? Double.POSITIVE_INFINITY : sb.maxForce());
        float maxTorque = (float) Math.min(sa.maxTorque(), sb == null ? Double.POSITIVE_INFINITY : sb.maxTorque());
        double cone = first(sa.cone(), sb == null ? Double.NaN : sb.cone(), DEFAULT_CONE);
        double speed = first(sa.winchSpeed(), sb == null ? Double.NaN : sb.winchSpeed(), DEFAULT_WINCH_SPEED);
        PendingAttach command = new PendingAttach(id, SocketTable.KINDS.indexOf(kind),
                AssemblyRuntime.handleIndexOf(a), (int) aFrame[0], tail(aFrame),
                b == null ? -1 : AssemblyRuntime.handleIndexOf(b), (int) bFrame[0], tail(bFrame), world, maxForce,
                maxTorque, (float) Math.toRadians(cone), (float) length, (float) winchMin, (float) winchMax,
                (float) speed);
        attached.command = command;
        pending.add(command);
        for (ContentRegistry.AttachmentListenerEntry entry : restoring ? List.<ContentRegistry.AttachmentListenerEntry>of()
                : listeners) {
            ContentListeners.call(entry.listener(), entry.modId(), "l'attache de " + ownName + " posée", () -> {
                entry.listener().attached(aView, ownName, bView, otherName, asked);
                return null;
            }, null);
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

    /**
     * Retire une attache du graphe ; {@code native} demande au natif de défaire son joint. {@code suspended} : une
     * extrémité est déchargée (D7, R-692) — l'assembly d'où elle part la garde, et la rétablira ; les écouteurs n'en
     * savent rien. Sinon elle est oubliée, et les écouteurs l'apprennent avec sa cause.
     */
    private void drop(int id, String cause, boolean nativeToo) {
        graph.remove(id);
        Live gone = live.remove(id);
        if (gone == null) {
            return;
        }
        if (nativeToo) {
            pending.add(new PendingDetach(id));
        }
        if (SUSPENDED.equals(cause)) {
            if (!gone.a.isRemoved()) {
                restoring.add(gone.a);
            }
            return;
        }
        gone.a.attachmentRecords().removeIf(record -> record.socket().equals(gone.aSocket.name()));
        Assembly aView = ForgeAxionApi.view(gone.a);
        Optional<Assembly> bView = gone.b == null || gone.b.isRemoved() ? Optional.empty()
                : Optional.of(ForgeAxionApi.view(gone.b));
        for (ContentRegistry.AttachmentListenerEntry entry : listeners) {
            ContentListeners.call(entry.listener(), entry.modId(), "l'attache de " + gone.aSocket.name() + " défaite",
                    () -> {
                        entry.listener().detached(aView, gone.aSocket.name(), bView,
                                gone.bSocket == null ? null : gone.bSocket.name(), cause);
                        return null;
                    }, null);
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
            if (attached.a.isRemoved()) {
                drop(attached.id, unloaded(attached.a) ? SUSPENDED : "removed", true);
                continue;
            }
            if (attached.b != null && (attached.b.isRemoved() || attached.b.level() != attached.a.level())) {
                // Déchargée, ou partie dans une autre dimension : l'attache attend son retour.
                boolean destroyed = attached.b.isRemoved() && !unloaded(attached.b);
                drop(attached.id, destroyed ? "removed" : SUSPENDED, true);
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
        restore();
        holdEnsembles();
        // Une attache ne part au natif qu'une fois ses deux extrémités dotées d'un corps (ADR-171, 8e) : posée juste
        // après leur apparition, quand leur création native est encore étalée sous budget (R-1730), le natif la
        // refuserait comme visant une extrémité inconnue, et le refus l'oublierait. Elle attend en file, avec ce qui la
        // suit ; défaite avant de partir, elle ne part pas.
        List<Pending> held = new ArrayList<>();
        java.util.Set<Integer> heldIds = new java.util.HashSet<>();
        for (Pending command : pending) {
            if (command instanceof PendingAttach at) {
                Live attached = live.get(at.id());
                if (attached == null) {
                    continue;
                }
                if (!attached.a.simulated() || attached.b != null && !attached.b.simulated()) {
                    held.add(at);
                    heldIds.add(at.id());
                    continue;
                }
            } else if (command instanceof PendingDetach detach && heldIds.contains(detach.id())) {
                held.removeIf(waiting -> waiting instanceof PendingAttach at && at.id() == detach.id()
                        || waiting instanceof PendingLength length && length.id() == detach.id());
                heldIds.remove(detach.id());
                continue;
            } else if (command instanceof PendingLength length && heldIds.contains(length.id())) {
                held.add(length);
                continue;
            }
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
        pending.addAll(held);
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
            attached.lastLength = report.length();
            attached.lastDistance = report.distance();
            attached.lastTension = report.force();
            if (report.broken()) {
                // ADR-171 D7, grille : le journal dit chaque rupture, sa force et son seuil.
                LOGGER.warn("AXION : attache {} rompue — {} « {} » de {} → {} : {} N et {} N·m au dernier sous-pas,"
                        + " seuils {} N et {} N·m", report.id(), attached.kind, attached.aSocket.name(),
                        attached.a.definitionId(), describeOther(attached), Math.round(report.force()),
                        Math.round(report.torque()), threshold(attached, true), threshold(attached, false));
                drop(report.id(), "broken", false);
                continue;
            }
            // Le désalignement ne vaut que pour ce qui tient deux sockets ensemble : une corde les écarte par nature.
            boolean held = !"rope".equals(attached.kind) && !"winch".equals(attached.kind);
            if (held && report.distance() > attached.misalignment) {
                if (++attached.misalignedTicks >= MISALIGNED_TICKS) {
                    LOGGER.warn("AXION : attache {} désalignée — {} « {} » de {} : écart {} bloc(s) au-delà de {}"
                            + " pendant {} ticks", report.id(), attached.kind, attached.aSocket.name(),
                            attached.a.definitionId(), String.format(java.util.Locale.ROOT, "%.3f", report.distance()),
                            attached.misalignment, MISALIGNED_TICKS);
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

    /** L'autre extrémité d'une attache, pour le journal. */
    private static String describeOther(Live attached) {
        return attached.b == null ? "le bloc " + attached.anchor.toShortString()
                : "« " + attached.bSocket.name() + " » de " + attached.b.definitionId();
    }

    /** Le seuil de rupture d'une attache, en force ou en couple, pour le journal ; « aucun » à l'infini. */
    private static String threshold(Live attached, boolean force) {
        double a = force ? attached.aSocket.maxForce() : attached.aSocket.maxTorque();
        double b = attached.bSocket == null ? Double.POSITIVE_INFINITY
                : force ? attached.bSocket.maxForce() : attached.bSocket.maxTorque();
        double weakest = Math.min(a, b);
        return Double.isInfinite(weakest) ? "aucun" : String.valueOf(Math.round(weakest));
    }

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

    // La réplication (ADR-171 D7).

    /**
     * {@return les attaches vivantes qu'une assembly a posées, telles qu'elles partent aux joueurs qui la suivent :
     * rangées par socket, longueur et écart au millième de bloc, tension au newton}
     *
     * @param assembly l'assembly
     */
    public dev.axion.net.AssemblyState.Links netLinks(AxionEntity assembly) {
        List<dev.axion.net.AssemblyState.Link> links = new ArrayList<>();
        List<Spec> mine = assembly.sockets().sockets();
        for (AttachmentGraph.Link link : graph.linksOf(assembly.getUUID())) {
            Live attached = live.get(link.id());
            if (attached == null || attached.a != assembly) {
                continue;
            }
            int socket = mine.indexOf(attached.aSocket);
            int otherSocket = attached.b == null ? -1 : attached.b.sockets().sockets().indexOf(attached.bSocket);
            if (socket < 0 || (attached.b != null && otherSocket < 0)) {
                continue;
            }
            links.add(new dev.axion.net.AssemblyState.Link(socket, SocketTable.KINDS.indexOf(attached.kind),
                    attached.b == null ? -1 : attached.b.getId(), otherSocket,
                    attached.anchor == null ? 0 : attached.anchor.getX() + 0.5,
                    attached.anchor == null ? 0 : attached.anchor.getY() + 0.5,
                    attached.anchor == null ? 0 : attached.anchor.getZ() + 0.5,
                    milli(attached.lastLength), milli(attached.lastDistance),
                    (int) Math.min(Integer.MAX_VALUE, Math.max(0, Math.round(attached.lastTension))),
                    setting(attached)));
        }
        links.sort(java.util.Comparator.comparingInt(dev.axion.net.AssemblyState.Link::socket));
        return links.isEmpty() ? dev.axion.net.AssemblyState.Links.NONE : new dev.axion.net.AssemblyState.Links(links);
    }

    /** {@return les réglages d'une attache tels qu'ils partent aux joueurs : ceux de sa commande, longueur courante} */
    private static dev.axion.net.AssemblyState.Setting setting(Live attached) {
        PendingAttach command = attached.command;
        if (command == null) {
            return dev.axion.net.AssemblyState.Setting.NONE;
        }
        float length = Double.isFinite(attached.lastLength) ? (float) attached.lastLength : command.length();
        return new dev.axion.net.AssemblyState.Setting(attached.id, command.maxForce(), command.maxTorque(),
                command.cone(), length, command.minLength(), command.maxLength(), command.speed());
    }

    private static int milli(double value) {
        return Double.isFinite(value) ? (int) Math.min(Integer.MAX_VALUE, Math.max(0, Math.round(value * 1000)))
                : 0;
    }

    // La sauvegarde (ADR-171 D7, R-692).

    /**
     * Une assembly entre au monde avec des attaches gardées : elles se rétablissent dès que leurs deux extrémités sont
     * là, compilées ; d'ici là, suspendues.
     *
     * @param assembly l'assembly
     */
    public void restoreLater(AxionEntity assembly) {
        restoring.add(assembly);
    }

    /**
     * {@return les attaches qu'une assembly a posées et qui attendent d'être rétablies}
     *
     * @param assembly l'assembly
     */
    public int suspended(AxionEntity assembly) {
        int count = 0;
        for (AttachmentRecord record : assembly.attachmentRecords()) {
            if (graph.socketLink(assembly.getUUID(), record.socket()).isEmpty()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Garde dans les attaches d'une assembly la longueur de leur dernier tick, avant qu'elle se sauvegarde : un treuil
     * rétabli reprend où il était.
     *
     * @param assembly l'assembly
     */
    public void refreshLengths(AxionEntity assembly) {
        List<AttachmentRecord> records = assembly.attachmentRecords();
        for (int i = 0; i < records.size(); i++) {
            AttachmentRecord record = records.get(i);
            Optional<AttachmentGraph.Link> link = graph.socketLink(assembly.getUUID(), record.socket());
            Live attached = link.map(found -> live.get(found.id())).orElse(null);
            if (attached != null && Double.isFinite(attached.lastLength) && Double.isFinite(record.length())) {
                record = record.withLength(attached.lastLength);
                records.set(i, record);
            }
            // ADR-179 D1 : le nom du socket de l'autre se garde avec la révision où il vaut, tant qu'elle est là et que
            // l'attache tient un socket de la definition qu'elle lit.
            if (attached != null && attached.b != null && !attached.b.isRemoved()
                    && attached.b.sockets().sockets().indexOf(attached.bSocket) >= 0) {
                records.set(i, record.withOther(attached.bSocket.name(), attached.b.definitionRevision()));
            }
        }
    }

    /**
     * Garde chargés les chunks de chaque ensemble tenu (ADR-171 D7, Q9) : un ensemble dont un membre est à portée de
     * simulation d'un joueur renouvelle un ticket sur le chunk de chacun de ses membres. Une remorque ne reste pas seule
     * au bord d'un chunk ; plus tenu, l'ensemble laisse ses tickets expirer ensemble.
     */
    private void holdEnsembles() {
        AuthoritativeThread.require();
        heldChunks.clear();
        // Les attaches entre deux assemblies présentes du même niveau, dans l'ordre de leurs identifiants.
        List<Live> links = new ArrayList<>();
        for (Live attached : live.values()) {
            if (attached.b != null && !attached.a.isRemoved() && !attached.b.isRemoved()
                    && attached.a.level() == attached.b.level()) {
                links.add(attached);
            }
        }
        Map<AxionEntity, AxionEntity> parent = new IdentityHashMap<>();
        for (Live attached : links) {
            AxionEntity a = root(parent, attached.a);
            AxionEntity b = root(parent, attached.b);
            if (a != b) {
                parent.put(a, b);
            }
        }
        Map<AxionEntity, List<AxionEntity>> ensembles = new LinkedHashMap<>();
        for (Live attached : links) {
            for (AxionEntity member : List.of(attached.a, attached.b)) {
                List<AxionEntity> list = ensembles.computeIfAbsent(root(parent, member), k -> new ArrayList<>());
                if (!list.contains(member)) {
                    list.add(member);
                }
            }
        }
        for (List<AxionEntity> members : ensembles.values()) {
            if (members.size() < 2 || !(members.get(0).level() instanceof ServerLevel level)) {
                continue;
            }
            List<EnsembleHold.Chunk> chunks = new ArrayList<>(members.size());
            for (AxionEntity member : members) {
                ChunkPos pos = member.chunkPosition();
                chunks.add(new EnsembleHold.Chunk(pos.x, pos.z));
            }
            List<EnsembleHold.Chunk> players = new ArrayList<>();
            for (net.minecraft.server.level.ServerPlayer player : level.players()) {
                ChunkPos pos = player.chunkPosition();
                players.add(new EnsembleHold.Chunk(pos.x, pos.z));
            }
            int simulation = level.getServer().getPlayerList().getSimulationDistance();
            for (EnsembleHold.Chunk chunk : EnsembleHold.chunksToHold(chunks, players, simulation)) {
                ChunkPos pos = new ChunkPos(chunk.x(), chunk.z());
                level.getChunkSource().addRegionTicket(ENSEMBLE_TICKET, pos, ENSEMBLE_TICKET_RADIUS, pos);
                heldChunks.add(pos);
            }
        }
    }

    /** {@return la racine d'un membre dans la forêt des ensembles} */
    private static AxionEntity root(Map<AxionEntity, AxionEntity> parent, AxionEntity member) {
        AxionEntity current = member;
        AxionEntity next = parent.get(current);
        while (next != null) {
            current = next;
            next = parent.get(current);
        }
        return current;
    }

    /** {@return les chunks que des ensembles ont tenus au dernier tick (essais)} */
    public List<ChunkPos> heldChunks() {
        return List.copyOf(heldChunks);
    }

    /**
     * Le corps natif d'une assembly vient d'être retiré (fin de suivi, AssemblyRuntime) : ses attaches partent avec lui,
     * en silence (REMOVE_ASSEMBLY). Une assembly détruite ou déchargée est déjà traitée au tick ; une assembly
     * seulement mise de côté — son chunk n'est plus suivi, elle existe encore — n'était vue par rien, et ses attaches
     * restaient crues vivantes sans plus aucun joint (ADR-171, 8e). Elles sont suspendues, et se rétablissent quand le
     * natif l'a recréée.
     *
     * @param assembly l'assembly dont le corps est retiré
     */
    void bodyRemoved(AxionEntity assembly) {
        AuthoritativeThread.require();
        if (assembly.isRemoved()) {
            return;
        }
        for (Live attached : List.copyOf(live.values())) {
            if (attached.a == assembly || attached.b == assembly) {
                LOGGER.info("AXION : attache {} suspendue — {} n'est plus suivie, à {} ; elle se rétablira à son retour",
                        attached.id, assembly.definitionId(), assembly.blockPosition());
                drop(attached.id, SUSPENDED, false);
            }
        }
    }

    /** Rétablit les attaches gardées dont les deux extrémités sont là ; les autres attendent (D7). */
    private void restore() {
        for (AxionEntity assembly : List.copyOf(restoring)) {
            if (assembly.isRemoved()) {
                restoring.remove(assembly);
                continue;
            }
            boolean waiting = false;
            for (AttachmentRecord record : List.copyOf(assembly.attachmentRecords())) {
                if (graph.socketLink(assembly.getUUID(), record.socket()).isPresent()) {
                    continue;
                }
                Optional<Spec> socket = assembly.sockets().named(record.socket());
                // Ses corps natifs d'abord : avant eux, le natif refuserait l'attache d'une extrémité inconnue, et
                // un refus l'oublie (ADR-171, 8d).
                if (assembly.sockets().isEmpty() || !assembly.simulated()) {
                    waiting = true;
                    continue;
                }
                if (socket.isEmpty()) {
                    unrestorableOnce(assembly, record, "le socket « " + record.socket() + " » n'existe plus");
                    continue;
                }
                AttachResult result;
                if (record.anchored()) {
                    BlockPos pos = new BlockPos(record.anchor()[0], record.anchor()[1], record.anchor()[2]);
                    if (!assembly.level().isLoaded(pos)) {
                        waiting = true;
                        continue;
                    }
                    result = place(assembly, socket.get(), null, null, pos, record.kind(), record.owner(),
                            record.length(), true);
                } else {
                    net.minecraft.world.entity.Entity found =
                            ((net.minecraft.server.level.ServerLevel) assembly.level()).getEntity(record.other());
                    if (!(found instanceof AxionEntity other) || other.sockets().isEmpty() || !other.simulated()) {
                        waiting = true;
                        continue;
                    }
                    // ADR-179 D2 : le nom gardé suit les migrations de l'autre depuis la révision où il a été écrit.
                    Optional<String> wanted = record.otherRevision() > 0
                            ? other.revisions().follow("sockets", record.otherRevision(), record.otherSocket())
                            : Optional.of(record.otherSocket());
                    Optional<Spec> otherSocket = wanted.flatMap(other.sockets()::named);
                    if (otherSocket.isEmpty()) {
                        unrestorableOnce(assembly, record, "le socket « " + record.otherSocket()
                                + " » de l'autre assembly n'existe plus");
                        continue;
                    }
                    result = place(assembly, socket.get(), other, otherSocket.get(), null, record.kind(),
                            record.owner(), record.length(), true);
                    int rank = assembly.attachmentRecords().indexOf(record);
                    if (result.attached() && rank >= 0) {
                        assembly.attachmentRecords().set(rank,
                                record.withOther(otherSocket.get().name(), other.definitionRevision()));
                    }
                }
                if (!result.attached()) {
                    waiting = true;
                    if (result.status() == Status.CYCLE || result.status() == Status.CHAIN
                            || result.status() == Status.INCOMPATIBLE) {
                        unrestorableOnce(assembly, record, result.message());
                    }
                }
            }
            if (!waiting) {
                restoring.remove(assembly);
            }
        }
    }

    /** CAP-38 : une attache gardée qu'on ne peut plus rétablir reste gardée, nommée une fois au journal. */
    private void unrestorableOnce(AxionEntity assembly, AttachmentRecord record, String why) {
        if (unrestorable.add(assembly.getUUID() + "/" + record.socket())) {
            LOGGER.warn("AXION : l'attache gardée « {} » de {} reste suspendue — {}", record.socket(),
                    assembly.definitionId(), why);
        }
    }

    /** {@return vrai si l'entité a quitté le monde sans être détruite : chunk déchargé, joueur parti} */
    private static boolean unloaded(AxionEntity assembly) {
        net.minecraft.world.entity.Entity.RemovalReason reason = assembly.getRemovalReason();
        return reason != null && !reason.shouldDestroy();
    }

    /** Oublie tout : le serveur s'arrête. */
    public void clear() {
        live.clear();
        pending.clear();
        restoring.clear();
        for (AttachmentGraph.Link link : graph.all()) {
            graph.remove(link.id());
        }
        Optional.ofNullable(current).filter(installed -> installed == this).ifPresent(installed -> install(null));
    }
}
