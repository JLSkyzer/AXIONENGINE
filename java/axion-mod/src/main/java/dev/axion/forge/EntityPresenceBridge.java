package dev.axion.forge;

import com.mojang.logging.LogUtils;
import dev.axion.physics.BodyFrame;
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
                    EntityProxySelector.Candidate candidate = candidateOf(entity);
                    if (candidate != null) {
                        candidates.putIfAbsent(entity.getId(), candidate);
                    }
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
     * poussable, elle est vivante, ni spectatrice, ni une assembly, ni passagère d'une assembly} Le monde prédit du
     * client la juge de même (ADR-168, précision 6c-C2).
     *
     * @param entity l'entité
     */
    public static boolean eligible(Entity entity) {
        return !(entity instanceof AxionEntity)
                && entity.isAlive()
                && !entity.isSpectator()
                && (entity.canBeCollidedWith() || entity.isPushable())
                && !(entity.getVehicle() instanceof AxionEntity);
    }

    /**
     * {@return l'entité en candidate : sa boîte, sa vitesse du dernier tick, vivante ou non ; {@code null} pour une entité portée
     * quand {@code physics.player_proxy_mass} est nul} Le monde prédit du client la relève de même (ADR-168, précision 6c-C2).
     *
     * <p>Au serveur, une entité portée (ADR-178) se voit <b>là où elle est sur son support aujourd'hui</b>, à la vitesse de ce
     * point du support, et elle pèse comme un joueur. La voir où le serveur la tient — un joueur y est en retard de son aller-retour
     * —, ou de masse infinie, arrêtait ce qui la portait.
     *
     * @param entity l'entité
     */
    public static EntityProxySelector.Candidate candidateOf(Entity entity) {
        double[] velocity = {
            (entity.getX() - entity.xo) * TICKS_PER_SECOND,
            (entity.getY() - entity.yo) * TICKS_PER_SECOND,
            (entity.getZ() - entity.zo) * TICKS_PER_SECOND
        };
        AABB box = entity.getBoundingBox();
        // ADR-178 D6 : un joueur pèse ; le reste est un mur.
        double mass = entity instanceof net.minecraft.world.entity.player.Player ? AxionEntities.playerProxyMass() : 0.0;
        CarrySupports.Support support = entity.level().isClientSide() ? null : CarrySupports.of(entity);
        double[] local = support == null ? null : support.local();
        if (local != null) {
            double[] now = support.assembly().carryPoses(0);
            double[] before = support.assembly().carryPoses(1);
            if (now != null && before != null) {
                // Ce qu'une assembly porte ne la heurte pas comme un mur : sans masse réglée, la physique ne le voit pas.
                mass = AxionEntities.playerProxyMass();
                if (mass <= 0.0) {
                    return null;
                }
                double[] here = BodyFrame.toWorld(now, support.body(), local);
                double[] was = BodyFrame.toWorld(before, support.body(), local);
                box = box.move(here[0] - entity.getX(), here[1] - entity.getY(), here[2] - entity.getZ());
                for (int axis = 0; axis < 3; axis++) {
                    velocity[axis] = (here[axis] - was[axis]) * TICKS_PER_SECOND;
                }
            }
        }
        return new EntityProxySelector.Candidate(
                entity.getId(), boxOf(box), velocity, entity instanceof LivingEntity, mass);
    }

    /**
     * {@return la boîte de Minecraft en boîte du sélecteur}
     *
     * @param box la boîte
     */
    public static EntityProxySelector.Box boxOf(AABB box) {
        return new EntityProxySelector.Box(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
    }
}
