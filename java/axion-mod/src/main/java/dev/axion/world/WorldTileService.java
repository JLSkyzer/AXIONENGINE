package dev.axion.world;

import dev.axion.physics.SimCommandStream;
import dev.axion.physics.SimulationTrace.Tile;
import dev.axion.world.WorldCollisionSource.CollisionShape;
import dev.axion.world.WorldCollisionSource.SectionTile;
import dev.axion.world.WorldTilePlanner.Footprint;
import dev.axion.world.WorldTilePlanner.SectionKey;
import dev.axion.world.WorldTilePlanner.TilePlan;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Orchestration du fournisseur de collision du monde (C-38) : à chaque tick, elle traduit
 * le plan du {@link WorldTilePlanner} en commandes de frontière sur un
 * {@link SimCommandStream}, en lisant la géométrie via une {@link WorldCollisionSource}.
 *
 * <p>Logique <b>pure</b> (sans Forge) : la source et le pilotage du tick sont fournis par
 * {@code dev.axion.forge} (R-401). Séparée ainsi, elle se teste avec une source factice.
 *
 * <p>Par tick : les sections libérées émettent un retrait des deux registres (collision et
 * fluide) ; les sections (re)construites émettent leur collision (boîtes ou champ de
 * hauteurs) puis leurs volumes de fluide. Une section de collision vide, ou un fluide vide,
 * se traduit par une commande à compte nul — le natif y retire la tuile (ADR-117).
 *
 * <p>Ce qu'un tick a posé et retiré reste lisible jusqu'au suivant ({@link #lastTiles}), pour la
 * trace de la simulation (C-71).
 */
public final class WorldTileService {

    private final WorldTilePlanner planner;
    private final WorldCollisionSource source;
    private final List<Tile> lastTiles = new ArrayList<>();

    /**
     * @param planner planificateur (rayon et budget par tick)
     * @param source source de la géométrie de collision du monde
     */
    public WorldTileService(WorldTilePlanner planner, WorldCollisionSource source) {
        this.planner = planner;
        this.source = source;
    }

    /**
     * Marque une section à reconstruire (invalidation, un {@code BlockEvent} ou
     * {@code LevelChunkEvent} côté Forge).
     *
     * @param key section invalidée
     */
    public void invalidate(SectionKey key) {
        planner.invalidate(key);
    }

    /**
     * Calcule et encode les commandes de tuiles de ce tick autour des assemblies.
     *
     * @param bodies les corps : la section qui abrite chacun et ce qu'il occupe (doublons
     *     tolérés)
     * @return le flux de commandes à soumettre (peut être vide)
     */
    public SimCommandStream tick(Collection<Footprint> bodies) {
        TilePlan plan = planner.plan(bodies);
        SimCommandStream stream = new SimCommandStream();
        lastTiles.clear();

        // Sections libérées : retrait des deux registres (une section pouvait porter
        // collision et fluide à la fois).
        for (SectionKey key : plan.toRemove()) {
            int[] section = {key.x(), key.y(), key.z()};
            stream.removeWorldCollision(key.dimension(), section);
            stream.removeWorldFluid(key.dimension(), section);
            lastTiles.add(Tile.released(key.x(), key.y(), key.z()));
        }

        // Sections (re)construites : collision puis fluide. Les urgentes, qu'un corps occupe,
        // sont en tête du plan.
        List<SectionKey> toBuild = plan.toBuild();
        for (int i = 0; i < toBuild.size(); i++) {
            SectionKey key = toBuild.get(i);
            int[] section = {key.x(), key.y(), key.z()};
            SectionTile tile = source.tileOf(key.dimension(), key.x(), key.y(), key.z());
            emitCollision(stream, key.dimension(), section, tile.collision());
            // Fluide : une liste vide retire les volumes (compte nul, ADR-117).
            stream.setWorldFluid(key.dimension(), section, tile.fluidBoxes(), tile.fluidDensity());
            int boxes = tile.collision() instanceof CollisionShape.Boxes solid
                    ? solid.boxes().length
                    : Tile.HEIGHTFIELD;
            lastTiles.add(
                    Tile.built(key.x(), key.y(), key.z(), boxes, tile.fluidBoxes().length, i < plan.urgent()));
        }

        return stream;
    }

    /** {@return les tuiles posées et retirées au dernier tick, dans l'ordre de leurs commandes} */
    public List<Tile> lastTiles() {
        return List.copyOf(lastTiles);
    }

    /** {@return le nombre de sections encore en file après le dernier tick} */
    public int pendingCount() {
        return planner.pendingCount();
    }

    private static void emitCollision(
            SimCommandStream stream, long dimension, int[] section, CollisionShape shape) {
        if (shape instanceof CollisionShape.Boxes boxes) {
            stream.setWorldCollision(
                    dimension, section, boxes.boxes(), boxes.friction(), boxes.restitution());
        } else if (shape instanceof CollisionShape.Heightfield hf) {
            stream.setWorldHeightfield(
                    dimension,
                    section,
                    hf.rows(),
                    hf.cols(),
                    hf.heights(),
                    hf.scale(),
                    hf.friction(),
                    hf.restitution());
        }
    }
}
