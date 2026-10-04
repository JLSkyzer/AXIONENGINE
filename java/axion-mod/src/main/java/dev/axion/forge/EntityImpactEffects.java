package dev.axion.forge;

import com.mojang.logging.LogUtils;
import dev.axion.AxionMod;
import dev.axion.physics.EntityImpacts;
import dev.axion.physics.PhysicsEvent;
import dev.axion.physics.SimEventSink;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;

/**
 * Effets des collisions d'assemblies sur les entités vanilla (R-614, ADR-123 §7) : poussée des
 * entités non joueuses, dégâts {@code axion:collision} aux entités vivantes, chacun selon son
 * réglage serveur ({@code physics.entity_push}, {@code physics.entity_damage}, ANNEXE A.3).
 *
 * <p>Le calcul vit dans {@link EntityImpacts}, agnostique et testé ; cette classe résout les
 * entités — dans la dimension de l'assembly qui les a touchées — et applique par les API
 * standard : {@code push}, qui est le {@code setDeltaMovement} de vanilla suivi du marquage de
 * l'impulsion (bytecode lu), et {@code hurt}. Jamais de poussée sur un joueur (§10.8) : son
 * mouvement appartient à son client. La source de dégâts ne porte aucune entité : vanilla
 * n'ajoute alors aucun recul — {@code LivingEntity.hurt} ne l'applique que si
 * {@code getEntity()} est non nul (bytecode lu) —, ce qui garde un joueur immobile.
 *
 * <p>Seul {@code dev.axion.forge} touche Minecraft (R-401).
 */
public final class EntityImpactEffects implements SimEventSink {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Type de dégâts des collisions : une donnée, {@code data/axion/damage_type/collision.json}.
     *
     * <p>Le constructeur de {@code ResourceLocation}, déprécié par Forge 47.4, existe dans tout
     * Forge 47 ; {@code fromNamespaceAndPath}, qu'il recommande, n'y est rétroporté que depuis
     * 47.3.19 (changelog de Forge, #10241) — et le mod se déclare compatible avec {@code [47,)}.
     */
    public static final ResourceKey<DamageType> COLLISION = ResourceKey.create(
            Registries.DAMAGE_TYPE, new ResourceLocation(AxionMod.MODID, "collision"));

    /** Ticks par seconde : convertit une vitesse en m/s en déplacement par tick. */
    private static final double TICKS_PER_SECOND = 20.0;

    private final AssemblyRuntime assemblies;
    private final boolean push;
    private final boolean damage;
    private boolean collisionTypeMissing;
    /** Types d'entité dont un effet a levé une exception : signalés une fois chacun. */
    private final Set<EntityType<?>> faultyTypes = new HashSet<>();

    /**
     * @param assemblies assemblies dotées d'un corps : la dimension d'une entité touchée est
     *     celle de l'assembly qui l'a touchée
     * @param push {@code physics.entity_push} : pousser les entités non joueuses
     * @param damage {@code physics.entity_damage} : blesser les entités vivantes
     */
    public EntityImpactEffects(AssemblyRuntime assemblies, boolean push, boolean damage) {
        this.assemblies = assemblies;
        this.push = push;
        this.damage = damage;
    }

    @Override
    public void applyEvents(long tick, List<PhysicsEvent> events) {
        if (!push && !damage) {
            return;
        }
        for (EntityImpacts.Impact impact : EntityImpacts.gather(events)) {
            AxionEntity assembly =
                    assemblies.assembly(impact.assemblyIndex(), impact.assemblyGeneration());
            if (assembly == null) {
                continue;
            }
            Entity target = assembly.level().getEntity(impact.entity());
            if (target == null || !target.isAlive() || target instanceof AxionEntity) {
                continue;
            }
            try {
                if (push && !(target instanceof Player)) {
                    pushAway(target, impact);
                }
                if (damage && target instanceof LivingEntity living) {
                    hurt(living, impact);
                }
            } catch (RuntimeException failure) {
                // `hurt` passe par les événements Forge des autres mods, que le bus ne protège
                // pas. Un gestionnaire qui lève à chaque choc ferait désactiver le hook du tick
                // en cinq ticks (E-1010), et la simulation avec : l'effet de ce choc est perdu,
                // le fautif désigné une fois par type d'entité.
                if (faultyTypes.add(target.getType())) {
                    LOGGER.warn("AXION : effet de collision impossible sur une entité {} ; ce choc "
                            + "reste sans effet, et l'avertissement ne sera pas répété pour ce "
                            + "type", EntityType.getKey(target.getType()), failure);
                }
            }
        }
    }

    /** Pousse une entité non joueuse de la vitesse que les contacts du tick lui rendent. */
    private static void pushAway(Entity target, EntityImpacts.Impact impact) {
        AABB box = target.getBoundingBox();
        double mass = EntityImpacts.massOf(box.getXsize() * box.getYsize() * box.getZsize());
        double[] change = impact.velocityChange(mass);
        target.push(
                change[0] / TICKS_PER_SECOND,
                change[1] / TICKS_PER_SECOND,
                change[2] / TICKS_PER_SECOND);
        // Synchronisée au tick même, comme un recul vanilla, plutôt qu'à la prochaine mise à
        // jour périodique de la vitesse.
        target.hurtMarked = true;
    }

    /** Blesse une entité vivante selon la courbe de chute, sur sa vitesse d'approche. */
    private void hurt(LivingEntity target, EntityImpacts.Impact impact) {
        float amount = (float) EntityImpacts.fallDamage(impact.approachSpeed());
        if (amount <= 0.0f) {
            return;
        }
        Optional<Holder.Reference<DamageType>> type = target.level()
                .registryAccess()
                .registryOrThrow(Registries.DAMAGE_TYPE)
                .getHolder(COLLISION);
        if (type.isEmpty()) {
            if (!collisionTypeMissing) {
                collisionTypeMissing = true;
                LOGGER.warn("AXION : type de dégâts {} absent des données ; les collisions ne "
                        + "blessent pas", COLLISION.location());
            }
            return;
        }
        target.hurt(new DamageSource(type.get()), amount);
    }
}
