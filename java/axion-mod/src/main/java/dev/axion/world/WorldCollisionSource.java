package dev.axion.world;

/**
 * Source de la géométrie de collision du monde pour une section (C-38, fiche 5.30).
 *
 * <p>C'est la <b>couture</b> (R-401) entre l'orchestration testable ({@link WorldTileService})
 * et le monde de Minecraft : l'implémentation concrète vit dans {@code dev.axion.forge}, lit
 * les {@code VoxelShape} via {@code Level.getBlockCollisions}, quantifie via
 * {@link WorldTileGeometry} et résout le matériau via {@link BlockMaterials}. Elle rend une
 * {@link SectionTile} déjà décidée (boîtes, champ de hauteurs, ou plein), en coordonnées
 * <b>relatives à la section</b>, prête à émettre sur la frontière.
 *
 * <p>R-640 : une section dont le chunk n'est pas chargé est rendue <b>pleine et solide</b>
 * (une boîte 16³), jamais un chargement de chunk — c'est le choix conservateur qui empêche
 * toute traversée.
 */
public interface WorldCollisionSource {

    /**
     * {@return la tuile de la section demandée, jamais {@code null}}
     *
     * @param dimension dimension visée
     * @param sectionX index de section sur x
     * @param sectionY index de section sur y
     * @param sectionZ index de section sur z
     */
    SectionTile tileOf(long dimension, int sectionX, int sectionY, int sectionZ);

    /**
     * Contenu d'une section : sa forme de collision et ses volumes de fluide, en repère
     * relatif à la section et quantifiés.
     *
     * @param collision forme de collision (jamais {@code null} ; boîtes vides = section vide)
     * @param fluidBoxes boîtes de fluide {@code [minx,miny,minz,maxx,maxy,maxz]}, vides si aucun
     * @param fluidDensity masse volumique du fluide (ignorée si {@code fluidBoxes} est vide)
     */
    record SectionTile(CollisionShape collision, float[][] fluidBoxes, float fluidDensity) {}

    /** Forme de collision d'une section : soit des boîtes, soit un champ de hauteurs (R-641). */
    sealed interface CollisionShape permits CollisionShape.Boxes, CollisionShape.Heightfield {

        /**
         * Index du matériau dominant (R-643) dans la table de la session (ADR-149 D4), ou
         * {@code 0xFFFF} sans matériau : le contact du solveur.
         */
        int material();

        /**
         * Collision par boîtes (cas courant) : {@code boxes} boîtes section-relatives
         * quantifiées. Une liste vide dénote une section sans collision (la tuile est retirée).
         *
         * @param palette les matériaux de la tuile (ADR-180), vide quand toutes les boîtes portent {@code material}
         * @param ranks le rang de chaque boîte dans la palette, vide sans palette
         */
        record Boxes(float[][] boxes, int material, int[] palette, byte[] ranks) implements CollisionShape {

            /** Des boîtes d'un seul matériau. */
            public Boxes(float[][] boxes, int material) {
                this(boxes, material, new int[0], new byte[0]);
            }
        }

        /**
         * Collision par champ de hauteurs (repli R-641) : {@code rows × cols} hauteurs
         * ligne-major, échelle {@code [x, y, z]}.
         *
         * @param palette les matériaux de la tuile (ADR-180), vide quand toutes les cellules portent {@code material}
         * @param ranks le rang de chaque cellule {@code (rows − 1) × (cols − 1)}, ligne-major, vide sans palette
         */
        record Heightfield(int rows, int cols, float[] heights, float[] scale, int material, int[] palette,
                byte[] ranks) implements CollisionShape {

            /** Un champ d'un seul matériau. */
            public Heightfield(int rows, int cols, float[] heights, float[] scale, int material) {
                this(rows, cols, heights, scale, material, new int[0], new byte[0]);
            }
        }
    }
}
