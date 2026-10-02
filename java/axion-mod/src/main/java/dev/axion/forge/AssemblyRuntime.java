package dev.axion.forge;

import dev.axion.asset.A3dSections;
import dev.axion.asset.AssetEntry;
import dev.axion.asset.AssetRegistry;
import dev.axion.definition.AssemblyKind;
import dev.axion.definition.Definition;
import dev.axion.definition.DefinitionRegistry;
import dev.axion.physics.BodyState;
import dev.axion.physics.SimCommandProvider;
import dev.axion.physics.SimCommandStream;
import dev.axion.physics.SimStateSink;
import dev.axion.world.DimensionId;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.joml.Quaternionf;

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
 *   <li>Fin de tick : les {@code BodyState} collectés repositionnent les entités par handle
 *       ({@link SimStateSink}).
 *   <li>À la disparition d'une entité : {@code REMOVE_ASSEMBLY}.
 * </ul>
 *
 * <p>Seul {@code dev.axion.forge} touche Minecraft (R-401). L'orientation initiale est
 * l'identité (l'entité vanilla n'a qu'un lacet/tangage) ; le rendu orienté et l'interpolation
 * viendront avec la chaîne de rendu (C-60+). Les handles sont attribués en mémoire pour la
 * session : la persistance de l'état natif est un sujet distinct (C-52, M4).
 */
public final class AssemblyRuntime implements SimCommandProvider, SimStateSink {

    /** Génération de handle fixe pour la session (l'index suffit à distinguer). */
    private static final int GENERATION = 1;

    private final DefinitionRegistry definitions;
    private final AssetRegistry assets;

    private int nextIndex = 1;
    /** entityId → index de handle : évite une double création, route le retrait. */
    private final Map<Integer, Integer> handleByEntity = new HashMap<>();
    /** index de handle → entité : route l'application des états. */
    private final Map<Integer, AxionEntity> entityByHandle = new HashMap<>();
    /** Commandes de création/retrait en attente d'émission au prochain tick. */
    private final Deque<Pending> pending = new ArrayDeque<>();

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
        if (assembly.isInert() || handleByEntity.containsKey(assembly.getId())) {
            return;
        }
        Definition definition = definitions.get(assembly.definitionId()).orElse(null);
        if (definition == null) {
            return;
        }
        byte[] phys = physOf(definition);
        if (phys == null) {
            // Asset non prêt ou sans collider : pas de corps (l'entité reste inerte au physique).
            return;
        }
        int index = nextIndex++;
        long dimension =
                DimensionId.of(((Level) event.getLevel()).dimension().location().toString());
        double[] position = {assembly.getX(), assembly.getY(), assembly.getZ()};
        // Orientation sauvegardée (§22.2, axion:rot), identité pour une entité neuve : un
        // corps tombé sur le flanc le reste après rechargement.
        Quaternionf saved = assembly.bodyRotation();
        float[] rotation = {saved.x(), saved.y(), saved.z(), saved.w()};
        pending.add(
                new Pending(true, index, dimension, position, rotation, bodyKind(definition.kind()), phys));
        handleByEntity.put(assembly.getId(), index);
        entityByHandle.put(index, assembly);
    }

    /** Disparition d'une entité (serveur) : retire son corps natif. */
    @SubscribeEvent
    public void onLeave(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof AxionEntity assembly)) {
            return;
        }
        Integer index = handleByEntity.remove(assembly.getId());
        if (index == null) {
            return;
        }
        entityByHandle.remove(index);
        pending.add(new Pending(false, index, 0L, null, null, 0, null));
    }

    @Override
    public SimCommandStream commandsForTick(long tick) {
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

    @Override
    public void applyStates(long tick, List<BodyState> states) {
        for (BodyState state : states) {
            if (state.handleGeneration() != GENERATION) {
                continue;
            }
            AxionEntity entity = entityByHandle.get(state.handleIndex());
            if (entity == null) {
                continue;
            }
            double[] p = state.position();
            // Position autoritaire du natif (coords monde recomposées par l'origine flottante).
            // Appliquée en fin de tick, elle prime sur toute position vanilla.
            entity.setPos(p[0], p[1], p[2]);
            float[] q = state.rotation();
            entity.setBodyRotation(q[0], q[1], q[2], q[3]);
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

    /** Se désabonne du bus d'événements (arrêt du serveur). */
    public void close() {
        MinecraftForge.EVENT_BUS.unregister(this);
    }
}
