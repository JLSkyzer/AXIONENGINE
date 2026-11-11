package dev.axion.forge;

import dev.axion.world.BlockMaterials;
import dev.axion.world.DimensionId;
import dev.axion.world.WorldCollisionSource.CollisionShape;
import dev.axion.world.WorldCollisionSource.SectionTile;
import dev.axion.world.WorldTileGeometry;
import java.util.Arrays;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
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

    /** Le dessus de la dalle des gabarits {@code plancher}, en coordonnées du test. */
    private static final double DESSUS_DALLE = 2.0;

    private static final float[] DROIT = {0f, 0f, 0f, 1f};

    private CollisionDuMondeGameTests() {}

    /**
     * Une section reconstruite à l'identique ne réveille rien. Une horloge de deux observateurs face à face fait
     * invalider sa section tous les deux ticks ; chaque remplacement de la tuile réveillait tout corps posé dessus
     * — rapier réveille les corps d'un contact dont il retire le collider — et tout véhicule à sa portée (ADR-159
     * D3) : un véhicule et un cube de la section, à plus de 4 m de l'horloge, restaient éveillés 300 ticks sur 300.
     * Poser les observateurs change vraiment la section et les réveille une fois — un vrai changement part toujours
     * au natif — ; ils se rendorment, puis dorment pendant que l'horloge bat.
     *
     * <p>Dans un lot à lui : les balises que Minecraft pose au coin des gabarits voisins changeraient vraiment la
     * section. Sur {@code plancher_16}, la plus grande part du gabarit que tient une seule colonne de sections a
     * huit blocs de côté au moins, où que le gabarit soit posé.
     */
    @GameTest(template = "test/plancher_16", batch = "horloge_de_redstone", timeoutTicks = 400)
    public static void uneHorlogeDeRedstoneNeReveillePasCeQuiDortDansSaSection(GameTestHelper helper) {
        BlockPos origine = helper.absolutePos(BlockPos.ZERO);
        int[] x = partDansUneSection(origine.getX());
        int[] z = partDansUneSection(origine.getZ());
        BlockPos[] horloge = {new BlockPos(x[0], 2, z[0]), new BlockPos(x[0] + 1, 2, z[0])};
        Vec3 centre = new Vec3((x[0] + x[1]) / 2.0, DESSUS_DALLE, (z[0] + z[1]) / 2.0);
        Vec3 coin = new Vec3(x[1] - 0.5, DESSUS_DALLE, z[1] - 0.5);
        SectionPos section = SectionPos.of(BlockPos.containing(helper.absoluteVec(centre)));
        for (Vec3 point : new Vec3[] {Vec3.atCenterOf(horloge[0]), Vec3.atCenterOf(horloge[1]), coin}) {
            helper.assertTrue(SectionPos.of(BlockPos.containing(helper.absoluteVec(point))).equals(section),
                    point + " hors de la section " + section + " du véhicule");
        }
        helper.assertTrue(centre.distanceTo(Vec3.atCenterOf(horloge[1])) > 4.0, "l'horloge, loin des roues");

        ObservateursDeTest.declarer(helper, centre);
        AxionEntity vehicule = AssembliesDeTest.poser(helper, "axion:test/vehicule", centre.add(0, 0.1, 0), DROIT);
        AxionEntity cube = AssembliesDeTest.poser(helper, "axion:test_cube", coin.add(0, 1.0, 0), DROIT);
        boolean[] posee = {false};
        boolean[] reveilles = {false, false};
        boolean[] guet = {false};
        boolean[] alimente = {false};
        int[] eveilles = {0};
        int[] battements = {0};
        helper.onEachTick(() -> {
            if (posee[0]) {
                reveilles[0] |= !vehicule.netSleeping();
                reveilles[1] |= !cube.netSleeping();
            }
            if (!guet[0]) {
                return;
            }
            if (!vehicule.netSleeping() || !cube.netSleeping()) {
                eveilles[0]++;
            }
            boolean bat = helper.getBlockState(horloge[0]).getValue(ObserverBlock.POWERED);
            if (bat != alimente[0]) {
                alimente[0] = bat;
                battements[0]++;
            }
        });
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(vehicule.netSleeping() && cube.netSleeping(),
                        "le véhicule et le cube s'endorment"))
                .thenExecute(() -> {
                    helper.setBlock(horloge[0], Blocks.OBSERVER.defaultBlockState()
                            .setValue(ObserverBlock.FACING, Direction.EAST));
                    helper.setBlock(horloge[1], Blocks.OBSERVER.defaultBlockState()
                            .setValue(ObserverBlock.FACING, Direction.WEST));
                    posee[0] = true;
                })
                // Un vrai changement de la section, qui part au natif : il réveille l'un et l'autre.
                .thenWaitUntil(() -> helper.assertTrue(reveilles[0] && reveilles[1],
                        "poser les observateurs devait réveiller le véhicule (" + reveilles[0] + ") et le cube ("
                                + reveilles[1] + ")"))
                .thenWaitUntil(() -> helper.assertTrue(vehicule.netSleeping() && cube.netSleeping(),
                        "l'horloge les tient éveillés : véhicule endormi " + vehicule.netSleeping()
                                + ", cube endormi " + cube.netSleeping()))
                .thenExecute(() -> guet[0] = true)
                .thenIdle(100)
                .thenExecute(() -> {
                    helper.assertTrue(battements[0] >= 20, "l'horloge bat : " + battements[0] + " changements");
                    helper.assertTrue(eveilles[0] == 0,
                            eveilles[0] + " tick(s) sur 100 où le véhicule ou le cube était éveillé");
                    // L'horloge s'arrête avec le test : elle battrait sinon jusqu'à la fin du serveur.
                    helper.setBlock(horloge[0], Blocks.AIR);
                    helper.setBlock(horloge[1], Blocks.AIR);
                })
                .thenSucceed();
    }

    /**
     * {@return {@code [début, fin)}, en coordonnées du test, de la plus longue part d'un gabarit de seize blocs qui
     * tient dans une seule section, le long d'un axe} Huit blocs au moins.
     *
     * @param debut coordonnée absolue du premier bloc du gabarit sur cet axe
     */
    private static int[] partDansUneSection(int debut) {
        int coupe = Math.floorMod(-debut, SIZE);
        if (coupe == 0) {
            return new int[] {0, SIZE};
        }
        return coupe >= SIZE - coupe ? new int[] {0, coupe} : new int[] {coupe, SIZE};
    }

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
                new ForgeWorldCollisionSource(
                        id -> id == dimension ? level : null, BlockMaterials::empty, material -> 0);
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
                new ForgeWorldCollisionSource(
                        id -> id == dimension ? level : null, BlockMaterials::empty, material -> 0);
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
