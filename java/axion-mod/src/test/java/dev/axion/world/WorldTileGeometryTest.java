package dev.axion.world;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Épingle la géométrie pure des tuiles du monde (C-38, fiche 5.30 ; ADR-117).
 *
 * <p>Tests d'acceptance (ADR-125) : T-370 — adressage, quantification et fusion des blocs pleins ;
 * T-374 — seuil du champ de hauteurs (R-641) ; T-375 — fusion de l'eau (R-642).
 */
class WorldTileGeometryTest {

    @Test
    void sectionOfBlockEstUnFloorDiv() {
        assertEquals(0, WorldTileGeometry.sectionOfBlock(0));
        assertEquals(0, WorldTileGeometry.sectionOfBlock(15));
        assertEquals(1, WorldTileGeometry.sectionOfBlock(16));
        // Négatif : descend vers la section inférieure, sans trou autour de zéro.
        assertEquals(-1, WorldTileGeometry.sectionOfBlock(-1));
        assertEquals(-1, WorldTileGeometry.sectionOfBlock(-16));
        assertEquals(-2, WorldTileGeometry.sectionOfBlock(-17));
    }

    @Test
    void sectionOfWorldSuitLaPositionMonde() {
        assertEquals(0, WorldTileGeometry.sectionOfWorld(0.0));
        assertEquals(0, WorldTileGeometry.sectionOfWorld(15.9));
        assertEquals(1, WorldTileGeometry.sectionOfWorld(16.0));
        assertEquals(-1, WorldTileGeometry.sectionOfWorld(-0.5));
    }

    @Test
    void quantizeArrondiAuSeizieme() {
        assertEquals(0.5f, WorldTileGeometry.quantize(0.5));
        // 0.9375 = 15/16, déjà sur la grille (barrière).
        assertEquals(0.9375f, WorldTileGeometry.quantize(0.9375));
        // Une valeur légèrement hors grille est ramenée au 1/16 le plus proche.
        assertEquals(0.0625f, WorldTileGeometry.quantize(0.06));
        assertEquals(1.0f, WorldTileGeometry.quantize(0.97));
    }

    @Test
    void sectionRelativeBoxRetireLOrigineEtQuantifie() {
        // Une dalle inférieure (hauteur 0.5) au bloc monde (18, 5, 3) → section (1, 0, 0).
        double[] worldBox = {18.0, 5.0, 3.0, 19.0, 5.5, 4.0};
        float[] rel = WorldTileGeometry.sectionRelativeBox(worldBox, 1, 0, 0);
        // x : 18 - 16 = 2 ; 19 - 16 = 3. y et z inchangés (section 0 sur ces axes).
        assertArrayEquals(new float[] {2f, 5f, 3f, 3f, 5.5f, 4f}, rel, 0f);
    }

    @Test
    void sectionRelativeBoxGereLesSectionsNegatives() {
        // Bloc monde (-3, 0, 0) est dans la section (-1, 0, 0) ; origine -16.
        double[] worldBox = {-3.0, 0.0, 0.0, -2.0, 1.0, 1.0};
        float[] rel = WorldTileGeometry.sectionRelativeBox(worldBox, -1, 0, 0);
        // x : -3 - (-16) = 13 ; -2 - (-16) = 14.
        assertArrayEquals(new float[] {13f, 0f, 0f, 14f, 1f, 1f}, rel, 0f);
    }

    @Test
    void needsHeightfieldAuDelaDuPlafond() {
        assertFalse(WorldTileGeometry.needsHeightfield(WorldTileGeometry.MAX_TILE_BOXES));
        assertTrue(WorldTileGeometry.needsHeightfield(WorldTileGeometry.MAX_TILE_BOXES + 1));
    }

    /** Une grille d'occupation dont la case {@code (x, y, z)} est pleine si {@code plein} le dit. */
    private static boolean[] grille(java.util.function.Predicate<int[]> plein) {
        boolean[] full = new boolean[4096];
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    full[WorldTileGeometry.cellIndex(x, y, z)] = plein.test(new int[] {x, y, z});
                }
            }
        }
        return full;
    }

    /**
     * Vérifie que les boîtes couvrent exactement les blocs pleins, chacun une seule fois : des
     * coordonnées entières dans la section, des boîtes non vides, aucun chevauchement, aucun
     * trou.
     */
    private static void couvreExactement(boolean[] full, List<float[]> boites) {
        int[] couverture = new int[4096];
        for (float[] b : boites) {
            assertEquals(6, b.length);
            for (float c : b) {
                assertEquals(Math.rint(c), c, "coordonnée entière : " + c);
                assertTrue(c >= 0f && c <= 16f, "dans la section : " + c);
            }
            assertTrue(b[0] < b[3] && b[1] < b[4] && b[2] < b[5], "boîte non vide");
            for (int x = (int) b[0]; x < b[3]; x++) {
                for (int y = (int) b[1]; y < b[4]; y++) {
                    for (int z = (int) b[2]; z < b[5]; z++) {
                        couverture[WorldTileGeometry.cellIndex(x, y, z)]++;
                    }
                }
            }
        }
        for (int i = 0; i < 4096; i++) {
            assertEquals(full[i] ? 1 : 0, couverture[i], "case " + i);
        }
    }

    @Test
    void uneSectionPleineDevientUneSeuleBoite() {
        List<float[]> boites = WorldTileGeometry.mergeFullBlocks(grille(c -> true));
        assertEquals(1, boites.size());
        assertArrayEquals(new float[] {0f, 0f, 0f, 16f, 16f, 16f}, boites.get(0), 0f);
    }

    @Test
    void uneSectionVideNeDonneAucuneBoite() {
        assertTrue(WorldTileGeometry.mergeFullBlocks(grille(c -> false)).isEmpty());
    }

    @Test
    void unSolPlatDevientUneDalle() {
        List<float[]> boites = WorldTileGeometry.mergeFullBlocks(grille(c -> c[1] < 3));
        assertEquals(1, boites.size());
        assertArrayEquals(new float[] {0f, 0f, 0f, 16f, 3f, 16f}, boites.get(0), 0f);
    }

    @Test
    void unBlocIsoleResteUnBloc() {
        List<float[]> boites =
                WorldTileGeometry.mergeFullBlocks(grille(c -> c[0] == 5 && c[1] == 7 && c[2] == 9));
        assertEquals(1, boites.size());
        assertArrayEquals(new float[] {5f, 7f, 9f, 6f, 8f, 10f}, boites.get(0), 0f);
    }

    @Test
    void unTerrainEnGradinsTientEnQuatreBoites() {
        // Sol à 8 blocs, puis une marche de plus tous les 4 blocs le long de x : un bloc par
        // boîte en donnerait 2 432, la fusion quatre — le socle et trois marches.
        boolean[] full = grille(c -> c[1] < 8 + c[0] / 4);
        List<float[]> boites = WorldTileGeometry.mergeFullBlocks(full);
        couvreExactement(full, boites);
        assertEquals(4, boites.size());
        assertArrayEquals(new float[] {0f, 0f, 0f, 16f, 8f, 16f}, boites.get(0), 0f);
    }

    @Test
    void unDamierNeSeFusionnePas() {
        // Aucun bloc plein n'en touche un autre par une face : 2 048 boîtes d'un bloc.
        boolean[] full = grille(c -> (c[0] + c[1] + c[2]) % 2 == 0);
        List<float[]> boites = WorldTileGeometry.mergeFullBlocks(full);
        couvreExactement(full, boites);
        assertEquals(2048, boites.size());
    }

    @Test
    void laFusionCouvreExactementDesGrillesQuelconques() {
        java.util.Random hasard = new java.util.Random(0x5EC7_10AL);
        for (int essai = 0; essai < 200; essai++) {
            double densite = hasard.nextDouble();
            boolean[] full = new boolean[4096];
            for (int i = 0; i < full.length; i++) {
                full[i] = hasard.nextDouble() < densite;
            }
            boolean[] copie = full.clone();
            List<float[]> boites = WorldTileGeometry.mergeFullBlocks(full);
            couvreExactement(full, boites);
            assertArrayEquals(copie, full, "la grille d'entrée n'est pas modifiée");
            // Même entrée, même sortie (R-1020).
            List<float[]> encore = WorldTileGeometry.mergeFullBlocks(full);
            assertEquals(boites.size(), encore.size());
            for (int i = 0; i < boites.size(); i++) {
                assertArrayEquals(boites.get(i), encore.get(i), 0f);
            }
        }
    }

    @Test
    void uneGrilleDeMauvaiseTailleEstRefusee() {
        assertThrows(
                IllegalArgumentException.class,
                () -> WorldTileGeometry.mergeFullBlocks(new boolean[4095]));
    }

    /** Hauteur de l'eau d'un bloc de surface sous l'air, celle que Minecraft donne à une source. */
    private static final float SURFACE = 8f / 9f;

    /** Une grille de hauteurs d'eau : {@code hauteur} donne celle de chaque bloc, 0 sans eau. */
    private static float[] eau(java.util.function.Function<int[], Float> hauteur) {
        float[] hauteurs = new float[4096];
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    hauteurs[WorldTileGeometry.cellIndex(x, y, z)] = hauteur.apply(new int[] {x, y, z});
                }
            }
        }
        return hauteurs;
    }

    /**
     * Vérifie que les boîtes couvrent exactement l'eau : chaque bloc d'eau une seule fois, sur
     * toute sa hauteur et pas au-delà ; aucun bloc sans eau.
     */
    private static void couvreExactementLEau(float[] hauteurs, List<float[]> boites) {
        int[] couverture = new int[4096];
        for (float[] b : boites) {
            assertEquals(6, b.length);
            assertTrue(b[0] < b[3] && b[1] < b[4] && b[2] < b[5], "boîte non vide");
            for (int x = (int) b[0]; x < b[3]; x++) {
                for (int z = (int) b[2]; z < b[5]; z++) {
                    for (int y = (int) b[1]; y < Math.ceil(b[4]); y++) {
                        int bloc = WorldTileGeometry.cellIndex(x, y, z);
                        couverture[bloc]++;
                        float haut = Math.min(b[4] - y, 1f);
                        assertEquals(Math.min(hauteurs[bloc], 1f), haut, 1e-6f, "hauteur du bloc " + bloc);
                    }
                }
            }
        }
        for (int i = 0; i < 4096; i++) {
            assertEquals(hauteurs[i] > 0f ? 1 : 0, couverture[i], "bloc " + i);
        }
    }

    @Test
    void uneSectionPleineDEauDevientUneSeuleBoite() {
        List<float[]> boites = WorldTileGeometry.mergeFluids(eau(c -> 1f));
        assertEquals(1, boites.size());
        assertArrayEquals(new float[] {0f, 0f, 0f, 16f, 16f, 16f}, boites.get(0), 0f);
    }

    @Test
    void unLacTientEnDeuxBoites() {
        // Quatorze couches pleines, puis la surface à 8/9 de bloc : 3 840 boîtes d'un bloc
        // jusqu'ici, deux désormais.
        float[] hauteurs = eau(c -> c[1] < 14 ? 1f : c[1] == 14 ? SURFACE : 0f);
        List<float[]> boites = WorldTileGeometry.mergeFluids(hauteurs);
        couvreExactementLEau(hauteurs, boites);
        assertEquals(2, boites.size());
        assertArrayEquals(new float[] {0f, 0f, 0f, 16f, 14f, 16f}, boites.get(0), 0f);
        assertArrayEquals(new float[] {0f, 14f, 0f, 16f, 14f + SURFACE, 16f}, boites.get(1), 1e-6f);
    }

    @Test
    void uneEauPartielleNeSEmpilePas() {
        // Deux couches d'eau à mi-hauteur : une boîte qui les réunirait couvrirait l'air entre
        // elles.
        float[] hauteurs = eau(c -> c[1] == 3 || c[1] == 4 ? 0.5f : 0f);
        List<float[]> boites = WorldTileGeometry.mergeFluids(hauteurs);
        couvreExactementLEau(hauteurs, boites);
        assertEquals(2, boites.size());
    }

    @Test
    void desHauteursDifferentesNeSeFusionnentPas() {
        // Une eau qui s'écoule : deux hauteurs dans la même couche, deux boîtes.
        float[] hauteurs = eau(c -> c[1] != 0 ? 0f : c[0] < 8 ? 0.5f : 0.75f);
        List<float[]> boites = WorldTileGeometry.mergeFluids(hauteurs);
        couvreExactementLEau(hauteurs, boites);
        assertEquals(2, boites.size());
    }

    @Test
    void uneHauteurAberranteNEstPasDeLEau() {
        float[] hauteurs = eau(c -> c[0] == 0 ? Float.NaN : c[0] == 1 ? -1f : 0f);
        assertTrue(WorldTileGeometry.mergeFluids(hauteurs).isEmpty());
    }

    @Test
    void laFusionDeLEauCouvreExactementDesGrillesQuelconques() {
        java.util.Random hasard = new java.util.Random(0xEA0L);
        float[] valeurs = {0f, 1f, SURFACE, 0.5f, 0.25f};
        for (int essai = 0; essai < 200; essai++) {
            double eauPresente = hasard.nextDouble();
            float[] hauteurs = new float[4096];
            for (int i = 0; i < hauteurs.length; i++) {
                hauteurs[i] = hasard.nextDouble() < eauPresente
                        ? valeurs[1 + hasard.nextInt(valeurs.length - 1)]
                        : 0f;
            }
            float[] copie = hauteurs.clone();
            List<float[]> boites = WorldTileGeometry.mergeFluids(hauteurs);
            couvreExactementLEau(hauteurs, boites);
            assertArrayEquals(copie, hauteurs, 0f, "la grille d'entrée n'est pas modifiée");
            // Même entrée, même sortie (R-1020).
            List<float[]> encore = WorldTileGeometry.mergeFluids(hauteurs);
            assertEquals(boites.size(), encore.size());
            for (int i = 0; i < boites.size(); i++) {
                assertArrayEquals(boites.get(i), encore.get(i), 0f);
            }
        }
    }

    @Test
    void uneGrilleDEauDeMauvaiseTailleEstRefusee() {
        assertThrows(
                IllegalArgumentException.class,
                () -> WorldTileGeometry.mergeFluids(new float[4095]));
    }
}
