package dev.axion.world;

import dev.axion.physics.SimCommandStream;
import dev.axion.physics.SimulationTrace.Tile;
import dev.axion.world.WorldCollisionSource.CollisionShape;
import dev.axion.world.WorldCollisionSource.SectionTile;
import dev.axion.world.WorldTilePlanner.Footprint;
import dev.axion.world.WorldTilePlanner.SectionKey;
import dev.axion.world.WorldTilePlanner.TilePlan;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 * <p>Une section relue identique à ce que le natif tient déjà ne lui est pas renvoyée. Il en
 * remplacerait la tuile en bloc, et ce remplacement réveille tout corps posé dessus — rapier réveille
 * les corps d'un contact dont il retire le collider — et tout véhicule à sa portée (ADR-159 D3) :
 * une horloge de redstone, qui fait invalider sa section tous les deux ticks, tenait éveillé tout ce
 * qui reposait dans la section. Chaque section tenue garde l'empreinte de ce qu'elle a envoyé
 * ({@link #digestOf}) ; une section libérée l'oublie, et repart entière quand elle revient.
 *
 * <p>Ce qu'un tick a posé et retiré reste lisible jusqu'au suivant ({@link #lastTiles}), pour la
 * trace de la simulation (C-71) ; une section relue identique n'y figure pas, n'ayant rien posé, et
 * se compte à part ({@link #lastUnchanged}).
 */
public final class WorldTileService {

    private final WorldTilePlanner planner;
    private final WorldCollisionSource source;
    private final List<Tile> lastTiles = new ArrayList<>();
    private int lastUnchanged;

    /**
     * L'empreinte de ce que chaque section tenue a envoyé au natif. Consultée par clé, jamais
     * itérée : l'ordre des commandes ne dépend que du plan (R-1020).
     */
    private final Map<SectionKey, byte[]> sent = new HashMap<>();

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
        lastUnchanged = 0;

        // Sections libérées : retrait des deux registres (une section pouvait porter
        // collision et fluide à la fois).
        for (SectionKey key : plan.toRemove()) {
            int[] section = {key.x(), key.y(), key.z()};
            stream.removeWorldCollision(key.dimension(), section);
            stream.removeWorldFluid(key.dimension(), section);
            sent.remove(key);
            lastTiles.add(Tile.released(key.x(), key.y(), key.z()));
        }

        // Sections (re)construites : collision puis fluide. Les urgentes, qu'un corps occupe,
        // sont en tête du plan. Une section relue identique à ce que le natif tient n'est pas
        // renvoyée : la remplacer réveillerait ce qui repose dessus.
        List<SectionKey> toBuild = plan.toBuild();
        for (int i = 0; i < toBuild.size(); i++) {
            SectionKey key = toBuild.get(i);
            int[] section = {key.x(), key.y(), key.z()};
            SectionTile tile = source.tileOf(key.dimension(), key.x(), key.y(), key.z());
            byte[] digest = digestOf(tile);
            if (Arrays.equals(digest, sent.get(key))) {
                lastUnchanged++;
                continue;
            }
            sent.put(key, digest);
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

    /** {@return les sections relues identiques au dernier tick, que le natif n'a pas reçues} */
    public int lastUnchanged() {
        return lastUnchanged;
    }

    /** {@return le nombre de sections encore en file après le dernier tick} */
    public int pendingCount() {
        return planner.pendingCount();
    }

    /** {@return le nombre de tuiles tenues après le dernier tick, celles que le natif garde} */
    public int loadedCount() {
        return planner.loadedCount();
    }

    /**
     * {@return l'empreinte SHA-256 de ce que la tuile envoie au natif, bit pour bit} Sa collision — matériau, puis
     * boîtes, ou dimensions, hauteurs et échelle du champ —, puis ses volumes de fluide et leur densité : deux
     * tuiles de même empreinte posent la même chose. Chaque tableau est précédé de sa longueur, pour que deux
     * découpages différents des mêmes nombres ne se confondent pas.
     *
     * @param tile tuile lue de la source
     */
    static byte[] digestOf(SectionTile tile) {
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException absent) {
            throw new IllegalStateException("SHA-256 est exigé de toute plateforme Java", absent);
        }
        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), sha)))) {
            CollisionShape shape = tile.collision();
            out.writeInt(shape.material());
            if (shape instanceof CollisionShape.Boxes boxes) {
                out.writeByte(0);
                writeRows(out, boxes.boxes());
            } else {
                // CollisionShape est scellée : ce qui n'est pas des boîtes est un champ de hauteurs.
                CollisionShape.Heightfield field = (CollisionShape.Heightfield) shape;
                out.writeByte(1);
                out.writeInt(field.rows());
                out.writeInt(field.cols());
                writeRow(out, field.heights());
                writeRow(out, field.scale());
            }
            writeRows(out, tile.fluidBoxes());
            out.writeFloat(tile.fluidDensity());
        } catch (IOException impossible) {
            // Le flux n'écrit nulle part : aucune entrée-sortie ne peut échouer.
            throw new UncheckedIOException(impossible);
        }
        return sha.digest();
    }

    private static void writeRows(DataOutputStream out, float[][] rows) throws IOException {
        out.writeInt(rows.length);
        for (float[] row : rows) {
            writeRow(out, row);
        }
    }

    private static void writeRow(DataOutputStream out, float[] row) throws IOException {
        out.writeInt(row.length);
        for (float value : row) {
            out.writeFloat(value);
        }
    }

    private static void emitCollision(
            SimCommandStream stream, long dimension, int[] section, CollisionShape shape) {
        if (shape instanceof CollisionShape.Boxes boxes) {
            stream.setWorldCollision(dimension, section, boxes.boxes(), boxes.material());
        } else if (shape instanceof CollisionShape.Heightfield hf) {
            stream.setWorldHeightfield(
                    dimension,
                    section,
                    hf.rows(),
                    hf.cols(),
                    hf.heights(),
                    hf.scale(),
                    hf.material());
        }
    }
}
