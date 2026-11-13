package dev.axion.forge.client;

import dev.axion.forge.AxionEntity;
import dev.axion.forge.ForgeWorldCollisionSource;
import dev.axion.lifecycle.AxionRuntime;
import dev.axion.physics.SimCommandStream;
import dev.axion.world.DimensionId;
import dev.axion.world.WorldTileGeometry;
import dev.axion.world.WorldTilePlanner;
import dev.axion.world.WorldTilePlanner.Footprint;
import dev.axion.world.WorldTilePlanner.SectionKey;
import dev.axion.world.WorldTileService;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.AABB;

/**
 * Les tuiles de blocs du monde prédit (ADR-168 D1) : celles du niveau du client autour du véhicule conduit, coupées
 * par le découpage du serveur ({@link ForgeWorldCollisionSource}) et planifiées comme les siennes
 * ({@link WorldTilePlanner}, {@code world.tile_radius}, {@code world.tiles_per_tick}).
 *
 * <p>Le client ne voit pas les événements de blocs du serveur : les sections que le véhicule touche se recoupent à
 * intervalle régulier, et une section dont l'empreinte n'a pas changé ne repart pas au natif. Un bloc changé entre
 * deux recoupes rend la prédiction fausse un instant, ce que la correction suivante rattrape.
 *
 * <p>Thread du client seul. Une instance par monde prédit : elle naît vide, comme lui.
 */
final class ClientTiles {

    /** Ticks entre deux recoupes des sections que le véhicule touche. */
    static final int RECUT_TICKS = 20;

    /** Marge autour de l'emprise du véhicule, en blocs, des sections recoupées. */
    private static final double RECUT_MARGIN = 2.0;

    private final ClientLevel level;
    private final long dimension;
    private final WorldTileService service;
    private int ticks;

    ClientTiles(ClientLevel level, AxionRuntime runtime) {
        this.level = level;
        this.dimension = DimensionId.of(level.dimension().location().toString());
        WorldTilePlanner planner = new WorldTilePlanner(
                (int) runtime.outcome().config().getInt("world.tile_radius"),
                (int) runtime.outcome().config().getInt("world.tiles_per_tick"));
        this.service = new WorldTileService(planner, new ForgeWorldCollisionSource(
                id -> id == dimension ? level : null, runtime::blockMaterials, runtime::physicsMaterialIndex));
    }

    /** {@return la dimension du niveau} */
    long dimension() {
        return dimension;
    }

    /**
     * Les tuiles de ce pas, autour du véhicule à sa pose prédite.
     *
     * @param vehicle le véhicule conduit
     * @param x sa position prédite
     * @param y sa position prédite
     * @param z sa position prédite
     * @param speed sa vitesse, en m/s
     * @return les constructions et les retraits de tuiles
     */
    SimCommandStream tick(AxionEntity vehicle, double x, double y, double z, double speed) {
        AABB here = vehicle.getBoundingBox().move(x - vehicle.getX(), y - vehicle.getY(), z - vehicle.getZ());
        if (++ticks % RECUT_TICKS == 0) {
            recut(here.inflate(RECUT_MARGIN));
        }
        SectionKey host = new SectionKey(dimension, WorldTileGeometry.sectionOfWorld(x),
                WorldTileGeometry.sectionOfWorld(y), WorldTileGeometry.sectionOfWorld(z));
        return service.tick(List.of(new Footprint(host,
                new double[] {here.minX, here.minY, here.minZ, here.maxX, here.maxY, here.maxZ}, speed)));
    }

    /**
     * Une colonne de chunk vient de se charger chez le client : ses sections se recoupent.
     *
     * @param chunkX la colonne
     * @param chunkZ la colonne
     */
    void chunkLoaded(int chunkX, int chunkZ) {
        for (int y = level.getMinSection(); y < level.getMaxSection(); y++) {
            service.invalidate(new SectionKey(dimension, chunkX, y, chunkZ));
        }
    }

    /** {@return les retraits de toutes les tuiles envoyées : le monde prédit se ferme} */
    SimCommandStream releaseAll() {
        return service.tick(List.of());
    }

    private void recut(AABB box) {
        int minX = WorldTileGeometry.sectionOfWorld(box.minX);
        int minY = WorldTileGeometry.sectionOfWorld(box.minY);
        int minZ = WorldTileGeometry.sectionOfWorld(box.minZ);
        int maxX = WorldTileGeometry.sectionOfWorld(box.maxX);
        int maxY = WorldTileGeometry.sectionOfWorld(box.maxY);
        int maxZ = WorldTileGeometry.sectionOfWorld(box.maxZ);
        for (int sx = minX; sx <= maxX; sx++) {
            for (int sy = minY; sy <= maxY; sy++) {
                for (int sz = minZ; sz <= maxZ; sz++) {
                    service.invalidate(new SectionKey(dimension, sx, sy, sz));
                }
            }
        }
    }
}
