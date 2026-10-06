package dev.axion.forge;

import dev.axion.world.BlockMaterials;
import dev.axion.world.WorldCollisionSource;
import dev.axion.world.WorldTileGeometry;
import dev.axion.world.WorldTilePlanner.SectionKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implémentation Forge de {@link WorldCollisionSource} (C-38, fiche 5.30) : lit la
 * géométrie de collision d'une section 16³ via l'API {@code Level} de Minecraft.
 *
 * <p>Seul paquet autorisé à importer {@code net.minecraft.*} (R-401). La quantification, la
 * fusion des blocs pleins en boîtes maximales (la « liste compacte » de l'étape 2) et la
 * résolution du matériau sont déléguées à {@link WorldTileGeometry} et
 * {@link BlockMaterials} (logique pure, testée).
 *
 * <p>R-640 : une section dont le chunk n'est pas chargé (ou dont le niveau est inconnu) est
 * rendue <b>pleine et solide</b> — jamais de chargement de chunk. R-641 : au-delà de
 * {@link WorldTileGeometry#MAX_TILE_BOXES} boîtes, la section bascule en champ de hauteurs
 * conservateur (hauteur maximale de collision par colonne), avec un avertissement par section.
 */
public final class ForgeWorldCollisionSource implements WorldCollisionSource {

    private static final Logger LOGGER = LoggerFactory.getLogger("axion");

    private static final int SIZE = WorldTileGeometry.SECTION_SIZE;

    /** Densité de l'eau douce, en kg/m³ (fluide par défaut d'une section). */
    private static final float WATER_DENSITY = 1000f;

    private final LongFunction<ServerLevel> levelById;
    private final BlockMaterials materials;

    /** Sections déjà signalées en champ de hauteurs : un avertissement par section (R-641). */
    private final Set<SectionKey> heightfieldsWarned = new HashSet<>();

    /**
     * @param levelById résout la clé de dimension ({@link dev.axion.world.DimensionId}) en
     *     son {@code ServerLevel}, ou {@code null} si absent
     * @param materials mappage data-driven des matériaux (R-643)
     */
    public ForgeWorldCollisionSource(
            LongFunction<ServerLevel> levelById, BlockMaterials materials) {
        this.levelById = levelById;
        this.materials = materials;
    }

    @Override
    public SectionTile tileOf(long dimension, int sectionX, int sectionY, int sectionZ) {
        ServerLevel level = levelById.apply(dimension);
        BlockMaterials.Material fallback = materials.fallback();
        // R-640 : niveau inconnu ou chunk non chargé → plein et solide, sans charger le chunk.
        if (level == null || !level.hasChunk(sectionX, sectionZ)) {
            return solidSection(fallback);
        }

        int ox = sectionX * SIZE;
        int oy = sectionY * SIZE;
        int oz = sectionZ * SIZE;
        // Fiche 5.30, étape 2 : une liste compacte de boîtes. Les blocs pleins vont dans une
        // grille, fusionnée en boîtes maximales ; les formes partielles gardent leurs boîtes.
        List<float[]> boxes = new ArrayList<>();
        boolean[] full = new boolean[SIZE * SIZE * SIZE];
        float[] fluidHeights = new float[SIZE * SIZE * SIZE];
        Map<String, Integer> blockCounts = new HashMap<>();
        Map<String, BlockState> representative = new HashMap<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        boolean overBudget = false;

        // Toute la section est lue, même une fois la collision passée en champ de hauteurs :
        // l'eau, elle, se lit jusqu'au bout.
        for (int lx = 0; lx < SIZE; lx++) {
            for (int ly = 0; ly < SIZE; ly++) {
                for (int lz = 0; lz < SIZE; lz++) {
                    pos.set(ox + lx, oy + ly, oz + lz);
                    BlockState state = level.getBlockState(pos);

                    VoxelShape shape = overBudget ? null : state.getCollisionShape(level, pos);
                    if (shape != null && !shape.isEmpty()) {
                        // La forme lue elle-même, et non le drapeau mis en cache par l'état :
                        // celui-ci est calculé hors contexte, la forme d'ici peut en dépendre.
                        if (Block.isShapeFullBlock(shape)) {
                            full[WorldTileGeometry.cellIndex(lx, ly, lz)] = true;
                        } else {
                            for (AABB aabb : shape.toAabbs()) {
                                boxes.add(
                                        new float[] {
                                            WorldTileGeometry.quantize(lx + aabb.minX),
                                            WorldTileGeometry.quantize(ly + aabb.minY),
                                            WorldTileGeometry.quantize(lz + aabb.minZ),
                                            WorldTileGeometry.quantize(lx + aabb.maxX),
                                            WorldTileGeometry.quantize(ly + aabb.maxY),
                                            WorldTileGeometry.quantize(lz + aabb.maxZ),
                                        });
                            }
                        }
                        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(state.getBlock());
                        if (id != null) {
                            String key = id.toString();
                            blockCounts.merge(key, 1, Integer::sum);
                            representative.putIfAbsent(key, state);
                        }
                        // Les formes partielles seules dépassent déjà le plafond : inutile de
                        // fusionner, la section bascule en champ de hauteurs.
                        if (boxes.size() > WorldTileGeometry.MAX_TILE_BOXES) {
                            overBudget = true;
                        }
                    }

                    if (!state.getFluidState().isEmpty()) {
                        fluidHeights[WorldTileGeometry.cellIndex(lx, ly, lz)] =
                                state.getFluidState().getHeight(level, pos);
                    }
                }
            }
        }
        if (!overBudget) {
            boxes.addAll(WorldTileGeometry.mergeFullBlocks(full));
            // R-641 : le seuil porte sur la liste compacte, celle qui traverse la frontière.
            overBudget = WorldTileGeometry.needsHeightfield(boxes.size());
        }

        if (overBudget && heightfieldsWarned.add(new SectionKey(dimension, sectionX, sectionY, sectionZ))) {
            LOGGER.warn("AXION : section ({}, {}, {}) de la dimension {} trop découpée pour sa liste de"
                    + " boîtes (plus de {}) : collision en champ de hauteurs conservateur (R-641)",
                    sectionX, sectionY, sectionZ, dimension, WorldTileGeometry.MAX_TILE_BOXES);
        }
        BlockMaterials.Material material = dominantMaterial(blockCounts, representative, fallback);
        // Fiche 5.30, étape 2, appliquée à l'eau (R-642) : une liste compacte, et non une boîte
        // par bloc d'eau — 3 840 pour une section de lac, que le natif garderait et parcourrait.
        float[][] fluidBoxes = WorldTileGeometry.mergeFluids(fluidHeights).toArray(new float[0][]);
        float fluidDensity = fluidBoxes.length == 0 ? 0f : WATER_DENSITY;

        CollisionShape collision =
                overBudget
                        ? heightfield(level, ox, oy, oz, material) // R-641
                        : new CollisionShape.Boxes(
                                boxes.toArray(new float[0][]), material.friction(), material.restitution());
        return new SectionTile(collision, fluidBoxes, fluidDensity);
    }

    /** Une section pleine : une boîte 16³, matériau générique (R-640). */
    private static SectionTile solidSection(BlockMaterials.Material fallback) {
        float[][] full = {{0f, 0f, 0f, SIZE, SIZE, SIZE}};
        return new SectionTile(
                new CollisionShape.Boxes(full, fallback.friction(), fallback.restitution()),
                new float[0][],
                0f);
    }

    private BlockMaterials.Material dominantMaterial(
            Map<String, Integer> counts,
            Map<String, BlockState> representative,
            BlockMaterials.Material fallback) {
        String dominant = null;
        int best = 0;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > best) {
                best = entry.getValue();
                dominant = entry.getKey();
            }
        }
        if (dominant == null) {
            return fallback;
        }
        List<String> tags = new ArrayList<>();
        representative.get(dominant).getTags().forEach(tag -> tags.add(tag.location().toString()));
        return materials.resolve(dominant, tags);
    }

    /**
     * Champ de hauteurs conservateur (R-641) : pour chaque colonne (x, z) de la section, la
     * hauteur maximale de collision, échantillonnée en 17×17 sommets. Hauteurs en blocs
     * (échelle y = 1), échelle x/z = 16.
     */
    private CollisionShape.Heightfield heightfield(
            ServerLevel level, int ox, int oy, int oz, BlockMaterials.Material material) {
        int verts = SIZE + 1; // 17 sommets pour 16 cellules
        float[] heights = new float[verts * verts];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int row = 0; row < verts; row++) { // z
            for (int col = 0; col < verts; col++) { // x
                int cx = ox + Math.min(col, SIZE - 1);
                int cz = oz + Math.min(row, SIZE - 1);
                float top = 0f;
                for (int ly = SIZE - 1; ly >= 0; ly--) {
                    pos.set(cx, oy + ly, cz);
                    if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
                        top = ly + 1f;
                        break;
                    }
                }
                heights[row * verts + col] = top;
            }
        }
        return new CollisionShape.Heightfield(
                verts, verts, heights, new float[] {SIZE, 1f, SIZE}, material.friction(), material.restitution());
    }
}
