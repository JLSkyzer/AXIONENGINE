package dev.axion.world;

/**
 * Géométrie des tuiles de collision du monde (C-38, fiche 5.30, étape 2).
 *
 * <p>Opérations <b>pures</b> — sans Minecraft ni Forge — qui transforment la géométrie de
 * collision brute d'une section 16³ en la forme attendue par la frontière (ADR-117) :
 * adressage de section, quantification en 1/16 de bloc, mise en repère relatif à la
 * section, et décision du repli en champ de hauteurs (R-641).
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
}
