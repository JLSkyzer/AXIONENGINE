package dev.axion.forge;

import dev.axion.AxionMod;
import dev.axion.definition.DefinitionRegistry;
import dev.axion.lifecycle.AxionRuntime;
import java.util.function.Supplier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Enregistrement de l'entité d'AXION (C-50, ADR-010).
 *
 * <p>Un seul {@code EntityType}, {@code axion:assembly} : le contenu d'une
 * assembly vient de sa definition, jamais d'une classe par sorte d'objet.
 */
public final class AxionEntities {

    /**
     * Côté de la hitbox, en blocs : <strong>provisoire</strong>.
     *
     * <p>R-702 veut l'AABB physique courante, qui arrive avec la physique en M3.
     * La boîte de l'asset n'atteint pas Java sans étendre l'ABI, et Killian a
     * décidé le 2026-09-13 d'attendre M3 plutôt que de figer ce contrat. D'ici
     * là, la hitbox fait un bloc, et le libellé de debug le dit.
     */
    static final float PROVISIONAL_SIZE = 1.0f;

    private static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, AxionMod.MODID);

    /** L'entité unique d'AXION. */
    public static final RegistryObject<EntityType<AxionEntity>> ASSEMBLY =
            ENTITY_TYPES.register("assembly", () -> EntityType.Builder
                    .<AxionEntity>of(AxionEntity::new, MobCategory.MISC)
                    .sized(PROVISIONAL_SIZE, PROVISIONAL_SIZE)
                    .build(AxionMod.MODID + ":assembly"));

    /**
     * Definitions consultées au chargement d'une entité.
     *
     * <p>Une entité est construite par Minecraft, sans rien d'AXION à portée :
     * elle retrouve la registry par ce fournisseur, que le point d'entrée pose.
     */
    private static volatile Supplier<DefinitionRegistry> definitions = DefinitionRegistry::empty;

    private AxionEntities() {}

    /**
     * Enregistre l'entité et la relie au runtime.
     *
     * @param modBus bus du mod
     * @param runtime runtime dont les definitions sont consultées
     */
    static void register(IEventBus modBus, AxionRuntime runtime) {
        ENTITY_TYPES.register(modBus);
        definitions = runtime::definitions;
    }

    /** {@return les definitions chargées} */
    static DefinitionRegistry definitions() {
        return definitions.get();
    }
}
