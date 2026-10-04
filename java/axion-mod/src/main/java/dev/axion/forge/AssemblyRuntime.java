package dev.axion.forge;

import com.mojang.logging.LogUtils;
import dev.axion.asset.A3dSections;
import dev.axion.asset.AssetEntry;
import dev.axion.asset.AssetRegistry;
import dev.axion.definition.AssemblyKind;
import dev.axion.definition.Definition;
import dev.axion.definition.DefinitionRegistry;
import dev.axion.physics.BodyBounds;
import dev.axion.physics.BodyLedger;
import dev.axion.physics.BodyState;
import dev.axion.physics.SimCommandProvider;
import dev.axion.physics.SimCommandStream;
import dev.axion.physics.SimStateSink;
import dev.axion.world.DimensionId;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.joml.Quaternionf;
import org.slf4j.Logger;

/**
 * Boucle d'exécution des assemblies (intégration C-40 ↔ C-50) : relie chaque
 * {@link AxionEntity} liée à un corps physique natif, et lui réapplique l'état simulé.
 *
 * <ul>
 *   <li>À l'apparition d'une entité liée (serveur) : résout les colliders de son asset
 *       (section {@code PHYS} de l'A3D), lui attribue un handle, et met en file une commande
 *       {@code CREATE_ASSEMBLY} (étape 1 du pas, C-40).
 *   <li>Chaque tick : les commandes en file (création, retrait) sont émises via
 *       {@link SimCommandProvider}.
 *   <li>Fin de tick : les {@code BodyState} collectés repositionnent et orientent les entités
 *       par handle, et leurs {@code BodyBounds} calent leur hitbox (R-702, ADR-120)
 *       ({@link SimStateSink}).
 *   <li>À la disparition d'une entité : {@code REMOVE_ASSEMBLY}.
 *   <li>Chaque tick, avant les commandes : une assembly sortie sans être retirée — son tronçon
 *       caché —, puis suivie de nouveau sans signal d'entrée, reçoit un nouveau corps
 *       ({@link BodyLedger}) ; sans quoi elle resterait figée.
 * </ul>
 *
 * <p>Seul {@code dev.axion.forge} touche Minecraft (R-401). Le corps est créé avec
 * l'orientation sauvegardée de l'entité ({@code axion:rot}, identité pour une entité neuve).
 * L'index de son handle est l'identifiant réseau de l'entité (ADR-121), valable le temps de
 * la session : la persistance de l'état natif est un sujet distinct (C-52, M4).
 */
public final class AssemblyRuntime implements SimCommandProvider, SimStateSink {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Génération de handle fixe pour la session (l'index suffit à distinguer). */
    public static final int GENERATION = 1;

    private final DefinitionRegistry definitions;
    private final AssetRegistry assets;

    /**
     * Index de handle → entité dotée d'un corps : route l'application des états, évite une
     * double création, route le retrait, et garde de côté les assemblies cachées qui peuvent
     * revenir. L'index est l'identifiant réseau de l'entité (ADR-121) : unique dans une
     * session de serveur, jamais réemployé, et connu du client — qui retrouve ainsi le corps
     * natif de chaque entité qu'il dessine sans donnée de plus.
     */
    private final BodyLedger<AxionEntity> ledger = new BodyLedger<>();
    /** Commandes de création/retrait en attente d'émission au prochain tick. */
    private final Deque<Pending> pending = new ArrayDeque<>();
    /** Entités dont une emprise aberrante a déjà été signalée : une fois chacune. */
    private final Set<Integer> implausibleBounds = new HashSet<>();
    /**
     * Norme de la vitesse du dernier état de chaque corps, en m/s : elle élargit le rayon
     * d'influence où les entités vanilla deviennent des proxies (R-614, ADR-123 §7).
     */
    private final Map<Integer, Double> speeds = new HashMap<>();

    private record Pending(
            boolean create,
            int index,
            long dimension,
            double[] position,
            float[] rotation,
            int bodyKind,
            byte[] phys) {}

    /**
     * @param definitions definitions chargées (résolution par identifiant)
     * @param assets assets compilés (octets A3D → section {@code PHYS})
     */
    public AssemblyRuntime(DefinitionRegistry definitions, AssetRegistry assets) {
        this.definitions = definitions;
        this.assets = assets;
        MinecraftForge.EVENT_BUS.register(this);
    }

    /** Apparition d'une entité (serveur) : crée son corps natif si elle est liée et a des colliders. */
    @SubscribeEvent
    public void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof AxionEntity assembly)) {
            return;
        }
        if (!ledger.hasBody(handleIndexOf(assembly))) {
            createBody(assembly, (Level) event.getLevel());
        }
    }

    /**
     * Met en file la création du corps d'une assembly liée, à sa position et à son orientation
     * — sauvegardée ({@code axion:rot}, §22.2) pour une entité qui entre, simulée pour une
     * entité qui revient : un corps tombé sur le flanc le reste.
     *
     * @return vrai si un corps est demandé ; faux pour une assembly inerte, sans definition, ou
     *     dont l'asset n'est pas prêt ou n'a pas de collider — elle reste inerte au physique
     */
    private boolean createBody(AxionEntity assembly, Level level) {
        if (assembly.isInert()) {
            return false;
        }
        Definition definition = definitions.get(assembly.definitionId()).orElse(null);
        if (definition == null) {
            return false;
        }
        byte[] phys = physOf(definition);
        if (phys == null) {
            return false;
        }
        int index = handleIndexOf(assembly);
        long dimension = DimensionId.of(level.dimension().location().toString());
        double[] position = {assembly.getX(), assembly.getY(), assembly.getZ()};
        Quaternionf saved = assembly.bodyRotation();
        float[] rotation = {saved.x(), saved.y(), saved.z(), saved.w()};
        pending.add(
                new Pending(true, index, dimension, position, rotation, bodyKind(definition.kind()), phys));
        ledger.attach(index, assembly);
        return true;
    }

    /**
     * Fin de suivi d'une entité (serveur) : retire son corps natif. Retirée du monde, elle est
     * oubliée ; seulement cachée — {@code setRemoved} pose la raison du retrait avant de
     * prévenir le gestionnaire d'entités, qui seul émet cette sortie (bytecode lu) —, elle est
     * mise de côté, et retrouve un corps si on la suit de nouveau.
     */
    @SubscribeEvent
    public void onLeave(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof AxionEntity assembly)) {
            return;
        }
        int index = handleIndexOf(assembly);
        if (!ledger.detach(index, assembly, assembly.getRemovalReason() != null)) {
            return;
        }
        implausibleBounds.remove(index);
        speeds.remove(index);
        pending.add(new Pending(false, index, 0L, null, null, 0, null));
    }

    /**
     * Visite les assemblies dotées d'un corps, par index de handle croissant, avec la norme de
     * la vitesse de leur dernier état (nulle avant le premier).
     *
     * @param visitor reçoit l'entité et sa vitesse, en m/s
     */
    public void forEachBody(BiConsumer<AxionEntity, Double> visitor) {
        ledger.forEachBody((index, entity) -> visitor.accept(entity, speeds.getOrDefault(index, 0.0)));
    }

    /**
     * {@return l'index du handle natif d'une assembly : l'identifiant réseau de son entité}
     *
     * <p>Le même des deux côtés (ADR-121) : le serveur crée le corps sous cet index, le client
     * y retrouve le corps de chaque entité qu'il dessine, sous la génération
     * {@link #GENERATION}.
     *
     * @param assembly entité de l'assembly
     */
    public static int handleIndexOf(AxionEntity assembly) {
        return assembly.getId();
    }

    @Override
    public SimCommandStream commandsForTick(long tick) {
        reconcile();
        SimCommandStream stream = new SimCommandStream();
        Pending cmd;
        while ((cmd = pending.poll()) != null) {
            if (cmd.create()) {
                stream.createAssembly(
                        cmd.index(),
                        GENERATION,
                        cmd.dimension(),
                        cmd.position(),
                        cmd.rotation(),
                        cmd.bodyKind(),
                        cmd.phys());
            } else {
                stream.removeAssembly(cmd.index(), GENERATION);
            }
        }
        return stream;
    }

    /**
     * Rend un corps aux assemblies cachées que le monde suit de nouveau, sans qu'aucune entrée
     * ne l'ait signalé. Le monde suit une entité tant que {@code getEntity} la trouve : ce
     * dernier ne lit que les entités suivies, que le suivi ajoute et que sa fin retire (bytecode
     * lu). Les commandes de retrait, mises en file à la sortie, partent avant ces créations.
     */
    private void reconcile() {
        List<Map.Entry<Integer, AxionEntity>> returning = ledger.reconcile(new BodyLedger.Probe<>() {
            @Override
            public boolean removed(AxionEntity entity) {
                return entity.isRemoved();
            }

            @Override
            public boolean tracked(int index, AxionEntity entity) {
                return entity.level().getEntity(index) == entity;
            }
        });
        for (Map.Entry<Integer, AxionEntity> entry : returning) {
            AxionEntity assembly = entry.getValue();
            if (createBody(assembly, assembly.level())) {
                LOGGER.debug("AXION : assembly {} suivie de nouveau sans entrée, corps recréé", assembly.getUUID());
            }
        }
    }

    @Override
    public void applyStates(long tick, List<BodyState> states, List<BodyBounds> bounds) {
        // ADR-120 : l'emprise de rang i est celle de l'état de rang i. Sans emprises (natif
        // antérieur au schéma 1 de SIM_OUT), les hitbox restent provisoires.
        boolean withBounds = bounds.size() == states.size();
        for (int i = 0; i < states.size(); i++) {
            BodyState state = states.get(i);
            if (state.handleGeneration() != GENERATION) {
                continue;
            }
            AxionEntity entity = ledger.entity(state.handleIndex());
            if (entity == null) {
                continue;
            }
            double[] p = state.position();
            // Position autoritaire du natif (coords monde recomposées par l'origine flottante).
            // Appliquée en fin de tick, elle prime sur toute position vanilla.
            entity.setPos(p[0], p[1], p[2]);
            float[] q = state.rotation();
            entity.setBodyRotation(q[0], q[1], q[2], q[3]);
            float[] v = state.linearVelocity();
            speeds.put(state.handleIndex(), Math.sqrt((double) v[0] * v[0] + (double) v[1] * v[1] + (double) v[2] * v[2]));
            if (withBounds) {
                applyBounds(entity, bounds.get(i));
            }
        }
    }

    /**
     * Cale la hitbox d'une entité sur l'emprise de son corps (R-702), après contrôle : une
     * donnée venue du natif reste une donnée externe. Aberrante, elle est ignorée pour ce
     * tick, la précédente gardée, et l'écart dit une seule fois par entité.
     */
    private void applyBounds(AxionEntity entity, BodyBounds bounds) {
        if (bounds.isPlausible()) {
            entity.setBodyBounds(bounds.min(), bounds.max());
            return;
        }
        if (implausibleBounds.add(entity.getId())) {
            LOGGER.warn(
                    "AXION : emprise aberrante ignorée pour l'assembly {} — min {}, max {}",
                    entity.getUUID(),
                    Arrays.toString(bounds.min()),
                    Arrays.toString(bounds.max()));
        }
    }

    /** {@return les octets de la section PHYS de l'asset de la definition, ou {@code null}} */
    private byte[] physOf(Definition definition) {
        AssetEntry entry = assets.entry(definition.asset());
        if (entry == null) {
            return null;
        }
        byte[] a3d = entry.compiled();
        return a3d == null ? null : A3dSections.section(a3d, "PHYS");
    }

    /** Mappe le genre d'assembly (DM-09) vers un type de corps (§10.3). */
    private static int bodyKind(AssemblyKind kind) {
        return kind == AssemblyKind.STATIC
                ? SimCommandStream.BODY_STATIC
                : SimCommandStream.BODY_DYNAMIC;
    }

    /** Se désabonne du bus d'événements et oublie les assemblies (arrêt du serveur). */
    public void close() {
        MinecraftForge.EVENT_BUS.unregister(this);
        ledger.clear();
        speeds.clear();
    }
}
