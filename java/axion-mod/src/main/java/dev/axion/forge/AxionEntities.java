package dev.axion.forge;

import dev.axion.AxionMod;
import dev.axion.asset.DefinitionCompiler;
import dev.axion.definition.DefinitionRegistry;
import dev.axion.definition.Seating;
import dev.axion.lifecycle.AxionRuntime;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.LongSupplier;
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
     * <p>R-702 veut l'AABB physique courante : depuis ADR-120, le natif rapporte
     * l'emprise de chaque corps et {@link AxionEntity#makeBoundingBox} en fait la
     * hitbox. Cette boîte d'un bloc ne sert plus que tant qu'aucune emprise n'a été
     * reçue — avant le premier tick simulé, ou pour une assembly sans corps
     * (statique, ou sans collider) — et le libellé de debug le dit.
     */
    static final float PROVISIONAL_SIZE = 1.0f;

    private static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, AxionMod.MODID);

    /**
     * Portée du suivi, en tronçons : celle de {@code net.sync_radius} par défaut (96 blocs). Le type
     * d'entité s'enregistre avant la lecture de la configuration ; une valeur plus grande que la portée
     * est bornée par elle, et par la distance de vue du serveur.
     */
    static final int SYNC_RADIUS_CHUNKS = 6;

    /** L'entité unique d'AXION. */
    public static final RegistryObject<EntityType<AxionEntity>> ASSEMBLY =
            ENTITY_TYPES.register("assembly", () -> EntityType.Builder
                    .<AxionEntity>of(AxionEntity::new, MobCategory.MISC)
                    .sized(PROVISIONAL_SIZE, PROVISIONAL_SIZE)
                    // ADR-150 D2 : le suivi de Minecraft porte la visibilité — qui voit l'assembly,
                    // son apparition, sa disparition —, sur une portée qui couvre net.sync_radius
                    // (96 blocs par défaut, six tronçons). Son suivi de position est tu : un
                    // intervalle que le compteur de ServerEntity n'atteint qu'à son premier tick.
                    // La position part dans les snapshots d'AXION.
                    .clientTrackingRange(SYNC_RADIUS_CHUNKS)
                    .updateInterval(Integer.MAX_VALUE)
                    .build(AxionMod.MODID + ":assembly"));

    /**
     * Definitions consultées au chargement d'une entité.
     *
     * <p>Une entité est construite par Minecraft, sans rien d'AXION à portée :
     * elle retrouve la registry par ce fournisseur, que le point d'entrée pose.
     */
    private static volatile Supplier<DefinitionRegistry> definitions = DefinitionRegistry::empty;

    /** Plafond des données personnalisées, que le point d'entrée relie au runtime ; nul avant. */
    private static volatile LongSupplier maxCustomBytes = () -> 0;

    /** Le résumé de la compilation d'une definition (ADR-156 D4), par identifiant. */
    private static volatile Function<String, Optional<DefinitionCompiler.Summary>> summaries = id -> Optional.empty();

    /** Les sièges et les interactions d'une definition compilée (ADR-158 D1), par identifiant. */
    private static volatile Function<String, Optional<Seating>> seatings = id -> Optional.empty();

    /** Le rayon contre les colliders d'une assembly (ADR-158 D2) ; sans runtime, il ne touche rien. */
    private static volatile Rays rays = (index, generation, ray, out) -> -2001;

    /** Le rayon contre les colliders d'une assembly, vu de l'entité. */
    @FunctionalInterface
    interface Rays {
        /**
         * @param index index du handle de l'assembly
         * @param generation génération du handle
         * @param ray origine monde, direction, portée
         * @param out l'impact ; {@code out[0]} vaut {@code -1} si rien n'est touché
         * @return 0 si l'appel a réussi
         */
        int cast(int index, int generation, double[] ray, double[] out);
    }

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
        maxCustomBytes = runtime::maxCustomBytes;
        summaries = runtime::compiledDefinition;
        seatings = runtime::seating;
        rays = runtime::raycastAssembly;
    }

    /** {@return les definitions chargées} */
    static DefinitionRegistry definitions() {
        return definitions.get();
    }

    /** {@return le plafond des données personnalisées d'une assembly, en octets (ADR-153 D7)} */
    static long maxCustomBytes() {
        return maxCustomBytes.getAsLong();
    }

    /**
     * {@return le résumé de la compilation d'une definition, vide tant qu'elle l'attend}
     *
     * @param id identifiant de la definition
     */
    static Optional<DefinitionCompiler.Summary> summary(String id) {
        return summaries.apply(id);
    }

    /**
     * {@return les sièges et les interactions d'une definition compilée, vide tant qu'elle l'attend}
     *
     * @param id identifiant de la definition
     */
    static Optional<Seating> seating(String id) {
        return seatings.apply(id);
    }

    /** {@return le rayon contre les colliders d'une assembly} */
    static Rays rays() {
        return rays;
    }
}
