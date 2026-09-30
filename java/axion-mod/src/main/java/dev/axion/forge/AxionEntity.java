package dev.axion.forge;

import com.mojang.logging.LogUtils;
import dev.axion.definition.Definition;
import dev.axion.entity.AssemblyBinding;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraftforge.entity.IEntityAdditionalSpawnData;
import net.minecraftforge.network.NetworkHooks;
import org.slf4j.Logger;

/**
 * L'entité d'une assembly (C-50, ADR-010).
 *
 * <p>N'hérite ni de {@code LivingEntity} ni de {@code VehicleEntity} (R-700) :
 * santé, montée et dégâts d'une assembly sont ceux de sa definition, pas ceux
 * d'un mob ou d'un bateau. {@code tick()} n'exécute aucune physique (R-701) : la
 * simulation est pilotée par le noyau natif (C-40) et réappliquée en fin de tick
 * serveur ; côté client, {@code tick()} n'avance que l'interpolation visuelle vers la
 * position reçue. L'entité porte sa definition, se sauvegarde, et se voit en debug.
 */
public final class AxionEntity extends Entity implements IEntityAdditionalSpawnData {

    private static final Logger LOGGER = LogUtils.getLogger();

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
        // Rien à synchroniser par tick : la definition ne change pas après
        // l'apparition, et elle voyage dans les données d'apparition.
    }

    /**
     * Interpolation côté client : mémorise la position autoritaire reçue du serveur pour y
     * glisser en {@code steps} ticks, au lieu de s'y téléporter (comportement par défaut
     * d'{@code Entity}, cause de la chute « bloc par bloc »). Le lacet et le tangage sont
     * ignorés — une assembly n'a pas d'orientation vanilla ; l'orientation physique et son
     * interpolation viendront avec le rendu orienté (C-60+). Un vrai saut ({@code teleport})
     * est appliqué sec.
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
        // Une entité fraîchement créée par commande passe ici sans clé d'AXION :
        // elle sera liée juste après, et n'a rien d'anormal à signaler.
        if (inert && !stored.isEmpty()) {
            LOGGER.warn("AXION : assembly {} inerte — {}", getUUID(), binding.inertReason());
        }
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
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
