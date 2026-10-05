package dev.axion.world;

import java.util.ArrayList;
import java.util.List;

/**
 * Géométrie des tuiles de collision du monde (C-38, fiche 5.30, étape 2).
 *
 * <p>Opérations <b>pures</b> — sans Minecraft ni Forge — qui transforment la géométrie de
 * collision brute d'une section 16³ en la forme attendue par la frontière (ADR-117) :
 * adressage de section, quantification en 1/16 de bloc, mise en repère relatif à la
 * section, fusion des blocs pleins en une liste compacte de boîtes, et décision du repli en
 * champ de hauteurs (R-641).
 *
 * <p>La lecture réelle des {@code VoxelShape} via {@code Level.getBlockCollisions} vit dans
 * {@code dev.axion.forge} (R-401) et alimente ces helpers ; les isoler ici les rend
 * testables sans démarrer le jeu.
 */
public final class WorldTileGeometry {

    /** Arête d'une section, en blocs. */
    public static final int SECTION_SIZE = 16;

    /** Résolution de quantification : 1/16 de bloc (grille de voxels de Minecraft). */
    public static final int QUANTUM_PER_BLOCK = 16;

    /**
     * Nombre de boîtes au-delà duquel une tuile de collision passe en champ de hauteurs
     * (R-641). Miroir de {@code MAX_WORLD_TILE_BOXES} côté natif (ADR-117).
     */
    public static final int MAX_TILE_BOXES = 4096;

    private WorldTileGeometry() {}

    /**
     * {@return l'index de la section 16³ contenant la coordonnée de bloc {@code block}}
     *
     * <p>C'est {@code floorDiv} : les coordonnées négatives descendent vers la section
     * inférieure (l'index de section est monotone, sans trou autour de zéro).
     *
     * @param block coordonnée de bloc (un axe)
     */
    public static int sectionOfBlock(int block) {
        return Math.floorDiv(block, SECTION_SIZE);
    }

    /**
     * {@return l'index de la section 16³ contenant la coordonnée monde {@code world}}
     *
     * @param world coordonnée monde (un axe), en blocs
     */
    public static int sectionOfWorld(double world) {
        return sectionOfBlock((int) Math.floor(world));
    }

    /**
     * Quantifie une valeur en 1/16 de bloc : la ramène sur la grille de voxels de
     * Minecraft, où vivent déjà dalles, escaliers et barrières.
     *
     * @param value valeur en blocs
     * @return {@code value} arrondie au 1/16 le plus proche
     */
    public static float quantize(double value) {
        return (float) (Math.round(value * QUANTUM_PER_BLOCK) / (double) QUANTUM_PER_BLOCK);
    }

    /**
     * Convertit une boîte en coordonnées monde en une boîte <b>relative à l'origine de la
     * section</b> ({@code section × 16}), quantifiée en 1/16 (ce qu'attendent les setters
     * natifs et {@link dev.axion.physics.SimCommandStream#setWorldCollision}).
     *
     * @param worldBox boîte monde {@code [minx, miny, minz, maxx, maxy, maxz]}, en blocs
     * @param sectionX index de section sur x
     * @param sectionY index de section sur y
     * @param sectionZ index de section sur z
     * @return boîte section-relative quantifiée, de longueur 6
     */
    public static float[] sectionRelativeBox(
            double[] worldBox, int sectionX, int sectionY, int sectionZ) {
        if (worldBox.length != 6) {
            throw new IllegalArgumentException("une boîte est [minx,miny,minz,maxx,maxy,maxz]");
        }
        double ox = (double) sectionX * SECTION_SIZE;
        double oy = (double) sectionY * SECTION_SIZE;
        double oz = (double) sectionZ * SECTION_SIZE;
        return new float[] {
            quantize(worldBox[0] - ox),
            quantize(worldBox[1] - oy),
            quantize(worldBox[2] - oz),
            quantize(worldBox[3] - ox),
            quantize(worldBox[4] - oy),
            quantize(worldBox[5] - oz),
        };
    }

    /**
     * {@return vrai si une tuile de {@code boxCount} boîtes doit être représentée par un
     * champ de hauteurs plutôt que par des boîtes (R-641)}
     *
     * @param boxCount nombre de boîtes de collision de la section
     */
    public static boolean needsHeightfield(int boxCount) {
        return boxCount > MAX_TILE_BOXES;
    }

    /**
     * {@return le rang du bloc {@code (x, y, z)} d'une section dans une grille d'occupation
     * de {@code 16³} cases, celle que lit {@link #mergeFullBlocks}}
     *
     * @param x coordonnée relative à la section, de 0 à 15
     * @param y coordonnée relative à la section, de 0 à 15
     * @param z coordonnée relative à la section, de 0 à 15
     */
    public static int cellIndex(int x, int y, int z) {
        return (x * SECTION_SIZE + y) * SECTION_SIZE + z;
    }

    /**
     * Fusionne les blocs pleins d'une section en boîtes maximales : la « liste compacte de
     * boîtes » de la fiche 5.30 (étape 2). Une section de pierre pleine devient une boîte, un
     * sol plat une dalle, là où un bloc par boîte en donnait 4 096.
     *
     * <p>Glouton et déterministe : balayage en y, puis z, puis x ; chaque bloc plein non encore
     * couvert ouvre une boîte, étendue d'abord le long de x, puis de z, puis de y, tant que
     * tous les blocs qu'elle gagnerait sont pleins et libres. Les boîtes couvrent exactement
     * les blocs pleins, sans se chevaucher : la collision est la même, en moins de pièces.
     *
     * @param full occupation de la section, de longueur {@code 16³}, au rang
     *     {@link #cellIndex(int, int, int)} ; n'est pas modifiée
     * @return boîtes relatives à la section, {@code [minx, miny, minz, maxx, maxy, maxz]} en
     *     blocs, dans l'ordre du balayage
     * @throws IllegalArgumentException si la grille n'a pas {@code 16³} cases
     */
    public static List<float[]> mergeFullBlocks(boolean[] full) {
        int cells = SECTION_SIZE * SECTION_SIZE * SECTION_SIZE;
        if (full.length != cells) {
            throw new IllegalArgumentException(
                    "une grille d'occupation a " + cells + " cases, pas " + full.length);
        }
        boolean[] free = full.clone();
        List<float[]> boxes = new ArrayList<>();
        for (int y = 0; y < SECTION_SIZE; y++) {
            for (int z = 0; z < SECTION_SIZE; z++) {
                for (int x = 0; x < SECTION_SIZE; x++) {
                    if (!free[cellIndex(x, y, z)]) {
                        continue;
                    }
                    int x1 = x + 1;
                    while (x1 < SECTION_SIZE && free[cellIndex(x1, y, z)]) {
                        x1++;
                    }
                    int z1 = z + 1;
                    while (z1 < SECTION_SIZE && allFree(free, x, x1, y, y + 1, z1, z1 + 1)) {
                        z1++;
                    }
                    int y1 = y + 1;
                    while (y1 < SECTION_SIZE && allFree(free, x, x1, y1, y1 + 1, z, z1)) {
                        y1++;
                    }
                    take(free, x, x1, y, y1, z, z1);
                    boxes.add(new float[] {x, y, z, x1, y1, z1});
                }
            }
        }
        return boxes;
    }

    /**
     * Fusionne l'eau d'une section en boîtes maximales, comme {@link #mergeFullBlocks} les blocs
     * pleins (C-38, R-642). Une boîte par bloc d'eau en donnait jusqu'à 4 096 par section :
     * autant de volumes que le natif gardait, et qu'il parcourait pour situer chaque coin de
     * chaque corps.
     *
     * <p>L'eau pleine (hauteur 1) se fusionne en volume, comme des blocs pleins. L'eau partielle
     * — la surface, à 8/9 de bloc, l'eau qui s'écoule — ne s'empile pas : sa boîte s'arrête à sa
     * hauteur, et ne se fusionne donc que dans sa couche, avec l'eau de même hauteur, le long de
     * x puis de z. Les boîtes couvrent exactement l'eau, sans se chevaucher : la flottabilité est
     * la même, en moins de pièces. Une hauteur non finie ou non positive n'est pas de l'eau.
     *
     * @param heights hauteur de l'eau de chaque bloc de la section, en blocs, de longueur
     *     {@code 16³}, au rang {@link #cellIndex(int, int, int)} ; n'est pas modifiée
     * @return boîtes relatives à la section, {@code [minx, miny, minz, maxx, maxy, maxz]} en
     *     blocs : l'eau pleine d'abord, puis l'eau partielle, couche par couche
     * @throws IllegalArgumentException si la grille n'a pas {@code 16³} cases
     */
    public static List<float[]> mergeFluids(float[] heights) {
        int cells = SECTION_SIZE * SECTION_SIZE * SECTION_SIZE;
        if (heights.length != cells) {
            throw new IllegalArgumentException(
                    "une grille de hauteurs d'eau a " + cells + " cases, pas " + heights.length);
        }
        boolean[] full = new boolean[cells];
        boolean[] partial = new boolean[cells];
        for (int i = 0; i < cells; i++) {
            // Comparaisons fausses pour NaN : une hauteur non finie n'est ni pleine ni partielle.
            full[i] = heights[i] >= 1f;
            partial[i] = heights[i] > 0f && heights[i] < 1f;
        }
        List<float[]> boxes = mergeFullBlocks(full);
        for (int y = 0; y < SECTION_SIZE; y++) {
            for (int z = 0; z < SECTION_SIZE; z++) {
                for (int x = 0; x < SECTION_SIZE; x++) {
                    if (!partial[cellIndex(x, y, z)]) {
                        continue;
                    }
                    float height = heights[cellIndex(x, y, z)];
                    int x1 = x + 1;
                    while (x1 < SECTION_SIZE && sameWater(partial, heights, height, x1, x1 + 1, y, z, z + 1)) {
                        x1++;
                    }
                    int z1 = z + 1;
                    while (z1 < SECTION_SIZE && sameWater(partial, heights, height, x, x1, y, z1, z1 + 1)) {
                        z1++;
                    }
                    take(partial, x, x1, y, y + 1, z, z1);
                    boxes.add(new float[] {x, y, z, x1, y + height, z1});
                }
            }
        }
        return boxes;
    }

    /**
     * {@return vrai si toute l'eau de {@code [x0, x1) × {y} × [z0, z1)} est partielle, pas encore
     * couverte, et de hauteur {@code height}}
     */
    private static boolean sameWater(
            boolean[] free, float[] heights, float height, int x0, int x1, int y, int z0, int z1) {
        for (int x = x0; x < x1; x++) {
            for (int z = z0; z < z1; z++) {
                int cell = cellIndex(x, y, z);
                if (!free[cell] || heights[cell] != height) {
                    return false;
                }
            }
        }
        return true;
    }

    /** {@return vrai si tous les blocs de la boîte {@code [x0, x1) × [y0, y1) × [z0, z1)} sont libres} */
    private static boolean allFree(boolean[] free, int x0, int x1, int y0, int y1, int z0, int z1) {
        for (int x = x0; x < x1; x++) {
            for (int y = y0; y < y1; y++) {
                for (int z = z0; z < z1; z++) {
                    if (!free[cellIndex(x, y, z)]) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** Marque couverts les blocs de la boîte {@code [x0, x1) × [y0, y1) × [z0, z1)}. */
    private static void take(boolean[] free, int x0, int x1, int y0, int y1, int z0, int z1) {
        for (int x = x0; x < x1; x++) {
            for (int y = y0; y < y1; y++) {
                for (int z = z0; z < z1; z++) {
                    free[cellIndex(x, y, z)] = false;
                }
            }
        }
    }
}
