package dev.axion.forge;

import com.mojang.logging.LogUtils;
import dev.axion.definition.Definition;
import dev.axion.entity.AssemblyBinding;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraftforge.entity.IEntityAdditionalSpawnData;
import net.minecraftforge.network.NetworkHooks;
import org.joml.Quaternionf;
import org.slf4j.Logger;

/**
 * L'entité d'une assembly (C-50, ADR-010).
 *
 * <p>N'hérite ni de {@code LivingEntity} ni de {@code VehicleEntity} (R-700) :
 * santé, montée et dégâts d'une assembly sont ceux de sa definition, pas ceux
 * d'un mob ou d'un bateau. {@code tick()} n'exécute aucune physique (R-701) : la
 * simulation est pilotée par le noyau natif (C-40) et réappliquée en fin de tick
 * serveur ; côté client, {@code tick()} n'avance que l'interpolation visuelle vers la
 * position et l'orientation reçues. L'entité porte sa definition, se sauvegarde, et se voit
 * en debug.
 */
public final class AxionEntity extends Entity implements IEntityAdditionalSpawnData {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Orientation autoritaire du corps, quaternion {@code (x, y, z, w)} (R-461).
     *
     * <p><b>Pont provisoire</b> (décision de Killian du 2026-10-02) : la donnée synchronisée
     * de Minecraft porte l'orientation au client à chaque tick où elle change, comme son
     * suivi d'entité porte la position. Le protocole d'AXION (C-51, {@code S2C_Snapshot},
     * rotation « smallest three », PARTIE 21.5) la remplacera en M4.
     */
    private static final EntityDataAccessor<Quaternionf> ROTATION =
            SynchedEntityData.defineId(AxionEntity.class, EntityDataSerializers.QUATERNION);

    /**
     * Pas d'interpolation d'une orientation reçue : autant que de position
     * ({@code lerpTo} reçoit 3 du suivi d'entité), pour que rotation et translation restent
     * en phase.
     */
    private static final int ROTATION_LERP_STEPS = 3;

    /** Clés {@code axion:*} du NBT, gardées pour être réécrites telles quelles. */
    private CompoundTag stored = new CompoundTag();

    private String definitionId = "";
    private boolean inert = true;

    /**
     * Cible d'interpolation côté client et nombre de ticks restants pour l'atteindre.
     * La physique serveur (C-40) repositionne l'entité à chaque tick et l'envoie au
     * client à 20 Hz ; sans lissage, la hitbox « saute » d'un tick à l'autre. Ces
     * champs ne servent que côté client (voir {@link #lerpTo} et {@link #tick}).
     */
    private double lerpTargetX;

    private double lerpTargetY;

    private double lerpTargetZ;

    private int lerpSteps;

    /**
     * Orientation affichée côté client : celle du tick précédent, celle du tick courant, la
     * dernière reçue, et les pas restants pour l'atteindre. Le rendu interpole entre les deux
     * premières ({@link #renderRotation}).
     */
    private final Quaternionf rotationO = new Quaternionf();

    private final Quaternionf rotation = new Quaternionf();

    private final Quaternionf rotationTarget = new Quaternionf();

    private int rotationSteps;

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
        // La definition ne change pas après l'apparition : elle voyage dans les données
        // d'apparition. Seule l'orientation du corps est synchronisée.
        entityData.define(ROTATION, new Quaternionf());
    }

    /**
     * Pose l'orientation autoritaire du corps (serveur, fin de tick, boucle C-40).
     * Inchangée, elle n'est pas renvoyée : un corps au repos ne coûte rien au réseau.
     */
    void setBodyRotation(float x, float y, float z, float w) {
        entityData.set(ROTATION, new Quaternionf(x, y, z, w));
    }

    /** {@return l'orientation autoritaire du corps, copie} */
    Quaternionf bodyRotation() {
        return new Quaternionf(entityData.get(ROTATION));
    }

    /**
     * {@return l'orientation à dessiner, interpolée entre deux ticks}
     *
     * @param partialTick fraction du tick écoulée
     * @param dest reçoit le résultat
     */
    public Quaternionf renderRotation(float partialTick, Quaternionf dest) {
        return rotationO.slerp(rotation, partialTick, dest);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> accessor) {
        super.onSyncedDataUpdated(accessor);
        if (!ROTATION.equals(accessor) || !level().isClientSide()) {
            return;
        }
        rotationTarget.set(entityData.get(ROTATION));
        if (tickCount == 0) {
            // L'orientation initiale arrive avec l'apparition, avant tout tick : rien d'où
            // glisser.
            rotation.set(rotationTarget);
            rotationO.set(rotationTarget);
            rotationSteps = 0;
            return;
        }
        rotationSteps = ROTATION_LERP_STEPS;
    }

    /**
     * Interpolation côté client : mémorise la position autoritaire reçue du serveur pour y
     * glisser en {@code steps} ticks, au lieu de s'y téléporter (comportement par défaut
     * d'{@code Entity}, cause de la chute « bloc par bloc »). Le lacet et le tangage sont
     * ignorés — une assembly n'a pas d'orientation vanilla ; son orientation physique arrive
     * par {@link #ROTATION}. Un vrai saut ({@code teleport}) est appliqué sec.
     */
    @Override
    public void lerpTo(
            double x, double y, double z, float yRot, float xRot, int steps, boolean teleport) {
        if (teleport || steps <= 0) {
            lerpSteps = 0;
            setPos(x, y, z);
            return;
        }
        lerpTargetX = x;
        lerpTargetY = y;
        lerpTargetZ = z;
        lerpSteps = steps;
    }

    /**
     * N'exécute aucune physique (R-701) : la simulation est pilotée par le noyau natif (C-40)
     * et réappliquée en fin de tick serveur. Côté client, {@code tick()} avance d'un pas
     * l'interpolation visuelle vers la dernière position reçue ; le rendu affine encore entre
     * deux ticks. Sans interpolation en cours (serveur, ou entité au repos), ce n'est que
     * {@code super.tick()}.
     */
    @Override
    public void tick() {
        super.tick();
        if (lerpSteps > 0) {
            setPos(
                    getX() + (lerpTargetX - getX()) / lerpSteps,
                    getY() + (lerpTargetY - getY()) / lerpSteps,
                    getZ() + (lerpTargetZ - getZ()) / lerpSteps);
            lerpSteps--;
        }
        // Même schéma pour l'orientation : un pas de slerp vers la dernière reçue.
        rotationO.set(rotation);
        if (rotationSteps > 0) {
            rotation.slerp(rotationTarget, 1.0f / rotationSteps);
            rotationSteps--;
        }
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        stored = AssemblyTags.extract(tag);
        AssemblyBinding binding = AssemblyBinding.resolve(
                AssemblyTags.version(stored),
                AssemblyTags.definition(stored),
                AxionEntities.definitions());
        inert = binding.isInert();
        definitionId = binding.bound().map(Definition::id).orElse(AssemblyTags.describe(stored));
        // §22.2 : le corps sera recréé avec l'orientation sauvegardée (identité si absente).
        entityData.set(ROTATION, AssemblyTags.rotation(stored));
        // Une entité fraîchement créée par commande passe ici sans clé d'AXION :
        // elle sera liée juste après, et n'a rien d'anormal à signaler.
        if (inert && !stored.isEmpty()) {
            LOGGER.warn("AXION : assembly {} inerte — {}", getUUID(), binding.inertReason());
        }
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        // Une assembly inerte est réécrite telle qu'elle a été lue (R-1710) : son orientation
        // n'est pas celle d'un corps simulé, elle n'a pas à en recevoir une.
        if (!inert) {
            AssemblyTags.putRotation(stored, entityData.get(ROTATION));
        }
        AssemblyTags.restore(stored, tag);
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        // Le paquet de Forge transporte les données d'apparition ; celui de
        // Minecraft les ignorerait.
        return NetworkHooks.getEntitySpawningPacket(this);
    }

    @Override
    public void writeSpawnData(FriendlyByteBuf buffer) {
        AssemblyTags.writeSpawn(buffer, new AssemblyTags.SpawnState(definitionId, inert));
    }

    @Override
    public void readSpawnData(FriendlyByteBuf buffer) {
        AssemblyTags.SpawnState state = AssemblyTags.readSpawn(buffer);
        definitionId = state.definitionId();
        inert = state.inert();
    }

    @Override
    public boolean isPickable() {
        // Sélectionnable, pour que la visée et `/axion remove @e[...]` la trouvent.
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

    /** {@return le libellé affiché avec les hitbox vanilla} */
    public Component debugLabel() {
        return Component.literal(definitionId + (inert ? " — inerte" : "") + " — hitbox provisoire");
    }
}
