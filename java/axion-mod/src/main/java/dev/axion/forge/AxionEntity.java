package dev.axion.forge;

import com.mojang.logging.LogUtils;
import dev.axion.api.assembly.CustomData;
import dev.axion.asset.Anatomy;
import dev.axion.asset.DefinitionCompiler;
import dev.axion.camera.ViewRig;
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
    private float[] wheelWear = new float[0];
    /** ADR-181 : l'index du matériau que le natif a rapporté sous chaque roue (serveur). */
    private int[] wheelMaterial = new int[0];

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

    /** Le carburant et la batterie (ADR-176 D3) ; {@code NaN} tant qu'on ne les a pas lus. */
    private double fuel = Double.NaN;
    private double battery = Double.NaN;
    /** L'usure des pneus gardée par la sauvegarde, par nom de roue, dans l'ordre où elle l'a écrite (ADR-176 D4). */
    private Map<String, Double> savedWheels = Map.of();
    /** Vrai une fois que l'usure de chaque roue a été lue de la sauvegarde, la definition connue. */
    private boolean wearInit;
    /** Vrai tant que le natif n'a pas reçu l'usure que Java tient ({@code SET_WEAR}). */
    private boolean wearPending;

    /** Le mouillé de la route sous le véhicule, de 0 à 1 (ADR-176 D5) ; il suit la pluie, au serveur, et ne se sauvegarde pas. */
    private double wetness;

    /** Vrai tant que le natif n'a pas reçu le mouillé que Java tient ({@code SET_WET}). */
    private boolean wetPending;
    /** L'énergie gardée par la sauvegarde, ou {@code null}. */
    private double[] savedEnergy;
    /** Vrai tant que le natif n'a pas reçu l'énergie que Java tient ({@code SET_ENERGY}). */
    private boolean energyPending;

    /** {@return le dernier rapport du natif sur le motopropulseur, ou {@code null} tant qu'il n'en a pas envoyé} */
    VehicleReport vehicleReport() {
        return vehicleReport;
    }

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
    /**
     * L'état du véhicule du plus récent snapshot reçu (client) : ce que la prédiction lit du carburant et du mouillé
     * (ADR-176 D3, D5). {@link #netState}, lui, n'est rempli qu'au serveur.
     */
    private AssemblyState.Vehicle receivedVehicle = AssemblyState.Vehicle.NONE;
    /** Les attaches qu'elle a posées, reçues du serveur (client, ADR-171 D7). */
    private AssemblyState.Links receivedLinks = AssemblyState.Links.NONE;

    /**
     * Les attaches reçues au client, par identifiant réseau de l'assembly qui les a posées (ADR-171 D7) : une assembly y
     * trouve celles que d'autres ont posées vers elle, pour ses sources {@code attach.*} et {@code socket.*}.
     */
    private static final Map<Integer, AssemblyState.Links> RECEIVED_LINKS =
            new java.util.concurrent.ConcurrentHashMap<>();

    private AssemblyState.Modifiers mirroredParameters;
    /** La masse du carburant sur laquelle le miroir a été composé (ADR-176 D3). */
    private double mirroredFuelMass = Double.NaN;
    private double predictedFuelMass = Double.NaN;

    /** Les variables du plus récent snapshot reçu (client), et celles dont le miroir est fait (ADR-167 D4). */
    private AssemblyState.VariableValues receivedVariables = AssemblyState.VariableValues.NONE;
    /** Ce que compte chaque capteur au plus récent snapshot (client, ADR-174 D5). */
    private AssemblyState.SensorCounts receivedSensors = AssemblyState.SensorCounts.NONE;

    /** {@return ce que compte chaque capteur : au serveur, le compte courant ; au client, celui du snapshot} */
    public AssemblyState.SensorCounts sensorState() {
        return level().isClientSide() ? receivedSensors : AssemblyState.SensorCounts.of(sensorCounts());
    }

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

    /**
     * Les attaches que cette assembly a posées (ADR-171 D7, R-692), vivantes ou suspendues : ce qui se sauvegarde et se
     * rétablit. Tenues par {@link AttachmentRuntime}, sur le thread autoritatif.
     */
    private final List<dev.axion.attach.AttachmentRecord> attachmentRecords = new ArrayList<>();

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

    /** Ce que cette instance lit de sa definition, pris à la création de son corps (serveur, ADR-175 D4). */
    private dev.axion.definition.HeldDefinition held;

    /**
     * {@return tout ce que l'instance lit de sa definition, ou {@code null} : inerte, ou pas encore compilée} Pris une
     * fois, d'une même compilation, et gardé : un rechargement ne le change pas (ADR-175 D4) ; seule la reliure à la
     * definition suivante le remplace (D5).
     */
    dev.axion.definition.HeldDefinition held() {
        if (level().isClientSide()) {
            // Le client ne tient pas de corps natif de la definition : il suit celle que son registre porte, et se
            // rafraîchit quand ce registre change.
            dev.axion.definition.DefinitionRegistry registry = AxionEntities.definitions();
            if (heldRegistry != registry) {
                heldRegistry = registry;
                held = null;
                if (!inert) {
                    // ADR-175 D3 : l'identifiant reçu à l'apparition peut avoir été remplacé ; le serveur relie sans le
                    // redire, le client suit le même remplacement.
                    definitionId = registry.current(definitionId);
                }
            }
        }
        if (held == null && !inert) {
            held = AxionEntities.held(definitionId).orElse(null);
        }
        return held;
    }

    /** Le registre dont {@link #held} a été pris, au client. */
    private dev.axion.definition.DefinitionRegistry heldRegistry;

    /**
     * {@return vrai si une definition rechargée remplace celle que l'instance tient, et qu'elle est compilée} Son contenu
     * a changé (empreinte), ou elle porte un autre identifiant — elle en remplace une autre —, ou son modèle a changé
     * (ADR-175 D3, D5). Une definition qui n'est plus chargée ne relie rien : l'instance garde ce qu'elle tient.
     */
    boolean needsRebind() {
        dev.axion.definition.HeldDefinition tenue = held;
        if (level().isClientSide() || inert || tenue == null) {
            return false;
        }
        Definition now = AxionEntities.definitions().byHash(dev.axion.definition.DefinitionIds.hash(definitionId))
                .orElse(null);
        // Une empreinte de modèle absente dit une compilation en cours : rien à relier encore.
        if (now == null || (now.sha256().equals(tenue.definition().sha256()) && now.id().equals(definitionId)
                && AxionEntities.assetStamp(now.id()).map(tenue.assetStamp()::equals).orElse(true))) {
            return false;
        }
        return AxionEntities.held(now.id()).filter(compiled -> compiled.definition().sha256().equals(now.sha256()))
                .isPresent();
    }

    /**
     * Relie l'assembly à la definition rechargée (serveur, ADR-175 D5) : son état vivant est repris dans son NBT, mené de
     * sa révision à celle de la definition par les migrations (D3), relu contre la nouvelle definition — corps et joints
     * rapprochés par nom (D2) —, puis ses occupants reprennent leur siège par nom. Son corps natif est retiré et recréé
     * par l'appelant ({@code AssemblyRuntime}), qui en restaure l'état comme à un chargement.
     */
    void rebind() {
        AuthoritativeThread.require();
        Seating old = seating();
        int[] seated = old == null ? new int[0] : entityData.get(SEATS).getIntArray(SEATS_KEY).clone();
        int from = definitionRevision();
        CompoundTag snapshot = new CompoundTag();
        addAdditionalSaveData(snapshot);
        // ADR-175 D3 : ce que l'instance garde vivant — variables, modificateurs — suit les mêmes renommages que le NBT.
        reboundFrom = from;
        held = null;
        nodesRestored = false;
        // ADR-178 : les poses gardées pour porter sont celles de la definition d'avant.
        carryHistory.clear();
        readAdditionalSaveData(snapshot);
        Seating fresh = seating();
        variables();
        parameters();
        if (old != null && fresh != null) {
            remapSeats(old, fresh, seated, from);
        }
        reboundFrom = NOT_REBOUND;
    }

    /**
     * Les occupants gardent leur siège par nom à la reliure (ADR-175 D5) : ceux dont la definition ne déclare plus le
     * siège prennent ensuite les premiers libres ; sans place, ils sont éjectés ({@link SeatEjection.Cause#NO_SEAT}).
     */
    private void remapSeats(Seating old, Seating fresh, int[] seated, int from) {
        java.util.Map<Integer, Entity> riders = new java.util.HashMap<>();
        getPassengers().forEach(passenger -> riders.put(passenger.getId(), passenger));
        // Une entrée sans passager (parti entre-temps) ne réserve aucun siège.
        int[] present = java.util.Arrays.stream(seated)
                .map(id -> riders.containsKey(id) ? id : SeatOccupancy.FREE).toArray();
        Map<String, String> names = revisions().successors("seats", from,
                old.seats().stream().map(Seating.Seat::name).toList());
        SeatOccupancy next = SeatPlacement.remap(old, fresh, present, names);
        List<Entity> lost = new ArrayList<>();
        for (int rank = 0; rank < present.length && rank < old.seats().size(); rank++) {
            Entity passenger = riders.get(present[rank]);
            if (passenger == null) {
                continue;
            }
            String name = old.seats().get(rank).name();
            int seat = next.seatOf(passenger.getId());
            if (seat == SeatOccupancy.FREE) {
                lost.add(passenger);
                continue;
            }
            // Le nom que le joueur garde pour son retour est désormais celui de ce siège.
            remember(passenger, fresh.seats().get(seat));
            if (!fresh.seats().get(seat).name().equals(names.get(name))) {
                LOGGER.info("AXION : assembly {}, reliure : le siège « {} » de {} n'existe plus ou est pris ; il est assis"
                        + " au siège « {} »", getUUID(), name, passenger.getName().getString(),
                        fresh.seats().get(seat).name());
            }
        }
        storeOccupancy(next);
        for (Entity passenger : lost) {
            eject(passenger, SeatEjection.Cause.NO_SEAT);
        }
    }

    /**
     * Fixe ce que l'instance lit de sa definition au résumé dont son corps natif vient d'être créé : le même, d'une même
     * compilation (ADR-175 D4). Sans effet si la compilation n'est plus celle de ce résumé.
     *
     * @param summary le résumé de la compilation dont le corps natif est créé
     * @return ce que l'instance tient, ou {@code null}
     */
    dev.axion.definition.HeldDefinition hold(DefinitionCompiler.Summary summary) {
        if (!inert && (held == null || held.summary() != summary)) {
            // Le corps natif naît de ce résumé : l'instance suit la compilation dont il vient, même si un rechargement
            // l'a remplacée entre-temps.
            held = AxionEntities.held(definitionId).filter(candidate -> candidate.summary() == summary).orElse(held);
        }
        return held();
    }

    /** {@return le document de la definition : celui que l'instance tient, sinon celui du registre, {@code null} : inerte} */
    /** {@return la révision et les migrations de la definition que l'instance lit} */
    dev.axion.definition.Revisions revisions() {
        Definition bound = document();
        return bound == null ? dev.axion.definition.Revisions.NONE : dev.axion.definition.Revisions.of(bound.root());
    }

    /** {@return le nom d'un paramètre d'avant la reliure en cours, mené par les migrations ; inchangé hors d'elle} */
    private String renamedParameter(String name) {
        if (reboundFrom == NOT_REBOUND) {
            return name;
        }
        for (dev.axion.definition.Revisions.Migration step : revisions().chain(reboundFrom)) {
            name = step.of("parameters").getOrDefault(name, name);
        }
        return name;
    }

    private Definition document() {
        dev.axion.definition.HeldDefinition tenue = held();
        return tenue != null ? tenue.definition() : inert ? null : AxionEntities.definitions().get(definitionId).orElse(null);
    }

    private DefinitionCompiler.Summary summaryHeld() {
        dev.axion.definition.HeldDefinition tenue = held();
        return tenue == null ? null : tenue.summary();
    }

    List<String> heldNodeNames() {
        dev.axion.definition.HeldDefinition tenue = held();
        return tenue == null ? List.of() : tenue.nodeNames();
    }

    /** {@return les sièges et les interactions de la definition, ou {@code null} : inerte, ou pas encore compilée} */
    private Seating seating() {
        return held() == null ? null : held().seating();
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
     * {@return ce que chaque siège règle de son occupant, dans l'ordre de la definition : l'overlay {@code seats}} Vide sans
     * siège, ou avant que la definition ne soit compilée.
     */
    public List<Seating.Occupant> seatOccupants() {
        Seating seating = seating();
        return seating == null ? List.of() : seating.seats().stream().map(Seating.Seat::occupant).toList();
    }

    /**
     * {@return ce que son siège règle d'un passager (ADR-177 D1) : sa pose, son échelle, son œil} Sans siège connu, ou
     * avant que la definition ne soit compilée, c'est l'occupant que rien ne règle.
     *
     * @param passenger l'entité
     */
    public Seating.Occupant occupantOf(Entity passenger) {
        Seating seating = seating();
        int seat = seating == null ? SeatOccupancy.FREE : occupancy(seating).seatOf(passenger.getId());
        return seating != null && seat >= 0 && seat < seating.seats().size()
                ? seating.seats().get(seat).occupant()
                : Seating.Occupant.DEFAULT;
    }

    /**
     * {@return de combien le passager est posé au-dessus du point de son siège} Assis, le décalage de monture de
     * Minecraft ; debout (ADR-177 D1), ses pieds sont au point du siège.
     *
     * @param passenger l'entité
     */
    public double riderOffset(Entity passenger) {
        return occupantOf(passenger).pose() == Seating.Pose.STAND ? 0.0 : passenger.getMyRidingOffset();
    }

    /**
     * Assis, ou debout : le client le règle, passager par passager, le temps de son dessin (ADR-177 D1).
     *
     * @return faux pendant le dessin d'un occupant que son siège met debout
     */
    @Override
    public boolean shouldRiderSit() {
        return !OccupantStance.standing();
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
        if (target.interaction() < 0) {
            return InteractionResult.PASS;
        }
        // ADR-174 D1 : les écouteurs des interactions jugent d'abord ; le premier refus l'emporte, et se dit au joueur.
        Seating.Interaction interaction = seating.interactions().get(target.interaction());
        String node = interactionNode(target.interaction());
        dev.axion.api.assembly.Assembly view = ForgeAxionApi.view(this);
        String action = interaction.action().key();
        ContentListeners.Refusal refused = ContentListeners.firstRefusal(ContentListeners.interactions(),
                listener -> listener.allow(view, node, player, action), "l'interaction " + action + " sur " + node);
        if (refused != null) {
            refuse(player, refused);
            return InteractionResult.CONSUME;
        }
        if (!play(interaction, player)) {
            return InteractionResult.PASS;
        }
        ContentListeners.notify(ContentListeners.interactions(),
                listener -> listener.interacted(view, node, player, action), "l'interaction " + action + " sur " + node);
        fireTriggers(dev.axion.definition.AnimationTriggers.Kind.INTERACT, node);
        return InteractionResult.CONSUME;
    }

    /** {@return le nom du node qui porte une interaction, tel que la definition le déclare ; vide sans} */
    private String interactionNode(int rank) {
        Definition definition = document();
        if (definition != null && definition.root().get("interaction") instanceof com.google.gson.JsonArray list
                && rank < list.size() && list.get(rank) instanceof com.google.gson.JsonObject declared
                && declared.get("node") != null) {
            return declared.get("node").getAsString();
        }
        return "";
    }

    /** Dit au joueur pourquoi son geste est refusé, et le journalise (ADR-174 D1). */
    private void refuse(Entity who, ContentListeners.Refusal refused) {
        if (who instanceof Player player) {
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable(refused.reason()), true);
        }
        LOGGER.info("AXION : assembly {}, geste de {} refusé par {} ({})", getUUID(), who.getName().getString(),
                refused.modId(), refused.reason());
    }

    /**
     * {@return vrai si l'interaction a eu son effet : son clip joué, ou sa variable posée (ADR-163 D2)} Un clip
     * refusé — R-200 — se journalise.
     */
    /**
     * Les interactions {@code attach} et {@code detach} d'un socket (ADR-171 D4). {@code attach} prend le socket
     * compatible et libre le plus proche d'une autre assembly, ou le bloc que vise le joueur pour un socket qui s'ancre ;
     * le joueur lit ce qui a été fait, ou pourquoi rien ne l'a été.
     */
    private boolean attachOrDetach(Seating.Interaction interaction, Player player) {
        AttachmentRuntime runtime = AttachmentRuntime.current();
        if (runtime == null) {
            return false;
        }
        if (interaction.action() == Seating.Action.DETACH) {
            boolean detached = runtime.detach(this, interaction.socket(), "detached");
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                    detached ? "axion.attach.detached" : "axion.attach.none_held", interaction.socket()), true);
            return detached;
        }
        net.minecraft.core.BlockPos target = null;
        if (player.pick(player.getBlockReach(), 1f, false) instanceof net.minecraft.world.phys.BlockHitResult hit
                && hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
            target = hit.getBlockPos();
        }
        dev.axion.api.attach.AttachResult result = runtime.interact(this, interaction.socket(), interaction.kinds(),
                interaction.distance(), target);
        player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                "axion.attach." + result.status().name().toLowerCase(java.util.Locale.ROOT), interaction.socket()),
                true);
        if (!result.attached()) {
            LOGGER.debug("AXION : assembly {}, attache refusée — {}", getUUID(), result.message());
        }
        return result.attached();
    }

    /**
     * Joue une interaction (serveur) : son action, puis sa liste d'actions, dans l'ordre, au même tick (ADR-174 D3).
     * La liste ne suit qu'une action qui a eu son effet ; {@code actions} n'en a pas d'autre.
     */
    private boolean play(Seating.Interaction interaction, Player player) {
        boolean done = interaction.action() == Seating.Action.ACTIONS
                || (interaction.action() == Seating.Action.EMIT_EVENT ? !interaction.actions().isEmpty()
                        : playAction(interaction, player));
        if (done && !interaction.actions().isEmpty()) {
            runActions(interaction.actions(), "interaction (" + player.getName().getString() + ")", player);
        }
        return done;
    }

    /**
     * Joue une interaction qu'un mod enregistre, ou {@code open_container} qu'AXION lui délègue (serveur, ADR-174 D6).
     * Une interaction que rien n'enregistre ne fait rien : la definition l'a déjà dit à son chargement.
     */
    private boolean playHandler(Seating.Interaction interaction, Player player) {
        dev.axion.definition.VehicleExtensions extensions = AxionEntities.definitions().extensions();
        java.util.Optional<dev.axion.definition.VehicleExtensions.Handler> found =
                interaction.action() == Seating.Action.OPEN_CONTAINER ? extensions.container()
                        : extensions.interaction(interaction.handler());
        if (found.isEmpty() || !(found.get().handler() instanceof dev.axion.api.assembly.InteractionHandler handler)) {
            return false;
        }
        Seating seating = seating();
        String node = interactionNode(seating == null ? -1 : seating.interactions().indexOf(interaction));
        dev.axion.api.assembly.Assembly view = ForgeAxionApi.view(this);
        return ContentListeners.call(handler, found.get().owner(), "l'interaction " + found.get().id() + " sur " + node,
                () -> handler.interact(view, node, player, interaction.parameters()), false);
    }

    /** L'état des nodes que les actions ont changé (serveur : l'autorité ; client : ce que le snapshot a porté). */
    private dev.axion.net.NodeStates nodeStates = dev.axion.net.NodeStates.NONE;
    /** Vrai une fois l'état gardé par nom relu contre la definition compilée (serveur). */
    private boolean nodesRestored;
    /** La révision d'avant, le temps d'une reliure à chaud ({@link #rebind}) ; {@link #NOT_REBOUND} hors d'elle. */
    private static final int NOT_REBOUND = -1;
    private int reboundFrom = NOT_REBOUND;

    /**
     * {@return l'état des nodes que les actions de l'assembly ont changé (ADR-174 D3)} Au serveur, l'état que son NBT
     * gardait est relu, par nom, dès que sa definition est compilée ; au client, celui du dernier snapshot.
     */
    public dev.axion.net.NodeStates nodeStates() {
        if (!nodesRestored && !level().isClientSide()) {
            Seating seating = seating();
            if (seating != null) {
                nodesRestored = true;
                java.util.Map<String, Integer> named = seating.actionNodes();
                java.util.Map<String, AssemblyTags.SavedNode> kept = AssemblyTags.nodes(stored);
                dev.axion.net.NodeStates restored = dev.axion.net.NodeStates.NONE;
                for (java.util.Map.Entry<String, AssemblyTags.SavedNode> saved : kept.entrySet()) {
                    Integer node = named.get(saved.getKey());
                    if (node != null) {
                        AssemblyTags.SavedNode state = saved.getValue();
                        Integer swapped = state.swap() == null ? null : named.get(state.swap());
                        restored = restored.with(new dev.axion.net.NodeStates.State(node, state.hidden(),
                                state.emissive(), state.color(), swapped == null ? -1 : swapped));
                    }
                }
                List<String> unnamed = kept.keySet().stream().filter(name -> !named.containsKey(name)).toList();
                if (!unnamed.isEmpty()) {
                    LOGGER.warn("AXION : E-7012 assembly {} — état gardé de nodes que {} ne nomme plus, sans effet : {}",
                            getUUID(), definitionId, unnamed);
                }
                // Un node de remplacement n'est pas dessiné à sa place tant qu'aucun swap_mesh ne l'y appelle ; ce que
                // le NBT gardait de lui l'emporte (il le garde aussi montré, pour ce cas).
                for (java.util.Map.Entry<String, Integer> target : seating.swapTargets().entrySet()) {
                    if (!kept.containsKey(target.getKey())) {
                        restored = restored.with(new dev.axion.net.NodeStates.State(target.getValue(), true,
                                Float.NaN, null));
                    }
                }
                nodeStates = restored;
            }
        }
        return nodeStates;
    }

    /**
     * Exécute des actions sur l'état des nodes (serveur, ADR-174 D3) : dans leur ordre, toutes au même tick ; l'état
     * part aux joueurs avec le snapshot suivant, et se sauvegarde.
     *
     * @param actions les actions
     * @param writer qui les joue, pour le journal
     */
    void runActions(List<Seating.NodeAction> actions, String writer) {
        runActions(actions, writer, null);
    }

    /**
     * Exécute des actions (serveur, ADR-174 D3, D6), comme {@link #runActions(List, String)}, une action de mod
     * comprise, à son rang.
     *
     * @param actions les actions
     * @param writer qui les joue, pour le journal
     * @param actor le joueur à l'origine de la liste, {@code null} sans
     */
    void runActions(List<Seating.NodeAction> actions, String writer, Player actor) {
        dev.axion.net.NodeStates next = nodeStates();
        try {
            for (Seating.NodeAction action : actions) {
                if (action.kind() == Seating.NodeActionKind.ADDON) {
                    // L'action d'un mod voit l'état des nodes que les précédentes ont laissé, et peut le changer.
                    nodeStates = next;
                    runHandler(action, writer, actor);
                    next = nodeStates;
                    continue;
                }
                dev.axion.net.NodeStates.State current = next.of(action.node());
                boolean hidden = current != null && current.hidden();
                float emissive = current == null ? Float.NaN : current.emissive();
                float[] color = current == null ? null : current.color();
                if (action.kind() == Seating.NodeActionKind.EMIT) {
                    emitEvent(action.name(), action.value(), writer);
                    continue;
                }
                if (action.kind() == Seating.NodeActionKind.REFUEL) {
                    refuel(action.value());
                    continue;
                }
                if (action.kind() == Seating.NodeActionKind.SET_WEAR) {
                    setWearByName(action.name().isEmpty() ? null : action.name(), action.value());
                    continue;
                }
                int swapped = current == null ? -1 : current.swapped();
                switch (action.kind()) {
                    case HIDE -> hidden = true;
                    case SHOW -> hidden = false;
                    case MATERIAL -> {
                        if (!Float.isNaN(action.emissive())) {
                            emissive = action.emissive();
                        }
                        if (action.color() != null) {
                            color = action.color();
                        }
                    }
                    case EMIT, REFUEL, SET_WEAR -> {
                        // Traités plus haut : un événement, un plein ou un pneu changé ne changent aucun node.
                    }
                    case SWAP -> {
                        // Le remplaçant d'avant retourne à l'ombre ; le nouveau se montre, l'original se cache.
                        if (swapped >= 0) {
                            next = next.with(visibility(next, swapped, true));
                        }
                        swapped = action.with();
                        hidden = swapped >= 0;
                        if (swapped >= 0) {
                            next = next.with(visibility(next, swapped, false));
                        }
                    }
                }
                next = next.with(new dev.axion.net.NodeStates.State(action.node(), hidden, emissive, color, swapped));
            }
        } catch (IllegalArgumentException refused) {
            // Plus de nodes touchés que le snapshot n'en porte : rien ne change, et le journal le dit.
            logRefusals(List.of("actions de " + writer + " refusées — " + refused.getMessage()));
            return;
        }
        nodeStates = next;
    }

    /** Les déclencheurs des animations de la definition, relus quand elle change (ADR-174 D4). */
    private dev.axion.definition.AnimationTriggers animationTriggers = dev.axion.definition.AnimationTriggers.NONE;
    private Definition triggersOf;
    /** Les variables que des déclencheurs {@code var:} suivent : non nulle au tick d'avant. */
    private final java.util.Map<String, Boolean> triggerVariables = new java.util.HashMap<>();

    /** {@return les déclencheurs des animations que la definition de l'assembly déclare (ADR-174 D4)} */
    public dev.axion.definition.AnimationTriggers animationTriggers() {
        Definition definition = document();
        if (definition == null) {
            return dev.axion.definition.AnimationTriggers.NONE;
        }
        if (definition != triggersOf) {
            animationTriggers = dev.axion.definition.AnimationTriggers.of(definition.root());
            triggersOf = definition;
            triggerVariables.clear();
        }
        return animationTriggers;
    }

    /**
     * Joue le clip de chaque animation que ce fait déclenche (serveur, ADR-174 D4), depuis son début. Un clip refusé —
     * R-200 — se journalise.
     *
     * @param kind ce qui a eu lieu
     * @param key ce qu'il nomme
     */
    void fireTriggers(dev.axion.definition.AnimationTriggers.Kind kind, String key) {
        if (level().isClientSide()) {
            return;
        }
        for (dev.axion.definition.AnimationTriggers.Trigger trigger : animationTriggers().matching(kind, key)) {
            try {
                animation.setSpeed(trigger.layer(), 1f);
                animation.play(trigger.clip(), trigger.layer(), 1f, 0f, trigger.mask());
            } catch (IllegalArgumentException refused) {
                LOGGER.warn("AXION : assembly {}, déclencheur de l'animation {} refusé — {}", getUUID(),
                        trigger.animation(), refused.getMessage());
            }
        }
    }

    /**
     * Les déclencheurs {@code var:} (serveur, ADR-174 D4) : une variable qui devient non nulle joue le clip, revenue à 0
     * elle l'arrête. Relevé une fois par tick, après les entrées.
     */
    private void watchTriggerVariables() {
        dev.axion.definition.AnimationTriggers triggers = animationTriggers();
        AssemblyVariables held = triggers.triggers().isEmpty() ? null : variables();
        if (held == null) {
            return;
        }
        for (String name : triggers.watchedVariables()) {
            // ADR-174 D2 : var:<nom>, ou var:<nom>.<siège> pour une variable par siège.
            dev.axion.definition.Variables.Reading reading = held.declared().resolve(name, held.seats());
            if (reading == null) {
                continue;
            }
            boolean on = (reading.seat() < 0 ? held.get(reading.variable())
                    : held.get(reading.variable(), reading.seat())) != 0.0;
            Boolean before = triggerVariables.put(name, on);
            if (before == null || before == on) {
                continue;
            }
            for (dev.axion.definition.AnimationTriggers.Trigger trigger
                    : triggers.matching(dev.axion.definition.AnimationTriggers.Kind.VAR, name)) {
                try {
                    if (on) {
                        animation.setSpeed(trigger.layer(), 1f);
                        animation.play(trigger.clip(), trigger.layer(), 1f, 0f, trigger.mask());
                    } else {
                        animation.stop(trigger.layer(), 0f);
                    }
                } catch (IllegalArgumentException refused) {
                    LOGGER.warn("AXION : assembly {}, déclencheur de l'animation {} refusé — {}", getUUID(),
                            trigger.animation(), refused.getMessage());
                }
            }
        }
    }

    /** Les événements que la definition déclare, relus quand elle change. */
    private dev.axion.definition.ContentEvents contentEvents = dev.axion.definition.ContentEvents.NONE;
    private Definition eventsOf;

    /** {@return les événements de contenu que la definition de l'assembly déclare (ADR-174 D2)} */
    public dev.axion.definition.ContentEvents contentEvents() {
        Definition definition = document();
        if (definition == null) {
            return dev.axion.definition.ContentEvents.NONE;
        }
        if (definition != eventsOf) {
            contentEvents = dev.axion.definition.ContentEvents.of(definition.root());
            eventsOf = definition;
        }
        return contentEvents;
    }

    /**
     * Émet un événement de contenu (serveur, ADR-174 D2) : les écouteurs des mods le reçoivent tout de suite, et les
     * joueurs que sa déclaration désigne avec le snapshot de ce tick.
     *
     * @param event son nom
     * @param value sa valeur
     * @param writer qui l'émet, pour le journal
     * @throws IllegalArgumentException si la definition ne le déclare pas
     */
    void emitEvent(String event, double value, String writer) {
        AuthoritativeThread.require();
        dev.axion.definition.ContentEvents declared = contentEvents();
        int rank = declared.indexOf(event);
        if (rank < 0) {
            throw new IllegalArgumentException("événement « " + event + " » inconnu de " + definitionId + " ; elle"
                    + " déclare : " + String.join(", ", declared.names()));
        }
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("événement « " + event + " » : valeur " + value + " non finie");
        }
        // ADR-174, axe Diagnostic : /axion events montre les derniers.
        ContentEventLog.record(new ContentEventLog.Entry(level().getGameTime(), getUUID(), definitionId, event, value,
                writer));
        dev.axion.api.assembly.Assembly view = ForgeAxionApi.view(this);
        ContentListeners.notify(ContentListeners.events(), listener -> listener.onEvent(view, event, value),
                "l'événement " + event + " de " + writer);
        fireTriggers(dev.axion.definition.AnimationTriggers.Kind.EVENT, event);
        dev.axion.definition.ContentEvents.Replicate replicate = declared.declared().get(rank).replicate();
        if (replicate != dev.axion.definition.ContentEvents.Replicate.NONE) {
            dev.axion.forge.net.AxionNetwork.queueEvent(this, rank, value,
                    replicate == dev.axion.definition.ContentEvents.Replicate.OCCUPANTS);
        }
    }

    /** Ce qui est dans chaque capteur déclaré (ADR-174 D5) : une assembly, ou une entité, par une clé. */
    private java.util.List<java.util.Set<Long>> sensorOccupants = java.util.List.of();

    /**
     * Le corps se crée : ses capteurs repartent vides (serveur, ADR-174 D5).
     *
     * @param count le nombre de capteurs que la definition déclare
     */
    void resetSensors(int count) {
        java.util.List<java.util.Set<Long>> fresh = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            fresh.add(new java.util.LinkedHashSet<>());
        }
        sensorOccupants = fresh;
    }

    /**
     * Quelque chose entre dans un capteur, ou en sort (serveur, ADR-174 D5) : le compte change, et l'événement
     * {@code sensor.<nom>.enter} ou {@code sensor.<nom>.exit} part, de valeur le compte après.
     *
     * @param sensor le rang du capteur
     * @param enter vrai pour une entrée
     * @param other ce qui entre ou sort : une assembly ou une entité, par une clé
     */
    void sensorChanged(int sensor, boolean enter, long other) {
        dev.axion.definition.Sensors declared = dev.axion.definition.Sensors.of(
                java.util.Optional.ofNullable(document()).map(Definition::root).orElse(new com.google.gson.JsonObject()));
        if (sensor < 0 || sensor >= sensorOccupants.size() || sensor >= declared.declared().size()) {
            return;
        }
        java.util.Set<Long> inside = sensorOccupants.get(sensor);
        if (!(enter ? inside.add(other) : inside.remove(other))) {
            return;
        }
        dev.axion.definition.Sensors.Sensor named = declared.declared().get(sensor);
        try {
            emitEvent(enter ? named.enterEvent() : named.exitEvent(), inside.size(), "capteur " + named.name());
        } catch (IllegalArgumentException refused) {
            LOGGER.warn("AXION : assembly {}, capteur {} — {}", getUUID(), named.name(), refused.getMessage());
        }
    }

    /**
     * {@return ce qui est dans un capteur déclaré, dans l'ordre d'entrée (serveur, ADR-174 D5)} Une entité qui n'est
     * plus chargée n'y figure pas.
     *
     * @param sensor le nom du capteur
     * @throws IllegalArgumentException si la definition ne le déclare pas
     */
    List<Entity> sensorOccupants(String sensor) {
        dev.axion.definition.Sensors declared = dev.axion.definition.Sensors.of(
                java.util.Optional.ofNullable(document()).map(Definition::root).orElse(new com.google.gson.JsonObject()));
        int rank = declared.indexOf(sensor);
        if (rank < 0) {
            throw new IllegalArgumentException("capteur « " + sensor + " » inconnu de " + definitionId);
        }
        List<Entity> inside = new ArrayList<>();
        if (rank < sensorOccupants.size()) {
            for (long key : sensorOccupants.get(rank)) {
                // Une entité par son identifiant réseau ; une assembly par son handle, dont l'index est le sien.
                Entity found = level().getEntity(key < 0 ? (int) (-1L - key) : (int) (key >>> 32));
                if (found != null) {
                    inside.add(found);
                }
            }
        }
        return inside;
    }

    /** {@return ce que compte chaque capteur déclaré, dans l'ordre de la definition (serveur, ADR-174 D5)} */
    public int[] sensorCounts() {
        int[] counts = new int[sensorOccupants.size()];
        for (int i = 0; i < counts.length; i++) {
            counts[i] = sensorOccupants.get(i).size();
        }
        return counts;
    }

    /** {@return l'état d'un node, caché ou montré, le reste gardé} */
    private static dev.axion.net.NodeStates.State visibility(dev.axion.net.NodeStates states, int node, boolean hidden) {
        dev.axion.net.NodeStates.State current = states.of(node);
        return current == null ? new dev.axion.net.NodeStates.State(node, hidden, Float.NaN, null)
                : new dev.axion.net.NodeStates.State(node, hidden, current.emissive(), current.color(),
                        current.swapped());
    }

    /**
     * Joue l'interaction de ce rang pour un joueur, comme son clic le ferait une fois jugé (serveur) : ce qu'un essai
     * appelle sans viser son node.
     *
     * @param rank le rang de l'interaction dans la definition
     * @param player le joueur
     * @return vrai si elle a eu son effet
     */
    boolean playInteraction(int rank, Player player) {
        Seating seating = seating();
        return seating != null && rank >= 0 && rank < seating.interactions().size()
                && play(seating.interactions().get(rank), player);
    }

    /** Une action qu'un mod enregistre (ADR-174 D6) ; que rien n'enregistre, elle ne fait rien. */
    private void runHandler(Seating.NodeAction action, String writer, Player actor) {
        java.util.Optional<dev.axion.definition.VehicleExtensions.Handler> found =
                AxionEntities.definitions().extensions().action(action.name());
        if (found.isEmpty() || !(found.get().handler() instanceof dev.axion.api.assembly.ActionHandler handler)) {
            return;
        }
        dev.axion.api.assembly.Assembly view = ForgeAxionApi.view(this);
        ContentListeners.call(handler, found.get().owner(), "l'action " + action.name() + " de " + writer, () -> {
            handler.perform(view, action.parameters(), actor);
            return null;
        }, null);
    }

    private boolean playAction(Seating.Interaction interaction, Player player) {
        if (interaction.action() == Seating.Action.ADDON || interaction.action() == Seating.Action.OPEN_CONTAINER) {
            return playHandler(interaction, player);
        }
        if (interaction.action() == Seating.Action.SET_VARIABLE) {
            AssemblyVariables held = interaction.variable() < 0 ? null : variables();
            if (held == null) {
                return false;
            }
            // ADR-174 D2 : une variable par siège s'écrit au siège de qui clique ; debout, il n'en a pas.
            int seat = held.declared().get(interaction.variable()).perSeat() ? seatOf(player) : 0;
            if (seat < 0) {
                logRefusals(List.of("variable par siège « " + held.declared().get(interaction.variable()).name()
                        + " » : " + player.getName().getString() + " n'est assis à aucun siège"));
                return false;
            }
            try {
                held.set(interaction.variable(), seat, interaction.value(),
                        "interaction (" + player.getName().getString() + ")");
            } catch (IllegalArgumentException refused) {
                // ADR-167 D2 : ce que la variable règle sortirait de ses bornes ; rien ne change.
                logRefusals(List.of(refused.getMessage() + " (interaction de " + player.getName().getString() + ")"));
                return false;
            }
            return true;
        }
        if ((interaction.action() == Seating.Action.ATTACH || interaction.action() == Seating.Action.DETACH)
                && interaction.socket() != null) {
            return attachOrDetach(interaction, player);
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
            AssemblyVariables fresh = new AssemblyVariables(seating.variables(),
                    seating.seats().stream().map(Seating.Seat::name).toList());
            List<String> refusals = new ArrayList<>();
            if (variables != null && reboundFrom != NOT_REBOUND) {
                refusals.addAll(fresh.adopt(variables,
                        revisions().successors("variables", reboundFrom, variables.names()),
                        revisions().successors("seats", reboundFrom, variables.seats())));
            } else if (variables != null) {
                refusals.addAll(fresh.adopt(variables));
            } else {
                refusals.addAll(fresh.restore(AssemblyTags.variables(stored)));
                // ADR-174 D2 : celles de chaque siège, par son nom.
                refusals.addAll(fresh.restoreSeats(AssemblyTags.seatVariables(stored)));
            }
            for (String refusal : refusals) {
                LOGGER.warn("AXION : assembly {} — {}", getUUID(), refusal);
            }
            // ADR-175 D2 : ce que le NBT garde et que cette definition ne reprend pas se dit, une fois par definition.
            List<String> unclaimed = fresh.unclaimed(AssemblyTags.variables(stored), AssemblyTags.seatVariables(stored));
            if (!unclaimed.isEmpty()) {
                LOGGER.warn("AXION : E-7012 assembly {} — variables gardées que {} ne reprend pas, sans effet : {}",
                        getUUID(), definitionId, unclaimed);
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
            double fuelMass = receivedFuelMass();
            if (parameters == null || parameters.tuning() != tuning || mirroredParameters != receivedParameters
                    || mirroredVariables != receivedVariables || mirroredFuelMass != fuelMass) {
                double[] variables = new double[receivedVariables.count()];
                for (int i = 0; i < variables.length; i++) {
                    variables[i] = receivedVariables.value(i);
                }
                parameters = dev.axion.entity.AssemblyParameters.mirror(tuning, receivedParameters, variables);
                parameters.engineCause(dev.axion.definition.EnergySpec.MASS_CAUSE,
                        dev.axion.api.assembly.Parameters.MASS, fuelMass);
                mirroredParameters = receivedParameters;
                mirroredVariables = receivedVariables;
                mirroredFuelMass = fuelMass;
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
                    ? fresh.adopt(parameters, this::renamedParameter)
                    : fresh.restore(AssemblyTags.parameters(stored), dev.axion.entity.AssemblyParameters.SAVE);
            for (String line : lines) {
                LOGGER.warn("AXION : E-7011 assembly {} — {}", getUUID(), line);
            }
            parameters = fresh;
            if (!Double.isNaN(fuel)) {
                syncFuelMass();
            }
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
        double fuelMass = receivedFuelMass();
        if (predictedParameters == null || predictedParameters.tuning() != tuning
                || predictedModifiers != receivedParameters || !Arrays.equals(predictedVariables, variables)
                || predictedFuelMass != fuelMass) {
            predictedParameters = dev.axion.entity.AssemblyParameters.mirror(tuning, receivedParameters, variables);
            predictedParameters.engineCause(dev.axion.definition.EnergySpec.MASS_CAUSE,
                    dev.axion.api.assembly.Parameters.MASS, fuelMass);
            predictedModifiers = receivedParameters;
            predictedVariables = variables.clone();
            predictedFuelMass = fuelMass;
        }
        return predictedParameters;
    }

    /**
     * {@return la masse du carburant que le serveur a envoyée, en kg, au cran du serveur (client, ADR-176 D3)} Le natif
     * prédit la reçoit comme celui du serveur : la masse à sec plus ce que le réservoir porte ; sans réservoir ou sans
     * masse par litre, zéro.
     */
    private double receivedFuelMass() {
        dev.axion.definition.EnergySpec spec = energySpec();
        return spec.unitMass() > 0f ? spec.fuelMassOfRatio(receivedVehicle.fuelRatio()) : 0.0;
    }

    private dev.axion.definition.Tuning tuning() {
        dev.axion.definition.Tuning compiled = held() == null ? null : held().tuning();
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
        Definition definition = document();
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
    /** La vue du véhicule prédit au début de son dernier tick (client, ADR-168 D6) ; {@code null} hors prédiction. */
    private dev.axion.entity.VehicleSnapshot predictedView;

    /**
     * Pose la vue du véhicule prédit au début de son dernier tick (client, ADR-168 D6) : {@code VehicleControl.state()}
     * la rend.
     *
     * @param view la vue ; {@code null} quand la prédiction se ferme
     */
    public void predictView(dev.axion.entity.VehicleSnapshot view) {
        predictedView = view;
    }

    /** {@return la vue du véhicule prédit au début de son dernier tick (client), {@code null} hors prédiction} */
    public dev.axion.entity.VehicleSnapshot predictedView() {
        return predictedView;
    }

    /** {@return les contrôleurs de l'instance que des mods enregistrent, dans leur ordre ; au client aussi} */
    public List<dev.axion.definition.VehicleExtensions.Controller> runnableControllers() {
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
                    known ? wheelSlipLong[i] : 0.0, known ? wheelSlipLat[i] : 0.0, wheelWear(i), wheelSurface(i)));
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
                table.tuning().names(), parameterValues).withSources(sources());
    }

    /**
     * {@return les sources procédurales de l'assembly à cet instant, lues au serveur (ADR-173 D1)} : la trame se
     * construit à la première source lue, de l'état que le serveur réplique au début du tick, de toutes ses
     * variables, et du monde au point de l'assembly. Un nom inconnu lève {@link IllegalArgumentException} ; sans
     * definition, toute lecture lève {@link IllegalStateException}.
     */
    public java.util.function.ToDoubleFunction<String> sources() {
        return sources(this::sourceFrame);
    }

    /**
     * {@return les sources procédurales de l'assembly, lues dans la trame que {@code frame} construit} : la même
     * résolution des noms au serveur et au client qui prédit (ADR-173 D1).
     *
     * @param frame construit la trame, à la première source lue
     */
    public java.util.function.ToDoubleFunction<String> sources(
            java.util.function.Supplier<ProceduralTracks.Frame> frame) {
        Definition definition = document();
        if (definition == null) {
            return name -> {
                throw new IllegalStateException("source « " + name + " » : l'assembly " + getUUID()
                        + " n'a pas de definition chargée");
            };
        }
        if (sourceReader == null || sourceDefinition != definition) {
            sourceReader = new dev.axion.render.SourceReader(definition.root());
            sourceDefinition = definition;
        }
        return sourceReader.at(frame);
    }

    private dev.axion.render.SourceReader sourceReader;
    private Definition sourceDefinition;
    /** L'angle des roues pour les sources {@code wheel.<n>.spin_angle} lues au serveur, intégré à chaque lecture. */
    private final ProceduralTracks.Spin sourceSpin = new ProceduralTracks.Spin();

    /** La trame des sources au serveur : l'état répliqué du début du tick, toutes les variables, le monde ici. */
    private ProceduralTracks.Frame sourceFrame() {
        AssemblyState state = netState();
        List<Interpolation.WheelPose> wheels = state == null ? List.of() : Interpolation.wheelsOf(state);
        Interpolation.VehiclePose vehicle = state == null ? Interpolation.VehiclePose.NONE
                : Interpolation.vehicleOf(state);
        AssemblyVariables held = variables();
        double[] values = held == null ? new double[0] : held.values();
        // ADR-174 D2 : chaque siège d'une variable par siège ; au serveur, aucun lecteur n'est assis.
        double[][] seats = new double[values.length][];
        for (int i = 0; i < values.length; i++) {
            if (held.declared().get(i).perSeat()) {
                seats[i] = held.seatValues(i);
            }
        }
        Level here = level();
        BlockPos at = BlockPos.containing(getX(), getY(), getZ());
        long tick = here.getGameTime();
        return new ProceduralTracks.Frame(wheels, sourceSpin.advance(tick, wheels).clone(), vehicle,
                AssemblyState.VariableValues.of(values, seats), seatsOccupied(),
                tick * dev.axion.net.ServerClock.TICK_MILLIS / 1000.0, ProceduralTracks.worldTime(here.getDayTime()),
                here.getBrightness(net.minecraft.world.level.LightLayer.SKY, at), here.getRainLevel(1f),
                socketReadings(), -1, sensorState());
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
        lastCorrectionTick = tick;
        long applied = driverSequence();
        int queued = applied < 0 ? -1 : Math.max(0, Math.min(255, driverQueueDepth()));
        return new dev.axion.net.CorrectionMessage(tick, applied, queued, getId(), netParameters.tick(),
                correctionVariables(), states);
    }

    /**
     * Ticks entre deux corrections d'un véhicule endormi (ADR-168 D4, précision 6e) : une par seconde. Sans elles, le
     * conducteur d'un véhicule garé n'avait aucun acquittement : son client n'ouvrait son monde prédit qu'au premier
     * appui, un aller-retour trop tard (T-415), et ne voyait pas sa file d'entrées (D3).
     */
    public static final int SLEEPING_CORRECTION_TICKS = 20;

    /** Le tick du serveur de la dernière correction de ce véhicule. */
    private long lastCorrectionTick = Long.MIN_VALUE / 2;

    /**
     * {@return vrai si ce véhicule, endormi, doit à son conducteur une correction à ce tick : la précédente date d'au
     * moins {@link #SLEEPING_CORRECTION_TICKS}}
     *
     * @param tick le tick du serveur
     */
    public boolean sleepingCorrectionDue(long tick) {
        return tick - lastCorrectionTick >= SLEEPING_CORRECTION_TICKS;
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
        return receiveInput(sender, seat, sequence, input, false);
    }

    /**
     * Reçoit les entrées d'un passager, dont le client dit s'il règle la file de son siège (ADR-168 D3).
     *
     * @param sender le passager qui les envoie
     * @param seat rang du siège annoncé
     * @param sequence leur séquence, non signée : celle du canal
     * @param input les entrées, telles que reçues
     * @param paced vrai si son client règle la profondeur de la file
     * @return la faute à journaliser, {@code null} sans
     */
    public String receiveInput(Entity sender, int seat, long sequence, VehicleInput input, boolean paced) {
        Seating seating = seating();
        if (seating == null || seat < 0 || seat >= seating.seats().size()
                || sender.getVehicle() != this || seatOf(sender) != seat) {
            return "entrées pour un siège que le joueur n'occupe pas";
        }
        Seating.Seat declared = seating.seats().get(seat);
        QueuedInput queued = new QueuedInput(input.sanitized(declared),
                "siège " + declared.name() + " (" + sender.getName().getString() + ")");
        if (!seatInputs.computeIfAbsent(seat, key -> new SeatInputs()).queue.offer(sequence, queued, paced)) {
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
            // Précision 6e : un véhicule endormi n'envoie pas de correction ; sa file rattrape ce qui ne le change pas —
            // tant que l'entrée appliquée vaut encore : muette au-delà de net.extrapolation_max_ms, elle ne vaut plus rien,
            // et des entrées qui lui sont égales changeraient l'état.
            dev.axion.seat.InputQueue.Step<QueuedInput> step = inputs.queue.next(sleeping && !inputSilent(seat));
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
     * {@return la cadence des entrées d'un siège au serveur (ADR-168 D3) : ses ticks sur une file vide, ses entrées
     * sautées pour rattraper, puis la profondeur trouvée à chaque tick ({@link dev.axion.seat.InputQueue#depths}) ;
     * {@code null} si le siège n'a jamais reçu d'entrée}
     *
     * @param seat le rang du siège
     */
    public long[] seatCadence(int seat) {
        SeatInputs inputs = seatInputs.get(seat);
        if (inputs == null) {
            return null;
        }
        long[] depths = inputs.queue.depths();
        long[] cadence = new long[2 + depths.length];
        cadence[0] = inputs.queue.starvedTotal();
        cadence[1] = inputs.queue.skippedTotal();
        System.arraycopy(depths, 0, cadence, 2, depths.length);
        return cadence;
    }

    /**
     * {@return les entrées que la file du siège {@code driver} tenait à ce tick, avant d'en prendre une (ADR-168 D3,
     * révisé le 2026-11-14) ; -1 sans} La correction la rend au conducteur, qui règle sur elle la cadence de ses envois.
     */
    public int driverQueueDepth() {
        Seating seating = seating();
        if (seating == null) {
            return -1;
        }
        for (int seat = 0; seat < seating.seats().size(); seat++) {
            if (seating.seats().get(seat).driver()) {
                SeatInputs inputs = seatInputs.get(seat);
                return inputs == null ? -1 : inputs.queue.lastDepth();
            }
        }
        return -1;
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
        DriverInput base = driverInputWithoutIgnition();
        int variable = ignitionVariable();
        AssemblyVariables held = variable < 0 ? null : variables();
        if (held == null) {
            return base;
        }
        // ADR-176 D1 : le contact est la valeur de la variable que le démarreur lit, que le conducteur soit là ou non :
        // sans conducteur, le moteur continue de tourner, et un mod ou l'API peut le couper.
        DriverInput carried = base == null ? DriverInput.ABSENT : base;
        return new DriverInput(carried.input().withIgnition(held.get(variable) != 0.0), carried.present());
    }

    /** {@return le rang de la variable que le démarreur lit comme contact (ADR-176 D1), -1 sans démarreur} */
    public int ignitionVariable() {
        dev.axion.definition.HeldDefinition tenue = held();
        return tenue == null ? -1 : tenue.ignition();
    }

    private DriverInput driverInputWithoutIgnition() {
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
        return java.util.Optional.ofNullable(document()).map(definition -> GearRange.of(definition.root()))
                .orElse(GearRange.NONE);
    }

    /**
     * {@return d'où part le rayon du serveur : l'œil de Minecraft, et pour un passager de cette assembly l'œil que son
     * siège règle (ADR-177 D1), celui d'où son client regarde à la première personne}
     */
    private Vec3 interactionEye(Player player) {
        Vec3 vanilla = player.getEyePosition();
        if (player.getVehicle() != this) {
            return vanilla;
        }
        SeatPlacement.Pose pose = seatPose(seatOf(player));
        if (pose == null) {
            return vanilla;
        }
        double[] eye = ViewRig.occupantEye(occupantOf(player), pose, new double[] {vanilla.x, vanilla.y, vanilla.z});
        return new Vec3(eye[0], eye[1], eye[2]);
    }

    /** {@return ce que vise le joueur, d'après le rayon du serveur, ou {@code null}} */
    private Seating.Target aim(Player player, Seating seating) {
        DefinitionCompiler.Summary summary = summaryHeld();
        if (summary == null) {
            return null;
        }
        Vec3 eye = interactionEye(player);
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
        // ADR-174 D1 : les écouteurs des sièges jugent la montée.
        String name = seating.seats().get(seat).name();
        dev.axion.api.assembly.Assembly view = ForgeAxionApi.view(this);
        ContentListeners.Refusal refused = ContentListeners.firstRefusal(ContentListeners.seats(),
                listener -> listener.allowEnter(view, name, passenger), "la montée au siège " + name);
        if (refused != null) {
            refuse(passenger, refused);
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
        // R-1742 : ce qui ne revient pas comme il était se dit — un siège que la definition ne déclare plus, ou pris.
        String cause = seating.seats().stream().anyMatch(declared -> declared.name().equals(seatName))
                ? "occupé" : "absent de la definition";
        if (seat == SeatOccupancy.FREE) {
            LOGGER.info("AXION : {} ne retrouve pas son siège « {} » de {} ({}) : aucun siège libre, il reste à côté",
                    passenger.getName().getString(), seatName, definitionId, cause);
            return false;
        }
        mountingSeat = seat;
        try {
            boolean seated = passenger.startRiding(this, true) && seatOf(passenger) == seat;
            String now = seating.seats().get(seat).name();
            if (seated && !now.equals(seatName)) {
                LOGGER.info("AXION : {} ne retrouve pas son siège « {} » de {} ({}) : assis au siège « {} »",
                        passenger.getName().getString(), seatName, definitionId, cause, now);
            }
            return seated;
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
            String name = seating.seats().get(seat).name();
            dev.axion.api.assembly.Assembly view = ForgeAxionApi.view(this);
            ContentListeners.notify(ContentListeners.seats(), listener -> listener.entered(view, name, passenger),
                    "la montée au siège " + name);
            fireTriggers(dev.axion.definition.AnimationTriggers.Kind.SEAT_ENTER, name);
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
        watchTriggerVariables();
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
        DefinitionCompiler.Summary summary = summaryHeld();
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
        Seating seating = seating();
        if (seating != null && seat >= 0 && seat < seating.seats().size()) {
            // ADR-174 D1 : une descente voulue se juge ; une éjection, non (R-712).
            String name = seating.seats().get(seat).name();
            dev.axion.api.assembly.Assembly view = ForgeAxionApi.view(this);
            ContentListeners.Refusal refused = ContentListeners.firstRefusal(ContentListeners.seats(),
                    listener -> listener.allowExit(view, name, passenger), "la descente du siège " + name);
            if (refused != null) {
                exitRefusedUntil.put(id, tickCount + EXIT_RETRY_TICKS);
                refuse(passenger, refused);
                positionRider(passenger);
                return false;
            }
        }
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
            if (seat < seating.seats().size()) {
                String name = seating.seats().get(seat).name();
                String cause = ejecting ? "ejected" : "dismounted";
                dev.axion.api.assembly.Assembly view = ForgeAxionApi.view(this);
                ContentListeners.notify(ContentListeners.seats(),
                        listener -> listener.exited(view, name, passenger, cause), "la descente du siège " + name);
                fireTriggers(dev.axion.definition.AnimationTriggers.Kind.SEAT_EXIT, name);
            }
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
        move.accept(passenger, pose.x(), pose.y() + riderOffset(passenger), pose.z());
    }

    /**
     * {@return la pose monde d'un siège, ou {@code null} : siège inconnu, definition pas encore
     * compilée, ou corps dont la pose n'est pas connue}
     *
     * @param seat rang du siège
     */
    public SeatPlacement.Pose seatPose(int seat) {
        Seating seating = seating();
        DefinitionCompiler.Summary summary = summaryHeld();
        if (seating == null || summary == null || seat < 0 || seat >= seating.seats().size()) {
            return null;
        }
        return SeatPlacement.place(summary.anatomy(), seating.seats().get(seat).node(), bodyPoses(summary));
    }

    /** {@return les attaches qu'elle a posées, vivantes ou suspendues (ADR-171 D7) : la table que le serveur tient} */
    List<dev.axion.attach.AttachmentRecord> attachmentRecords() {
        return attachmentRecords;
    }

    /** {@return les attaches qu'elle a posées, telles que le client les a reçues (ADR-171 D7)} */
    public AssemblyState.Links receivedLinks() {
        return receivedLinks;
    }

    /**
     * {@return son ensemble au client (ADR-171 D7, CAP-37) : elle et les assemblies que les attaches reçues lui
     * relient, de proche en proche, présentes dans son niveau, en ordre d'identifiant réseau}
     */
    public List<AxionEntity> clientEnsemble() {
        Map<Integer, List<Integer>> posed = new java.util.HashMap<>();
        for (Map.Entry<Integer, AssemblyState.Links> origin : RECEIVED_LINKS.entrySet()) {
            List<Integer> others = new ArrayList<>();
            for (AssemblyState.Link link : origin.getValue().links()) {
                if (!link.anchored()) {
                    others.add(link.other());
                }
            }
            posed.put(origin.getKey(), others);
        }
        List<AxionEntity> members = new ArrayList<>();
        for (int id : dev.axion.attach.EnsembleLinks.members(getId(), posed)) {
            if (level().getEntity(id) instanceof AxionEntity member && !member.isRemoved()) {
                members.add(member);
            }
        }
        return members;
    }

    /**
     * {@return ce que lisent ses sources {@code attach.*} et {@code socket.*} à cette image (client, ADR-171 D7)} Pour
     * chaque socket, dans l'ordre de la definition : attaché, longueur de la corde, tension, désalignement — celles
     * qu'elle a posées et celles que d'autres ont posées vers elle.
     */
    public double[] socketReadings() {
        int count = sockets().sockets().size();
        double[] readings = new double[count * dev.axion.render.ProceduralTracks.Frame.SOCKET_READINGS];
        if (count == 0) {
            return readings;
        }
        for (AssemblyState.Link link : receivedLinks.links()) {
            read(readings, link.socket(), link);
        }
        for (Map.Entry<Integer, AssemblyState.Links> origin : RECEIVED_LINKS.entrySet()) {
            if (origin.getKey() == getId()) {
                continue;
            }
            for (AssemblyState.Link link : origin.getValue().links()) {
                if (link.other() == getId()) {
                    read(readings, link.otherSocket(), link);
                }
            }
        }
        return readings;
    }

    private static void read(double[] readings, int socket, AssemblyState.Link link) {
        int at = socket * dev.axion.render.ProceduralTracks.Frame.SOCKET_READINGS;
        if (socket < 0 || at + 3 >= readings.length) {
            return;
        }
        readings[at] = 1.0;
        readings[at + 1] = link.length() / 1000.0;
        readings[at + 2] = link.tension();
        // Le désalignement n'a de sens que pour ce qui tient deux sockets ensemble : rigid, hitch, magnet.
        boolean held = link.kind() != 2 && link.kind() != 3;
        readings[at + 3] = held ? link.distance() / 1000.0 : 0.0;
    }

    /** {@return le node de chaque capteur déclaré, dans l'ordre de la definition (ADR-174 D5) ; vide sans} */
    public int[] sensorNodes() {
        Seating seating = seating();
        return seating == null ? new int[0] : seating.sensorNodes();
    }

    /** {@return les capteurs que la definition déclare (ADR-174 D5)} */
    public dev.axion.definition.Sensors sensorTable() {
        return dev.axion.definition.Sensors.of(java.util.Optional.ofNullable(document()).map(Definition::root)
                .orElse(new com.google.gson.JsonObject()));
    }

    /** {@return les sockets d'attache de sa definition (ADR-171 D2) ; vide sans definition compilée} */
    public dev.axion.definition.SocketTable sockets() {
        Seating seating = seating();
        return seating == null ? dev.axion.definition.SocketTable.NONE : seating.sockets();
    }

    /**
     * {@return le repère d'un socket dans son corps, pour {@code ATTACH} (ADR-171 D6) : le node racine du corps, puis la
     * position {@code x, y, z} et la rotation {@code x, y, z, w} du socket dans ce corps ; {@code null} si la definition
     * n'est pas compilée ou si le node n'a pas de corps}
     *
     * @param node le node du socket
     */
    public float[] socketFrame(int node) {
        DefinitionCompiler.Summary summary = summaryHeld();
        if (summary == null || node < 0 || node >= summary.anatomy().size()) {
            return null;
        }
        dev.axion.asset.Anatomy anatomy = summary.anatomy();
        int body = anatomy.body(node);
        if (body < 0 || body >= summary.roots().length) {
            return null;
        }
        float[] t = anatomy.translation(node);
        float[] r = anatomy.rotation(node);
        return new float[] {summary.roots()[body], t[0], t[1], t[2], r[0], r[1], r[2], r[3]};
    }

    /**
     * {@return la pose monde d'un node au dernier tick (serveur), ou {@code null}} Celle d'un socket, que l'interaction
     * {@code attach} rapproche d'un autre (ADR-171 D4).
     *
     * @param node l'index du node dans l'asset
     */
    public SeatPlacement.Pose nodePose(int node) {
        DefinitionCompiler.Summary summary = summaryHeld();
        if (summary == null || node < 0 || node >= summary.anatomy().size()) {
            return null;
        }
        return SeatPlacement.place(summary.anatomy(), node, bodyPoses(summary));
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
        if (seating == null || seat < 0 || seat >= seating.seats().size()) {
            return null;
        }
        return nodePoseAt(seating.seats().get(seat).node(), shown);
    }

    /**
     * {@return la pose monde d'un node à une image (client), ou {@code null} : definition pas encore compilée, node
     * inconnu, ou corps dont la pose n'est pas connue} Celle d'une vue fixe (ADR-170 D1).
     *
     * @param node l'index du node dans l'asset
     * @param shown la pose dessinée à cette image ({@link #renderPose})
     */
    public SeatPlacement.Pose nodePoseAt(int node, RenderPose shown) {
        DefinitionCompiler.Summary summary = summaryHeld();
        if (summary == null || node < 0 || node >= summary.anatomy().size()) {
            return null;
        }
        return SeatPlacement.place(summary.anatomy(), node,
                relativePoses(summary, shown.x(), shown.y(), shown.z(), shown.rotation(), shown.bodies()));
    }

    /**
     * {@return faux si la definition dit {@code "carry": false} (ADR-178 Q5) : elle arrête ce qui la heurte, mais ne porte
     * rien}
     */
    boolean carries() {
        Definition definition = document();
        if (definition == null) {
            return false;
        }
        com.google.gson.JsonElement carry = definition.root().get("carry");
        return carry == null || carry.getAsBoolean();
    }

    /**
     * {@return l'empreinte de la definition que le natif a compilée, la clé du balayage de collision (ADR-178 D1), ou
     * {@code 0} sans definition compilée}
     */
    long collisionKey() {
        Definition definition = document();
        return definition == null || summaryHeld() == null ? 0L : definition.hash();
    }

    /**
     * {@return la pose monde de chaque corps, à la queue leu leu : 7 valeurs chacun — position, rotation {@code xyzw}
     * —, ou {@code null} si la definition n'est pas compilée ou si la pose d'un corps n'est pas connue (ADR-178 D1)}
     * Au serveur, les états du dernier tick ; au client, la pose que le tick a prise de l'interpolation : celle que le
     * joueur voit.
     */
    public double[] collisionPoses() {
        DefinitionCompiler.Summary summary = summaryHeld();
        return summary == null ? null : flatten(bodyPoses(summary));
    }

    /** Le nombre de ticks de poses que le serveur garde pour porter un joueur à la pose que son client dessine (ADR-178 D4). */
    static final int CARRY_HISTORY = 21;

    /** Les poses des derniers ticks, la plus récente d'abord (serveur). */
    private final java.util.ArrayDeque<double[]> carryHistory = new java.util.ArrayDeque<>(CARRY_HISTORY);

    /** {@return la compilation de la definition que lisent les poses à porter ; une autre après une reliure} */
    Object carryDefinition() {
        return summaryHeld();
    }

    /** Retient les poses du tick (serveur, fin de tick). */
    void rememberCarryPoses() {
        double[] poses = collisionPoses();
        if (poses == null) {
            carryHistory.clear();
            return;
        }
        if (carryHistory.size() == CARRY_HISTORY) {
            carryHistory.removeLast();
        }
        carryHistory.addFirst(poses);
    }

    /**
     * {@return les poses d'il y a {@code lag} ticks, ou {@code null} si elles ne sont pas gardées}
     *
     * @param lag 0 pour le dernier tick
     */
    double[] carryPoses(int lag) {
        if (lag < 0 || lag >= carryHistory.size()) {
            return null;
        }
        java.util.Iterator<double[]> poses = carryHistory.iterator();
        for (int i = 0; i < lag; i++) {
            poses.next();
        }
        return poses.next();
    }

    /**
     * {@return les poses de chaque corps telles qu'une image les dessine (client), à la queue leu leu comme
     * {@link #collisionPoses}, ou {@code null} si elles ne sont pas connues} Ce qu'un joueur porté suit d'image en image
     * (ADR-178 D3).
     *
     * @param shown la pose dessinée à cette image ({@link #renderPose})
     */
    public double[] framePoses(RenderPose shown) {
        DefinitionCompiler.Summary summary = summaryHeld();
        return summary == null ? null : flatten(relativePoses(summary, shown.x(), shown.y(), shown.z(), shown.rotation(),
                shown.bodies()));
    }

    /** Les poses à la queue leu leu, 7 valeurs chacune ; {@code null} si l'une d'elles manque. */
    private static double[] flatten(List<SeatPlacement.BodyPose> bodies) {
        double[] poses = new double[bodies.size() * 7];
        for (int rank = 0; rank < bodies.size(); rank++) {
            SeatPlacement.BodyPose body = bodies.get(rank);
            if (body == null) {
                return null;
            }
            float[] q = body.rotation();
            poses[rank * 7] = body.x();
            poses[rank * 7 + 1] = body.y();
            poses[rank * 7 + 2] = body.z();
            poses[rank * 7 + 3] = q[0];
            poses[rank * 7 + 4] = q[1];
            poses[rank * 7 + 5] = q[2];
            poses[rank * 7 + 6] = q[3];
        }
        return poses;
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
            float slipLat, float wear, int material) {
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
            wheelWear = Arrays.copyOf(wheelWear, wheel + 1);
        }
        if (wheel >= wheelMaterial.length) {
            int known = wheelMaterial.length;
            wheelMaterial = Arrays.copyOf(wheelMaterial, wheel + 1);
            Arrays.fill(wheelMaterial, known, wheel + 1, dev.axion.physics.SimCommandStream.NO_MATERIAL);
        }
        wheelMaterial[wheel] = material;
        wheelCompression[wheel] = compression;
        wheelSpin[wheel] = spin;
        wheelSteer[wheel] = steer;
        wheelGround[wheel] = grounded;
        wheelSlipLong[wheel] = slipLong;
        wheelSlipLat[wheel] = slipLat;
        // ADR-176 D4 : tant que l'usure relue ou posée n'est pas partie au natif, un rapport ne la remplace pas.
        initWear();
        if (!wearPending) {
            wheelWear[wheel] = wear;
        }
    }

    /**
     * Donne à l'usure de chaque roue sa valeur de départ, la première fois qu'on la lit, la definition connue : celle que
     * la sauvegarde gardait pour son nom, 0 pour une roue nouvelle (ADR-176 D4, CAP-38). Une roue que la sauvegarde nomme
     * et que la definition n'a plus est gardée telle quelle à la sauvegarde suivante (R-1710), et dite une fois (E-7012).
     */
    private void initWear() {
        List<String> names = wheelNodes();
        if (wearInit || names.isEmpty()) {
            return;
        }
        wearInit = true;
        if (wheelWear.length < names.size()) {
            wheelWear = Arrays.copyOf(wheelWear, names.size());
        }
        for (int rank = 0; rank < names.size(); rank++) {
            Double saved = savedWheels.get(names.get(rank));
            if (saved != null) {
                wheelWear[rank] = saved.floatValue();
            }
        }
        if (!savedWheels.isEmpty()) {
            wearPending = true;
            List<String> gone = savedWheels.keySet().stream().filter(name -> !names.contains(name)).toList();
            if (!gone.isEmpty()) {
                LOGGER.warn("AXION : E-7012 assembly {} — usure gardée pour des roues que {} ne déclare plus : {}",
                        getUUID(), definitionId, gone);
            }
        }
    }

    /**
     * Pose l'usure du pneu d'une roue (ADR-176 D4) ; le natif la reçoit par {@code SET_WEAR}, sans que le véhicule soit
     * réveillé.
     *
     * @param wheel le rang de la roue dans la definition
     * @param value l'usure, de 0 à 1
     * @throws IllegalArgumentException si la roue n'existe pas, ou si l'usure n'est pas un nombre de 0 à 1
     */
    public void setWear(int wheel, double value) {
        AuthoritativeThread.require();
        initWear();
        if (wheel < 0 || wheel >= wheelNodes().size()) {
            throw new IllegalArgumentException("roue " + wheel + " : la definition en déclare " + wheelNodes().size());
        }
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException("usure : un nombre de 0 à 1, et non " + value);
        }
        wheelWear[wheel] = (float) value;
        wearPending = true;
    }

    /**
     * Pose l'usure du pneu d'une roue nommée, ou de toutes (ADR-176 D4) ; l'action de contenu {@code set_wear} et l'API s'en
     * servent.
     *
     * @param wheel le node de la roue, {@code null} pour toutes
     * @param value l'usure, de 0 à 1
     * @throws IllegalArgumentException si la roue n'existe pas, ou si l'usure n'est pas de 0 à 1
     */
    public void setWearByName(String wheel, double value) {
        List<String> names = wheelNodes();
        if (wheel == null) {
            for (int rank = 0; rank < names.size(); rank++) {
                setWear(rank, value);
            }
            return;
        }
        int rank = names.indexOf(wheel);
        if (rank < 0) {
            throw new IllegalArgumentException("roue « " + wheel + " » : la definition déclare " + names);
        }
        setWear(rank, value);
    }

    /**
     * Fait suivre le mouillé du véhicule à la pluie qu'il reçoit (ADR-176 D5) : vers 1 en {@code riseSeconds} sous la pluie,
     * vers 0 en {@code drySeconds} sinon. La pluie est celle que la position reçoit — ciel visible, biome non froid —, pas
     * celle du monde : sous un toit, un véhicule sèche. L'orage compte comme la pluie. Serveur seulement.
     *
     * @param dt le temps écoulé depuis le dernier appel, en secondes
     * @param riseSeconds la durée pour devenir tout à fait mouillé
     * @param drySeconds la durée pour sécher tout à fait
     */
    void stepWetness(double dt, double riseSeconds, double drySeconds) {
        if (level().isClientSide()) {
            return;
        }
        boolean raining = level().isRainingAt(BlockPos.containing(getX(), getBoundingBox().maxY, getZ()));
        double next = raining ? Math.min(1.0, wetness + dt / riseSeconds) : Math.max(0.0, wetness - dt / drySeconds);
        if (next != wetness) {
            wetness = next;
            wetPending = true;
        }
    }

    /** {@return le mouillé de la route sous le véhicule, de 0 (sec) à 1 (ADR-176 D5)} */
    public double wetness() {
        return wetness;
    }

    /** {@return vrai une seule fois, quand le mouillé que Java tient doit partir au natif ({@code SET_WET})} */
    boolean takeWetPush() {
        boolean push = wetPending;
        wetPending = false;
        return push;
    }

    /** {@return vrai une seule fois, quand l'usure que Java tient doit partir au natif ({@code SET_WEAR})} */
    boolean takeWearPush() {
        initWear();
        boolean push = wearPending;
        wearPending = false;
        return push;
    }

    /**
     * {@return l'identifiant du matériau que le natif a rapporté sous une roue (ADR-181), {@code null} en l'air, sans
     * rapport ou sur un sol sans matériau} Serveur seulement.
     *
     * @param wheel le rang de la roue dans la definition
     */
    public String wheelSurface(int wheel) {
        return wheel >= 0 && wheel < wheelMaterial.length && wheelGround[wheel]
                ? RuntimeAccess.get().physicsMaterialId(wheelMaterial[wheel]) : null;
    }

    /** {@return vrai si le natif a rapporté une roue au sol} */
    boolean wheelGrounded(int wheel) {
        return wheel >= 0 && wheel < wheelGround.length && wheelGround[wheel];
    }

    /**
     * {@return l'usure du pneu d'une roue, de 0 à 1, telle que le natif l'a rapportée (ADR-176 D4) ; 0 sans rapport}
     *
     * @param wheel le rang de la roue dans la definition
     */
    public float wheelWear(int wheel) {
        initWear();
        return wheel >= 0 && wheel < wheelWear.length ? wheelWear[wheel] : 0f;
    }

    /**
     * Retient l'état que le motopropulseur a rapporté (serveur, thread autoritatif, ADR-163 D1).
     *
     * @param report le rapport du natif
     */
    void setVehicleState(VehicleReport report) {
        AuthoritativeThread.require();
        vehicleReport = report;
        // ADR-176 D3 : le natif tient l'énergie ; ce que Java en sait vient de ses rapports. Tant que l'énergie relue
        // d'une sauvegarde n'est pas partie au natif, un rapport ne la remplace pas.
        if (energySpec().declared() && !energyPending) {
            initEnergy();
            fuel = report.fuel();
            battery = report.battery();
            syncFuelMass();
        }
    }

    /** {@return ce que la definition déclare de réservoir et de batterie (ADR-176 D3)} */
    public dev.axion.definition.EnergySpec energySpec() {
        dev.axion.definition.HeldDefinition tenue = held();
        return tenue == null ? dev.axion.definition.EnergySpec.NONE : tenue.energy();
    }

    /**
     * Donne au carburant et à la batterie leur valeur de départ, la première fois qu'on les lit : celle que la sauvegarde
     * gardait, bornée aux capacités, ou le plein d'une assembly neuve (ADR-176 D3). L'énergie relue part au natif, qui
     * naît plein.
     */
    private void initEnergy() {
        if (!Double.isNaN(fuel)) {
            return;
        }
        dev.axion.definition.EnergySpec spec = energySpec();
        if (savedEnergy != null) {
            fuel = Math.min(savedEnergy[0], spec.fuelCapacity());
            battery = Math.min(savedEnergy[1], spec.batteryCapacity());
            energyPending = true;
        } else {
            fuel = spec.fuelCapacity();
            battery = spec.batteryCapacity();
        }
        syncFuelMass();
    }

    /** {@return le carburant restant, en litres ; 0 sans réservoir (ADR-176 D3)} */
    public double fuel() {
        initEnergy();
        return energySpec().fuelCapacity() > 0f ? fuel : 0.0;
    }

    /**
     * Verse du carburant dans le réservoir (ADR-176 D3), jusqu'à sa capacité ; le natif le reçoit par {@code SET_ENERGY},
     * sans que le véhicule soit réveillé.
     *
     * @param litres ce qu'on verse, de 0 ou plus
     * @return ce qui est entré dans le réservoir, en litres
     * @throws IllegalArgumentException si {@code litres} est négatif ou n'est pas fini
     */
    public double refuel(double litres) {
        AuthoritativeThread.require();
        if (!Double.isFinite(litres) || litres < 0.0) {
            throw new IllegalArgumentException("litres : un nombre fini, 0 ou plus, et non " + litres);
        }
        dev.axion.definition.EnergySpec spec = energySpec();
        if (!(spec.fuelCapacity() > 0f)) {
            return 0.0;
        }
        initEnergy();
        double before = fuel;
        fuel = Math.min(spec.fuelCapacity(), fuel + litres);
        energyPending = true;
        syncFuelMass();
        return fuel - before;
    }

    /**
     * Pose la charge de la batterie (ADR-176 D3), ramenée à sa capacité ; sans batterie, rien.
     *
     * @param charge la charge, de 0 ou plus
     * @throws IllegalArgumentException si {@code charge} est négative ou n'est pas finie
     */
    public void setBattery(double charge) {
        AuthoritativeThread.require();
        if (!Double.isFinite(charge) || charge < 0.0) {
            throw new IllegalArgumentException("charge : un nombre fini, 0 ou plus, et non " + charge);
        }
        dev.axion.definition.EnergySpec spec = energySpec();
        if (!(spec.batteryCapacity() > 0f)) {
            return;
        }
        initEnergy();
        battery = Math.min(spec.batteryCapacity(), charge);
        energyPending = true;
    }

    /** {@return la charge de la batterie ; 0 sans batterie (ADR-176 D3)} */
    public double battery() {
        initEnergy();
        return energySpec().batteryCapacity() > 0f ? battery : 0.0;
    }

    /**
     * {@return vrai une seule fois, quand l'énergie relue d'une sauvegarde — ou celle que Java tient d'une assembly dont
     * le corps natif a été recréé — doit partir au natif} Il la reçoit par {@code SET_ENERGY}, avant son premier pas.
     */
    boolean takeEnergyPush() {
        if (!energySpec().declared()) {
            return false;
        }
        initEnergy();
        boolean push = energyPending;
        energyPending = false;
        return push;
    }

    /**
     * La masse du carburant que le réservoir porte, posée comme la cause {@code axion:energy} du paramètre {@code mass}
     * (ADR-176 D2, D3) : au cran de 1/255 de la capacité, la masse de la definition étant la masse à sec.
     */
    private void syncFuelMass() {
        dev.axion.definition.EnergySpec spec = energySpec();
        if (parameters == null || level().isClientSide() || !(spec.unitMass() > 0f)) {
            return;
        }
        parameters.engineCause(dev.axion.definition.EnergySpec.MASS_CAUSE, dev.axion.api.assembly.Parameters.MASS,
                spec.fuelMass(fuel));
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
        dev.axion.definition.EnergySpec energy = energySpec();
        return AssemblyState.Vehicle.of(vehicleReport.rpm(), vehicleReport.load(), vehicleReport.gear(),
                vehicleReport.flags(), input.throttle(), input.brake(), vehicleReport.steer(),
                held.present() ? input.handbrake() : 1f,
                dev.axion.net.PredictedStates.ratio(vehicleReport.fuel(), energy.fuelCapacity()),
                dev.axion.net.PredictedStates.ratio(vehicleReport.battery(), energy.batteryCapacity()),
                (float) wetness);
    }

    /**
     * {@return le mouillé que le serveur a envoyé, de 0 à 1 (client, ADR-176 D5)} Le client ne lit pas la pluie : le natif
     * prédit le reçoit, au cran du serveur.
     */
    public float receivedWet() {
        return receivedVehicle.wet();
    }

    private AssemblyState.Wheels wheelStates() {
        return wheelCompression.length == 0
                ? AssemblyState.Wheels.NONE
                : AssemblyState.Wheels.of(wheelCompression, wheelSpin, wheelSteer, wheelGround, wheelWear);
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
    /** {@return vrai dès que le natif a rapporté son état depuis sa création ou sa lecture : ses corps existent} */
    boolean simulated() {
        return simulated;
    }

    /** Son corps natif est retiré (fin de suivi) : jusqu'à son prochain état, ses corps n'existent plus. */
    void forgetSimulation() {
        simulated = false;
        // Le prochain corps natif naît plein : l'énergie que Java tient lui est rendue.
        if (!Double.isNaN(fuel) && energySpec().declared()) {
            energyPending = true;
        }
        // De même l'usure des pneus : le corps neuf naît avec des pneus neufs.
        if (wearInit) {
            wearPending = true;
        }
        // Et le mouillé : le corps neuf naît sec.
        if (wetness > 0.0) {
            wetPending = true;
        }
    }

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
        // ADR-174 D3 : l'état des nodes, qui part aussi pour une assembly endormie.
        if (netState != null && !level().isClientSide() && !netState.nodes().equals(nodeStates())) {
            netState = netState.withNodes(nodeStates());
        }
        // ADR-174 D5 : ce que compte chaque capteur.
        if (netState != null && !level().isClientSide()) {
            AssemblyState.SensorCounts counts = AssemblyState.SensorCounts.of(sensorCounts());
            if (!counts.equals(netState.sensors())) {
                netState = netState.withSensors(counts);
            }
        }
        // ADR-171 D7 : les attaches qu'elle a posées, vivantes, avec leur longueur, leur écart et leur tension.
        if (netState != null && !level().isClientSide()) {
            AttachmentRuntime attachments = AttachmentRuntime.current();
            AssemblyState.Links links = attachments == null ? AssemblyState.Links.NONE : attachments.netLinks(this);
            if (!links.equals(netState.links())) {
                netState = netState.withLinks(links);
            }
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
        // ADR-174 D2 : une variable par siège part avec la valeur de chaque siège.
        double[][] seats = new double[variables.size()][];
        for (int i = 0; i < seats.length; i++) {
            if (variables.declared().get(i).perSeat()) {
                seats[i] = variables.seatValues(i);
            }
        }
        return AssemblyState.VariableValues.of(variables.values(), seats).only(sent);
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
            receivedVehicle = state.vehicle();
            // ADR-171 D7 : les attaches qu'elle a posées, telles que le serveur les a vues ; les cordes et les sources
            // attach.* les lisent.
            receivedLinks = state.links();
            // ADR-174 D3 : l'état des nodes que ses actions ont changé, tel quel ; le rendu le compose aux pistes.
            nodeStates = state.nodes();
            // ADR-174 D5 : ce que compte chaque capteur, que lit sensor.<nom>.count.
            receivedSensors = state.sensors();
            if (receivedLinks.links().isEmpty()) {
                RECEIVED_LINKS.remove(getId());
            } else {
                RECEIVED_LINKS.put(getId(), receivedLinks);
            }
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
     * {@return l'heure à laquelle {@link #renderPose} date la pose de l'image en cours, en nanosecondes ; {@code 0} avant le
     * premier snapshot} Une mesure de ce qui est dessiné d'une image à l'autre se fait avec elle.
     */
    long frameNanos() {
        return clock == null ? 0L : clock.frameNanos();
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
        AssemblyBinding binding = AssemblyBinding.resolve(
                AssemblyTags.version(stored),
                AssemblyTags.definition(stored),
                AxionEntities.definitions());
        inert = binding.isInert();
        inertReason = binding.inertReason();
        definitionId = binding.bound().map(Definition::id).orElse(AssemblyTags.describe(stored));
        // ADR-175 D3 : une definition qui en remplace une, ou qui a changé de révision, mène l'état à elle avant
        // qu'il soit lu : ses renommages s'appliquent aux noms que le NBT garde.
        binding.bound().ifPresent(bound -> AssemblyTags.evolve(stored, dev.axion.definition.Revisions.of(bound.root()),
                bound.sha256(), bound.hash(), note -> LOGGER.warn("AXION : assembly {} de {} — {}", getUUID(),
                        bound.id(), note)));
        // ADR-153 D3 : l'état gardé, rendu à la création de l'assembly ; ses vitesses et ses corps
        // secondaires sont aussi ceux que la sauvegarde suivante réécrit, tant que l'assembly dort.
        // ADR-176 D3 : le carburant et la batterie gardés, bornés aux capacités de la definition quand elle est connue.
        savedWheels = AssemblyTags.wheels(stored);
        wheelWear = new float[0];
        wearInit = false;
        wearPending = false;
        savedEnergy = AssemblyTags.energy(stored);
        fuel = Double.NaN;
        battery = Double.NaN;
        energyPending = false;
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
        custom = AssemblyTags.custom(stored, AxionEntities::maxCustomBytes, inert);
        // ADR-167 D7 : un contrôleur dont aucun mod ne s'enregistre plus reste attaché, inactif, et se dit (CAP-38).
        attachedControllers.clear();
        attachedControllers.addAll(AssemblyTags.controllers(stored));
        // ADR-171 D7 : les attaches posées, rétablies quand l'assembly entre au monde (onAddedToWorld).
        attachmentRecords.clear();
        attachmentRecords.addAll(AssemblyTags.attachments(stored));
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
            // ADR-171 D7 : ses attaches gardées se rétablissent, ou attendent l'autre extrémité.
            AttachmentRuntime attachments = AttachmentRuntime.current();
            if (attachments != null && !attachmentRecords.isEmpty()) {
                attachments.restoreLater(this);
            }
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
        if (level().isClientSide()) {
            // ADR-171 D7 : ses attaches reçues ne valent plus pour les autres.
            RECEIVED_LINKS.remove(getId());
        }
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
        // ADR-175 D3 : la révision et l'empreinte de la definition à laquelle cet état est écrit.
        if (!inert) {
            java.util.Optional.ofNullable(document()).ifPresent(bound -> AssemblyTags.putRevision(stored,
                    dev.axion.definition.Revisions.of(bound.root()).revision(), bound.sha256()));
        }
        // ADR-163 D2 : les variables gardées, si l'assembly en a déjà ; sinon, celles que son NBT portait restent.
        if (!inert && variables != null) {
            AssemblyTags.putVariables(stored, variables.persisted());
            AssemblyTags.putSeatVariables(stored, variables.persistedSeats());
        }
        // ADR-167 D7 : les modificateurs posés, si l'assembly en a déjà une table ; sinon, ceux que son NBT portait.
        if (!inert && parameters != null) {
            AssemblyTags.putParameters(stored, parameters.modifiers());
        }
        // ADR-167 D7, D9 : les contrôleurs attachés par l'API, relus à la lecture.
        if (!inert) {
            AssemblyTags.putControllers(stored, attachedControllers);
        }
        // ADR-176 D3 : le carburant et la batterie, tels que le natif les a rapportés ; sans énergie déclarée, la clé
        // gardée du NBT reste.
        if (!inert && energySpec().declared()) {
            initEnergy();
            AssemblyTags.putEnergy(stored, fuel, battery);
        }
        // ADR-176 D4 : l'usure des pneus, par nom de roue ; celle d'une roue que la definition ne déclare plus reste.
        initWear();
        if (!inert && wearInit) {
            Map<String, Double> wear = new java.util.LinkedHashMap<>();
            List<String> names = wheelNodes();
            for (int rank = 0; rank < names.size(); rank++) {
                if (wheelWear[rank] > 0f) {
                    wear.put(names.get(rank), (double) wheelWear[rank]);
                }
            }
            savedWheels.forEach((name, value) -> {
                if (!names.contains(name) && value > 0.0) {
                    wear.put(name, value);
                }
            });
            AssemblyTags.putWheels(stored, wear);
        }
        // ADR-174 D3 : l'état des nodes, par nom, une fois relu ; sinon, celui que son NBT portait reste.
        if (!inert && nodesRestored) {
            Seating seating = seating();
            if (seating != null) {
                java.util.Map<String, Integer> named = seating.actionNodes();
                java.util.Map<String, String> names = new java.util.HashMap<>();
                named.forEach((name, node) -> names.putIfAbsent(String.valueOf(node), name));
                java.util.Set<String> targets = seating.swapTargets().keySet();
                java.util.Map<String, AssemblyTags.SavedNode> current = new java.util.LinkedHashMap<>();
                named.forEach((name, node) -> {
                    dev.axion.net.NodeStates.State state = nodeStates.of(node);
                    if (state != null) {
                        current.put(name, new AssemblyTags.SavedNode(state.hidden(), state.emissive(), state.color(),
                                state.swapped() < 0 ? null : names.get(String.valueOf(state.swapped()))));
                    } else if (targets.contains(name)) {
                        // Un remplaçant montré : gardé tel, pour que la relecture ne le recache pas.
                        current.put(name, new AssemblyTags.SavedNode(false, Float.NaN, null, null));
                    }
                });
                AssemblyTags.putNodes(stored, current, named.keySet());
            }
        }
        // ADR-171 D7 : les attaches posées, vivantes ou suspendues, avec la longueur de leur dernier tick.
        if (!inert) {
            AttachmentRuntime attachments = AttachmentRuntime.current();
            if (attachments != null) {
                attachments.refreshLengths(this);
            }
            AssemblyTags.putAttachments(stored, attachmentRecords);
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
        // ADR-175 D1 : chaque corps et chaque joint s'écrit avec son nom, d'après la definition de l'assembly.
        List<String> nodeNames = heldNodeNames();
        List<String> jointNames = StateMatching.jointNames(
                java.util.Optional.ofNullable(document()).map(Definition::root).orElse(null), jointFlags.length);
        List<PersistedState.Body> bodies = new ArrayList<>(secondaryBodies.size());
        for (BodyPose body : secondaryBodies) {
            Quaternionf r = body.rotation();
            bodies.add(new PersistedState.Body(body.node(),
                    body.node() >= 0 && body.node() < nodeNames.size() ? nodeNames.get(body.node()) : null,
                    body.x(), body.y(), body.z(), new float[] {r.x, r.y, r.z, r.w}, body.linear().clone(),
                    body.angular().clone()));
        }
        List<PersistedState.Joint> joints = new ArrayList<>(jointFlags.length);
        for (int i = 0; i < jointFlags.length; i++) {
            joints.add(new PersistedState.Joint(
                    (jointFlags[i] & JointState.BROKEN) != 0, i < jointMisalignment.length ? jointMisalignment[i] : 0f,
                    jointNames == null ? null : jointNames.get(i)));
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

    /**
     * {@return la révision de la definition que cette instance tient (ADR-175 D3), 1 si elle n'en tient pas ou n'en
     * déclare pas}
     */
    public int definitionRevision() {
        dev.axion.definition.HeldDefinition tenue = held();
        return tenue == null ? 1 : dev.axion.definition.Revisions.of(tenue.definition().root()).revision();
    }

    /** {@return l'identifiant de la definition, ou ce qu'on sait d'une definition inconnue} */
    public String definitionId() {
        if (level() != null && level().isClientSide()) {
            held();
        }
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
