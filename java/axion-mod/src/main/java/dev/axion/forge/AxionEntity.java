package dev.axion.forge;

import com.mojang.logging.LogUtils;
import dev.axion.definition.Definition;
import dev.axion.entity.AssemblyBinding;
import dev.axion.net.AssemblyState;
import dev.axion.net.Interpolation;
import dev.axion.net.RenderClock;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
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

    /**
     * État de l'assembly tel que les snapshots le portent, côté serveur : posé à chaque tick simulé,
     * {@code null} avant le premier (ADR-150 D6).
     */
    private AssemblyState netState;

    /** Clés {@code axion:*} du NBT, gardées pour être réécrites telles quelles. */
    private CompoundTag stored = new CompoundTag();

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
        definitionId = definition.id();
        inert = false;
    }

    @Override
    protected void defineSynchedData() {
        // Rien à synchroniser par Minecraft : la definition voyage dans les données d'apparition,
        // l'état du corps dans les snapshots d'AXION (ADR-150 D2). Une donnée synchronisée sale
        // réveillerait aussi le suivi de position de Minecraft.
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

    /** Pose l'orientation autoritaire du corps (serveur, fin de tick, boucle C-40). */
    void setBodyRotation(float x, float y, float z, float w) {
        bodyRotation.set(x, y, z, w);
    }

    /** {@return l'orientation autoritaire du corps, copie} */
    Quaternionf bodyRotation() {
        return new Quaternionf(bodyRotation);
    }

    /**
     * Quantifie l'état du corps pour les snapshots (serveur, fin de tick, après la position,
     * l'orientation et l'emprise). Une position non finie ne se quantifie pas : l'état précédent reste.
     *
     * @param linear vitesse linéaire monde, en m/s
     * @param angular vitesse angulaire monde, en rad/s
     * @param sleeping le corps dort
     */
    void updateNetState(float[] linear, float[] angular, boolean sleeping) {
        boolean bounded = hasPhysicalBounds();
        try {
            netState = AssemblyState.of(
                    new double[] {getX(), getY(), getZ()},
                    new float[] {bodyRotation.x, bodyRotation.y, bodyRotation.z, bodyRotation.w},
                    linear,
                    angular,
                    bounded ? new float[] {boundsMin.x, boundsMin.y, boundsMin.z} : null,
                    bounded ? new float[] {boundsMax.x, boundsMax.y, boundsMax.z} : null,
                    sleeping);
        } catch (IllegalArgumentException notFinite) {
            // Le natif ramène lui-même un corps non fini dans un état valide (E-2030) ; l'état précédent
            // reste celui des snapshots d'ici là.
        }
    }

    /** {@return l'état que les snapshots portent, ou {@code null} avant le premier tick simulé} */
    public AssemblyState netState() {
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
    }

    /**
     * Où dessiner une assembly à une image.
     *
     * @param x position de l'origine du corps (bas-centre de l'entité)
     * @param y position
     * @param z position
     * @param rotation orientation du corps, autour de son origine
     * @param mode comment l'interpolation de 21.7 l'a obtenue ; {@code null} avant le premier snapshot
     */
    public record RenderPose(double x, double y, double z, Quaternionf rotation, Interpolation.Mode mode) {}

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
                    null);
        }
        long frame = clock.frameNanos();
        Interpolation.Pose pose = interpolation.sample(clock.renderMillis(frame), clock.localMillis(frame));
        float[] q = pose.rotation();
        return new RenderPose(pose.x(), pose.y(), pose.z(), new Quaternionf(q[0], q[1], q[2], q[3]), pose.mode());
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
        if (interpolation == null) {
            return;
        }
        long now = System.nanoTime();
        Interpolation.Pose pose = interpolation.sample(clock.renderMillis(now), clock.localMillis(now));
        float[] q = pose.rotation();
        rotation.set(q[0], q[1], q[2], q[3]);
        if (pose.boundsMin() != null && pose.boundsMax() != null) {
            boundsMin.set(pose.boundsMin()[0], pose.boundsMin()[1], pose.boundsMin()[2]);
            boundsMax.set(pose.boundsMax()[0], pose.boundsMax()[1], pose.boundsMax()[2]);
        }
        // setPos recalcule la hitbox autour de la position, à l'emprise ci-dessus.
        setPos(pose.x(), pose.y(), pose.z());
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        stored = AssemblyTags.extract(tag);
        AssemblyBinding binding = AssemblyBinding.resolve(
                AssemblyTags.version(stored),
                AssemblyTags.definition(stored),
                AxionEntities.definitions());
        inert = binding.isInert();
        inertReason = binding.inertReason();
        definitionId = binding.bound().map(Definition::id).orElse(AssemblyTags.describe(stored));
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
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        // Une assembly inerte est réécrite telle qu'elle a été lue (R-1710) : son orientation
        // n'est pas celle d'un corps simulé, elle n'a pas à en recevoir une.
        if (!inert) {
            AssemblyTags.putRotation(stored, bodyRotation);
        }
        AssemblyTags.restore(stored, tag);
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
