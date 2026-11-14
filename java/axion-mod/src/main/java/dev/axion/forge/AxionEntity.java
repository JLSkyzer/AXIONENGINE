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
import dev.axion.entity.AssemblyVariables;
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
import java.util.Iterator;
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

    /** Glissement longitudinal, puis latéral en radians, de chaque roue (serveur, ADR-167 D9) : ses contrôleurs le lisent. */
    private float[] wheelSlipLong = new float[0];

    private float[] wheelSlipLat = new float[0];

    /** L'angle de chaque roue, intégré au rendu (client, ADR-159 D9). */
    private final ProceduralTracks.Spin wheelVisualSpin = new ProceduralTracks.Spin();

    /** Ce que ses pistes lissées gardent d'une image à l'autre (client, ADR-163 D3). */
    private final ProceduralTracks.State trackState = new ProceduralTracks.State();

    /**
     * Le motopropulseur au dernier rapport du natif (serveur, ADR-163 D1) : régime, charge, rapport, drapeaux et
     * position de la direction ; {@code null} tant que le natif n'en a rapporté aucun, l'assembly n'étant alors pas
     * un véhicule.
     */
    private VehicleReport vehicleReport;

    /**
     * Les variables de l'assembly (serveur, ADR-163 D2) ; {@code null} tant que sa definition n'est pas compilée.
     * Voir {@link #variables()}.
     */
    private AssemblyVariables variables;

    /**
     * Les paramètres réglés de l'assembly (serveur, ADR-167), ou leur miroir chez le client (D8) ; {@code null} tant
     * que sa definition n'est pas compilée. Voir {@link #parameters()}.
     */
    private dev.axion.entity.AssemblyParameters parameters;

    /**
     * Les modificateurs en vigueur au natif, datés du tick où ils ont pris effet (serveur, ADR-167 D8) : ceux que les
     * snapshots portent. Voir {@link #parametersInEffect}.
     */
    private AssemblyState.Modifiers netParameters = AssemblyState.Modifiers.NONE;

    /** Les modificateurs du plus récent snapshot reçu (client, ADR-167 D8), et ceux dont le miroir est fait. */
    private AssemblyState.Modifiers receivedParameters = AssemblyState.Modifiers.NONE;

    private AssemblyState.Modifiers mirroredParameters;

    /** Les variables du plus récent snapshot reçu (client), et celles dont le miroir est fait (ADR-167 D4). */
    private AssemblyState.VariableValues receivedVariables = AssemblyState.VariableValues.NONE;

    private AssemblyState.VariableValues mirroredVariables;

    /** Les variables que les modificateurs déclarés suivent, et leur version (serveur, ADR-167 D4). */
    private AssemblyVariables variedBy;

    private long variedVersion = -1L;

    /** Le débit des refus {@code E-7010} au journal (serveur) : un par seconde au plus. */
    private final dev.axion.entity.JournalThrottle refusalJournal = new dev.axion.entity.JournalThrottle(20);

    /** Le débit des sorties de contrôleur écartées au journal (serveur) : une par seconde au plus. */
    private final dev.axion.entity.JournalThrottle controlJournal = new dev.axion.entity.JournalThrottle(20);

    /**
     * Les contrôleurs que l'API a attachés à cette instance (serveur, ADR-167 D9), dans l'ordre de l'attache : relus de
     * {@code axion:ctrls}, sauvegardés, envoyés avec les modificateurs. Au client, voir {@link #attached()}.
     */
    private final List<String> attachedControllers = new ArrayList<>();

    /** Un numéro qui change à chaque attache ou retrait (serveur) : la liste repart alors aux joueurs. */
    private long controllersVersion;

    /**
     * La table effective des paramètres : celle de la definition, avec ceux des mods des contrôleurs que l'API a
     * attachés (Q6) ; la table compilée et les contrôleurs qui l'ont faite.
     */
    private dev.axion.definition.Tuning tuned;

    private dev.axion.definition.Tuning tunedFrom;

    private List<String> tunedWith = List.of();

    /** L'entrée qu'un mod pose au véhicule (serveur, ADR-167 D9), {@code null} sans ; un joueur au volant la fait tomber. */
    private DriverInput modDrive;

    /** Les nodes des roues de la definition, dans son ordre, et la definition qui les a donnés. */
    private List<String> wheelNodes = List.of();

    private Definition wheelsOf;

    /** La version des variables que {@link #netState} porte, et celles que seul le conducteur reçoit (serveur). */
    private long netVariablesVersion = -1L;

    private long driverOnly;

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
    /** La piste prédite du véhicule que le joueur local pilote et prédit (ADR-168 D2) ; {@code null} sinon. */
    private dev.axion.net.PredictedTrack predicted;
    /** Vrai tant que la pose affichée est celle de la piste prédite : le retour à l'interpolation se lisse. */
    private boolean showingPredicted;
    /** La dernière pose prédite affichée, d'où part le retour à l'interpolation. */
    private Interpolation.Pose lastPredictedPose;

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

    /** Une entrée reçue, bornée, et qui l'a écrite : le journal des variables la nomme. */
    private record QueuedInput(VehicleInput input, String writer) {}

    /**
     * Les entrées d'un siège (ADR-168 D3) : leur file, et celle que le dernier tick a appliquée, avec ce tick.
     */
    private static final class SeatInputs {
        final dev.axion.seat.InputQueue<QueuedInput> queue = new dev.axion.seat.InputQueue<>();
        VehicleInput applied;
        int appliedTick;
    }

    private static final int MILLIS_PER_TICK = 50;

    /** Les entrées de chaque siège, par rang : un tick les parcourt dans l'ordre des sièges. */
    private final Map<Integer, SeatInputs> seatInputs = new java.util.TreeMap<>();

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

    /**
     * {@return les sièges, les commandes liées et les variables que la definition déclare, des deux côtés ; {@code null}
     * : inerte, ou pas encore compilée} Le client qui prédit y lit les commandes de son siège (ADR-168 D6).
     */
    public Seating declaredSeating() {
        return seating();
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
        return target.interaction() >= 0 && play(seating.interactions().get(target.interaction()), player)
                ? InteractionResult.CONSUME : InteractionResult.PASS;
    }

    /**
     * {@return vrai si l'interaction a eu son effet : son clip joué, ou sa variable posée (ADR-163 D2)} Un clip
     * refusé — R-200 — se journalise.
     */
    private boolean play(Seating.Interaction interaction, Player player) {
        if (interaction.action() == Seating.Action.SET_VARIABLE) {
            AssemblyVariables held = interaction.variable() < 0 ? null : variables();
            if (held == null) {
                return false;
            }
            try {
                held.set(interaction.variable(), interaction.value(),
                        "interaction (" + player.getName().getString() + ")");
            } catch (IllegalArgumentException refused) {
                // ADR-167 D2 : ce que la variable règle sortirait de ses bornes ; rien ne change.
                logRefusals(List.of(refused.getMessage() + " (interaction de " + player.getName().getString() + ")"));
                return false;
            }
            return true;
        }
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

    /**
     * {@return les commandes génériques qu'un siège lie (ADR-163 D2), vide sans siège de ce rang ou sans definition
     * compilée}
     *
     * @param seat rang du siège
     */
    public List<Seating.Binding> seatBindings(int seat) {
        Seating seating = seating();
        return seating == null || seat < 0 || seat >= seating.seats().size()
                ? List.of()
                : seating.seats().get(seat).bindings();
    }

    /** {@return vrai si ce siège envoie des entrées : un contrôle ou une commande liée (ADR-163 D2)} */
    public boolean seatTakesInput(int seat) {
        Seating seating = seating();
        return seating != null && seat >= 0 && seat < seating.seats().size() && seating.seats().get(seat).takesInput();
    }

    /**
     * {@return vrai si ce passager tient le siège {@code driver} de ce véhicule : celui que son client prédit (ADR-168
     * D1)} Une assembly sans roues n'est pas un véhicule.
     *
     * @param passenger le passager
     */
    public boolean drivenBy(Entity passenger) {
        Seating seating = seating();
        int seat = seatOf(passenger);
        return seating != null && seat >= 0 && seat < seating.seats().size() && seating.seats().get(seat).driver()
                && !wheelNodes().isEmpty();
    }

    /**
     * {@return l'entrée telle que le serveur la retient de ce siège (R-1110)} Le monde prédit l'applique bornée de même
     * (ADR-168 D3).
     *
     * @param seat le rang du siège
     * @param input l'entrée envoyée
     */
    public VehicleInput sanitizedInput(int seat, VehicleInput input) {
        Seating seating = seating();
        return seating == null || seat < 0 || seat >= seating.seats().size() ? VehicleInput.NONE
                : input.sanitized(seating.seats().get(seat));
    }

    /**
     * {@return les variables de l'assembly (serveur, ADR-163 D2), {@code null} si sa definition n'est pas encore
     * compilée, ou au client} Créées à la première demande, à leur défaut puis aux valeurs que la sauvegarde gardait
     * ({@code axion:vars}). Une definition recompilée les refait, en gardant la valeur de chaque variable qui existe
     * encore et l'accepte.
     */
    public AssemblyVariables variables() {
        Seating seating = level().isClientSide() ? null : seating();
        if (seating == null) {
            return null;
        }
        if (variables == null || variables.declared() != seating.variables()) {
            AssemblyVariables fresh = new AssemblyVariables(seating.variables());
            List<String> refusals = variables != null ? fresh.adopt(variables) : fresh.restore(AssemblyTags.variables(stored));
            for (String refusal : refusals) {
                LOGGER.warn("AXION : assembly {} — {}", getUUID(), refusal);
            }
            // ADR-167 D4 : une écriture explicite se juge avec ce que la variable règle.
            fresh.guard((index, next) -> {
                dev.axion.entity.AssemblyParameters table = parameters();
                return table == null ? null : table.refusal(index, next);
            });
            variables = fresh;
        }
        return variables;
    }

    /**
     * {@return les paramètres réglés de l'assembly (ADR-167), {@code null} si sa definition n'est pas encore compilée}
     *
     * <p>Au serveur, ils sont créés à la première demande, à leur base, puis avec les modificateurs que la sauvegarde
     * gardait ({@code axion:params}). Une definition recompilée les refait, en reprenant chaque modificateur : celui
     * que ses nouvelles bornes refusent, ou qui vise un paramètre qu'elle n'a plus, reste posé mais inactif, et se dit
     * ({@code E-7011}).
     *
     * <p>Les modificateurs déclarés suivent les variables (D4) : au serveur, à chaque lecture où elles ont changé.
     *
     * <p>Au client, c'est leur miroir (D8) : les modificateurs du plus récent snapshot, rejoués sur la definition reçue
     * avec les variables reçues — les valeurs du serveur, au bit près. Il se lit ; rien ne l'écrit que le snapshot
     * suivant.
     */
    public dev.axion.entity.AssemblyParameters parameters() {
        dev.axion.definition.Tuning tuning = tuning();
        if (tuning == null) {
            return null;
        }
        if (level().isClientSide()) {
            if (parameters == null || parameters.tuning() != tuning || mirroredParameters != receivedParameters
                    || mirroredVariables != receivedVariables) {
                double[] variables = new double[receivedVariables.count()];
                for (int i = 0; i < variables.length; i++) {
                    variables[i] = receivedVariables.value(i);
                }
                parameters = dev.axion.entity.AssemblyParameters.mirror(tuning, receivedParameters, variables);
                mirroredParameters = receivedParameters;
                mirroredVariables = receivedVariables;
            }
            return parameters;
        }
        AssemblyVariables held = variables();
        if (parameters == null || parameters.tuning() != tuning) {
            dev.axion.entity.AssemblyParameters fresh = new dev.axion.entity.AssemblyParameters(tuning);
            // Les déclarés d'abord, pour que le rejeu des modificateurs relus les compte.
            if (held != null) {
                fresh.vary(held.values());
            }
            variedBy = held;
            variedVersion = held == null ? -1L : held.version();
            List<String> lines = parameters != null
                    ? fresh.adopt(parameters)
                    : fresh.restore(AssemblyTags.parameters(stored), dev.axion.entity.AssemblyParameters.SAVE);
            for (String line : lines) {
                LOGGER.warn("AXION : E-7011 assembly {} — {}", getUUID(), line);
            }
            parameters = fresh;
        } else if (held != null && (held != variedBy || held.version() != variedVersion)) {
            parameters.vary(held.values());
            variedBy = held;
            variedVersion = held.version();
        }
        return parameters;
    }

    /**
     * {@return la table des paramètres de l'assembly, {@code null} si sa definition n'est pas encore compilée} : celle
     * de sa definition, avec les paramètres des mods des contrôleurs que l'API a attachés (ADR-167 D9, Q6). Elle ne se
     * refait qu'au changement de l'une ou des autres ; une table refaite refait les paramètres, qui reprennent leurs
     * modificateurs.
     */
    /** Le dernier miroir composé sur des variables prédites, et ce sur quoi il l'a été. */
    private dev.axion.entity.AssemblyParameters predictedParameters;
    private AssemblyState.Modifiers predictedModifiers;
    private double[] predictedVariables;

    /**
     * {@return les paramètres que le serveur composerait avec ces variables (client, ADR-168 D6) ; {@code null} si la
     * definition n'est pas encore compilée} Le miroir de {@link #parameters()} — les modificateurs reçus rejoués par la
     * règle du serveur — mais sur les variables prédites : un mode basculé par le conducteur règle son couple au pas
     * même, dans le monde prédit. Recomposé seulement quand la definition, les modificateurs reçus ou les variables
     * changent.
     *
     * @param variables les variables prédites, une par variable déclarée
     */
    public dev.axion.entity.AssemblyParameters predictedParameters(double[] variables) {
        dev.axion.definition.Tuning tuning = tuning();
        if (tuning == null) {
            return null;
        }
        if (predictedParameters == null || predictedParameters.tuning() != tuning
                || predictedModifiers != receivedParameters || !Arrays.equals(predictedVariables, variables)) {
            predictedParameters = dev.axion.entity.AssemblyParameters.mirror(tuning, receivedParameters, variables);
            predictedModifiers = receivedParameters;
            predictedVariables = variables.clone();
        }
        return predictedParameters;
    }

    private dev.axion.definition.Tuning tuning() {
        dev.axion.definition.Tuning compiled = inert ? null : AxionEntities.tuning(definitionId).orElse(null);
        if (compiled == null) {
            return null;
        }
        List<String> attached = attached();
        if (compiled != tunedFrom || !attached.equals(tunedWith)) {
            tunedFrom = compiled;
            tunedWith = List.copyOf(attached);
            tuned = tunedWith.isEmpty() ? compiled
                    : compiled.with(dev.axion.definition.VehicleExtensions.current().parametersFor(tunedWith));
        }
        return tuned;
    }

    /**
     * {@return les nodes des roues de sa definition, dans son ordre ; vide si elle n'en a pas ou n'est pas chargée}
     */
    public List<String> wheelNodes() {
        Definition definition = inert ? null : AxionEntities.definitions().get(definitionId).orElse(null);
        if (definition != wheelsOf) {
            wheelsOf = definition;
            wheelNodes = definition == null || !(definition.root().get("wheels") instanceof com.google.gson.JsonArray
                    wheels) ? List.of()
                    : wheels.asList().stream()
                            .map(wheel -> wheel.getAsJsonObject().get("node").getAsString())
                            .toList();
        }
        return wheelNodes;
    }

    /**
     * {@return ses contrôleurs (ADR-167 D9) : ceux de sa definition, puis ceux que l'API a attachés, dans l'ordre de
     * l'attache ; vide tant que sa definition n'est pas compilée}
     */
    public List<String> controllers() {
        dev.axion.definition.Tuning tuning = tuning();
        if (tuning == null) {
            return List.of();
        }
        List<String> all = new ArrayList<>(tuning.controllers());
        for (String id : attached()) {
            // Relu d'une sauvegarde, un contrôleur que la definition nomme désormais n'y figure qu'une fois.
            if (!all.contains(id)) {
                all.add(id);
            }
        }
        return List.copyOf(all);
    }

    /**
     * {@return les contrôleurs attachés par l'API} : au serveur, ceux de l'instance ; au client, ceux du plus récent
     * snapshot (ADR-167 D8).
     */
    private List<String> attached() {
        return level().isClientSide() ? receivedParameters.controllers() : attachedControllers;
    }

    /** {@return un numéro qui change à chaque attache ou retrait d'un contrôleur par l'API (serveur)} */
    long controllersVersion() {
        return controllersVersion;
    }

    /**
     * Attache un contrôleur à cette instance (serveur, ADR-167 D9) : les paramètres de son mod y existent dès lors.
     *
     * @param id l'identifiant du contrôleur
     * @throws IllegalArgumentException si aucun mod ne l'enregistre, s'il est déjà là, si l'assembly n'a pas de roues
     *     ou porte déjà le plafond de contrôleurs
     */
    public void attachController(String id) {
        AuthoritativeThread.require();
        if (dev.axion.definition.VehicleExtensions.current().controller(id).isEmpty()) {
            throw new IllegalArgumentException("contrôleur « " + id + " » inconnu ; aucun mod ne l'enregistre");
        }
        if (wheelNodes().isEmpty()) {
            throw new IllegalArgumentException("contrôleur « " + id + " » : l'assembly de " + definitionId
                    + " n'est pas un véhicule");
        }
        List<String> present = controllers();
        if (present.contains(id)) {
            throw new IllegalArgumentException("contrôleur « " + id + " » déjà attaché à cette assembly");
        }
        if (present.size() >= dev.axion.definition.Tuning.MAX_CONTROLLERS) {
            throw new IllegalArgumentException("contrôleur « " + id + " » : l'assembly en porte déjà "
                    + dev.axion.definition.Tuning.MAX_CONTROLLERS + ", le plafond");
        }
        attachedControllers.add(id);
        controllersVersion++;
    }

    /**
     * Retire un contrôleur que l'API avait attaché (serveur, ADR-167 D9) ; ceux de la definition restent.
     *
     * @param id l'identifiant du contrôleur
     * @return vrai s'il était attaché par l'API
     */
    public boolean detachController(String id) {
        AuthoritativeThread.require();
        boolean removed = attachedControllers.remove(id);
        if (removed) {
            controllersVersion++;
        }
        return removed;
    }

    /** {@return ses contrôleurs que les mods enregistrent, dans leur ordre ; un contrôleur sans mod est passé} */
    List<dev.axion.definition.VehicleExtensions.Controller> runnableControllers() {
        dev.axion.definition.VehicleExtensions extensions = dev.axion.definition.VehicleExtensions.current();
        List<dev.axion.definition.VehicleExtensions.Controller> found = new ArrayList<>();
        for (String id : controllers()) {
            extensions.controller(id).ifPresent(found::add);
        }
        return found;
    }

    /**
     * {@return l'état du véhicule au début du tick (serveur, ADR-167 D9), {@code null} s'il n'a pas de roues ou que sa
     * definition n'est pas compilée} Ses paramètres y sont composés sans les sorties des contrôleurs.
     */
    public dev.axion.entity.VehicleSnapshot vehicleSnapshot() {
        List<String> nodes = wheelNodes();
        dev.axion.entity.AssemblyParameters table = parameters();
        if (nodes.isEmpty() || table == null) {
            return null;
        }
        List<dev.axion.entity.VehicleSnapshot.Wheel> wheels = new ArrayList<>(nodes.size());
        for (int i = 0; i < nodes.size(); i++) {
            boolean known = i < wheelCompression.length;
            wheels.add(new dev.axion.entity.VehicleSnapshot.Wheel(nodes.get(i), known && wheelGround[i],
                    known ? wheelCompression[i] : 0.0, known ? wheelSpin[i] : 0.0, known ? wheelSteer[i] : 0.0,
                    known ? wheelSlipLong[i] : 0.0, known ? wheelSlipLat[i] : 0.0));
        }
        AssemblyVariables held = variables();
        List<String> variableNames = held == null ? List.of()
                : held.declared().declared().stream().map(dev.axion.definition.Variables.Variable::name).toList();
        double[] variableValues = held == null ? new double[0] : held.values();
        double[] parameterValues = new double[table.tuning().size()];
        for (int i = 0; i < parameterValues.length; i++) {
            parameterValues[i] = table.uncontrolled(i);
        }
        Quaternionf q = orientation();
        double speed = Interpolation.forwardSpeed(linearVelocity, new float[] {q.x, q.y, q.z, q.w});
        DriverInput driver = driverInput();
        dev.axion.api.vehicle.DriverInput input = driver == null || !driver.present()
                ? dev.axion.api.vehicle.DriverInput.NONE
                : new dev.axion.api.vehicle.DriverInput(driver.input().throttle(), driver.input().brake(),
                        driver.input().steer(), driver.input().clutch(), driver.input().handbrake(),
                        driver.input().gear());
        return new dev.axion.entity.VehicleSnapshot(speed, vehicleReport == null ? 0.0 : vehicleReport.rpm(),
                vehicleReport == null ? 0 : vehicleReport.gear(), input, wheels, variableNames, variableValues,
                table.tuning().names(), parameterValues);
    }

    /**
     * Applique les sorties des contrôleurs d'un tick (serveur, ADR-167 D9) : les modificateurs du tick, puis les
     * écritures de variables, chacune jugée comme une écriture de l'API. Ce que les bornes écartent se journalise, à
     * débit limité ; le couple des roues part au natif par le runtime.
     *
     * @param outcome les sorties assemblées
     */
    void applyControl(dev.axion.entity.ControllerRunner.Outcome outcome) {
        AuthoritativeThread.require();
        outcome.journal().forEach(line -> LOGGER.warn("AXION : {}", line));
        dev.axion.entity.AssemblyParameters table = parameters();
        List<String> lines = new ArrayList<>(table == null ? List.of() : table.control(outcome.modifiers()));
        AssemblyVariables held = outcome.writes().isEmpty() ? null : variables();
        for (dev.axion.entity.ControllerRunner.Write write : outcome.writes()) {
            int index = held == null ? -1 : held.declared().indexOf(write.variable());
            if (index < 0) {
                continue;
            }
            try {
                held.set(index, write.value(), "contrôleur " + write.controller());
            } catch (IllegalArgumentException refused) {
                lines.add("écriture du contrôleur « " + write.controller() + " » écartée : " + refused.getMessage());
            }
        }
        for (String line : lines) {
            int hushed = controlJournal.admit(tickCount);
            if (hushed >= 0) {
                LOGGER.warn("AXION : assembly {} — {}{}", getUUID(), line,
                        hushed == 0 ? "" : " (" + hushed + " sortie(s) écartée(s) tue(s) depuis la précédente)");
            }
        }
    }

    /**
     * {@return le nom du joueur qui tient le volant, {@code null} si le siège {@code driver} est libre ou que la
     * definition n'en déclare pas}
     */
    String driverName() {
        int occupant = driverOccupant();
        if (occupant == SeatOccupancy.FREE) {
            return null;
        }
        for (Entity passenger : getPassengers()) {
            if (passenger.getId() == occupant) {
                return passenger.getName().getString();
            }
        }
        return "#" + occupant;
    }

    /** {@return l'identifiant de l'occupant du siège {@code driver}, {@link SeatOccupancy#FREE} sans} */
    private int driverOccupant() {
        Seating seating = seating();
        if (seating == null) {
            return SeatOccupancy.FREE;
        }
        for (int seat = 0; seat < seating.seats().size(); seat++) {
            if (seating.seats().get(seat).driver()) {
                int occupant = occupancy(seating).occupant(seat);
                return occupant == SeatOccupancy.LOST ? SeatOccupancy.FREE : occupant;
            }
        }
        return SeatOccupancy.FREE;
    }

    /**
     * {@return le joueur que la correction suit (serveur, ADR-168 D4) : celui qui tient le siège {@code driver} d'un
     * véhicule, {@code null} sans} Un mod qui conduit n'en reçoit pas : rien ne prédit chez lui.
     */
    public ServerPlayer correctedDriver() {
        int occupant = driverOccupant();
        if (occupant == SeatOccupancy.FREE || wheelNodes().isEmpty()) {
            return null;
        }
        for (Entity passenger : getPassengers()) {
            if (passenger.getId() == occupant && passenger instanceof ServerPlayer player) {
                return player;
            }
        }
        return null;
    }

    /**
     * {@return la correction de ce véhicule à ce tick (serveur, ADR-168 D4)} La séquence est celle de la dernière entrée
     * que son conducteur a appliquée ; les variables, celles qu'il reçoit, en pleine précision ; les paramètres se
     * datent du tick de leurs modificateurs, que les snapshots portent.
     *
     * @param tick le tick du serveur dont l'état est le résultat
     * @param states l'état complet exporté de chaque assembly de l'ensemble, celui-ci d'abord
     */
    public dev.axion.net.CorrectionMessage correction(long tick, List<dev.axion.net.CorrectionMessage.State> states) {
        return new dev.axion.net.CorrectionMessage(tick, driverSequence(), getId(), netParameters.tick(),
                correctionVariables(), states);
    }

    /** {@return les variables que le conducteur reçoit : toutes, sauf {@code replicate: none} (ADR-163 D2)} */
    private dev.axion.net.CorrectionMessage.Variables correctionVariables() {
        if (variables == null || variables.size() == 0) {
            return dev.axion.net.CorrectionMessage.Variables.NONE;
        }
        long sent = 0L;
        for (int i = 0; i < variables.size(); i++) {
            if (variables.declared().get(i).replicate() != dev.axion.definition.Variables.Replicate.NONE) {
                sent |= 1L << i;
            }
        }
        return dev.axion.net.CorrectionMessage.Variables.of(variables.values(), sent);
    }

    /**
     * Conduit le véhicule à la place d'un joueur (serveur, ADR-167 D9) : l'entrée vaut jusqu'à la suivante, frein de
     * stationnement relâché — le frein à main de l'entrée le remplace. Chaque valeur est bornée à sa plage, une valeur
     * non finie compte pour zéro.
     *
     * @param input l'entrée
     * @throws IllegalStateException si un joueur tient le volant
     */
    void drive(dev.axion.api.vehicle.DriverInput input) {
        AuthoritativeThread.require();
        String holder = driverName();
        if (holder != null) {
            throw new IllegalStateException("le joueur " + holder + " tient le volant de cette assembly : un mod ne la"
                    + " conduit pas à sa place");
        }
        modDrive = new DriverInput(new VehicleInput(bounded(input.throttle(), 0f), bounded(input.brake(), 0f),
                bounded(input.steer(), -1f), bounded(input.clutch(), 0f), bounded(input.handbrake(), 0f),
                (byte) Math.max(Byte.MIN_VALUE, Math.min(Byte.MAX_VALUE, input.gear())), 0), true);
    }

    /** {@return une valeur bornée à {@code [low, 1]}, zéro si elle n'est pas finie} */
    private static float bounded(double value, float low) {
        return Double.isFinite(value) ? (float) Math.max(low, Math.min(1.0, value)) : 0f;
    }

    /**
     * Journalise les écritures de variables refusées (serveur, ADR-167 D2, {@code E-7010}) : une par seconde au plus,
     * les suivantes comptées et dites avec la prochaine.
     */
    private void logRefusals(List<String> refusals) {
        for (String refusal : refusals) {
            int hushed = refusalJournal.admit(tickCount);
            if (hushed >= 0) {
                LOGGER.warn("AXION : E-7010 assembly {} — {}{}", getUUID(), refusal,
                        hushed == 0 ? "" : " (" + hushed + " refus tus depuis le précédent)");
            }
        }
    }

    /**
     * Reçoit des entrées sans séquence (serveur) : à la suite des dernières reçues de ce siège. Le canal porte une
     * séquence ({@link #receiveInput(Entity, int, long, VehicleInput)}) ; un essai qui pose les entrées lui-même, non.
     *
     * @param sender le passager qui les envoie
     * @param seat rang du siège annoncé
     * @param input les entrées, telles que reçues
     * @return la faute à journaliser, {@code null} sans
     */
    public String receiveInput(Entity sender, int seat, VehicleInput input) {
        SeatInputs inputs = seatInputs.get(seat);
        return receiveInput(sender, seat, inputs == null ? 0 : inputs.queue.lastOffered() + 1, input);
    }

    /**
     * Reçoit les entrées d'un passager (serveur, ADR-158 D4) : elles ne valent que s'il occupe ce siège, et sont
     * bornées avant d'être retenues (R-1110). Elles attendent leur tick dans la file du siège : chaque tick en applique
     * une, dans l'ordre des séquences (ADR-168 D3).
     *
     * @param sender le passager qui les envoie
     * @param seat rang du siège annoncé
     * @param sequence leur séquence, non signée : celle du canal
     * @param input les entrées, telles que reçues
     * @return la faute à journaliser, {@code null} sans ; une valeur non finie compte pour zéro, et se journalise
     */
    public String receiveInput(Entity sender, int seat, long sequence, VehicleInput input) {
        Seating seating = seating();
        if (seating == null || seat < 0 || seat >= seating.seats().size()
                || sender.getVehicle() != this || seatOf(sender) != seat) {
            return "entrées pour un siège que le joueur n'occupe pas";
        }
        Seating.Seat declared = seating.seats().get(seat);
        QueuedInput queued = new QueuedInput(input.sanitized(declared),
                "siège " + declared.name() + " (" + sender.getName().getString() + ")");
        if (!seatInputs.computeIfAbsent(seat, key -> new SeatInputs()).queue.offer(sequence, queued)) {
            return "séquence d'entrées qui ne croît pas";
        }
        return input.finite() ? null : "entrée non finie, comptée pour zéro (R-1110)";
    }

    /**
     * Chaque tick, au serveur (ADR-168 D3) : chaque siège applique une entrée de sa file, la plus ancienne ; une file
     * vide garde celle d'avant. Les commandes tenues suivent, entrée par entrée, celles que la file a sautées pour
     * rattraper comprises (ADR-163 D2).
     */
    private void tickInputs(Seating seating) {
        for (Iterator<Map.Entry<Integer, SeatInputs>> it = seatInputs.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, SeatInputs> entry = it.next();
            int seat = entry.getKey();
            if (seat >= seating.seats().size()) {
                it.remove();
                continue;
            }
            SeatInputs inputs = entry.getValue();
            dev.axion.seat.InputQueue.Step<QueuedInput> step = inputs.queue.next();
            if (step == null) {
                continue;
            }
            Seating.Seat declared = seating.seats().get(seat);
            for (QueuedInput skipped : step.skipped()) {
                hold(seat, declared, skipped);
            }
            hold(seat, declared, step.applied());
            inputs.applied = step.applied().input();
            inputs.appliedTick = tickCount;
        }
    }

    /** ADR-163 D2 : les commandes tenues d'un siège, sur la séquence de ses entrées. */
    private void hold(int seat, Seating.Seat declared, QueuedInput input) {
        AssemblyVariables held = declared.bindings().isEmpty() ? null : variables();
        if (held != null) {
            logRefusals(held.input(seat, declared.bindings(), input.input().flags(), input.writer()));
        }
    }

    /**
     * {@return les entrées d'un siège, à ce tick : celles que sa file a appliquées, ou aucune s'il est libre ou que
     * rien n'arrive depuis {@code net.extrapolation_max_ms}} Les véhicules les liront (C-33).
     *
     * @param seat rang du siège
     */
    public VehicleInput seatInput(int seat) {
        return inputSilent(seat) ? VehicleInput.NONE : seatInputs.get(seat).applied;
    }

    /** {@return vrai si rien n'est appliqué à ce siège, ou plus rien depuis {@code net.extrapolation_max_ms}} */
    private boolean inputSilent(int seat) {
        SeatInputs inputs = seatInputs.get(seat);
        return inputs == null || inputs.applied == null
                || (tickCount - inputs.appliedTick) * MILLIS_PER_TICK > AxionEntities.inputTimeoutMillis();
    }

    /**
     * {@return la séquence de la dernière entrée que le siège {@code driver} a appliquée (ADR-168 D3), -1 sans} La
     * correction la rend au conducteur : ce qu'il a envoyé après, il le rejoue.
     */
    public long driverSequence() {
        Seating seating = seating();
        if (seating == null) {
            return -1;
        }
        for (int seat = 0; seat < seating.seats().size(); seat++) {
            if (seating.seats().get(seat).driver()) {
                SeatInputs inputs = seatInputs.get(seat);
                return inputs == null ? -1 : inputs.queue.lastApplied();
            }
        }
        return -1;
    }

    /**
     * Chaque tick, au serveur (ADR-163 D2) : un {@code press} qui a eu son tick revient, et un siège quitté ou dont
     * les entrées se taisent relâche ses commandes {@code hold}.
     */
    private void tickVariables(Seating seating) {
        AssemblyVariables held = variables();
        if (held == null) {
            return;
        }
        held.tick();
        SeatOccupancy occupancy = occupancy(seating);
        for (int seat = 0; seat < seating.seats().size(); seat++) {
            Seating.Seat declared = seating.seats().get(seat);
            if (declared.bindings().isEmpty()) {
                continue;
            }
            int occupant = occupancy.occupant(seat);
            if (occupant == SeatOccupancy.FREE || occupant == SeatOccupancy.LOST) {
                held.release(seat, declared.bindings(), "siège " + declared.name() + " (quitté)");
            } else if (inputSilent(seat)) {
                held.release(seat, declared.bindings(), "siège " + declared.name() + " (entrées tues)");
            }
        }
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
     * Sans joueur au volant, l'entrée qu'un mod a posée le conduit (ADR-167 D9), siège {@code driver} ou non.
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
                if (present) {
                    return new DriverInput(seatInput(seat), true);
                }
                return modDrive != null ? modDrive : DriverInput.ABSENT;
            }
        }
        return modDrive;
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
     * descend. Un occupant qui n'est plus passager libère le sien. Puis chaque siège applique l'entrée de ce tick
     * ({@link #tickInputs}), et les variables ont le leur ({@link #tickVariables}).
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
        if (modDrive != null && driverName() != null) {
            // ADR-167 D9 : le joueur qui prend le volant reprend la conduite ; en sortant, il ne la rend pas au mod.
            modDrive = null;
        }
        tickInputs(seating);
        tickVariables(seating);
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
            seatInputs.remove(seat);
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
     * @param slipLong glissement longitudinal
     * @param slipLat glissement latéral, en radians
     */
    void setWheelState(int wheel, boolean grounded, float spin, float compression, float steer, float slipLong,
            float slipLat) {
        AuthoritativeThread.require();
        if (wheel < 0 || wheel >= AssemblyState.Wheels.MAX_WHEELS) {
            return;
        }
        if (wheel >= wheelCompression.length) {
            wheelCompression = Arrays.copyOf(wheelCompression, wheel + 1);
            wheelSpin = Arrays.copyOf(wheelSpin, wheel + 1);
            wheelSteer = Arrays.copyOf(wheelSteer, wheel + 1);
            wheelGround = Arrays.copyOf(wheelGround, wheel + 1);
            wheelSlipLong = Arrays.copyOf(wheelSlipLong, wheel + 1);
            wheelSlipLat = Arrays.copyOf(wheelSlipLat, wheel + 1);
        }
        wheelCompression[wheel] = compression;
        wheelSpin[wheel] = spin;
        wheelSteer[wheel] = steer;
        wheelGround[wheel] = grounded;
        wheelSlipLong[wheel] = slipLong;
        wheelSlipLat[wheel] = slipLat;
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

    /** {@return l'état des pistes lissées de l'assembly (client, thread de rendu, ADR-163 D3)} */
    public ProceduralTracks.State trackState() {
        return trackState;
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
    public Quaternionf orientation() {
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
                    .withVehicle(vehicleState())
                    .withVariables(replicatedVariables())
                    .withParameters(netParameters);
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
        // ADR-163 D2 : une variable posée sur une assembly endormie, que le natif ne rapporte plus, part aussi.
        if (netState != null && variables != null && variables.version() != netVariablesVersion) {
            netState = netState.withVariables(replicatedVariables());
        }
        // ADR-167 D8 : de même un réglage d'une assembly endormie.
        if (netState != null && netState.parameters() != netParameters) {
            netState = netState.withParameters(netParameters);
        }
        return netState;
    }

    /**
     * Date les modificateurs que le natif applique désormais (serveur, ADR-167 D8) : appelé quand la liste posée part
     * au natif, avec le tick du pas qui l'applique le premier. Les snapshots la portent ensuite, datée de ce tick.
     *
     * @param tick le tick du serveur où elle prend effet
     */
    void parametersInEffect(long tick) {
        dev.axion.entity.AssemblyParameters table = parameters();
        netParameters = table == null ? AssemblyState.Modifiers.NONE : table.posed(tick, attachedControllers);
    }

    /**
     * {@return l'état que les snapshots portent à ce joueur (serveur, ADR-163 D2)} Celui de {@link #netState()}, sans
     * les variables {@code replicate: driver} s'il n'occupe pas le siège {@code driver}.
     *
     * @param player le joueur qui le recevra
     */
    public AssemblyState netStateFor(Entity player) {
        AssemblyState state = netState();
        if (state == null || variables == null || driverOnly == 0L) {
            return state;
        }
        Seating seating = seating();
        int seat = seating == null ? -1 : seatOf(player);
        boolean driver = seat >= 0 && seating.seats().get(seat).driver();
        return driver ? state : state.withVariables(state.variables().only(~driverOnly));
    }

    /**
     * {@return les variables que les snapshots portent (serveur, ADR-163 D2) : toutes celles qui partent, aux valeurs
     * du serveur} Celles de {@code replicate: none} n'y sont pas ; celles de {@code driver}, si — {@link #netStateFor}
     * les retire pour les autres joueurs.
     */
    private AssemblyState.VariableValues replicatedVariables() {
        if (variables == null || variables.size() == 0) {
            return AssemblyState.VariableValues.NONE;
        }
        long sent = 0L;
        long onlyDriver = 0L;
        for (int i = 0; i < variables.size(); i++) {
            dev.axion.definition.Variables.Replicate replicate = variables.declared().get(i).replicate();
            if (replicate != dev.axion.definition.Variables.Replicate.NONE) {
                sent |= 1L << i;
            }
            if (replicate == dev.axion.definition.Variables.Replicate.DRIVER) {
                onlyDriver |= 1L << i;
            }
        }
        driverOnly = onlyDriver;
        netVariablesVersion = variables.version();
        return AssemblyState.VariableValues.of(variables.values()).only(sent);
    }

    /**
     * {@return les variables de contenu du plus récent snapshot reçu (client, ADR-163 D2)} La pose prédite les porte
     * telles quelles jusqu'à ce qu'elles se prédisent (ADR-168 D6, 6d).
     */
    public AssemblyState.VariableValues receivedVariables() {
        return receivedVariables;
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
            // ADR-167 D8 : les modificateurs non plus, ni les variables qui règlent les déclarés (D4) ; le miroir se
            // refait à la lecture suivante.
            receivedParameters = state.parameters();
            receivedVariables = state.variables();
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
     * @param variables les variables de contenu reçues (ADR-163 D2) ; {@code NONE} avant le premier snapshot
     */
    public record RenderPose(
            double x, double y, double z, Quaternionf rotation, Interpolation.Mode mode, List<Interpolation.BodyPose> bodies,
            List<Interpolation.WheelPose> wheels, Interpolation.VehiclePose vehicle,
            AssemblyState.VariableValues variables) {}

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
                    null, List.of(), List.of(), Interpolation.VehiclePose.NONE, AssemblyState.VariableValues.NONE);
        }
        Interpolation.Pose pose = shownPose(clock.frameNanos(), partialTick, true);
        float[] q = pose.rotation();
        return new RenderPose(pose.x(), pose.y(), pose.z(), new Quaternionf(q[0], q[1], q[2], q[3]), pose.mode(),
                pose.bodies(), pose.wheels(), pose.vehicle(), pose.variables());
    }

    /**
     * La pose affichée à cet instant (client) : celle de la piste prédite pour le véhicule que le joueur local pilote et
     * prédit (ADR-168 D2), celle de l'interpolation de 21.7 sinon. Le passage de l'une à l'autre, dans les deux sens,
     * se résorbe en 200 ms, ou se téléporte au-delà de {@code net.snap_threshold}.
     *
     * @param nanos l'heure locale de l'image ou du tick
     * @param fraction la fraction de tick de l'image ; 1 au tick, où l'entité se pose à son dernier tick prédit
     * @param frame vrai pour une image dessinée, faux pour le tick de l'entité
     */
    private Interpolation.Pose shownPose(long nanos, double fraction, boolean frame) {
        double local = localMillis(nanos);
        Interpolation.Pose interpolated = interpolation.sample(clock.renderMillis(nanos), local);
        if (predicted != null && predicted.started()) {
            if (!showingPredicted) {
                predicted.blendFrom(interpolated, fraction, local);
                showingPredicted = true;
            }
            // Le tick de l'entité (fraction 1) n'est pas une image dessinée : un rejeu mesure son écart à l'écran.
            Interpolation.Pose pose = frame ? predicted.sample(fraction, local) : predicted.pose(fraction, local);
            if (pose != null) {
                lastPredictedPose = pose;
                return pose;
            }
        }
        if (showingPredicted) {
            interpolation.blend(lastPredictedPose, clock.renderMillis(nanos), local);
            showingPredicted = false;
            interpolated = interpolation.sample(clock.renderMillis(nanos), local);
        }
        return interpolated;
    }

    /**
     * Rend la pose de l'assembly à une piste prédite, ou la rend à l'interpolation (client, ADR-168 D2) : seul le
     * véhicule que le joueur local pilote et prédit en a une.
     *
     * @param track la piste ; {@code null} : retour à l'interpolation de 21.7
     */
    public void predictWith(dev.axion.net.PredictedTrack track) {
        predicted = track;
    }

    /**
     * {@return la pose de l'assembly à un temps du serveur, telle que ses snapshots la donnent (client) ; {@code null}
     * avant le premier} Interpolée, ou extrapolée au plus de {@code net.extrapolation_max_ms} : un proxy du monde
     * prédit s'y pose (ADR-168, précision 6c-C2).
     *
     * @param serverMillis le temps, en millisecondes du serveur
     */
    public Interpolation.Pose poseAt(double serverMillis) {
        return interpolation == null ? null : interpolation.at(serverMillis);
    }

    /**
     * {@return le temps local de l'entité, pauses exclues, en millisecondes (client)} Celui de son horloge de rendu, sur
     * lequel sa piste prédite se date aussi.
     *
     * @param nanos l'heure locale, en nanosecondes
     */
    public double localMillis(long nanos) {
        return clock == null ? nanos / 1e6 : clock.localMillis(nanos);
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
        Interpolation.Pose pose = shownPose(System.nanoTime(), 1.0, false);
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
        // ADR-167 D7 : un contrôleur dont aucun mod ne s'enregistre plus reste attaché, inactif, et se dit (CAP-38).
        attachedControllers.clear();
        attachedControllers.addAll(AssemblyTags.controllers(stored));
        // Relus, ils partent aux joueurs dès la création du corps, comme une attache.
        controllersVersion = attachedControllers.isEmpty() ? 0 : 1;
        for (String id : attachedControllers) {
            if (dev.axion.definition.VehicleExtensions.current().controller(id).isEmpty()) {
                LOGGER.warn("AXION : E-7011 assembly {} — contrôleur « {} » gardé inactif : aucun mod ne l'enregistre",
                        getUUID(), id);
            }
        }
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
        // ADR-163 D2 : les variables gardées, si l'assembly en a déjà ; sinon, celles que son NBT portait restent.
        if (!inert && variables != null) {
            AssemblyTags.putVariables(stored, variables.persisted());
        }
        // ADR-167 D7 : les modificateurs posés, si l'assembly en a déjà une table ; sinon, ceux que son NBT portait.
        if (!inert && parameters != null) {
            AssemblyTags.putParameters(stored, parameters.modifiers());
        }
        // ADR-167 D7, D9 : les contrôleurs attachés par l'API, relus à la lecture.
        if (!inert) {
            AssemblyTags.putControllers(stored, attachedControllers);
        }
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
