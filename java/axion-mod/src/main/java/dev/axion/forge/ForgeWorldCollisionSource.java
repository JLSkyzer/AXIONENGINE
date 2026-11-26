package dev.axion.forge;

import dev.axion.diag.WorldTileMetrics;
import dev.axion.physics.SimCommandStream;
import dev.axion.world.BlockMaterials;
import dev.axion.world.WorldCollisionSource;
import dev.axion.world.WorldTileGeometry;
import dev.axion.world.WorldTilePlanner.SectionKey;
import java.util.IdentityHashMap;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongFunction;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
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

    private final LongFunction<? extends Level> levelById;
    private final Supplier<BlockMaterials> materials;
    /** Index d'un matériau dans la table de la session (ADR-149 D4). */
    private final ToIntFunction<String> materialIndex;

    /** Sections déjà signalées en champ de hauteurs : un avertissement par section (R-641). */
    private final Set<SectionKey> heightfieldsWarned = new HashSet<>();
    /** Les sections repliées sur leur matériau dominant (ADR-180 D4), dites une fois. */
    private final Set<SectionKey> uniformWarned = new HashSet<>();

    /**
     * @param levelById résout la clé de dimension ({@link dev.axion.world.DimensionId}) en son niveau, ou
     *     {@code null} si absent : celui du serveur, ou celui du client pour son monde prédit (ADR-168 D1)
     * @param materials mappage data-driven des matériaux (R-643, ADR-145), lu à chaque tuile : une
     *     tuile construite après un rechargement prend le mappage nouveau
     */
    public ForgeWorldCollisionSource(
            LongFunction<? extends Level> levelById,
            Supplier<BlockMaterials> materials,
            ToIntFunction<String> materialIndex) {
        this.materialIndex = materialIndex;
        this.levelById = levelById;
        this.materials = materials;
    }

    @Override
    public SectionTile tileOf(long dimension, int sectionX, int sectionY, int sectionZ) {
        Level level = levelById.apply(dimension);
        BlockMaterials mapping = materials.get();
        BlockMaterials.Material fallback = mapping.fallback();
        // R-640 : niveau inconnu ou chunk non chargé → plein et solide, sans charger le chunk.
        if (level == null || !level.hasChunk(sectionX, sectionZ)) {
            WorldTileMetrics.GLOBAL.unloadedSection();
            return solidSection(fallback);
        }

        int ox = sectionX * SIZE;
        int oy = sectionY * SIZE;
        int oz = sectionZ * SIZE;
        // Fiche 5.30, étape 2 : une liste compacte de boîtes. Les blocs pleins vont dans une
        // grille, fusionnée en boîtes maximales ; les formes partielles gardent leurs boîtes.
        // Les formes partielles, chacune avec son matériau ; les blocs pleins dans une grille, celui de chacun.
        List<float[]> boxes = new ArrayList<>();
        List<Integer> boxMaterials = new ArrayList<>();
        boolean[] full = new boolean[SIZE * SIZE * SIZE];
        int[] fullMaterials = new int[SIZE * SIZE * SIZE];
        Arrays.fill(fullMaterials, -1);
        float[] fluidHeights = null;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        boolean overBudget = false;
        // Les états lus d'abord : le bloc au-dessus de chacun, dans la section, est déjà là (ADR-180 D3).
        BlockState[] states = new BlockState[SIZE * SIZE * SIZE];
        for (int lx = 0; lx < SIZE; lx++) {
            for (int ly = 0; ly < SIZE; ly++) {
                for (int lz = 0; lz < SIZE; lz++) {
                    states[WorldTileGeometry.cellIndex(lx, ly, lz)] =
                            level.getBlockState(pos.set(ox + lx, oy + ly, oz + lz));
                }
            }
        }
        Census census = new Census(mapping, fallback);

        // Toute la section est lue, même une fois la collision passée en champ de hauteurs :
        // l'eau, elle, se lit jusqu'au bout.
        for (int lx = 0; lx < SIZE; lx++) {
            for (int ly = 0; ly < SIZE; ly++) {
                for (int lz = 0; lz < SIZE; lz++) {
                    pos.set(ox + lx, oy + ly, oz + lz);
                    BlockState state = states[WorldTileGeometry.cellIndex(lx, ly, lz)];

                    VoxelShape shape = overBudget ? null : state.getCollisionShape(level, pos);
                    if (shape != null && !shape.isEmpty()) {
                        // ADR-180 D1 : chaque bloc garde son matériau — ou celui du bloc qui le couvre (D3) —, résolu
                        // une fois par état.
                        BlockState covering = null;
                        if (mapping.hasCovers()) {
                            BlockPos above = pos.above();
                            BlockState on = ly < SIZE - 1 ? states[WorldTileGeometry.cellIndex(lx, ly + 1, lz)]
                                    : level.getBlockState(above);
                            covering = census.covers(on) && on.getCollisionShape(level, above).isEmpty() ? on : null;
                        }
                        int material = census.solid(state, covering == null ? state : covering);
                        // La forme lue elle-même, et non le drapeau mis en cache par l'état :
                        // celui-ci est calculé hors contexte, la forme d'ici peut en dépendre.
                        if (Block.isShapeFullBlock(shape)) {
                            full[WorldTileGeometry.cellIndex(lx, ly, lz)] = true;
                            fullMaterials[WorldTileGeometry.cellIndex(lx, ly, lz)] = material;
                        } else {
                            for (AABB aabb : shape.toAabbs()) {
                                boxMaterials.add(material);
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
                        // Les formes partielles seules dépassent déjà le plafond : inutile de
                        // fusionner, la section bascule en champ de hauteurs.
                        if (boxes.size() > WorldTileGeometry.MAX_TILE_BOXES) {
                            overBudget = true;
                        }
                    }

                    if (!state.getFluidState().isEmpty()) {
                        fluidHeights = fluidHeights == null ? new float[SIZE * SIZE * SIZE] : fluidHeights;
                        fluidHeights[WorldTileGeometry.cellIndex(lx, ly, lz)] =
                                state.getFluidState().getHeight(level, pos);
                    }
                }
            }
        }
        boolean uniform = false;
        if (!overBudget) {
            List<float[]> partial = new ArrayList<>(boxes);
            boxes.addAll(WorldTileGeometry.mergeByMaterial(fullMaterials, boxMaterials));
            // R-641 : le seuil porte sur la liste compacte, celle qui traverse la frontière.
            if (WorldTileGeometry.needsHeightfield(boxes.size())) {
                // ADR-180 D4 : d'abord la fusion sans matériau, avec le dominant ; le champ de hauteurs ensuite.
                boxes = partial;
                boxes.addAll(WorldTileGeometry.mergeFullBlocks(full));
                uniform = true;
                overBudget = WorldTileGeometry.needsHeightfield(boxes.size());
                if (!overBudget && uniformWarned.add(new SectionKey(dimension, sectionX, sectionY, sectionZ))) {
                    LOGGER.warn("AXION : section ({}, {}, {}) de la dimension {} trop découpée par matériau : elle"
                            + " porte son seul matériau dominant (ADR-180 D4)", sectionX, sectionY, sectionZ,
                            dimension);
                }
            }
        }

        if (overBudget && heightfieldsWarned.add(new SectionKey(dimension, sectionX, sectionY, sectionZ))) {
            LOGGER.warn("AXION : section ({}, {}, {}) de la dimension {} trop découpée pour sa liste de"
                    + " boîtes (plus de {}) : collision en champ de hauteurs conservateur (R-641)",
                    sectionX, sectionY, sectionZ, dimension, WorldTileGeometry.MAX_TILE_BOXES);
        }
        BlockMaterials.Material material = census.dominant();
        // Fiche 5.30, étape 2, appliquée à l'eau (R-642) : une liste compacte, et non une boîte
        // par bloc d'eau — 3 840 pour une section de lac, que le natif garderait et parcourrait.
        float[][] fluidBoxes = fluidHeights == null ? new float[0][]
                : WorldTileGeometry.mergeFluids(fluidHeights).toArray(new float[0][]);
        float fluidDensity = fluidBoxes.length == 0 ? 0f : WATER_DENSITY;

        int dominant = indexOf(material);
        CollisionShape collision;
        if (overBudget) {
            collision = heightfield(level, ox, oy, oz, mapping, fallback, dominant); // R-641
        } else if (uniform) {
            collision = new CollisionShape.Boxes(boxes.toArray(new float[0][]), dominant);
        } else {
            WorldTileGeometry.TilePalette tile = WorldTileGeometry.palette(
                    boxMaterials.stream().mapToInt(Integer::intValue).toArray(), census.materialBlocks(), dominant);
            collision = new CollisionShape.Boxes(boxes.toArray(new float[0][]), tile.material(), tile.palette(),
                    tile.ranks());
        }
        return new SectionTile(collision, fluidBoxes, fluidDensity);
    }

    /**
     * {@return le bloc couvrant posé sur {@code pos} (ADR-180 D3) : sans collision et déclaré couvrant par le mappage ;
     * {@code null} sans} Il donne son matériau au bloc qu'il surmonte — la première couche de neige.
     */
    private static BlockState coveringAbove(Level level, BlockMaterials mapping, BlockPos pos) {
        if (!mapping.hasCovers()) {
            return null;
        }
        BlockPos above = pos.above();
        BlockState state = level.getBlockState(above);
        ResourceLocation id = state.isAir() ? null : ForgeRegistries.BLOCKS.getKey(state.getBlock());
        return id != null && mapping.covers(id.toString()) && state.getCollisionShape(level, above).isEmpty()
                ? state : null;
    }

    /** {@return le contact qu'un état de bloc a dans le mappage, par son identifiant puis ses étiquettes} */
    private static BlockMaterials.Material materialOf(
            BlockMaterials mapping, BlockState state, BlockMaterials.Material fallback) {
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        if (id == null) {
            return fallback;
        }
        List<String> tags = new ArrayList<>();
        state.getTags().forEach(tag -> tags.add(tag.location().toString()));
        return mapping.resolve(id.toString(), tags);
    }

    /**
     * L'index d'un contact de bloc dans la table de la session (ADR-149 D4) ; le contact du
     * solveur, sans matériau, n'en a pas.
     */
    private int indexOf(BlockMaterials.Material material) {
        return material.id() == null ? SimCommandStream.NO_MATERIAL : materialIndex.applyAsInt(material.id());
    }

    /** Une section pleine : une boîte 16³, matériau générique (R-640). */
    private SectionTile solidSection(BlockMaterials.Material fallback) {
        float[][] full = {{0f, 0f, 0f, SIZE, SIZE, SIZE}};
        return new SectionTile(
                new CollisionShape.Boxes(full, indexOf(fallback)),
                new float[0][],
                0f);
    }

    /** Un état de bloc rencontré dans la tuile en cours de lecture. */
    private static final class Seen {
        /** Le contact qu'il donne aux blocs qu'il habille, -1 tant qu'il n'en a habillé aucun. */
        private int material = -1;
        /** Les blocs à collision qui ont son contact. */
        private int clad;
        /** Le compte de son bloc, {@code null} tant qu'il n'a pas eu de collision. */
        private Tally own;
        /** Vrai ou faux une fois demandé : le mappage le dit-il couvrant. */
        private Boolean cover;
    }

    /** Un bloc à collision de la tuile : son premier état rencontré et son nombre. */
    private static final class Tally {
        private final BlockState first;
        private int count;

        private Tally(BlockState first) {
            this.first = first;
        }
    }

    /**
     * Ce qu'une tuile en cours de lecture sait de ses blocs. Chaque état se résout une fois — son contact, s'il couvre —
     * et les comptes se tiennent par état et par bloc ; le nom en texte d'un bloc ne se calcule qu'à la fin, une fois par
     * bloc : le calculer à chaque case prenait près de la moitié du temps d'une tuile (profil du 2026-11-25).
     */
    private final class Census {
        private final BlockMaterials mapping;
        private final BlockMaterials.Material fallback;
        private final Map<BlockState, Seen> states = new IdentityHashMap<>();
        private final Map<Block, Tally> byBlock = new IdentityHashMap<>();
        /** Les états qui ont donné un contact, dans l'ordre de leur première case. */
        private final List<Seen> claddings = new ArrayList<>();
        /** Les blocs à collision, dans l'ordre de leur première case. */
        private final List<Tally> blocks = new ArrayList<>();
        private BlockState lastState;
        private Seen lastSeen;

        private Census(BlockMaterials mapping, BlockMaterials.Material fallback) {
            this.mapping = mapping;
            this.fallback = fallback;
        }

        private Seen seen(BlockState state) {
            return states.computeIfAbsent(state, ignored -> new Seen());
        }

        /** {@return vrai si le mappage dit cet état couvrant (ADR-180 D3)} Sa forme, elle, se lit à sa place. */
        private boolean covers(BlockState state) {
            if (state.isAir()) {
                return false;
            }
            Seen seen = seen(state);
            if (seen.cover == null) {
                ResourceLocation id = ForgeRegistries.BLOCKS.getKey(state.getBlock());
                seen.cover = id != null && mapping.covers(id.toString());
            }
            return seen.cover;
        }

        /**
         * Compte un bloc à collision.
         *
         * @param state le bloc
         * @param cladding l'état qui lui donne son contact : lui-même, ou celui qui le couvre
         * @return l'index de son contact dans la table de la session
         */
        private int solid(BlockState state, BlockState cladding) {
            // Le terrain est fait de suites du même état : la table n'est lue qu'au changement.
            if (state != lastState) {
                lastState = state;
                lastSeen = seen(state);
            }
            Seen self = lastSeen;
            Seen contact = cladding == state ? self : seen(cladding);
            if (contact.material < 0) {
                contact.material = indexOf(materialOf(mapping, cladding, fallback));
                claddings.add(contact);
            }
            contact.clad++;
            if (self.own == null) {
                self.own = byBlock.computeIfAbsent(state.getBlock(), ignored -> {
                    Tally tally = new Tally(state);
                    blocks.add(tally);
                    return tally;
                });
            }
            self.own.count++;
            return contact.material;
        }

        /** {@return le nombre de blocs de chaque contact} */
        private Map<Integer, Integer> materialBlocks() {
            Map<Integer, Integer> counts = new HashMap<>();
            claddings.forEach(seen -> counts.merge(seen.material, seen.clad, Integer::sum));
            return counts;
        }

        /** {@return le contact du bloc le plus nombreux (R-643), celui de repli sans bloc à collision} */
        private BlockMaterials.Material dominant() {
            // Les mêmes noms, ajoutés dans l'ordre des cases : à égalité, le même bloc l'emporte qu'en comptant par nom.
            Map<String, Integer> counts = new HashMap<>();
            Map<String, BlockState> representative = new HashMap<>();
            for (Tally tally : blocks) {
                ResourceLocation id = ForgeRegistries.BLOCKS.getKey(tally.first.getBlock());
                if (id != null) {
                    String key = id.toString();
                    counts.merge(key, tally.count, Integer::sum);
                    representative.putIfAbsent(key, tally.first);
                }
            }
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
            return mapping.resolve(dominant, tags);
        }
    }

    /**
     * Champ de hauteurs conservateur (R-641) : pour chaque colonne (x, z) de la section, la
     * hauteur maximale de collision, échantillonnée en 17×17 sommets. Hauteurs en blocs
     * (échelle y = 1), échelle x/z = 16.
     */
    private CollisionShape.Heightfield heightfield(
            Level level, int ox, int oy, int oz, BlockMaterials mapping, BlockMaterials.Material fallback,
            int dominant) {
        int verts = SIZE + 1; // 17 sommets pour 16 cellules
        float[] heights = new float[verts * verts];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        // ADR-180 D1 : chaque cellule porte le matériau du bloc le plus haut de sa colonne.
        int[] cells = new int[SIZE * SIZE];
        Arrays.fill(cells, dominant);
        Map<Integer, Integer> counts = new HashMap<>();
        for (int z = 0; z < SIZE; z++) {
            for (int x = 0; x < SIZE; x++) {
                for (int ly = SIZE - 1; ly >= 0; ly--) {
                    pos.set(ox + x, oy + ly, oz + z);
                    BlockState state = level.getBlockState(pos);
                    if (!state.getCollisionShape(level, pos).isEmpty()) {
                        BlockState covering = coveringAbove(level, mapping, pos);
                        cells[z * SIZE + x] = indexOf(
                                materialOf(mapping, covering == null ? state : covering, fallback));
                        break;
                    }
                }
                counts.merge(cells[z * SIZE + x], 1, Integer::sum);
            }
        }
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
        WorldTileGeometry.TilePalette tile = WorldTileGeometry.palette(cells, counts, dominant);
        return new CollisionShape.Heightfield(
                verts, verts, heights, new float[] {SIZE, 1f, SIZE}, tile.material(), tile.palette(), tile.ranks());
    }
}
