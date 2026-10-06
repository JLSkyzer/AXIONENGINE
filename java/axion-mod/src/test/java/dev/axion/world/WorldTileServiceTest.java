package dev.axion.world;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.axion.physics.SimCommandStream;
import dev.axion.physics.SimulationTrace.Tile;
import dev.axion.world.WorldCollisionSource.CollisionShape;
import dev.axion.world.WorldCollisionSource.SectionTile;
import dev.axion.world.WorldTilePlanner.Footprint;
import dev.axion.world.WorldTilePlanner.SectionKey;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Épingle la traduction du plan de tuiles en commandes de frontière (C-38, ADR-117).
 *
 * <p>Tests d'acceptance (ADR-125) : T-371 — tuiles émises par lot, collision puis fluide ;
 * T-373 — une section libérée retire ses deux tuiles.
 */
class WorldTileServiceTest {

    /** Source factice : rend la même tuile pour toute section. */
    private static WorldCollisionSource constant(SectionTile tile) {
        return (dim, x, y, z) -> tile;
    }

    private static int opcodeAt(byte[] bytes, int commandOffset) {
        // en-tête flux (8) puis, par commande, en-tête (8) + payload.
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(commandOffset);
    }

    @Test
    void uneSectionSolideEmetCollisionPuisFluide() {
        SectionTile tile =
                new SectionTile(
                        new CollisionShape.Boxes(
                                new float[][] {{0f, 0f, 0f, 16f, 1f, 16f}}, 0.6f, 0.0f),
                        new float[0][],
                        0.0f);
        WorldTileService service =
                new WorldTileService(new WorldTilePlanner(0, 8), constant(tile));

        SimCommandStream stream = service.tick(abri(0, 0, 0));
        assertEquals(2, stream.count(), "une collision et un fluide (vide = retrait)");
        assertEquals(
                SimCommandStream.OP_SET_WORLD_COLLISION, opcodeAt(stream.toBytes(), 8), "collision d'abord");
    }

    @Test
    void unFluideEstEmis() {
        SectionTile tile =
                new SectionTile(
                        new CollisionShape.Boxes(new float[0][], 0.6f, 0.0f),
                        new float[][] {{0f, 1f, 0f, 16f, 9f, 16f}},
                        1000.0f);
        WorldTileService service =
                new WorldTileService(new WorldTilePlanner(0, 8), constant(tile));

        byte[] bytes = service.tick(abri(0, 0, 0)).toBytes();
        // Collision vide (SET_WORLD_COLLISION, retrait) puis fluide (SET_WORLD_FLUID).
        assertEquals(SimCommandStream.OP_SET_WORLD_COLLISION, opcodeAt(bytes, 8));
        int second = 8 + (8 + 32); // en-tête flux + première commande (en-tête 8 + payload 32)
        assertEquals(SimCommandStream.OP_SET_WORLD_FLUID, opcodeAt(bytes, second));
    }

    @Test
    void unChampDeHauteursEstEmis() {
        SectionTile tile =
                new SectionTile(
                        new CollisionShape.Heightfield(
                                2, 2, new float[] {1f, 1f, 1f, 1f}, new float[] {16f, 1f, 16f}, 0.6f, 0f),
                        new float[0][],
                        0.0f);
        WorldTileService service =
                new WorldTileService(new WorldTilePlanner(0, 8), constant(tile));

        byte[] bytes = service.tick(abri(0, 0, 0)).toBytes();
        assertEquals(SimCommandStream.OP_SET_WORLD_HEIGHTFIELD, opcodeAt(bytes, 8));
        assertEquals(Tile.HEIGHTFIELD, service.lastTiles().get(0).boxes(), "tracé comme un champ");
    }

    @Test
    void uneSectionLibereeEmetLesDeuxRetraits() {
        SectionTile empty =
                new SectionTile(new CollisionShape.Boxes(new float[0][], 0.6f, 0f), new float[0][], 0f);
        WorldTileService service =
                new WorldTileService(new WorldTilePlanner(0, 64), constant(empty));

        service.tick(abri(0, 0, 0)); // charge l'origine
        SimCommandStream moved = service.tick(abri(50, 0, 0));
        // origine libérée : REMOVE_WORLD_COLLISION + REMOVE_WORLD_FLUID ; nouvelle section :
        // SET_WORLD_COLLISION + SET_WORLD_FLUID.
        assertEquals(4, moved.count());
        assertEquals(
                SimCommandStream.OP_REMOVE_WORLD_COLLISION,
                opcodeAt(moved.toBytes(), 8),
                "les retraits sont émis d'abord");
    }

    @Test
    void lesTuilesDuTickSontRapporteesPourLaTrace() {
        // C-71 : la trace dit quand chaque section arrive dans la simulation, avec quoi, et si un
        // corps l'occupait déjà.
        SectionTile tile =
                new SectionTile(
                        new CollisionShape.Boxes(
                                new float[][] {{0f, 0f, 0f, 16f, 1f, 16f}, {0f, 1f, 0f, 1f, 2f, 1f}}, 0.6f, 0f),
                        new float[][] {{0f, 1f, 0f, 16f, 9f, 16f}},
                        1000f);
        WorldTileService service =
                new WorldTileService(new WorldTilePlanner(0, 64), constant(tile));

        service.tick(abri(0, 0, 0));
        assertEquals(List.of(Tile.built(0, 0, 0, 2, 1, true)), service.lastTiles(), "l'abri, en urgence");

        service.tick(abri(50, 0, 0));
        assertEquals(
                List.of(Tile.released(0, 0, 0), Tile.built(50, 0, 0, 2, 1, true)), service.lastTiles());
        assertEquals(0, service.pendingCount());

        service.tick(abri(50, 0, 0));
        assertEquals(List.of(), service.lastTiles(), "rien de neuf, rien à tracer");
    }

    /** {@return un corps réduit à la section {@code (x, y, z)}} */
    private static List<Footprint> abri(int x, int y, int z) {
        return List.of(Footprint.at(new SectionKey(0L, x, y, z)));
    }
}
