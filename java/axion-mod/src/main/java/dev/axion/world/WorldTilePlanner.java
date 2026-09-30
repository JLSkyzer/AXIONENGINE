package dev.axion.world;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Planificateur des tuiles de collision du monde (C-38, fiche 5.30, étapes 4-5).
 *
 * <p>Logique <b>pure</b> (sans Forge) qui décide, à chaque tick, quelles sections
 * (re)construire et quelles sections libérer, à partir de la position des assemblies :
 *
 * <ul>
 *   <li>l'<b>ensemble désiré</b> est le voisinage cubique de rayon {@code tile_radius}
 *       (en sections) autour de chaque section d'assembly ;
 *   <li>les sections <b>chargées hors du désiré</b> sont libérées (étape 5) ;
 *   <li>les sections <b>désirées non chargées</b> entrent dans une file de reconstruction,
 *       vidée au plus à {@code tiles_per_tick} par tick (étape 4, reconstruction amortie) ;
 *   <li>une {@link #invalidate section invalidée} (BlockEvent) est remise en file.
 * </ul>
 *
 * <p>La lecture réelle des collisions et l'émission des commandes (via
 * {@link dev.axion.physics.SimCommandStream}) sont faites par l'appelant Forge (T3e) sur
 * le plan renvoyé ; les isoler ici rend l'ordonnancement testable sans le jeu. L'ordre de
 * construction est déterministe (sections triées) à entrée égale.
 */
public final class WorldTilePlanner {

    /** Clé d'une section 16³ : dimension + index de section {@code (x, y, z)}. */
    public record SectionKey(long dimension, int x, int y, int z) {}

    /** Plan d'un tick : sections à construire (bornées) et sections à libérer. */
    public record TilePlan(List<SectionKey> toBuild, List<SectionKey> toRemove) {}

    private static final Comparator<SectionKey> ORDER =
            Comparator.comparingLong(SectionKey::dimension)
                    .thenComparingInt(SectionKey::x)
                    .thenComparingInt(SectionKey::y)
                    .thenComparingInt(SectionKey::z);

    private final int tileRadius;
    private final int tilesPerTick;

    /** Sections pour lesquelles une tuile a été émise (possiblement vide). */
    private final Set<SectionKey> loaded = new HashSet<>();

    /** File de reconstruction, dédupliquée et ordonnée par insertion puis vidée en tête. */
    private final LinkedHashSet<SectionKey> dirty = new LinkedHashSet<>();

    /**
     * @param tileRadius rayon en sections du voisinage maintenu ({@code world.tile_radius})
     * @param tilesPerTick nombre maximal de sections construites par tick
     *     ({@code world.tiles_per_tick})
     */
    public WorldTilePlanner(int tileRadius, int tilesPerTick) {
        if (tileRadius < 0) {
            throw new IllegalArgumentException("tileRadius >= 0");
        }
        if (tilesPerTick < 1) {
            throw new IllegalArgumentException("tilesPerTick >= 1");
        }
        this.tileRadius = tileRadius;
        this.tilesPerTick = tilesPerTick;
    }

    /**
     * Marque une section à (re)construire (invalidation, étape 4 : un {@code BlockEvent} ou
     * un {@code LevelChunkEvent}). Sans effet visible si la section n'est pas, ou plus,
     * dans le voisinage désiré — {@link #plan} l'y élaguera.
     *
     * @param key section invalidée
     */
    public void invalidate(SectionKey key) {
        dirty.add(key);
    }

    /**
     * Calcule le plan du tick à partir des sections où se trouvent les assemblies.
     *
     * @param assemblySections sections abritant une assembly (une par corps, doublons
     *     tolérés)
     * @return les sections à construire (au plus {@code tilesPerTick}) et à libérer
     */
    public TilePlan plan(Collection<SectionKey> assemblySections) {
        Set<SectionKey> desired = expand(assemblySections);

        // Étape 5 : les sections chargées hors du désiré sont libérées.
        List<SectionKey> toRemove = new ArrayList<>();
        for (SectionKey key : loaded) {
            if (!desired.contains(key)) {
                toRemove.add(key);
            }
        }
        toRemove.sort(ORDER);
        toRemove.forEach(loaded::remove);
        dirty.removeAll(toRemove);

        // Désirées non chargées et pas déjà en file : à construire, en ordre déterministe.
        List<SectionKey> newlyDesired = new ArrayList<>();
        for (SectionKey key : desired) {
            if (!loaded.contains(key) && !dirty.contains(key)) {
                newlyDesired.add(key);
            }
        }
        newlyDesired.sort(ORDER);
        dirty.addAll(newlyDesired);

        // Une invalidation hors du voisinage désiré est élaguée (elle n'a plus à être bâtie).
        dirty.removeIf(key -> !desired.contains(key));

        // Étape 4 : vider la file au plus à tilesPerTick. Les sections qui ABRITENT une
        // assembly passent en tête : un corps dynamique créé (ou rechargé après un
        // redémarrage de serveur, où le backlog de reconstruction repart de zéro) doit
        // trouver son sol au tick même de sa création, sinon il tombe au travers avant que
        // sa section soit reconstruite. Priorité triée puis reste en ordre d'insertion :
        // l'ordre de construction reste déterministe à entrée égale.
        List<SectionKey> priority = new ArrayList<>();
        for (SectionKey center : assemblySections) {
            if (dirty.contains(center) && !priority.contains(center)) {
                priority.add(center);
            }
        }
        priority.sort(ORDER);

        List<SectionKey> toBuild = new ArrayList<>();
        for (SectionKey key : priority) {
            if (toBuild.size() >= tilesPerTick) {
                break;
            }
            toBuild.add(key);
        }
        for (SectionKey key : dirty) {
            if (toBuild.size() >= tilesPerTick) {
                break;
            }
            if (!toBuild.contains(key)) {
                toBuild.add(key);
            }
        }
        toBuild.forEach(dirty::remove);
        loaded.addAll(toBuild);

        return new TilePlan(List.copyOf(toBuild), List.copyOf(toRemove));
    }

    /** {@return le nombre de sections en attente de construction} */
    public int pendingCount() {
        return dirty.size();
    }

    /** {@return le nombre de sections chargées (tuile émise)} */
    public int loadedCount() {
        return loaded.size();
    }

    private Set<SectionKey> expand(Collection<SectionKey> assemblySections) {
        Set<SectionKey> desired = new HashSet<>();
        for (SectionKey center : assemblySections) {
            for (int dx = -tileRadius; dx <= tileRadius; dx++) {
                for (int dy = -tileRadius; dy <= tileRadius; dy++) {
                    for (int dz = -tileRadius; dz <= tileRadius; dz++) {
                        desired.add(
                                new SectionKey(
                                        center.dimension(),
                                        center.x() + dx,
                                        center.y() + dy,
                                        center.z() + dz));
                    }
                }
            }
        }
        return desired;
    }
}
