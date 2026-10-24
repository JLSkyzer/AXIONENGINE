package dev.axion.forge;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import dev.axion.asset.A3dSections;
import dev.axion.asset.AssetEntry;
import dev.axion.asset.AssetRegistry;
import dev.axion.definition.AssemblyKind;
import dev.axion.definition.CollisionGroupRegistry;
import dev.axion.definition.Definition;
import dev.axion.definition.DefinitionRegistry;
import dev.axion.diag.EntityMetrics;
import dev.axion.physics.AssemblyBodies;
import dev.axion.physics.BodyBounds;
import dev.axion.physics.BodyDescription;
import dev.axion.physics.BodyLedger;
import dev.axion.physics.BodyState;
import dev.axion.physics.BodyStateFlags;
import dev.axion.physics.CollisionFilter;
import dev.axion.physics.JointDescription;
import dev.axion.physics.JointState;
import dev.axion.physics.MaterialMarking;
import dev.axion.physics.RefitMarking;
import dev.axion.physics.SimCommandProvider;
import dev.axion.physics.SimCommandStream;
import dev.axion.physics.SimStateSink;
import dev.axion.world.DimensionId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
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

    private final Supplier<DefinitionRegistry> definitions;
    private final Supplier<AssetRegistry> assets;
    /** Index d'un matériau dans la table de la session (ADR-149 D2). */
    private final ToIntFunction<String> materialIndex;
    /**
     * {@code modules.joints} : faux, les joints des definitions sont ignorés, avec un avertissement
     * par definition, et leurs corps déclarés restent séparés (ADR-151 D5).
     */
    private final boolean joints;
    /** Definitions dont les joints ignorés ont déjà été signalés : une fois chacune. */
    private final Set<String> jointsIgnored = new HashSet<>();

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
    /**
     * Node racine du corps racine de chaque assembly (ADR-151 D2) : son état pose l'entité ; les
     * états des autres corps la complètent.
     */
    private final Map<Integer, Integer> rootNodes = new HashMap<>();

    private record Pending(
            boolean create,
            int index,
            long dimension,
            double[] position,
            float[] rotation,
            List<SimCommandStream.AssemblyBody> bodies,
            List<JointDescription> joints,
            List<float[]> jamPoints,
            float assemblyMass,
            boolean selfCollision,
            byte[] nodes,
            byte[] phys) {}

    /**
     * Les deux registres sont lus à chaque création de corps : un {@code /reload} ou un
     * {@code /axion assets reload} en installe de nouveaux, que la création suivante prend.
     *
     * @param definitions definitions courantes (résolution par identifiant)
     * @param assets assets compilés courants (octets A3D → section {@code PHYS}), ou {@code null}
     *     avant tout chargement
     * @param materialIndex index d'un matériau physique dans la table de la session (ADR-149)
     * @param joints {@code modules.joints} : faux, les joints des definitions sont ignorés
     */
    public AssemblyRuntime(
            Supplier<DefinitionRegistry> definitions,
            Supplier<AssetRegistry> assets,
            ToIntFunction<String> materialIndex,
            boolean joints) {
        this.definitions = definitions;
        this.assets = assets;
        this.materialIndex = materialIndex;
        this.joints = joints;
        MinecraftForge.EVENT_BUS.register(this);
    }

    /** Apparition d'une entité (serveur) : crée son corps natif si elle est liée et a des colliders. */
    @SubscribeEvent
    public void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof AxionEntity assembly)) {
            return;
        }
        // R-500 : une entrée, et une erreur si elle entre inerte (R-704).
        EntityMetrics.GLOBAL.joined(assembly.isInert());
        if (!ledger.hasBody(handleIndexOf(assembly))) {
            createBody(assembly, (Level) event.getLevel());
        }
        EntityMetrics.GLOBAL.bodies(ledger.bodies());
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
        DefinitionRegistry registry = definitions.get();
        Definition definition = registry.get(assembly.definitionId()).orElse(null);
        if (definition == null) {
            return false;
        }
        byte[] phys = sectionOf(definition, "PHYS");
        if (phys == null) {
            return false;
        }
        int index = handleIndexOf(assembly);
        long dimension = DimensionId.of(level.dimension().location().toString());
        double[] position = {assembly.getX(), assembly.getY(), assembly.getZ()};
        Quaternionf saved = assembly.bodyRotation();
        float[] rotation = {saved.x(), saved.y(), saved.z(), saved.w()};
        // ADR-130 : la description DM-08 que la definition déclare ; REFITTABLE posé par Java sur les
        // colliders des parts déformables d'un corps dynamique (R-623).
        JsonObject root = definition.root();
        int kind = bodyKind(definition.kind());
        BodyDescription body = BodyDescription.of(root, kind);
        byte[] nodes = sectionOf(definition, "NODE");
        if (kind == SimCommandStream.BODY_DYNAMIC) {
            phys = RefitMarking.mark(phys, nodes, RefitMarking.rootsOf(root));
        }
        // ADR-149 D2 : chaque collider porte l'index du matériau de sa part, l'acier générique sans.
        phys = MaterialMarking.mark(phys, nodes, MaterialMarking.rootsOf(root), materialIndex);
        // R-980 : les groupes que la definition nomme, sur chaque collider du corps — résolus en bits
        // par le registre contre lequel elle a été validée (ADR-132 A1), groupes tiers compris.
        CollisionGroupRegistry groups = registry.collisionGroups();
        phys = CollisionFilter.apply(phys, groups.membership(root), groups.filter(root));
        // ADR-151 D2, D7 : les corps que la definition déclare, chacun à son node racine, ceux que
        // créent les b_node de ses joints, et ses joints ; la section NODE part avec PHYS, chaque
        // collider posé par la chaîne de son node.
        AssemblyBodies.Layout layout = AssemblyBodies.of(root, nodes, body, joints);
        if (layout == null) {
            LOGGER.warn(
                    "AXION : {} déclare un corps ou un joint qui ne se résout pas contre son asset ; assembly inerte",
                    definition.id());
            return false;
        }
        if (!joints && root.has("joints") && jointsIgnored.add(definition.id().toString())) {
            LOGGER.warn("AXION : modules.joints est désactivé ; les joints de {} sont ignorés", definition.id());
        }
        rootNodes.put(index, layout.rootNode());
        assembly.resetJoints(layout.joints().size());
        byte[] nodeSection = nodes == null ? new byte[0] : nodes;
        pending.add(new Pending(true, index, dimension, position, rotation, layout.bodies(), layout.joints(),
                layout.jamPoints(), layout.assemblyMass(), layout.selfCollision(), nodeSection, phys));
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
        EntityMetrics.GLOBAL.left();
        int index = handleIndexOf(assembly);
        boolean detached = ledger.detach(index, assembly, assembly.getRemovalReason() != null);
        EntityMetrics.GLOBAL.bodies(ledger.bodies());
        if (!detached) {
            return;
        }
        implausibleBounds.remove(index);
        speeds.remove(index);
        rootNodes.remove(index);
        pending.add(new Pending(false, index, 0L, null, null, null, null, null, 0.0f, false, null, null));
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
     * {@return l'assembly dotée du corps de ce handle, ou {@code null}}
     *
     * @param index index du handle
     * @param generation génération du handle : {@link #GENERATION} pour une assembly de la session
     */
    public AxionEntity assembly(int index, int generation) {
        return generation == GENERATION ? ledger.entity(index) : null;
    }

    /**
     * {@return ce qui nomme une assembly dans le journal des faits de simulation : sa
     * definition, sa position et sa dimension, ou son seul index si elle n'a plus de corps}
     *
     * @param index index du handle
     */
    public String describe(int index) {
        AxionEntity entity = ledger.entity(index);
        if (entity == null) {
            return "l'assembly #" + index;
        }
        return String.format(
                Locale.ROOT,
                "l'assembly #%d (%s) en %.1f %.1f %.1f, %s",
                index,
                entity.definitionId(),
                entity.getX(),
                entity.getY(),
                entity.getZ(),
                entity.level().dimension().location());
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
                        cmd.bodies(),
                        cmd.joints(),
                        cmd.jamPoints(),
                        cmd.assemblyMass(),
                        cmd.selfCollision(),
                        cmd.nodes(),
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
    public void applyStates(long tick, List<BodyState> states, List<BodyBounds> bounds, List<JointState> joints) {
        AuthoritativeThread.require();
        long start = System.nanoTime();
        // ADR-151 D8 : l'état de chaque joint rapporté, retenu sur son entité avant que son état réseau
        // ne se calcule ; le natif tait ceux d'une assembly endormie, qui gardent le leur.
        for (JointState joint : joints) {
            AxionEntity entity = joint.handleGeneration() == GENERATION ? ledger.entity(joint.handleIndex()) : null;
            if (entity != null) {
                entity.setJointFlags(joint.index(), joint.flags());
            }
        }
        // ADR-120 : l'emprise de rang i est celle de l'état de rang i. Sans emprises (natif
        // antérieur au schéma 1 de SIM_OUT), les hitbox restent provisoires.
        boolean withBounds = bounds.size() == states.size();
        // ADR-151 D2 : les états d'une assembly, regroupés dans l'ordre du natif ; celui de son corps
        // racine pose l'entité.
        Map<Integer, List<Integer>> ranks = new LinkedHashMap<>();
        for (int i = 0; i < states.size(); i++) {
            BodyState state = states.get(i);
            if (state.handleGeneration() == GENERATION) {
                ranks.computeIfAbsent(state.handleIndex(), key -> new ArrayList<>()).add(i);
            }
        }
        for (Map.Entry<Integer, List<Integer>> group : ranks.entrySet()) {
            int handle = group.getKey();
            AxionEntity entity = ledger.entity(handle);
            if (entity == null) {
                continue;
            }
            List<Integer> own = group.getValue();
            int rootNode = rootNodes.getOrDefault(handle, 0);
            int i = -1;
            List<AxionEntity.BodyPose> secondary = new ArrayList<>(own.size());
            boolean sleeping = true;
            for (int rank : own) {
                BodyState other = states.get(rank);
                sleeping &= (other.flags() & BodyStateFlags.SLEEPING) != 0;
                if (i < 0 && other.node() == rootNode) {
                    i = rank;
                    continue;
                }
                double[] o = other.position();
                float[] r = other.rotation();
                secondary.add(new AxionEntity.BodyPose(
                        other.node(), o[0], o[1], o[2], new Quaternionf(r[0], r[1], r[2], r[3])));
            }
            entity.setSecondaryBodies(secondary);
            if (i < 0) {
                // ADR-151 D8 : un corps racine statique n'a pas d'état. L'entité garde sa pose, la sienne,
                // et ses corps secondaires partent relatifs à elle ; son emprise reste celle qu'elle a.
                speeds.put(handle, 0.0);
                entity.updateNetState(new float[3], new float[3], sleeping);
                continue;
            }
            BodyState state = states.get(i);
            double[] p = state.position();
            // Position autoritaire du natif (coords monde recomposées par l'origine flottante).
            // Appliquée en fin de tick, elle prime sur toute position vanilla.
            entity.setPos(p[0], p[1], p[2]);
            float[] q = state.rotation();
            entity.setBodyRotation(q[0], q[1], q[2], q[3]);
            float[] v = state.linearVelocity();
            speeds.put(state.handleIndex(), Math.sqrt((double) v[0] * v[0] + (double) v[1] * v[1] + (double) v[2] * v[2]));
            if (withBounds) {
                applyBounds(entity, unionOf(states, bounds, own, p));
            }
            // ADR-150 D6 : l'état que les snapshots portent, une fois position, orientation et emprise
            // posées.
            entity.updateNetState(v, state.angularVelocity(), (state.flags() & BodyStateFlags.SLEEPING) != 0);
        }
        // R-500 : la durée de l'application des états, côté entité.
        EntityMetrics.GLOBAL.applied(System.nanoTime() - start);
    }

    /**
     * L'emprise d'une assembly (ADR-151 D8) : l'union de celles de ses corps, relative à la position
     * de son corps racine. Chaque emprise est relative à la translation de son corps.
     */
    private static BodyBounds unionOf(List<BodyState> states, List<BodyBounds> bounds, List<Integer> ranks, double[] root) {
        if (ranks.size() == 1) {
            return bounds.get(ranks.get(0));
        }
        float[] min = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY};
        float[] max = {Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
        for (int rank : ranks) {
            double[] at = states.get(rank).position();
            BodyBounds own = bounds.get(rank);
            for (int axis = 0; axis < 3; axis++) {
                float offset = (float) (at[axis] - root[axis]);
                min[axis] = Math.min(min[axis], offset + own.min()[axis]);
                max[axis] = Math.max(max[axis], offset + own.max()[axis]);
            }
        }
        return new BodyBounds(min, max);
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

    /** {@return les octets d'une section de l'asset de la definition, ou {@code null}} */
    private byte[] sectionOf(Definition definition, String tag) {
        AssetRegistry registry = assets.get();
        AssetEntry entry = registry == null ? null : registry.entry(definition.asset());
        if (entry == null) {
            return null;
        }
        byte[] a3d = entry.compiled();
        return a3d == null ? null : A3dSections.section(a3d, tag);
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
        rootNodes.clear();
    }
}
