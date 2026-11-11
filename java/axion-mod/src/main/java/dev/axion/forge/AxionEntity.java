package dev.axion.forge;

import com.mojang.logging.LogUtils;
import dev.axion.api.assembly.CustomData;
import dev.axion.asset.Anatomy;
import dev.axion.asset.DefinitionCompiler;
import dev.axion.definition.Definition;
import dev.axion.definition.Seating;
import dev.axion.seat.GearRange;
import dev.axion.seat.SeatEjection;
import dev.axion.seat.SeatExit;
import dev.axion.seat.SeatOccupancy;
import dev.axion.seat.SeatPlacement;
import dev.axion.seat.VehicleInput;
import dev.axion.world.DimensionId;
import dev.axion.entity.AssemblyAnimation;
import dev.axion.entity.AssemblyBinding;
import dev.axion.entity.CustomTable;
import dev.axion.net.AnimationLayers;
import dev.axion.net.AssemblyState;
import dev.axion.net.Interpolation;
import dev.axion.net.RenderClock;
import dev.axion.physics.JointState;
import dev.axion.physics.VehicleReport;
import dev.axion.render.ProceduralTracks;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.entity.IEntityAdditionalSpawnData;
import net.minecraftforge.network.NetworkHooks;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.slf4j.Logger;

/**
 * L'entité d'une assembly (C-50, ADR-010).
 *
 * <p>N'hérite ni de {@code LivingEntity} ni de {@code VehicleEntity} (R-700) :
 * santé, montée et dégâts d'une assembly sont ceux de sa definition, pas ceux
 * d'un mob ou d'un bateau. {@code tick()} n'exécute aucune physique (R-701) : la
 * simulation est pilotée par le noyau natif (C-40) et réappliquée en fin de tick
 * serveur ; côté client, {@code tick()} y pose ce que donne l'interpolation de 21.7
 * (ADR-150 D7). L'entité porte sa definition, se sauvegarde, et se voit en debug.
 *
 * <p>Minecraft porte sa visibilité — apparition, disparition, quel joueur la voit —, le canal d'AXION
 * son état (ADR-150 D2) : position, orientation, vitesses et emprise arrivent au client par
 * {@code S2C_Snapshot}. Le suivi de position de Minecraft est tu ({@code updateInterval}) et le client
 * ignore ce qu'il enverrait encore.
 */
public final class AxionEntity extends Entity implements IEntityAdditionalSpawnData {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Orientation autoritaire du corps, quaternion {@code (x, y, z, w)} (R-461), côté serveur : la
     * boucle C-40 la pose à chaque tick, le NBT la garde, et elle part au client par l'apparition puis
     * les snapshots.
     */
    private final Quaternionf bodyRotation = new Quaternionf();
    /** Les corps autres que le corps racine, à leur dernier état (serveur, ADR-151 D2). */
    private List<BodyPose> secondaryBodies = List.of();

    /**
     * Drapeaux de chaque joint de l'assembly, dans l'ordre de la definition, au dernier état que le
     * natif en a rapporté (serveur, ADR-151 D8).
     */
    private int[] jointFlags = new int[0];

    /**
     * Le désalignement que Java a commandé en dernier à chaque joint, en blocs (serveur, ADR-153 D2) :
     * en M4, la seule source d'un grippage, que le NBT garde.
     */
    private float[] jointMisalignment = new float[0];

    /** Compression publiée de chaque roue (serveur, ADR-159 D9), en fraction du débattement. */
    private float[] wheelCompression = new float[0];

    /** Vitesse de rotation de chaque roue (serveur, ADR-159 D9), en rad/s. */
    private float[] wheelSpin = new float[0];

    /** Braquage de chaque roue (serveur, ADR-159 D9), en radians. */
    private float[] wheelSteer = new float[0];

    /** Contact au sol de chaque roue (serveur, ADR-159 D9). */
    private boolean[] wheelGround = new boolean[0];

    /** L'angle de chaque roue, intégré au rendu (client, ADR-159 D9). */
    private final ProceduralTracks.Spin wheelVisualSpin = new ProceduralTracks.Spin();

    /**
     * Le motopropulseur au dernier rapport du natif (serveur, ADR-163 D1) : régime, charge, rapport, drapeaux et
     * position de la direction ; {@code null} tant que le natif n'en a rapporté aucun, l'assembly n'étant alors pas
     * un véhicule.
     */
    private VehicleReport vehicleReport;

    /** Vitesses monde du corps racine au dernier état, en m/s et rad/s, et son sommeil (serveur). */
    private final float[] linearVelocity = new float[3];

    private final float[] angularVelocity = new float[3];

    private boolean sleeping;

    /**
     * Vrai dès que le natif a rapporté un état depuis la lecture du NBT (serveur) : avant, l'état gardé
     * est celui que le NBT portait, et il est réécrit tel quel.
     */
    private boolean simulated;

    /**
     * Vrai pour une entité lue d'une sauvegarde, jusqu'à la création de son assembly (serveur) : elle
     * naît endormie (22.4). Une entité liée par {@link #bind} — {@code /axion spawn} — ne l'est pas.
     */
    private boolean loadedFromSave;

    /** L'état que son NBT portait, jusqu'à la création de son assembly ; {@code null} sans état lu. */
    private PersistedState savedState;

    /**
     * Les bits des joints du plus récent snapshot reçu, son état d'animation, et son tick serveur (client,
     * ADR-151 D8, ADR-155).
     */
    private AssemblyState.JointBits receivedJoints = AssemblyState.JointBits.NONE;

    private AnimationLayers receivedAnimation = AnimationLayers.NONE;

    private long receivedJointsTick = Long.MIN_VALUE;

    /**
     * L'animation de l'assembly (serveur, C-37) : ses clips sont ceux de l'asset, posés à la création de son
     * corps ; le descripteur d'états v3 porte son état aux clients (ADR-155).
     */
    private final AssemblyAnimation animation = new AssemblyAnimation(
            () -> getServer() == null ? 0L : getServer().getTickCount(), AuthoritativeThread::require);

    /**
     * État de l'assembly tel que les snapshots le portent, côté serveur : posé à chaque tick simulé,
     * {@code null} avant le premier (ADR-150 D6).
     */
    private AssemblyState netState;

    /** Clés {@code axion:*} du NBT, gardées pour être réécrites telles quelles. */
    private CompoundTag stored = new CompoundTag();

    /**
     * Données personnalisées des mods ({@code axion:custom}, ADR-153 D7) : lues avec le NBT, en lecture
     * seule tant que l'entité est inerte, réécrites dans {@link #stored} à la sauvegarde si elles ont changé.
     */
    private CustomTable custom = CustomTable.read(null, AxionEntities::maxCustomBytes, true);

    private String definitionId = "";
    private boolean inert = true;

    /**
     * Pourquoi l'assembly est inerte, tel que sa lecture l'a établi : dit à son entrée dans le
     * monde si elle l'est encore ({@link #onAddedToWorld}). Sans objet une fois liée.
     */
    private String inertReason = "créée sans definition";

    /**
     * L'interpolation de 21.7 côté client (ADR-150 D7), et l'horloge de rendu de la connexion qui la
     * date : posées au premier snapshot reçu ; {@code null} avant, et côté serveur.
     */
    private Interpolation interpolation;

    private RenderClock clock;

    /**
     * Orientation côté client : celle du tick précédent et celle du tick courant. Le rendu interpole
     * entre les deux tant qu'aucun snapshot n'est arrivé ({@link #renderPose}).
     */
    private final Quaternionf rotationO = new Quaternionf();

    private final Quaternionf rotation = new Quaternionf();

    /**
     * Emprise courante, relative à la position — celle qu'applique {@link #makeBoundingBox}. {@code NaN} :
     * aucune. Nuls pendant la construction d'{@code Entity}, dont le premier {@code setPos} appelle déjà
     * {@link #makeBoundingBox}.
     */
    private final Vector3f boundsMin = new Vector3f(Float.NaN);

    private final Vector3f boundsMax = new Vector3f(Float.NaN);

    /**
     * Construit une entité, comme Minecraft le fait au chargement.
     *
     * @param type {@code axion:assembly}
     * @param level monde
     */
    public AxionEntity(EntityType<? extends AxionEntity> type, Level level) {
        super(type, level);
    }

    /** Lie l'entité à une definition, au schéma NBT courant. */
    void bind(Definition definition) {
        stored = AssemblyTags.bound(stored, definition.hash());
        // Inerte jusqu'ici, rien n'a pu écrire dans la table : la relire suffit à la rendre inscriptible.
        custom = AssemblyTags.custom(stored, AxionEntities::maxCustomBytes, false);
        definitionId = definition.id();
        inert = false;
        loadedFromSave = false;
        savedState = null;
    }

    /**
     * {@return l'état que la sauvegarde de l'entité a gardé, pour la création de son assembly, ou vide
     * pour une entité qui n'en sort pas} Rendu une fois : une assembly recréée plus tard part de l'état
     * courant de l'entité (ADR-153 D3, D4).
     */
    Optional<PersistedState> takeSavedState() {
        PersistedState state = savedState;
        savedState = null;
        return Optional.ofNullable(state);
    }

    /** {@return vrai si l'entité sort d'une sauvegarde : son assembly naît endormie (22.4)} Rendu une fois. */
    boolean takeLoadedFromSave() {
        boolean loaded = loadedFromSave;
        loadedFromSave = false;
        return loaded;
    }

    /**
     * Pose l'état restauré des joints, à la création de l'assembly (serveur, ADR-153 D3) : rompus, et
     * le désalignement commandé de chacun.
     *
     * @param joints un état par joint, dans l'ordre de la definition
     */
    void restoreJoints(List<PersistedState.Joint> joints) {
        for (int i = 0; i < joints.size() && i < jointFlags.length; i++) {
            jointFlags[i] = joints.get(i).broken() ? JointState.BROKEN : 0;
            jointMisalignment[i] = joints.get(i).misalignment();
        }
    }

    /**
     * L'occupant de chaque siège (ADR-158 D3), sous la clé {@code s} : le client place lui-même ses
     * passagers à chaque tick, et doit savoir qui est assis où. Minecraft ne transporte que la liste des
     * passagers, dans leur ordre de montée.
     */
    private static final EntityDataAccessor<CompoundTag> SEATS =
            SynchedEntityData.defineId(AxionEntity.class, EntityDataSerializers.COMPOUND_TAG);

    /** Clé de la table des occupants dans {@link #SEATS}. */
    private static final String SEATS_KEY = "s";

    /**
     * Clé, dans les données persistantes d'un joueur, du nom du siège qu'il occupe (ADR-158 D3) : à son
     * retour, {@link SeatReturns} le lui rend.
     */
    static final String REMEMBERED_SEAT = "axion:seat";

    /** Clé, dans les mêmes données, de l'assembly où il est assis. */
    static final String REMEMBERED_ASSEMBLY = "axion:seat_assembly";

    /** Le siège qu'une montée en cours a choisi, le temps que Minecraft ajoute le passager ; -1 sans. */
    private int mountingSeat = SeatOccupancy.FREE;

    /**
     * Ticks avant de chercher de nouveau une sortie pour un joueur qui tient le sneak dans une assembly
     * sans place libre : la recherche complète ne se refait pas à chaque tick.
     */
    private static final int EXIT_RETRY_TICKS = 20;

    /**
     * Un passager qui s'en va : Minecraft le retire de l'assembly avant d'en demander la sortie, et son
     * siège serait déjà oublié.
     *
     * @param passenger identifiant d'entité
     * @param seat son siège, -1 sans
     * @param spot la place que la descente a déjà trouvée, {@code null} sans
     */
    private record Departure(int passenger, int seat, SeatExit.Spot spot) {}

    /** Un node perdu, en attente du tick suivant (R-712). */
    private record LostNode(int node, SeatEjection.Cause cause) {}

    /** Les dernières entrées valides d'un siège, et le tick de leur réception. */
    private record ReceivedInput(VehicleInput input, int tick) {}

    private static final int MILLIS_PER_TICK = 50;

    private final Map<Integer, ReceivedInput> receivedInputs = new HashMap<>();

    private Departure departure;

    /** Vrai pendant une éjection : elle n'est pas refusée. */
    private boolean ejecting;

    /** Tick où chaque joueur refusé pourra de nouveau chercher une sortie. */
    private final Map<Integer, Integer> exitRefusedUntil = new HashMap<>();

    private final List<LostNode> lostNodes = new ArrayList<>();

    private SeatEjection lastEjection;

    /** Ce qui juge une place à la place du monde, pour les tests ; {@code null} en jeu. */
    private SeatExit.FreeSpot exitProbe;

    /** Les corps secondaires à la pose du dernier tick, relatifs au corps racine (client) ; aucun avant. */
    private List<Interpolation.BodyPose> tickBodies = List.of();

    @Override
    protected void defineSynchedData() {
        // La definition voyage dans les données d'apparition, l'état du corps dans les snapshots d'AXION
        // (ADR-150 D2). Seuls les sièges passent par Minecraft : ils changent à la montée et à la descente,
        // et la position que le suivi de Minecraft renvoie alors est ignorée ({@link #lerpTo}).
        entityData.define(SEATS, new CompoundTag());
    }

    /** {@return les sièges et les interactions de la definition, ou {@code null} : inerte, ou pas encore compilée} */
    private Seating seating() {
        return inert ? null : AxionEntities.seating(definitionId).orElse(null);
    }

    /** {@return l'occupation des sièges, telle que la donnée synchronisée la porte} */
    private SeatOccupancy occupancy(Seating seating) {
        return SeatOccupancy.of(seating.seats().size(), entityData.get(SEATS).getIntArray(SEATS_KEY));
    }

    private void storeOccupancy(SeatOccupancy occupancy) {
        CompoundTag tag = new CompoundTag();
        tag.putIntArray(SEATS_KEY, occupancy.toArray());
        entityData.set(SEATS, tag);
    }

    /**
     * {@return le siège qu'occupe une entité dans cette assembly, -1 sans}
     *
     * @param passenger l'entité
     */
    public int seatOf(Entity passenger) {
        Seating seating = seating();
        return seating == null ? SeatOccupancy.FREE : occupancy(seating).seatOf(passenger.getId());
    }

    /**
     * {@return l'occupation de chaque siège, dans l'ordre de la definition : la source {@code seat.<n>.occupied}
     * (ADR-163 D1)} Un siège dont l'occupant est perdu est libre. Vide sans sièges.
     */
    public boolean[] seatsOccupied() {
        Seating seating = seating();
        if (seating == null) {
            return new boolean[0];
        }
        SeatOccupancy occupancy = occupancy(seating);
        boolean[] occupied = new boolean[seating.seats().size()];
        for (int seat = 0; seat < occupied.length; seat++) {
            int occupant = occupancy.occupant(seat);
            occupied[seat] = occupant != SeatOccupancy.FREE && occupant != SeatOccupancy.LOST;
        }
        return occupied;
    }

    /**
     * Le clic droit d'un joueur (ADR-158 D2) : sur une assembly qui déclare des sièges ou des
     * interactions, le serveur lance son propre rayon, de l'œil du joueur dans sa direction de regard
     * et sur sa portée d'entité, contre les seuls colliders de l'assembly ; le collider touché donne son
     * node, puis le premier ancêtre qui porte une interaction ou un siège. Le point que le client a visé
     * n'est pas lu (R-1620). Un siège visé assoit le joueur ; une interaction joue son clip, que les
     * snapshots portent aux clients ; une action sans système ne fait rien (ADR-158 D6). La main secondaire
     * ne fait rien : la principale a déjà répondu.
     */
    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        Seating seating = seating();
        if (seating == null || hand != InteractionHand.MAIN_HAND
                || (seating.seats().isEmpty() && seating.interactions().isEmpty())) {
            return InteractionResult.PASS;
        }
        if (level().isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        Seating.Target target = aim(player, seating);
        if (target == null) {
            return InteractionResult.PASS;
        }
        if (target.seat() >= 0) {
            return sit(player, target.seat()) ? InteractionResult.CONSUME : InteractionResult.PASS;
        }
        return target.interaction() >= 0 && play(seating.interactions().get(target.interaction()))
                ? InteractionResult.CONSUME : InteractionResult.PASS;
    }

    /** {@return vrai si l'interaction a joué son clip} Un clip refusé — R-200 — se journalise. */
    private boolean play(Seating.Interaction interaction) {
        if (interaction.action() != Seating.Action.TOGGLE_ANIMATION
                && interaction.action() != Seating.Action.PLAY_ANIMATION) {
            return false;
        }
        try {
            if (interaction.action() == Seating.Action.TOGGLE_ANIMATION) {
                animation.toggle(interaction.clip(), interaction.layer());
            } else {
                animation.setSpeed(interaction.layer(), 1f);
                animation.play(interaction.clip(), interaction.layer(), 1f, 0f);
            }
            return true;
        } catch (IllegalArgumentException refused) {
            LOGGER.warn("AXION : assembly {}, interaction refusée — {}", getUUID(), refused.getMessage());
            return false;
        }
    }

    /** {@return vrai si ce siège déclare au moins un contrôle} */
    public boolean seatTakesInput(int seat) {
        Seating seating = seating();
        return seating != null && seat >= 0 && seat < seating.seats().size() && seating.seats().get(seat).controls() != 0;
    }

    /**
     * Reçoit les entrées d'un passager (serveur, ADR-158 D4) : elles ne valent que s'il occupe ce siège, et
     * sont bornées avant d'être retenues (R-1110). La dernière reçue l'emporte.
     *
     * @param sender le passager qui les envoie
     * @param seat rang du siège annoncé
     * @param input les entrées, telles que reçues
     * @return la faute à journaliser, {@code null} sans ; une valeur non finie compte pour zéro, et se journalise
     */
    public String receiveInput(Entity sender, int seat, VehicleInput input) {
        Seating seating = seating();
        if (seating == null || seat < 0 || seat >= seating.seats().size()
                || sender.getVehicle() != this || seatOf(sender) != seat) {
            return "entrées pour un siège que le joueur n'occupe pas";
        }
        receivedInputs.put(seat, new ReceivedInput(input.sanitized(seating.seats().get(seat)), tickCount));
        return input.finite() ? null : "entrée non finie, comptée pour zéro (R-1110)";
    }

    /**
     * {@return les entrées d'un siège, à ce tick : les dernières reçues, ou aucune s'il est libre ou que
     * rien n'arrive depuis {@code net.extrapolation_max_ms}} Les véhicules les liront (C-33).
     *
     * @param seat rang du siège
     */
    public VehicleInput seatInput(int seat) {
        ReceivedInput received = receivedInputs.get(seat);
        if (received == null || (tickCount - received.tick()) * MILLIS_PER_TICK > AxionEntities.inputTimeoutMillis()) {
            return VehicleInput.NONE;
        }
        return received.input();
    }

    /**
     * Ce que le siège {@code driver} commande au véhicule (ADR-159 D8).
     *
     * @param input son entrée : nulle s'il est libre, muet ou que rien n'arrive depuis
     *     {@code net.extrapolation_max_ms}
     * @param present vrai s'il est occupé ; sans conducteur, le frein de stationnement est serré (Q3)
     */
    public record DriverInput(VehicleInput input, boolean present) {

        /** Siège libre : le frein de stationnement serré, ce que le natif suppose d'un véhicule neuf. */
        public static final DriverInput ABSENT = new DriverInput(VehicleInput.NONE, false);
    }

    /**
     * {@return l'entrée du siège {@code driver}, ou {@code null} si la definition n'en déclare pas} Seul ce siège
     * pilote ; les contrôles d'un passager restent lisibles par {@link #seatInput}, sans effet sur le véhicule (D8).
     */
    public DriverInput driverInput() {
        Seating seating = seating();
        if (seating == null) {
            return null;
        }
        for (int seat = 0; seat < seating.seats().size(); seat++) {
            if (seating.seats().get(seat).driver()) {
                int occupant = occupancy(seating).occupant(seat);
                boolean present = occupant != SeatOccupancy.FREE && occupant != SeatOccupancy.LOST;
                return present ? new DriverInput(seatInput(seat), true) : DriverInput.ABSENT;
            }
        }
        return null;
    }

    /** {@return les rangs de rapport que la boîte de ce véhicule accepte de son conducteur (ADR-159 D6)} */
    public GearRange gearRange() {
        return AxionEntities.definitions().get(definitionId).map(definition -> GearRange.of(definition.root()))
                .orElse(GearRange.NONE);
    }

    /** {@return ce que vise le joueur, d'après le rayon du serveur, ou {@code null}} */
    private Seating.Target aim(Player player, Seating seating) {
        DefinitionCompiler.Summary summary = AxionEntities.summary(definitionId).orElse(null);
        if (summary == null) {
            return null;
        }
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getViewVector(1.0f);
        double[] ray = {eye.x, eye.y, eye.z, look.x, look.y, look.z, player.getEntityReach()};
        double[] hit = new double[dev.axion.bridge.NativeBridge.RAY_OUTPUT];
        int code = AxionEntities.rays().cast(AssemblyRuntime.handleIndexOf(this), AssemblyRuntime.GENERATION, ray, hit);
        if (code != 0 || hit[0] < 0) {
            return null;
        }
        int node = summary.anatomy().carrier((int) hit[0], (int) hit[1]);
        return seating.target(node, summary.anatomy());
    }

    /**
     * Assoit une entité dans un siège libre (serveur) ; déjà passagère de cette assembly, elle en change.
     *
     * @param passenger l'entité
     * @param seat rang du siège
     * @return vrai si elle y est assise
     */
    boolean sit(Entity passenger, int seat) {
        Seating seating = seating();
        if (seating == null || seat < 0 || seat >= seating.seats().size()) {
            return false;
        }
        SeatOccupancy occupancy = occupancy(seating);
        if (passenger.getVehicle() == this) {
            if (!occupancy.sit(passenger.getId(), seat)) {
                return false;
            }
            storeOccupancy(occupancy);
            remember(passenger, seating.seats().get(seat));
            return true;
        }
        if (occupancy.occupant(seat) != SeatOccupancy.FREE) {
            return false;
        }
        mountingSeat = seat;
        try {
            return passenger.startRiding(this);
        } finally {
            mountingSeat = SeatOccupancy.FREE;
        }
    }

    private void remember(Entity passenger, Seating.Seat seat) {
        if (passenger instanceof Player player) {
            player.getPersistentData().putString(REMEMBERED_SEAT, seat.name());
            player.getPersistentData().putUUID(REMEMBERED_ASSEMBLY, getUUID());
        }
    }

    /**
     * Oublie le siège d'un joueur. À sa déconnexion, Minecraft sauvegarde le joueur avant de le faire
     * descendre (bytecode de {@code PlayerList.remove} lu) : le fichier garde son siège, sa descente ne
     * l'efface que de la mémoire.
     *
     * @param passenger le joueur
     */
    static void forgetSeat(Entity passenger) {
        if (passenger instanceof Player player) {
            player.getPersistentData().remove(REMEMBERED_SEAT);
            player.getPersistentData().remove(REMEMBERED_ASSEMBLY);
        }
    }

    /**
     * Minecraft emporte avec lui le véhicule d'un joueur qui se déconnecte, s'il en est le seul passager
     * joueur, et le sauve dans le fichier du joueur. Pas une assembly : elle reste au monde, visible des
     * autres, et le joueur retrouve son siège à son retour (décision de Killian du 2026-11-09).
     */
    @Override
    public boolean hasExactlyOnePlayerPassenger() {
        return false;
    }

    /** {@return vrai si les sièges de la definition sont connus : elle est compilée (serveur)} */
    boolean seatingKnown() {
        return seating() != null;
    }

    /**
     * Rassoit un passager de force, sans le délai de Minecraft entre deux montées : le siège de ce nom s'il
     * est libre, sinon le premier libre.
     *
     * @param passenger le passager
     * @param seatName nom du siège
     * @return vrai s'il est assis
     */
    boolean reseat(Entity passenger, String seatName) {
        Seating seating = seating();
        if (seating == null) {
            return false;
        }
        int seat = SeatPlacement.seatFor(seating, occupancy(seating), seatName);
        if (seat == SeatOccupancy.FREE) {
            return false;
        }
        mountingSeat = seat;
        try {
            return passenger.startRiding(this, true) && seatOf(passenger) == seat;
        } finally {
            mountingSeat = SeatOccupancy.FREE;
        }
    }

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        Seating seating = seating();
        return seating != null && occupancy(seating).firstFree() != SeatOccupancy.FREE;
    }

    /**
     * Un passager arrive (ADR-158 D3) : au siège que la montée a choisi ; sinon — une commande assoit une
     * entité — au premier libre. Sans siège libre, il est descendu au tick suivant (une remontée forcée ne
     * demande pas {@link #canAddPassenger}).
     */
    @Override
    protected void addPassenger(Entity passenger) {
        super.addPassenger(passenger);
        Seating seating = seating();
        if (level().isClientSide() || seating == null) {
            return;
        }
        SeatOccupancy occupancy = occupancy(seating);
        int seat = mountingSeat != SeatOccupancy.FREE && occupancy.occupant(mountingSeat) == SeatOccupancy.FREE
                ? mountingSeat
                : occupancy.firstFree();
        if (seat != SeatOccupancy.FREE && occupancy.sit(passenger.getId(), seat)) {
            storeOccupancy(occupancy);
            remember(passenger, seating.seats().get(seat));
        }
    }

    /**
     * Chaque tick, au serveur : un passager sans siège — arrivé avant que la definition ne soit compilée,
     * ou remonté de force dans une assembly pleine — reçoit le premier libre ; sans siège libre, il
     * descend. Un occupant qui n'est plus passager libère le sien.
     */
    private void settlePassengers() {
        Seating seating = seating();
        if (seating == null) {
            return;
        }
        applyLostNodes(seating);
        SeatOccupancy occupancy = occupancy(seating);
        boolean changed = false;
        for (int seat = 0; seat < occupancy.seats(); seat++) {
            int occupant = occupancy.occupant(seat);
            if (occupant != SeatOccupancy.FREE && occupant != SeatOccupancy.LOST
                    && getPassengers().stream().noneMatch(passenger -> passenger.getId() == occupant)) {
                occupancy.release(occupant);
                changed = true;
            }
        }
        List<Entity> unseated = new ArrayList<>();
        for (Entity passenger : getPassengers()) {
            if (occupancy.seatOf(passenger.getId()) != SeatOccupancy.FREE) {
                continue;
            }
            int seat = occupancy.firstFree();
            if (seat != SeatOccupancy.FREE && occupancy.sit(passenger.getId(), seat)) {
                remember(passenger, seating.seats().get(seat));
                changed = true;
            } else {
                unseated.add(passenger);
            }
        }
        if (changed) {
            storeOccupancy(occupancy);
        }
        for (Entity passenger : unseated) {
            eject(passenger, SeatEjection.Cause.NO_SEAT);
        }
    }

    /**
     * Un node de l'assembly est perdu (serveur, R-712) : au tick suivant, tout siège qu'il porte, lui ou un
     * descendant, éjecte son occupant et ne reçoit plus personne. La chaîne de dommage (M6) et les
     * détachements l'appelleront ; rien d'autre ne perd de node aujourd'hui.
     *
     * @param node index du node
     * @param cause {@code DETACHED} ou {@code DESTROYED}
     */
    void loseNode(int node, SeatEjection.Cause cause) {
        lostNodes.add(new LostNode(node, cause));
    }

    private void applyLostNodes(Seating seating) {
        if (lostNodes.isEmpty()) {
            return;
        }
        DefinitionCompiler.Summary summary = AxionEntities.summary(definitionId).orElse(null);
        if (summary == null) {
            return;
        }
        Anatomy anatomy = summary.anatomy();
        for (LostNode lost : List.copyOf(lostNodes)) {
            for (int seat = 0; seat < seating.seats().size(); seat++) {
                if (!carriedBy(anatomy, seating.seats().get(seat).node(), lost.node())) {
                    continue;
                }
                int occupant = occupancy(seating).occupant(seat);
                for (Entity passenger : getPassengers()) {
                    if (passenger.getId() == occupant) {
                        eject(passenger, lost.cause());
                    }
                }
                SeatOccupancy occupancy = occupancy(seating);
                occupancy.lose(seat);
                storeOccupancy(occupancy);
            }
        }
        lostNodes.clear();
    }

    /** {@return vrai si {@code node} est {@code carrier} ou l'un de ses descendants} */
    private static boolean carriedBy(Anatomy anatomy, int node, int carrier) {
        int current = node;
        for (int steps = 0; current >= 0 && steps <= anatomy.size(); steps++) {
            if (current == carrier) {
                return true;
            }
            current = anatomy.parent(current);
        }
        return false;
    }

    /** {@return la dernière éjection de cette assembly, {@code null} sans (serveur)} */
    SeatEjection lastEjection() {
        return lastEjection;
    }

    /**
     * Fait descendre un passager sans lui laisser le refus (R-712), à la place qu'{@link SeatExit#eject}
     * trouve.
     *
     * @param passenger le passager
     * @param cause ce qui l'éjecte
     */
    private void eject(Entity passenger, SeatEjection.Cause cause) {
        int seat = seatOf(passenger);
        Seating seating = seating();
        String name = seating != null && seat >= 0 && seat < seating.seats().size()
                ? seating.seats().get(seat).name() : null;
        ejecting = true;
        try {
            passenger.stopRiding();
        } finally {
            ejecting = false;
        }
        // Minecraft ne demande une sortie que pour une entité vivante ; les autres restent à poser.
        if (!(passenger instanceof LivingEntity) && departure != null && departure.passenger() == passenger.getId()) {
            SeatExit.Spot spot = exitSpot(passenger, departure.seat(), true);
            passenger.setPos(spot.x(), spot.y(), spot.z());
            departure = null;
        }
        lastEjection = new SeatEjection(name, passenger.getId(), cause);
        LOGGER.info("AXION : assembly {}, passager {} éjecté du siège {} ({})", getUUID(), passenger.getId(), name, cause);
    }

    /**
     * Un passager descend (serveur, {@code EntityMountEvent}). Une descente au sneak se refuse s'il n'y a
     * aucune place libre (R-711) : le joueur reste assis, et le serveur le journalise. Tout le reste — une
     * éjection, la mort, une téléportation, une commande — passe, la sortie étant alors celle d'une
     * éjection ({@link #getDismountLocationForPassenger}).
     *
     * @param passenger le passager
     * @return faux si la descente est refusée
     */
    boolean allowsDismount(Entity passenger) {
        if (level().isClientSide() || ejecting || isRemoved()
                || !(passenger instanceof ServerPlayer player) || !player.isShiftKeyDown() || player.hasDisconnected()) {
            return true;
        }
        int id = passenger.getId();
        Integer until = exitRefusedUntil.get(id);
        // Minecraft saute la pose du passager au tick d'une descente : un refus la refait.
        if (until != null && tickCount < until) {
            positionRider(passenger);
            return false;
        }
        int seat = seatOf(passenger);
        SeatExit.Spot spot = exitSpot(passenger, seat, false);
        if (spot == null) {
            exitRefusedUntil.put(id, tickCount + EXIT_RETRY_TICKS);
            LOGGER.info("AXION : assembly {}, aucune place libre pour faire descendre {} (R-711)", getUUID(), player.getName().getString());
            positionRider(passenger);
            return false;
        }
        exitRefusedUntil.remove(id);
        departure = new Departure(id, seat, spot);
        return true;
    }

    /**
     * La sortie d'un passager (ADR-158 D5) : celle que sa descente a trouvée ; sinon — éjection, mort,
     * commande — celle d'une éjection, qui ne se refuse pas.
     */
    @Override
    public Vec3 getDismountLocationForPassenger(LivingEntity passenger) {
        Departure leaving = departure != null && departure.passenger() == passenger.getId() ? departure : null;
        departure = null;
        SeatExit.Spot spot = leaving != null && leaving.spot() != null
                ? leaving.spot()
                : exitSpot(passenger, leaving == null ? SeatOccupancy.FREE : leaving.seat(), true);
        return new Vec3(spot.x(), spot.y(), spot.z());
    }

    /**
     * {@return la place où poser un passager, {@code null} si sa descente est à refuser} Sans siège connu,
     * la recherche part de lui, sans position de sortie.
     *
     * @param passenger le passager
     * @param seat son siège, -1 sans
     * @param forced vrai pour une éjection, qui trouve toujours une place
     */
    private SeatExit.Spot exitSpot(Entity passenger, int seat, boolean forced) {
        Seating seating = seating();
        SeatPlacement.Pose pose = seatPose(seat);
        if (pose == null) {
            pose = new SeatPlacement.Pose(passenger.getX(), passenger.getY(), passenger.getZ(), new float[] {0f, 0f, 0f, 1f});
        }
        List<float[]> exits = seating != null && seat >= 0 && seat < seating.seats().size()
                ? seating.seats().get(seat).exits() : List.of();
        AABB bounds = getBoundingBox();
        int radius = SeatExit.radius(pose.x(), pose.z(), bounds.minX, bounds.minZ, bounds.maxX, bounds.maxZ);
        SeatExit.FreeSpot free = exitProbe != null ? exitProbe : (x, y, z) -> exitFree(passenger, x, y, z);
        return forced
                ? SeatExit.eject(pose, exits, radius, level().getMaxBuildHeight(), free)
                : SeatExit.descend(pose, exits, radius, free);
    }

    /**
     * {@return vrai si le passager, debout, tient à cette place : son tronçon est chargé, sa boîte ne
     * touche ni bloc solide, ni bloc qui brûle, ni collider d'assembly (ADR-158 D5)} Effleurer ne compte
     * pas : une assembly au repos s'enfonce d'un rien dans le sol, et une sortie à sa hauteur avec elle.
     */
    private boolean exitFree(Entity passenger, double x, double y, double z) {
        if (!level().isLoaded(BlockPos.containing(x, y, z))) {
            return false;
        }
        AABB core = passenger.getDimensions(Pose.STANDING).makeBoundingBox(x, y, z).deflate(1.0e-3);
        if (!level().noCollision(passenger, core)
                || level().getBlockStatesIfLoaded(core).anyMatch(WalkNodeEvaluator::isBurningBlock)) {
            return false;
        }
        return !AxionEntities.boxes().touch(DimensionId.of(level().dimension().location().toString()),
                new double[] {core.minX, core.minY, core.minZ, core.maxX, core.maxY, core.maxZ});
    }

    /**
     * Remplace le jugement du monde, pour un test.
     *
     * @param probe ce qui juge une place, {@code null} pour le monde
     */
    void exitProbe(SeatExit.FreeSpot probe) {
        exitProbe = probe;
    }

    @Override
    protected void removePassenger(Entity passenger) {
        super.removePassenger(passenger);
        Seating seating = seating();
        if (level().isClientSide() || seating == null) {
            return;
        }
        SeatOccupancy occupancy = occupancy(seating);
        int seat = occupancy.release(passenger.getId());
        if (seat != SeatOccupancy.FREE) {
            storeOccupancy(occupancy);
            receivedInputs.remove(seat);
        }
        exitRefusedUntil.remove(passenger.getId());
        forgetSeat(passenger);
        if (departure == null || departure.passenger() != passenger.getId()) {
            departure = new Departure(passenger.getId(), seat, null);
        }
    }

    /**
     * Pose un passager à son siège, à chaque tick, au serveur comme au client (ADR-158 D3) : la pose
     * monde du node {@code SEAT}, celle de son corps composée avec la sienne dans ce corps. Le node
     * marque l'assise : le passager y est posé, son décalage de monture compris. Sans siège connu, ou
     * avant que la definition ne soit compilée, Minecraft le pose comme sur toute entité.
     */
    @Override
    protected void positionRider(Entity passenger, Entity.MoveFunction move) {
        SeatPlacement.Pose pose = seatPose(seatOf(passenger));
        if (pose == null) {
            super.positionRider(passenger, move);
            return;
        }
        move.accept(passenger, pose.x(), pose.y() + passenger.getMyRidingOffset(), pose.z());
    }

    /**
     * {@return la pose monde d'un siège, ou {@code null} : siège inconnu, definition pas encore
     * compilée, ou corps dont la pose n'est pas connue}
     *
     * @param seat rang du siège
     */
    public SeatPlacement.Pose seatPose(int seat) {
        Seating seating = seating();
        DefinitionCompiler.Summary summary = AxionEntities.summary(definitionId).orElse(null);
        if (seating == null || summary == null || seat < 0 || seat >= seating.seats().size()) {
            return null;
        }
        return SeatPlacement.place(summary.anatomy(), seating.seats().get(seat).node(), bodyPoses(summary));
    }

    /**
     * {@return la pose monde d'un siège à une image (client), ou {@code null}} Celle que donne la pose dont
     * l'assembly est dessinée à cette image, et non celle du dernier tick : Minecraft dessine une entité entre
     * ses deux derniers ticks, l'assembly l'est à l'heure de l'image, et un passager posé au tick aurait un tick
     * de retard sur son siège.
     *
     * @param seat rang du siège
     * @param shown la pose dessinée à cette image ({@link #renderPose})
     */
    public SeatPlacement.Pose seatPoseAt(int seat, RenderPose shown) {
        Seating seating = seating();
        DefinitionCompiler.Summary summary = AxionEntities.summary(definitionId).orElse(null);
        if (seating == null || summary == null || seat < 0 || seat >= seating.seats().size()) {
            return null;
        }
        return SeatPlacement.place(summary.anatomy(), seating.seats().get(seat).node(),
                relativePoses(summary, shown.x(), shown.y(), shown.z(), shown.rotation(), shown.bodies()));
    }

    /**
     * {@return la pose monde de chaque corps, dans l'ordre de l'assembly ; {@code null} pour un corps dont
     * la pose n'est pas connue} Au serveur, les états du dernier tick ; au client, la pose que le tick a
     * prise de l'interpolation.
     */
    private List<SeatPlacement.BodyPose> bodyPoses(DefinitionCompiler.Summary summary) {
        if (level().isClientSide()) {
            return relativePoses(summary, getX(), getY(), getZ(), rotation, tickBodies);
        }
        Quaternionf q = bodyRotation;
        SeatPlacement.BodyPose root = new SeatPlacement.BodyPose(getX(), getY(), getZ(), new float[] {q.x, q.y, q.z, q.w});
        int[] roots = summary.roots();
        List<SeatPlacement.BodyPose> poses = new ArrayList<>(roots.length);
        poses.add(root);
        for (int rank = 1; rank < roots.length; rank++) {
            poses.add(worldBody(roots[rank]));
        }
        return poses;
    }

    /**
     * {@return la pose monde de chaque corps, au client : le corps racine à la pose donnée, les autres
     * composés avec leur pose relative de l'interpolation ; {@code null} pour un corps qui n'y figure pas}
     */
    private static List<SeatPlacement.BodyPose> relativePoses(DefinitionCompiler.Summary summary, double x, double y,
            double z, Quaternionf q, List<Interpolation.BodyPose> bodies) {
        SeatPlacement.BodyPose root = new SeatPlacement.BodyPose(x, y, z, new float[] {q.x, q.y, q.z, q.w});
        int[] roots = summary.roots();
        List<SeatPlacement.BodyPose> poses = new ArrayList<>(roots.length);
        poses.add(root);
        for (int rank = 1; rank < roots.length; rank++) {
            poses.add(relativeBody(root, roots[rank], bodies));
        }
        return poses;
    }

    private SeatPlacement.BodyPose worldBody(int node) {
        BodyPose body = secondaryBody(node);
        if (body == null) {
            return null;
        }
        Quaternionf r = body.rotation();
        return new SeatPlacement.BodyPose(body.x(), body.y(), body.z(), new float[] {r.x, r.y, r.z, r.w});
    }

    private static SeatPlacement.BodyPose relativeBody(SeatPlacement.BodyPose root, int node,
            List<Interpolation.BodyPose> bodies) {
        for (Interpolation.BodyPose body : bodies) {
            if (body.node() == node) {
                return SeatPlacement.compose(root, body.offset(), body.rotation());
            }
        }
        return null;
    }

    /**
     * Cale la hitbox sur l'emprise physique du corps (serveur, fin de tick, R-702). L'emprise
     * a été contrôlée par l'appelant ; inchangée, elle n'est pas renvoyée au client.
     *
     * @param min coin minimal, relatif à la position
     * @param max coin maximal, relatif à la position
     */
    void setBodyBounds(float[] min, float[] max) {
        boundsMin.set(min[0], min[1], min[2]);
        boundsMax.set(max[0], max[1], max[2]);
        setBoundingBox(makeBoundingBox());
    }

    /**
     * {@return vrai si la hitbox suit l'emprise physique du corps, et non la boîte provisoire}
     */
    public boolean hasPhysicalBounds() {
        return boundsMin != null && boundsMin.isFinite() && boundsMax.isFinite();
    }

    /**
     * La hitbox vanilla : l'emprise physique courante du corps autour de sa position (R-702),
     * ou, faute d'emprise reçue, la boîte provisoire des dimensions de l'entité.
     *
     * <p>Minecraft l'appelle à chaque {@code setPos} — dès la construction d'{@code Entity},
     * avant que les champs de cette classe n'existent : d'où la garde.
     */
    @Override
    protected AABB makeBoundingBox() {
        if (!hasPhysicalBounds()) {
            return super.makeBoundingBox();
        }
        double x = getX();
        double y = getY();
        double z = getZ();
        return new AABB(
                x + boundsMin.x, y + boundsMin.y, z + boundsMin.z,
                x + boundsMax.x, y + boundsMax.y, z + boundsMax.z);
    }

    /**
     * Pose d'un corps de l'assembly autre que le corps racine (ADR-151 D2) : son node racine dans
     * l'asset, sa position monde, son orientation et ses vitesses monde.
     *
     * @param node node racine du corps
     * @param x position monde, en blocs
     * @param y idem
     * @param z idem
     * @param rotation orientation monde, copie
     * @param linear vitesse linéaire monde, en m/s
     * @param angular vitesse angulaire monde, en rad/s
     */
    public record BodyPose(
            int node, double x, double y, double z, Quaternionf rotation, float[] linear, float[] angular) {}

    /**
     * Pose les corps de l'assembly autres que le corps racine (serveur, fin de tick), dans l'ordre
     * de leurs états. Un corps que la gestion d'activité a endormi ({@code SUSPENDED}) a des vitesses
     * gelées, que son état rapporte nulles : il garde celles qu'il avait, que la sauvegarde écrit et que
     * son réveil lui rendra (ADR-153 D4).
     *
     * @param bodies les corps, à leur état du tick
     * @param suspended pour chacun, vrai s'il est endormi par la gestion d'activité
     */
    void setSecondaryBodies(List<BodyPose> bodies, List<Boolean> suspended) {
        List<BodyPose> merged = new ArrayList<>(bodies.size());
        for (int i = 0; i < bodies.size(); i++) {
            BodyPose body = bodies.get(i);
            BodyPose before = i < suspended.size() && suspended.get(i) ? secondaryBody(body.node()) : null;
            merged.add(before == null ? body : new BodyPose(body.node(), body.x(), body.y(), body.z(),
                    body.rotation(), before.linear(), before.angular()));
        }
        secondaryBodies = List.copyOf(merged);
    }

    /** {@return les corps de l'assembly autres que le corps racine, à leur dernier état} */
    List<BodyPose> secondaryBodies() {
        return secondaryBodies;
    }

    /** {@return le dernier état du corps secondaire de node racine {@code node}, ou {@code null}} */
    BodyPose secondaryBody(int node) {
        for (BodyPose body : secondaryBodies) {
            if (body.node() == node) {
                return body;
            }
        }
        return null;
    }

    /**
     * Pose les joints de l'assembly, aucun rompu ni grippé (serveur, à la création de ses corps : le
     * natif les crée intacts).
     *
     * @param count nombre de joints de la definition
     */
    void resetJoints(int count) {
        jointFlags = new int[count];
        jointMisalignment = new float[count];
    }

    /**
     * Retient le désalignement commandé à un joint (serveur, ADR-153 D2), au moment où la commande
     * {@code JAM_JOINT} part. Un rang hors de l'assembly est ignoré.
     *
     * @param joint rang du joint dans la definition
     * @param misalignment désalignement de son ancrage, en blocs ; 0 rend ses réglages d'origine
     */
    void commandMisalignment(int joint, float misalignment) {
        if (joint >= 0 && joint < jointMisalignment.length) {
            jointMisalignment[joint] = misalignment;
        }
    }

    /**
     * Retient les drapeaux qu'un joint a au dernier tick (serveur, ADR-151 D8). Un rang hors de
     * l'assembly est ignoré.
     *
     * @param joint rang du joint dans la definition
     * @param flags ses drapeaux, rompu et grippé
     */
    void setJointFlags(int joint, int flags) {
        if (joint >= 0 && joint < jointFlags.length) {
            jointFlags[joint] = flags;
        }
    }

    /**
     * {@return l'état des joints de l'assembly (ADR-151 D8) : au serveur, le dernier que le natif a
     * rapporté ; au client, celui du plus récent snapshot reçu}
     */
    public AssemblyState.JointBits jointBits() {
        return level().isClientSide() ? receivedJoints : AssemblyState.JointBits.of(jointFlags);
    }

    /**
     * Retient l'état qu'une roue a rapporté (serveur, thread autoritatif, ADR-159 D9). Le tableau suit le
     * plus grand rang rapporté ; une roue hors de {@link AssemblyState.Wheels#MAX_WHEELS} est ignorée.
     *
     * @param wheel rang de la roue dans la definition
     * @param grounded elle touche le sol
     * @param spin vitesse de rotation, en rad/s
     * @param compression compression relative au modèle posé, en fraction du débattement
     * @param steer braquage, en radians
     */
    void setWheelState(int wheel, boolean grounded, float spin, float compression, float steer) {
        AuthoritativeThread.require();
        if (wheel < 0 || wheel >= AssemblyState.Wheels.MAX_WHEELS) {
            return;
        }
        if (wheel >= wheelCompression.length) {
            wheelCompression = Arrays.copyOf(wheelCompression, wheel + 1);
            wheelSpin = Arrays.copyOf(wheelSpin, wheel + 1);
            wheelSteer = Arrays.copyOf(wheelSteer, wheel + 1);
            wheelGround = Arrays.copyOf(wheelGround, wheel + 1);
        }
        wheelCompression[wheel] = compression;
        wheelSpin[wheel] = spin;
        wheelSteer[wheel] = steer;
        wheelGround[wheel] = grounded;
    }

    /**
     * Retient l'état que le motopropulseur a rapporté (serveur, thread autoritatif, ADR-163 D1).
     *
     * @param report le rapport du natif
     */
    void setVehicleState(VehicleReport report) {
        AuthoritativeThread.require();
        vehicleReport = report;
    }

    /**
     * {@return l'état du véhicule pour les snapshots (serveur, ADR-163 D1)} Le motopropulseur vient du natif ;
     * l'accélérateur, le frein et le frein à main, de l'entrée du siège {@code driver}. Sans conducteur, le frein de
     * stationnement est serré, comme le natif le suppose (ADR-159 D8, Q3).
     */
    private AssemblyState.Vehicle vehicleState() {
        if (vehicleReport == null) {
            return AssemblyState.Vehicle.NONE;
        }
        DriverInput driver = driverInput();
        DriverInput held = driver == null ? DriverInput.ABSENT : driver;
        VehicleInput input = held.input();
        return AssemblyState.Vehicle.of(vehicleReport.rpm(), vehicleReport.load(), vehicleReport.gear(),
                vehicleReport.flags(), input.throttle(), input.brake(), vehicleReport.steer(),
                held.present() ? input.handbrake() : 1f);
    }

    private AssemblyState.Wheels wheelStates() {
        return wheelCompression.length == 0
                ? AssemblyState.Wheels.NONE
                : AssemblyState.Wheels.of(wheelCompression, wheelSpin, wheelSteer, wheelGround);
    }

    /** {@return l'angle accumulé de chaque roue pour le rendu (client, thread de rendu, ADR-159 D9)} */
    public ProceduralTracks.Spin wheelSpin() {
        return wheelVisualSpin;
    }

    /** {@return l'animation de l'assembly (serveur, C-37)} */
    public AssemblyAnimation animation() {
        return animation;
    }

    /**
     * {@return l'état d'animation de l'assembly (ADR-155) : au serveur, celui de son animation ; au client,
     * celui du plus récent snapshot reçu}
     */
    public AnimationLayers animationState() {
        return level().isClientSide() ? receivedAnimation : animation.state();
    }

    /** Pose l'orientation autoritaire du corps (serveur, fin de tick, boucle C-40). */
    void setBodyRotation(float x, float y, float z, float w) {
        bodyRotation.set(x, y, z, w);
    }

    /** {@return l'orientation autoritaire du corps, copie} */
    Quaternionf bodyRotation() {
        return new Quaternionf(bodyRotation);
    }

    /**
     * {@return l'orientation du corps racine, copie} Au serveur, l'orientation autoritaire ; au client, celle que son
     * dernier tick a prise de l'interpolation.
     */
    Quaternionf orientation() {
        return new Quaternionf(level().isClientSide() ? rotation : bodyRotation);
    }

    /**
     * Quantifie l'état de l'assembly pour les snapshots (serveur, fin de tick, après la position,
     * l'orientation, l'emprise, les corps secondaires et les joints). Une position non finie ne se
     * quantifie pas : l'état précédent reste.
     *
     * <p>Chaque corps secondaire part en pose relative au corps racine, dans son repère (ADR-151 D8) :
     * tant que l'assembly bouge d'un bloc, rien ne change pour lui.
     *
     * @param linear vitesse linéaire monde du corps racine, en m/s
     * @param angular vitesse angulaire monde du corps racine, en rad/s
     * @param sleeping l'assembly dort
     * @param suspended le corps racine dort, endormi par la gestion d'activité ({@code SUSPENDED})
     */
    /** {@return vrai si la physique a endormi l'assembly} */
    boolean netSleeping() {
        return sleeping;
    }

    void updateNetState(float[] linear, float[] angular, boolean sleeping, boolean suspended) {
        // ADR-153 D4 : endormi par la gestion d'activité, le corps a des vitesses gelées que l'état
        // rapporte nulles ; la sauvegarde garde celles d'avant, que le réveil lui rendra.
        if (!suspended) {
            System.arraycopy(linear, 0, linearVelocity, 0, 3);
            System.arraycopy(angular, 0, angularVelocity, 0, 3);
        }
        this.sleeping = sleeping;
        simulated = true;
        boolean bounded = hasPhysicalBounds();
        double[] root = {getX(), getY(), getZ()};
        float[] rootRotation = {bodyRotation.x, bodyRotation.y, bodyRotation.z, bodyRotation.w};
        List<AssemblyState.Body> bodies = new ArrayList<>(secondaryBodies.size());
        for (BodyPose body : secondaryBodies) {
            Quaternionf r = body.rotation();
            bodies.add(AssemblyState.Body.relative(body.node(), root, rootRotation,
                    new double[] {body.x(), body.y(), body.z()}, new float[] {r.x, r.y, r.z, r.w}));
        }
        try {
            netState = AssemblyState.of(
                    root,
                    rootRotation,
                    linear,
                    angular,
                    bounded ? new float[] {boundsMin.x, boundsMin.y, boundsMin.z} : null,
                    bounded ? new float[] {boundsMax.x, boundsMax.y, boundsMax.z} : null,
                    sleeping)
                    .with(bodies, AssemblyState.JointBits.of(jointFlags))
                    .withAnimation(animation.state())
                    .withWheels(wheelStates())
                    .withVehicle(vehicleState());
        } catch (IllegalArgumentException notFinite) {
            // Le natif ramène lui-même un corps non fini dans un état valide (E-2030) ; l'état précédent
            // reste celui des snapshots d'ici là.
        }
    }

    /**
     * {@return l'état que les snapshots portent, ou {@code null} avant le premier tick simulé} Son animation
     * est la courante, même pour une assembly endormie dont le natif ne rapporte plus l'état.
     */
    public AssemblyState netState() {
        if (netState != null && netState.animation() != animation.state()) {
            netState = netState.withAnimation(animation.state());
        }
        return netState;
    }

    /** {@return vrai si l'interpolation de 21.7 a commencé : un snapshot au moins est arrivé (client)} */
    public boolean interpolating() {
        return interpolation != null;
    }

    /**
     * Commence l'interpolation de 21.7, au premier snapshot reçu (client, thread du client).
     *
     * @param fresh interpolation, réglée par la configuration du client
     * @param renderClock horloge de rendu de la connexion
     */
    public void startInterpolation(Interpolation fresh, RenderClock renderClock) {
        interpolation = fresh;
        clock = renderClock;
    }

    /**
     * Reçoit l'état d'un snapshot (client, thread du client, après {@link #startInterpolation}) : il prend
     * place dans l'interpolation. Rien de Minecraft n'est touché ici : c'est {@link #tick} qui avance
     * l'entité, comme Minecraft l'appelle (INV-03).
     *
     * @param state état reconstitué
     * @param serverTick tick du serveur dont il est l'état
     */
    public void applySnapshot(AssemblyState state, long serverTick) {
        long now = System.nanoTime();
        interpolation.push(serverTick, state, clock.renderMillis(now), clock.localMillis(now));
        // ADR-151 D8, ADR-155 : les bits des joints et l'animation ne s'interpolent pas ; le plus récent état
        // du serveur prime.
        if (serverTick >= receivedJointsTick) {
            receivedJoints = state.joints();
            receivedAnimation = state.animation();
            receivedJointsTick = serverTick;
        }
    }

    /**
     * Où dessiner une assembly à une image.
     *
     * @param x position de l'origine du corps (bas-centre de l'entité)
     * @param y position
     * @param z position
     * @param rotation orientation du corps, autour de son origine
     * @param mode comment l'interpolation de 21.7 l'a obtenue ; {@code null} avant le premier snapshot
     * @param bodies les corps autres que le corps racine, en poses relatives à lui (ADR-151 D9) ; aucun avant le
     *     premier snapshot
     * @param wheels l'état de chaque roue, dans l'ordre de la definition (ADR-159 D9) ; aucun avant le
     *     premier snapshot
     * @param vehicle l'état du véhicule et la vitesse du corps racine (ADR-163 D1) ; {@code NONE} avant le premier
     *     snapshot
     */
    public record RenderPose(
            double x, double y, double z, Quaternionf rotation, Interpolation.Mode mode, List<Interpolation.BodyPose> bodies,
            List<Interpolation.WheelPose> wheels, Interpolation.VehiclePose vehicle) {}

    /**
     * {@return où dessiner l'assembly à cette image (client)} C'est la pose de l'interpolation de 21.7,
     * datée de l'image et non de l'appel : toutes les passes d'une image la reçoivent identique. Avant le
     * premier snapshot, c'est celle de l'apparition, entre deux ticks.
     *
     * @param partialTick fraction du tick écoulée
     */
    public RenderPose renderPose(float partialTick) {
        if (interpolation == null) {
            return new RenderPose(Mth.lerp(partialTick, xo, getX()), Mth.lerp(partialTick, yo, getY()),
                    Mth.lerp(partialTick, zo, getZ()), rotationO.slerp(rotation, partialTick, new Quaternionf()),
                    null, List.of(), List.of(), Interpolation.VehiclePose.NONE);
        }
        long frame = clock.frameNanos();
        Interpolation.Pose pose = interpolation.sample(clock.renderMillis(frame), clock.localMillis(frame));
        float[] q = pose.rotation();
        return new RenderPose(pose.x(), pose.y(), pose.z(), new Quaternionf(q[0], q[1], q[2], q[3]), pose.mode(),
                pose.bodies(), pose.wheels(), pose.vehicle());
    }

    /**
     * Ignore la position que le suivi d'entité de Minecraft enverrait encore — à la première mise à
     * jour, après une impulsion : celle d'AXION arrive par les snapshots, au 1/1024 de bloc, et les deux
     * se disputeraient l'entité (ADR-150 D2). La position d'apparition, elle, est posée par Minecraft
     * sans passer ici.
     */
    @Override
    public void lerpTo(
            double x, double y, double z, float yRot, float xRot, int steps, boolean teleport) {
        // Rien : la position vient des snapshots.
    }

    /**
     * Ce que l'overlay {@code network} montre d'une assembly (31.4).
     *
     * @param shown la pose dessinée à cette image : celle de l'interpolation de 21.7
     * @param received le dernier état reçu du serveur, tel quel
     */
    public record NetworkView(Interpolation.Pose shown, Interpolation.Pose received) {}

    /**
     * {@return ce que l'overlay {@code network} montre de l'assembly à cette image (client), ou {@code null} avant
     * le premier snapshot} La pose dessinée est celle de {@link #renderPose}, datée de l'image.
     */
    public NetworkView networkView() {
        if (interpolation == null) {
            return null;
        }
        long frame = clock.frameNanos();
        return new NetworkView(interpolation.sample(clock.renderMillis(frame), clock.localMillis(frame)),
                interpolation.latest());
    }

    /**
     * N'exécute aucune physique (R-701) : la simulation est pilotée par le noyau natif (C-40)
     * et réappliquée en fin de tick serveur. Côté client, {@code tick()} pose l'entité où
     * l'interpolation de 21.7 la place à cet instant — position, orientation, et la hitbox à son
     * emprise ; le rendu, lui, l'échantillonne à chaque image ({@link #renderPose}). Sans
     * interpolation (serveur, ou avant le premier snapshot), ce n'est que {@code super.tick()}.
     */
    @Override
    public void tick() {
        super.tick();
        rotationO.set(rotation);
        if (!level().isClientSide()) {
            settlePassengers();
        }
        if (interpolation == null) {
            return;
        }
        long now = System.nanoTime();
        Interpolation.Pose pose = interpolation.sample(clock.renderMillis(now), clock.localMillis(now));
        float[] q = pose.rotation();
        rotation.set(q[0], q[1], q[2], q[3]);
        tickBodies = pose.bodies();
        if (pose.boundsMin() != null && pose.boundsMax() != null) {
            boundsMin.set(pose.boundsMin()[0], pose.boundsMin()[1], pose.boundsMin()[2]);
            boundsMax.set(pose.boundsMax()[0], pose.boundsMax()[1], pose.boundsMax()[2]);
        }
        // setPos recalcule la hitbox autour de la position, à l'emprise ci-dessus.
        setPos(pose.x(), pose.y(), pose.z());
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        // R-1710 : un schéma antérieur se migre par une fonction explicite avant d'être lu.
        stored = AssemblyTags.migrate(AssemblyTags.extract(tag));
        simulated = false;
        loadedFromSave = true;
        // ADR-153 D3 : l'état gardé, rendu à la création de l'assembly ; ses vitesses et ses corps
        // secondaires sont aussi ceux que la sauvegarde suivante réécrit, tant que l'assembly dort.
        savedState = PersistedState.read(stored).orElse(null);
        if (savedState != null) {
            System.arraycopy(savedState.linear(), 0, linearVelocity, 0, 3);
            System.arraycopy(savedState.angular(), 0, angularVelocity, 0, 3);
            sleeping = savedState.sleeping();
            List<BodyPose> bodies = new ArrayList<>(savedState.bodies().size());
            for (PersistedState.Body body : savedState.bodies()) {
                float[] r = body.rotation();
                bodies.add(new BodyPose(body.node(), body.x(), body.y(), body.z(),
                        new Quaternionf(r[0], r[1], r[2], r[3]), body.linear(), body.angular()));
            }
            secondaryBodies = List.copyOf(bodies);
        }
        AssemblyBinding binding = AssemblyBinding.resolve(
                AssemblyTags.version(stored),
                AssemblyTags.definition(stored),
                AxionEntities.definitions());
        inert = binding.isInert();
        inertReason = binding.inertReason();
        definitionId = binding.bound().map(Definition::id).orElse(AssemblyTags.describe(stored));
        custom = AssemblyTags.custom(stored, AxionEntities::maxCustomBytes, inert);
        custom.problem().ifPresent(problem ->
                LOGGER.warn("AXION : assembly {}, données personnalisées — {}", getUUID(), problem));
        // §22.2 : le corps sera recréé avec l'orientation sauvegardée (identité si absente).
        bodyRotation.set(AssemblyTags.rotation(stored));
    }

    /**
     * Entrée dans le monde : une assembly qui y entre encore inerte le dit, côté serveur.
     *
     * <p>Pas à la lecture de son NBT : {@code /axion spawn} lit l'entité depuis le NBT donné,
     * {@code axion:rot} compris, puis la lie avant de l'ajouter — elle n'a rien d'anormal à
     * signaler. Forge appelle ce crochet une fois par entité ajoutée, fraîche ou chargée
     * depuis le disque.
     */
    @Override
    public void onAddedToWorld() {
        super.onAddedToWorld();
        if (inert && !level().isClientSide()) {
            LOGGER.warn("AXION : assembly {} inerte — {}", getUUID(), inertReason);
        }
        if (!level().isClientSide()) {
            // R-1745 : présente dans le monde, son NBT fait foi ; le journal ne la recrée pas.
            JournalBridge.seen(getUUID());
        }
    }

    /**
     * Suppression : une suppression explicite — tuée, retirée pour de bon — efface l'assembly du journal
     * latéral ; un déchargement de chunk ou un changement de dimension ne l'efface pas (ADR-153 D6).
     *
     * <p>Ici et non dans {@code onRemovedFromWorld} : Forge n'appelle ce dernier qu'à la fin du suivi de
     * l'entité, que n'a pas une entité supprimée pendant que son tronçon est caché. {@code kill()} et
     * {@code discard()} passent tous deux par cette méthode.
     */
    @Override
    public void remove(RemovalReason reason) {
        // ADR-158 D5, avant que l'entité ne soit marquée retirée : Minecraft ne demande plus alors de sortie.
        if (!level().isClientSide() && reason.shouldDestroy()) {
            for (Entity passenger : List.copyOf(getPassengers())) {
                eject(passenger, SeatEjection.Cause.ASSEMBLY_REMOVED);
            }
        }
        super.remove(reason);
        if (!level().isClientSide() && reason.shouldDestroy()) {
            JournalBridge.removed(getUUID());
        }
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        // Une assembly inerte est réécrite telle qu'elle a été lue (R-1710) : son orientation
        // n'est pas celle d'un corps simulé, elle n'a pas à en recevoir une. Liée mais pas encore
        // simulée depuis sa lecture, elle garde l'état que son NBT portait (ADR-153 D2).
        if (!inert && simulated) {
            persistedState().write(stored);
        } else if (!inert) {
            AssemblyTags.putRotation(stored, bodyRotation);
        }
        AssemblyTags.putCustom(stored, custom);
        AssemblyTags.restore(stored, tag);
        // 22.5.2 : chaque sauvegarde part aussi au journal latéral, filet de sécurité si vanilla perd l'entité.
        JournalBridge.saved(this, stored);
    }

    /**
     * {@return les données personnalisées des mods (serveur, thread autoritatif, ADR-153 D7)} En lecture
     * seule pour une assembly inerte. Pas encore exposées aux mods par {@code AxionApi}.
     */
    CustomData customData() {
        return custom;
    }

    /** {@return l'état que le NBT garde au schéma 3, au dernier tick simulé (serveur, ADR-153 D2)} */
    PersistedState persistedState() {
        List<PersistedState.Body> bodies = new ArrayList<>(secondaryBodies.size());
        for (BodyPose body : secondaryBodies) {
            Quaternionf r = body.rotation();
            bodies.add(new PersistedState.Body(body.node(), body.x(), body.y(), body.z(),
                    new float[] {r.x, r.y, r.z, r.w}, body.linear().clone(), body.angular().clone()));
        }
        List<PersistedState.Joint> joints = new ArrayList<>(jointFlags.length);
        for (int i = 0; i < jointFlags.length; i++) {
            joints.add(new PersistedState.Joint(
                    (jointFlags[i] & JointState.BROKEN) != 0, i < jointMisalignment.length ? jointMisalignment[i] : 0f));
        }
        return new PersistedState(
                new double[] {getX(), getY(), getZ()},
                new float[] {bodyRotation.x, bodyRotation.y, bodyRotation.z, bodyRotation.w},
                linearVelocity.clone(),
                angularVelocity.clone(),
                sleeping,
                List.copyOf(bodies),
                List.copyOf(joints));
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        // Le paquet de Forge transporte les données d'apparition ; celui de
        // Minecraft les ignorerait.
        return NetworkHooks.getEntitySpawningPacket(this);
    }

    /**
     * {@code S2C_AssemblySpawn} (ADR-150 D2) : la definition, l'état inerte et la transformation
     * complète — l'orientation et l'emprise ; la position est celle de l'apparition de Minecraft.
     */
    @Override
    public void writeSpawnData(FriendlyByteBuf buffer) {
        boolean bounded = hasPhysicalBounds();
        AssemblyTags.writeSpawn(buffer, new AssemblyTags.SpawnState(definitionId, inert, new Quaternionf(bodyRotation),
                bounded ? new Vector3f(boundsMin) : null, bounded ? new Vector3f(boundsMax) : null));
    }

    @Override
    public void readSpawnData(FriendlyByteBuf buffer) {
        AssemblyTags.SpawnState state = AssemblyTags.readSpawn(buffer);
        definitionId = state.definitionId();
        inert = state.inert();
        // L'assembly apparaît telle qu'elle est, jusqu'à son premier snapshot.
        rotation.set(state.rotation());
        rotationO.set(state.rotation());
        if (state.boundsMin() != null && state.boundsMax() != null) {
            boundsMin.set(state.boundsMin());
            boundsMax.set(state.boundsMax());
            setBoundingBox(makeBoundingBox());
        }
    }

    @Override
    public boolean isPickable() {
        // Visée du client (`GameRenderer.pick`) : sans cela, le réticule la traverse et elle ne
        // peut être ni frappée ni utilisée. Les sélecteurs de commande (`@e[...]`, donc
        // `/axion remove`) n'en dépendent pas : `EntitySelector` ne l'appelle jamais (bytecode
        // de Minecraft 1.20.1 lu le 2026-10-15).
        return !isRemoved();
    }

    /** {@return l'identifiant de la definition, ou ce qu'on sait d'une definition inconnue} */
    public String definitionId() {
        return definitionId;
    }

    /** {@return vrai si l'entité n'est liée à aucune definition} */
    public boolean isInert() {
        return inert;
    }

    /**
     * {@return le libellé affiché avec les hitbox vanilla}
     *
     * <p>« hitbox provisoire » tant que la hitbox n'est pas l'emprise physique du corps
     * (R-702) : avant le premier tick simulé, ou pour une assembly sans corps.
     */
    public Component debugLabel() {
        return Component.literal(definitionId
                + (inert ? " — inerte" : "")
                + (hasPhysicalBounds() ? "" : " — hitbox provisoire"));
    }
}
