package dev.axion.forge;

import dev.axion.world.BlockMaterials;
import dev.axion.world.WorldCollisionSource;
import dev.axion.world.WorldTileGeometry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Implémentation Forge de {@link WorldCollisionSource} (C-38, fiche 5.30) : lit la
 * géométrie de collision d'une section 16³ via l'API {@code Level} de Minecraft.
 *
 * <p>Seul paquet autorisé à importer {@code net.minecraft.*} (R-401). La quantification et
 * la résolution du matériau sont déléguées à {@link WorldTileGeometry} et
 * {@link BlockMaterials} (logique pure, testée).
 *
 * <p>R-640 : une section dont le chunk n'est pas chargé (ou dont le niveau est inconnu) est
 * rendue <b>pleine et solide</b> — jamais de chargement de chunk. R-641 : au-delà de
 * {@link WorldTileGeometry#MAX_TILE_BOXES} boîtes, la section bascule en champ de hauteurs
 * conservateur (hauteur maximale de collision par colonne).
 */
public final class ForgeWorldCollisionSource implements WorldCollisionSource {

    private static final int SIZE = WorldTileGeometry.SECTION_SIZE;

    /** Densité de l'eau douce, en kg/m³ (fluide par défaut d'une section). */
    private static final float WATER_DENSITY = 1000f;

    private final LongFunction<ServerLevel> levelById;
    private final BlockMaterials materials;

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
        List<float[]> boxes = new ArrayList<>();
        List<float[]> fluids = new ArrayList<>();
        Map<String, Integer> blockCounts = new HashMap<>();
        Map<String, BlockState> representative = new HashMap<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        boolean overBudget = false;

        for (int lx = 0; lx < SIZE && !overBudget; lx++) {
            for (int ly = 0; ly < SIZE && !overBudget; ly++) {
                for (int lz = 0; lz < SIZE; lz++) {
                    pos.set(ox + lx, oy + ly, oz + lz);
                    BlockState state = level.getBlockState(pos);

                    VoxelShape shape = state.getCollisionShape(level, pos);
                    if (!shape.isEmpty()) {
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
                        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(state.getBlock());
                        if (id != null) {
                            String key = id.toString();
                            blockCounts.merge(key, 1, Integer::sum);
                            representative.putIfAbsent(key, state);
                        }
                        if (boxes.size() > WorldTileGeometry.MAX_TILE_BOXES) {
                            overBudget = true;
                            break;
                        }
                    }

                    if (!state.getFluidState().isEmpty()) {
                        float height = state.getFluidState().getHeight(level, pos);
                        fluids.add(new float[] {lx, ly, lz, lx + 1f, ly + height, lz + 1f});
                    }
                }
            }
        }

        BlockMaterials.Material material = dominantMaterial(blockCounts, representative, fallback);
        float[][] fluidBoxes = fluids.toArray(new float[0][]);
        float fluidDensity = fluids.isEmpty() ? 0f : WATER_DENSITY;

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
