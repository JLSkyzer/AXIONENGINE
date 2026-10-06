package dev.axion.forge;

import com.google.gson.JsonParser;
import dev.axion.physics.SimCommandProvider;
import dev.axion.physics.SimCommandStream;
import dev.axion.physics.SimulationTrace;
import dev.axion.world.BlockMaterials;
import dev.axion.world.DimensionId;
import dev.axion.world.WorldCollisionSource;
import dev.axion.world.WorldTileGeometry;
import dev.axion.world.WorldTilePlanner;
import dev.axion.world.WorldTilePlanner.Footprint;
import dev.axion.world.WorldTilePlanner.SectionKey;
import dev.axion.world.WorldTileService;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pont Forge du fournisseur de collision du monde (C-38 T3e) : relie le cycle de simulation
 * ({@link SimCommandProvider}) au monde de Minecraft.
 *
 * <p>Chaque tick, il rassemble les {@link AxionEntity} — la section qui abrite chacune, l'emprise
 * de son corps (R-702) et sa vitesse —, laisse {@link WorldTileService} en déduire les tuiles à
 * poser/retirer (autour d'elles, à {@code world.tile_radius}, amorti à
 * {@code world.tiles_per_tick}, ce que leurs corps occupent en tête), et rend le flux de
 * commandes {@code SIM_IN} correspondant ; les tuiles du tick vont à la trace de la simulation
 * (C-71). Il écoute aussi les changements de bloc et les chargements de chunk pour invalider les
 * sections concernées.
 *
 * <p>Seul {@code dev.axion.forge} touche Forge (R-401) ; la décision et l'encodage vivent
 * dans {@code dev.axion.world} (logique pure, testée).
 */
public final class WorldTileBridge implements SimCommandProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger("axion");
    private static final String MATERIALS_RESOURCE = "/axion/world/block_materials.json";

    /** Destination des tuiles posées et retirées à chaque tick : la trace de la simulation. */
    @FunctionalInterface
    public interface TileTrace {

        /**
         * @param tick numéro du tick
         * @param tiles sections posées et retirées, dans l'ordre de leurs commandes
         * @param pending sections encore en file après ce tick
         */
        void tiles(long tick, List<SimulationTrace.Tile> tiles, int pending);
    }

    private final MinecraftServer server;
    private final AssemblyRuntime assemblies;
    private final TileTrace trace;
    private final WorldTileService service;

    /**
     * @param server serveur courant, source des niveaux et des entités
     * @param materials mappage des matériaux (R-643)
     * @param tileRadius {@code world.tile_radius}
     * @param tilesPerTick {@code world.tiles_per_tick}
     * @param assemblies assemblies dotées d'un corps, et leur vitesse
     * @param trace destination des tuiles de chaque tick
     */
    public WorldTileBridge(
            MinecraftServer server,
            BlockMaterials materials,
            int tileRadius,
            int tilesPerTick,
            AssemblyRuntime assemblies,
            TileTrace trace) {
        this.server = server;
        this.assemblies = assemblies;
        this.trace = trace;
        WorldTilePlanner planner = new WorldTilePlanner(tileRadius, tilesPerTick);
        WorldCollisionSource source = new ForgeWorldCollisionSource(this::levelForId, materials);
        this.service = new WorldTileService(planner, source);
        MinecraftForge.EVENT_BUS.register(this);
    }

    /** Charge la ressource des matériaux ; rend un mappage vide (défaut générique) si absente. */
    public static BlockMaterials loadMaterials() {
        try (InputStream in = WorldTileBridge.class.getResourceAsStream(MATERIALS_RESOURCE)) {
            if (in == null) {
                LOGGER.warn("AXION : ressource {} absente ; matériaux par défaut", MATERIALS_RESOURCE);
                return BlockMaterials.empty();
            }
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return BlockMaterials.fromJson(JsonParser.parseString(json));
        } catch (Exception e) {
            LOGGER.warn("AXION : block_materials illisible ({}) ; matériaux par défaut", e.toString());
            return BlockMaterials.empty();
        }
    }

    @Override
    public SimCommandStream commandsForTick(long tick) {
        Map<AxionEntity, Double> speeds = new IdentityHashMap<>();
        assemblies.forEachBody(speeds::put);
        List<Footprint> bodies = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            long dim = DimensionId.of(level.dimension().location().toString());
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof AxionEntity assembly) {
                    BlockPos pos = assembly.blockPosition();
                    SectionKey host =
                            new SectionKey(
                                    dim,
                                    WorldTileGeometry.sectionOfBlock(pos.getX()),
                                    WorldTileGeometry.sectionOfBlock(pos.getY()),
                                    WorldTileGeometry.sectionOfBlock(pos.getZ()));
                    // L'emprise du corps (R-702), ou la hitbox provisoire avant son premier état.
                    AABB box = assembly.getBoundingBox();
                    bodies.add(
                            new Footprint(
                                    host,
                                    new double[] {box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ},
                                    speeds.getOrDefault(assembly, 0.0)));
                }
            }
        }
        SimCommandStream stream = service.tick(bodies);
        trace.tiles(tick, service.lastTiles(), service.pendingCount());
        return stream;
    }

    /** Résout une clé de dimension en son {@code ServerLevel}, ou {@code null}. */
    private ServerLevel levelForId(long dim) {
        for (ServerLevel level : server.getAllLevels()) {
            if (DimensionId.of(level.dimension().location().toString()) == dim) {
                return level;
            }
        }
        return null;
    }

    /** Un bloc changé invalide la section qui le contient. */
    private void invalidateBlock(Level level, BlockPos pos) {
        long dim = DimensionId.of(level.dimension().location().toString());
        service.invalidate(
                new SectionKey(
                        dim,
                        WorldTileGeometry.sectionOfBlock(pos.getX()),
                        WorldTileGeometry.sectionOfBlock(pos.getY()),
                        WorldTileGeometry.sectionOfBlock(pos.getZ())));
    }

    /** Casse d'un bloc : invalidation de sa section. */
    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.getLevel() instanceof Level level) {
            invalidateBlock(level, event.getPos());
        }
    }

    /** Pose d'un bloc : invalidation de sa section. */
    @SubscribeEvent
    public void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getLevel() instanceof Level level) {
            invalidateBlock(level, event.getPos());
        }
    }

    /**
     * Chargement d'un chunk : invalidation de sa colonne de sections. Une section rendue
     * « pleine et solide » faute de chunk (R-640) doit se reconstruire une fois le chunk là.
     */
    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof Level level)) {
            return;
        }
        long dim = DimensionId.of(level.dimension().location().toString());
        int cx = event.getChunk().getPos().x;
        int cz = event.getChunk().getPos().z;
        for (int cy = level.getMinSection(); cy < level.getMaxSection(); cy++) {
            service.invalidate(new SectionKey(dim, cx, cy, cz));
        }
    }

    /** Se désabonne du bus d'événements (arrêt du serveur). */
    public void close() {
        MinecraftForge.EVENT_BUS.unregister(this);
    }
}
