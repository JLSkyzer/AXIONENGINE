package dev.axion.world;

import java.util.ArrayList;
import java.util.Arrays;
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
 *   <li>ce qu'un corps <b>occupe</b> — la section qui l'abrite, puis toutes celles que son
 *       emprise touchera d'ici deux ticks — passe en tête de file, dans le même budget ;
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

    /**
     * Un corps tel que le planificateur le voit : la section qui l'abrite, autour de laquelle
     * son voisinage est maintenu, et ce qu'il occupe.
     *
     * @param host section abritant l'assembly (celle de sa position)
     * @param box emprise du corps en coordonnées monde, en blocs,
     *     {@code [minx, miny, minz, maxx, maxy, maxz]}, ou {@code null} si elle n'est pas connue
     * @param speed norme de sa vitesse, en m/s (nulle si inconnue)
     */
    public record Footprint(SectionKey host, double[] box, double speed) {

        /**
         * {@return un corps réduit à la section qui l'abrite, sans emprise connue}
         *
         * @param host section abritant l'assembly
         */
        public static Footprint at(SectionKey host) {
            return new Footprint(host, null, 0.0);
        }
    }

    /**
     * Plan d'un tick : sections à construire (bornées) et sections à libérer.
     *
     * @param toBuild sections à construire, les urgentes en tête
     * @param toRemove sections à libérer
     * @param urgent nombre de sections en tête de {@code toBuild} qu'un corps occupe
     */
    public record TilePlan(List<SectionKey> toBuild, List<SectionKey> toRemove, int urgent) {}

    /**
     * Course anticipée d'un corps, en secondes : deux ticks. Le plan d'un tick se fait sur l'état
     * du tick précédent, et ses tuiles servent au pas qui suit ; le second tick couvre une
     * section remise au lot suivant, budget épuisé.
     */
    static final double LOOKAHEAD_SECONDS = 0.1;

    /** Marge autour de l'emprise, en blocs : ce que la rotation du corps y ajoute d'ici là. */
    static final double MARGIN_BLOCKS = 1.0;

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
     * Calcule le plan du tick à partir des corps.
     *
     * @param bodies les corps : la section qui abrite chacun et ce qu'il occupe (doublons
     *     tolérés)
     * @return les sections à construire (au plus {@code tilesPerTick}), celles qu'un corps
     *     occupe en tête, et celles à libérer
     */
    public TilePlan plan(Collection<Footprint> bodies) {
        List<SectionKey> assemblySections = new ArrayList<>(bodies.size());
        for (Footprint body : bodies) {
            assemblySections.add(body.host());
        }
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

        // Étape 4 : vider la file au plus à tilesPerTick, ce qu'un corps occupe en tête. D'abord
        // les sections qui ABRITENT une assembly : un corps dynamique créé (ou rechargé après un
        // redémarrage de serveur, où le backlog de reconstruction repart de zéro) doit trouver
        // son sol au tick même de sa création, sinon il tombe au travers avant que sa section
        // soit reconstruite. Puis toutes celles que son emprise touche d'ici deux ticks : à
        // cheval sur deux sections, ou tombant dans la suivante, un corps n'y trouverait sinon
        // ni sol ni eau tant que la file n'y est pas arrivée — la poussée ne porte alors que sur
        // une part de lui, et son couple le fait tourner (essai du 2026-10-06). Chaque groupe
        // trié, le reste en ordre d'insertion : l'ordre de construction reste déterministe à
        // entrée égale.
        Set<SectionKey> priority = new LinkedHashSet<>();
        assemblySections.stream().sorted(ORDER).filter(dirty::contains).forEach(priority::add);
        bodies.stream()
                .flatMap(body -> occupiedBy(body).stream())
                .sorted(ORDER)
                .filter(dirty::contains)
                .forEach(priority::add);

        List<SectionKey> toBuild = new ArrayList<>();
        for (SectionKey key : priority) {
            if (toBuild.size() >= tilesPerTick) {
                break;
            }
            toBuild.add(key);
        }
        int urgent = toBuild.size();
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

        return new TilePlan(List.copyOf(toBuild), List.copyOf(toRemove), urgent);
    }

    /**
     * {@return les sections que l'emprise d'un corps touchera d'ici {@link #LOOKAHEAD_SECONDS},
     * marge comprise, bornées au voisinage de la section qui l'abrite, en ordre déterministe ;
     * cette seule section si l'emprise est inconnue ou non finie}
     *
     * <p>L'emprise vient du natif : non finie, elle ne fait rien énumérer ; démesurée, la borne
     * du voisinage la ramène à au plus {@code (2 × tile_radius + 1)³} sections.
     */
    private List<SectionKey> occupiedBy(Footprint body) {
        SectionKey host = body.host();
        double[] box = body.box();
        if (box == null || box.length != 6 || !Arrays.stream(box).allMatch(Double::isFinite)) {
            return List.of(host);
        }
        double speed = body.speed();
        double reach = MARGIN_BLOCKS + (Double.isFinite(speed) && speed > 0 ? speed * LOOKAHEAD_SECONDS : 0);
        int x0 = near(WorldTileGeometry.sectionOfWorld(box[0] - reach), host.x());
        int y0 = near(WorldTileGeometry.sectionOfWorld(box[1] - reach), host.y());
        int z0 = near(WorldTileGeometry.sectionOfWorld(box[2] - reach), host.z());
        int x1 = near(WorldTileGeometry.sectionOfWorld(box[3] + reach), host.x());
        int y1 = near(WorldTileGeometry.sectionOfWorld(box[4] + reach), host.y());
        int z1 = near(WorldTileGeometry.sectionOfWorld(box[5] + reach), host.z());
        List<SectionKey> sections = new ArrayList<>();
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    sections.add(new SectionKey(host.dimension(), x, y, z));
                }
            }
        }
        return sections;
    }

    /** {@return {@code section} ramenée à au plus {@code tile_radius} sections de {@code host}} */
    private int near(int section, int host) {
        return Math.max(host - tileRadius, Math.min(host + tileRadius, section));
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
