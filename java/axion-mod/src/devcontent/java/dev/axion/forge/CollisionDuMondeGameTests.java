package dev.axion.forge;

import dev.axion.world.BlockMaterials;
import dev.axion.world.DimensionId;
import dev.axion.world.WorldCollisionSource.CollisionShape;
import dev.axion.world.WorldCollisionSource.SectionTile;
import dev.axion.world.WorldTileGeometry;
import java.util.Arrays;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * Le fournisseur de collision du monde (C-38, fiche 5.30) exercé dans le vrai monde, là où les
 * tests unitaires ne vont pas : un chunk réellement absent, de vraies formes de blocs. Test
 * d'acceptance T-374 (ADR-125). Contenu de développement, jamais empaqueté (R-1790).
 */
@GameTestHolder("axion")
@PrefixGameTestTemplate(false)
public final class CollisionDuMondeGameTests {

    private static final int SIZE = WorldTileGeometry.SECTION_SIZE;

    private CollisionDuMondeGameTests() {}

    /**
     * R-640 : une section dont le chunk n'est pas chargé est rendue pleine et solide, et la lire ne
     * charge pas le chunk — {@code getBlockState} l'aurait chargé, voire généré. Une dimension que
     * le serveur ne connaît pas est rendue de même.
     */
    @GameTest(template = "test/plancher")
    public static void uneSectionNonChargeeEstPleineSansQueSonChunkSeCharge(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        long dimension = DimensionId.of(level.dimension().location().toString());
        ForgeWorldCollisionSource source =
                new ForgeWorldCollisionSource(id -> id == dimension ? level : null, BlockMaterials.empty());
        // À cent mille blocs de l'aire de test, aucun joueur ni aucun test n'a chargé ce chunk.
        ChunkPos loin = new ChunkPos(helper.absolutePos(BlockPos.ZERO).offset(100_000, 0, 0));
        helper.assertFalse(
                level.hasChunk(loin.x, loin.z), "le chunk " + loin + " est déjà chargé : le test ne prouverait rien");

        SectionTile tile = source.tileOf(dimension, loin.x, 4, loin.z);
        helper.assertFalse(level.hasChunk(loin.x, loin.z), "lire la section a chargé le chunk " + loin);
        pleineEtSolide(helper, tile, "chunk non chargé");
        pleineEtSolide(helper, source.tileOf(dimension + 1, loin.x, 4, loin.z), "dimension inconnue");
        helper.succeed();
    }

    /**
     * R-641 : une section trop découpée pour sa liste de boîtes passe en champ de hauteurs
     * conservateur. Des escaliers, deux boîtes chacun, empilés colonne par colonne jusqu'à une
     * hauteur propre à chaque colonne : quelque 6 400 boîtes, au-delà du plafond de 4 096. Chaque
     * sommet du champ porte le dessus du bloc le plus haut de sa colonne — l'escalier n'est plein
     * qu'à moitié en haut, le champ le compte plein.
     */
    @GameTest(template = "test/plancher")
    public static void uneSectionTropDecoupeePasseEnChampDeHauteursConservateur(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        long dimension = DimensionId.of(level.dimension().location().toString());
        ForgeWorldCollisionSource source =
                new ForgeWorldCollisionSource(id -> id == dimension ? level : null, BlockMaterials.empty());
        // Loin de l'aire de test, et au-dessus de tout relief : la section ne contient que ce que
        // le test y pose.
        ChunkPos chunk = new ChunkPos(helper.absolutePos(BlockPos.ZERO).offset(-100_000, 0, 0));
        level.getChunk(chunk.x, chunk.z);
        int sectionY = SectionPos.blockToSectionCoord(level.getMaxBuildHeight()) - 2;

        BlockState escalier = Blocks.OAK_STAIRS.defaultBlockState();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                for (int y = 0; y < hauteur(x, z); y++) {
                    pos.set(chunk.getMinBlockX() + x, sectionY * SIZE + y, chunk.getMinBlockZ() + z);
                    level.setBlock(pos, escalier, Block.UPDATE_CLIENTS);
                }
            }
        }

        SectionTile tile = source.tileOf(dimension, chunk.x, sectionY, chunk.z);
        if (!(tile.collision() instanceof CollisionShape.Heightfield champ)) {
            helper.fail("collision en " + tile.collision().getClass().getSimpleName() + ", champ de hauteurs attendu");
            return;
        }
        int sommets = SIZE + 1;
        helper.assertTrue(
                champ.rows() == sommets && champ.cols() == sommets,
                champ.rows() + "×" + champ.cols() + " sommets, " + sommets + "×" + sommets + " attendus");
        helper.assertTrue(
                Arrays.equals(champ.scale(), new float[] {SIZE, 1f, SIZE}), "échelle " + Arrays.toString(champ.scale()));
        // Ligne-major : la ligne suit z, la colonne x ; le dernier sommet reprend la dernière colonne.
        for (int ligne = 0; ligne < sommets; ligne++) {
            for (int colonne = 0; colonne < sommets; colonne++) {
                float attendue = hauteur(Math.min(colonne, SIZE - 1), Math.min(ligne, SIZE - 1));
                float lue = champ.heights()[ligne * sommets + colonne];
                helper.assertTrue(
                        lue == attendue,
                        "sommet (x " + colonne + ", z " + ligne + ") : " + lue + ", " + attendue + " attendus");
            }
        }
        helper.succeed();
    }

    /** {@return la hauteur d'escaliers de la colonne (x, z), de 9 à 16 blocs} */
    private static int hauteur(int x, int z) {
        return 9 + (x + 2 * z) % 8;
    }

    private static void pleineEtSolide(GameTestHelper helper, SectionTile tile, String cas) {
        if (!(tile.collision() instanceof CollisionShape.Boxes boites)) {
            helper.fail(cas + " : collision en " + tile.collision().getClass().getSimpleName() + ", boîtes attendues");
            return;
        }
        helper.assertTrue(
                boites.boxes().length == 1 && Arrays.equals(boites.boxes()[0], new float[] {0f, 0f, 0f, SIZE, SIZE, SIZE}),
                cas + " : " + Arrays.deepToString(boites.boxes()) + ", une boîte 16³ attendue");
        helper.assertTrue(
                tile.fluidBoxes().length == 0 && tile.fluidDensity() == 0f, cas + " : de l'eau dans une section inconnue");
    }
}
