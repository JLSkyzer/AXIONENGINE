package dev.axion.forge;

import com.mojang.logging.LogUtils;
import dev.axion.physics.EntityProxySelector;
import dev.axion.physics.SimCommandProvider;
import dev.axion.physics.SimCommandStream;
import dev.axion.world.DimensionId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;

/**
 * Ce que la physique doit savoir du monde vanilla à chaque tick (ADR-123 §2, §5, §7) : où sont
 * les joueurs ({@code SET_OBSERVERS}, R-612/R-613/R-610) et quelles entités peuvent heurter une
 * assembly ({@code SET_ENTITY_PROXIES}, R-614).
 *
 * <ul>
 *   <li><b>Observateurs</b> : les joueurs non spectateurs de chaque dimension.
 *   <li><b>Proxies</b> : autour de chaque assembly dotée d'un corps, les entités que vanilla
 *       tient elle-même pour solides ou poussables, vivantes, ni spectatrices, ni assemblies, ni
 *       passagères d'une assembly ; le rayon d'influence se déduit des vitesses
 *       ({@link EntityProxySelector}).
 * </ul>
 *
 * <p>La vitesse d'une entité se lit dans son déplacement depuis le tick précédent : celle que
 * le serveur tient pour un joueur ne suit pas son mouvement, piloté par son client. Seul
 * {@code dev.axion.forge} touche Minecraft (R-401).
 */
public final class EntityPresenceBridge implements SimCommandProvider {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Vitesse d'entité qui borne la recherche des candidates autour d'une assembly, en m/s :
     * cinq blocs par tick. La marge exacte de chaque candidate se calcule ensuite sur sa vitesse
     * réelle.
     */
    private static final double SEARCH_ENTITY_SPEED = 100.0;

    /** Ticks par seconde : convertit un déplacement par tick en vitesse. */
    private static final double TICKS_PER_SECOND = 20.0;

    private final MinecraftServer server;
    private final AssemblyRuntime assemblies;
    private boolean observersTruncated;
    private boolean proxiesTruncated;

    /**
     * @param server serveur dont les dimensions sont relevées
     * @param assemblies assemblies dotées d'un corps, et leur vitesse
     */
    public EntityPresenceBridge(MinecraftServer server, AssemblyRuntime assemblies) {
        this.server = server;
        this.assemblies = assemblies;
    }

    @Override
    public SimCommandStream commandsForTick(long tick) {
        Map<Level, List<EntityProxySelector.Assembly>> zones = new HashMap<>();
        Map<Level, List<AABB>> searches = new HashMap<>();
        assemblies.forEachBody((assembly, speed) -> {
            AABB bounds = assembly.getBoundingBox();
            zones.computeIfAbsent(assembly.level(), level -> new ArrayList<>())
                    .add(new EntityProxySelector.Assembly(boxOf(bounds), speed));
            searches.computeIfAbsent(assembly.level(), level -> new ArrayList<>())
                    .add(bounds.inflate(EntityProxySelector.margin(speed, SEARCH_ENTITY_SPEED)));
        });

        SimCommandStream stream = new SimCommandStream();
        for (ServerLevel level : server.getAllLevels()) {
            long dimension = DimensionId.of(level.dimension().location().toString());
            double[][] observers = observersOf(level);
            if (observers.length > 0) {
                stream.setObservers(dimension, observers);
            }
            List<EntityProxySelector.Assembly> here = zones.get(level);
            if (here == null) {
                continue;
            }
            Map<Integer, EntityProxySelector.Candidate> candidates = new LinkedHashMap<>();
            for (AABB search : searches.get(level)) {
                for (Entity entity : level.getEntities((Entity) null, search, EntityPresenceBridge::eligible)) {
                    candidates.putIfAbsent(entity.getId(), candidateOf(entity));
                }
            }
            List<SimCommandStream.EntityProxy> proxies =
                    EntityProxySelector.select(here, new ArrayList<>(candidates.values()));
            if (proxies.size() > SimCommandStream.MAX_ENTITY_PROXIES) {
                if (!proxiesTruncated) {
                    proxiesTruncated = true;
                    LOGGER.warn("AXION : plus de {} entités autour des assemblies d'une dimension ; "
                            + "les premières seules sont vues", SimCommandStream.MAX_ENTITY_PROXIES);
                }
                proxies = proxies.subList(0, SimCommandStream.MAX_ENTITY_PROXIES);
            }
            if (!proxies.isEmpty()) {
                stream.setEntityProxies(dimension, proxies);
            }
        }
        return stream;
    }

    /** {@return les positions des joueurs non spectateurs de la dimension} */
    private double[][] observersOf(ServerLevel level) {
        List<double[]> positions = new ArrayList<>();
        for (ServerPlayer player : level.players()) {
            if (!player.isSpectator()) {
                positions.add(new double[] {player.getX(), player.getY(), player.getZ()});
            }
        }
        if (positions.size() > SimCommandStream.MAX_OBSERVERS) {
            if (!observersTruncated) {
                observersTruncated = true;
                LOGGER.warn("AXION : plus de {} joueurs dans une dimension ; les premiers seuls "
                        + "gardent les corps éveillés", SimCommandStream.MAX_OBSERVERS);
            }
            positions = positions.subList(0, SimCommandStream.MAX_OBSERVERS);
        }
        return positions.toArray(double[][]::new);
    }

    /**
     * {@return vrai pour une entité que la physique doit voir : vanilla la tient pour solide ou
     * poussable, elle est vivante, ni spectatrice, ni une assembly, ni passagère d'une assembly}
     */
    private static boolean eligible(Entity entity) {
        return !(entity instanceof AxionEntity)
                && entity.isAlive()
                && !entity.isSpectator()
                && (entity.canBeCollidedWith() || entity.isPushable())
                && !(entity.getVehicle() instanceof AxionEntity);
    }

    private static EntityProxySelector.Candidate candidateOf(Entity entity) {
        double[] velocity = {
            (entity.getX() - entity.xo) * TICKS_PER_SECOND,
            (entity.getY() - entity.yo) * TICKS_PER_SECOND,
            (entity.getZ() - entity.zo) * TICKS_PER_SECOND
        };
        return new EntityProxySelector.Candidate(
                entity.getId(), boxOf(entity.getBoundingBox()), velocity, entity instanceof LivingEntity);
    }

    private static EntityProxySelector.Box boxOf(AABB box) {
        return new EntityProxySelector.Box(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
    }
}
