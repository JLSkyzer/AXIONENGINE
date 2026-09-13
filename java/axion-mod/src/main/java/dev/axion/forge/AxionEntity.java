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
 * d'un mob ou d'un bateau. {@code tick()} n'exécute aucune physique (R-701) :
 * la simulation sera pilotée par C-40 en un lot, en M3. D'ici là, l'entité porte
 * sa definition, se sauvegarde, et se voit en debug.
 */
public final class AxionEntity extends Entity implements IEntityAdditionalSpawnData {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Clés {@code axion:*} du NBT, gardées pour être réécrites telles quelles. */
    private CompoundTag stored = new CompoundTag();

    private String definitionId = "";
    private boolean inert = true;

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
