package dev.axion.forge.client;

import dev.axion.asset.DefinitionCompiler;
import dev.axion.definition.Definition;
import dev.axion.forge.AxionEntity;
import dev.axion.forge.EntityPresenceBridge;
import dev.axion.lifecycle.AxionRuntime;
import dev.axion.net.Interpolation;
import dev.axion.net.ServerClock;
import dev.axion.physics.EntityProxySelector;
import dev.axion.physics.PredictedWorld;
import dev.axion.physics.SimCommandStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.joml.Quaternionf;
import org.joml.Vector3d;

/**
 * Les proxies du monde prédit (ADR-168 D1, R-1680, précision 6c-C2) : ce que le véhicule prédit peut heurter, hors des
 * blocs.
 *
 * <ul>
 *   <li>Les autres assemblies proches, avec leurs vrais colliders, tous leurs corps cinématiques, chacun posé à chaque
 *       pas — rejeux compris — à sa pose au tick du serveur où s'applique l'entrée du pas, telle que ses snapshots la
 *       donnent : interpolée, ou extrapolée au plus de {@code net.extrapolation_max_ms}.</li>
 *   <li>Les entités vanilla proches, comme au serveur (R-614) : les mêmes règles d'éligibilité et de forme, leur boîte
 *       avancée de leur vitesse jusqu'au tick du pas.</li>
 * </ul>
 *
 * <p>Le choix se refait à chaque pas d'une entrée neuve, dans {@code net.prediction_proxy_radius} autour du véhicule.
 * Thread du client seul.
 */
final class ClientProxies {

    /** Un proxy d'assembly vivant dans le monde prédit : son handle est l'id de son entité. */
    private record Proxy(AxionEntity entity, int rootNode) {}

    private final AxionRuntime runtime;
    private final long dimension;
    private final Map<Integer, Proxy> assemblies = new LinkedHashMap<>();
    private List<SimCommandStream.EntityProxy> entities = List.of();

    /**
     * @param runtime le runtime du client, qui tient les definitions compilées
     * @param dimension la dimension du monde prédit
     */
    ClientProxies(AxionRuntime runtime, long dimension) {
        this.runtime = runtime;
        this.dimension = dimension;
    }

    /**
     * Refait le choix des proxies, au pas d'une entrée neuve : les assemblies entrées dans le rayon naissent proxies, celles
     * qui en sortent ou disparaissent sont retirées ; les entités vanilla sont relevées.
     *
     * @param level le niveau du client
     * @param vehicle le véhicule prédit, posé à sa pose prédite
     * @param speed sa vitesse prédite, en m/s
     * @param radius {@code net.prediction_proxy_radius}, en blocs
     * @return les créations et retraits à soumettre
     */
    SimCommandStream select(ClientLevel level, AxionEntity vehicle, double speed, double radius) {
        SimCommandStream out = new SimCommandStream();
        AABB around = vehicle.getBoundingBox().inflate(radius);
        Map<Integer, AxionEntity> wanted = new LinkedHashMap<>();
        for (AxionEntity other : level.getEntitiesOfClass(AxionEntity.class, around,
                other -> other != vehicle && other.isAlive() && other.interpolating())) {
            wanted.put(other.getId(), other);
        }
        for (Iterator<Map.Entry<Integer, Proxy>> it = assemblies.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, Proxy> entry = it.next();
            if (!wanted.containsKey(entry.getKey())) {
                out.removeAssembly(entry.getKey(), PredictedWorld.GENERATION);
                it.remove();
            }
        }
        for (AxionEntity other : wanted.values()) {
            if (assemblies.containsKey(other.getId())) {
                continue;
            }
            Definition definition = runtime.definitions().get(other.definitionId()).orElse(null);
            DefinitionCompiler.Summary summary = definition == null ? null
                    : runtime.compiledDefinition(definition.id().toString()).orElse(null);
            if (summary == null) {
                // Une definition que ce client n'a pas compilée : rien à poser, la correction du serveur rattrape.
                continue;
            }
            Quaternionf rotation = other.orientation();
            out.createKinematicAssembly(other.getId(), PredictedWorld.GENERATION, dimension,
                    new double[] {other.getX(), other.getY(), other.getZ()},
                    new float[] {rotation.x(), rotation.y(), rotation.z(), rotation.w()}, definition.hash());
            assemblies.put(other.getId(), new Proxy(other, summary.rootNode()));
        }
        List<EntityProxySelector.Candidate> candidates = new ArrayList<>();
        for (Entity entity : level.getEntities((Entity) null, around, EntityPresenceBridge::eligible)) {
            candidates.add(EntityPresenceBridge.candidateOf(entity));
        }
        List<SimCommandStream.EntityProxy> chosen = EntityProxySelector.select(
                List.of(new EntityProxySelector.Assembly(EntityPresenceBridge.boxOf(around), speed)), candidates);
        entities = chosen.size() > SimCommandStream.MAX_ENTITY_PROXIES
                ? chosen.subList(0, SimCommandStream.MAX_ENTITY_PROXIES) : chosen;
        return out;
    }

    /**
     * La pose de chaque proxy pour un pas.
     *
     * @param stepTick le tick du serveur où s'applique l'entrée du pas
     * @param displayedTick le tick du serveur que le client voit pour les entités vanilla : celui de la dernière
     *     correction reçue
     * @return les poses à soumettre avec le pas
     */
    SimCommandStream poses(long stepTick, long displayedTick) {
        SimCommandStream out = new SimCommandStream();
        double millis = stepTick * ServerClock.TICK_MILLIS;
        for (Map.Entry<Integer, Proxy> entry : assemblies.entrySet()) {
            Interpolation.Pose pose = entry.getValue().entity().poseAt(millis);
            if (pose != null) {
                out.setAssemblyPose(entry.getKey(), PredictedWorld.GENERATION, bodies(pose, entry.getValue().rootNode()));
            }
        }
        if (!entities.isEmpty()) {
            double lead = Math.max(0, stepTick - displayedTick) * ServerClock.TICK_MILLIS / 1000.0;
            List<SimCommandStream.EntityProxy> moved = new ArrayList<>(entities.size());
            for (SimCommandStream.EntityProxy proxy : entities) {
                double[] c = proxy.center();
                float[] v = proxy.velocity();
                moved.add(new SimCommandStream.EntityProxy(proxy.entity(),
                        new double[] {c[0] + v[0] * lead, c[1] + v[1] * lead, c[2] + v[2] * lead}, proxy.halfExtents(),
                        v, proxy.shape()));
            }
            out.setEntityProxies(dimension, moved);
        }
        return out;
    }

    /** {@return les retraits de tous les proxies d'assembly, à la fermeture du monde prédit} */
    SimCommandStream releaseAll() {
        SimCommandStream out = new SimCommandStream();
        for (int handle : assemblies.keySet()) {
            out.removeAssembly(handle, PredictedWorld.GENERATION);
        }
        assemblies.clear();
        entities = List.of();
        return out;
    }

    /** {@return le nombre de proxies d'assembly vivants} */
    int assemblyCount() {
        return assemblies.size();
    }

    /** {@return le nombre d'entités vanilla posées en proxies} */
    int entityCount() {
        return entities.size();
    }

    /** La pose monde de chaque corps : le corps racine à la pose, les autres composés de leur pose relative. */
    private static List<SimCommandStream.KinematicPose> bodies(Interpolation.Pose pose, int rootNode) {
        float[] q = pose.rotation();
        Quaternionf root = new Quaternionf(q[0], q[1], q[2], q[3]);
        List<SimCommandStream.KinematicPose> poses = new ArrayList<>(1 + pose.bodies().size());
        poses.add(new SimCommandStream.KinematicPose(rootNode, new double[] {pose.x(), pose.y(), pose.z()}, q.clone()));
        for (Interpolation.BodyPose body : pose.bodies()) {
            Vector3d offset = new Vector3d(body.offset()[0], body.offset()[1], body.offset()[2]).rotate(
                    new org.joml.Quaterniond(root.x(), root.y(), root.z(), root.w()));
            float[] r = body.rotation();
            Quaternionf world = new Quaternionf(root).mul(new Quaternionf(r[0], r[1], r[2], r[3]));
            poses.add(new SimCommandStream.KinematicPose(body.node(),
                    new double[] {pose.x() + offset.x, pose.y() + offset.y, pose.z() + offset.z},
                    new float[] {world.x(), world.y(), world.z(), world.w()}));
            if (poses.size() == SimCommandStream.MAX_ASSEMBLY_BODIES) {
                break;
            }
        }
        return poses;
    }
}
