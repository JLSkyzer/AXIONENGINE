package dev.axion.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Disposition des pages d'atlas (fiche C-26, R-570, ADR-122 T-c). */
class AtlasLayoutTest {

    private static final TextureBinding PAGE = new TextureBinding("axion:texture/a/1/atlas/0", false, true);

    /** Vérifie tout ce qu'une disposition garantit, quelles que soient les images. */
    private static void assertSound(int[] widths, int[] heights, int levels, List<AtlasLayout.Page> pages) {
        int align = 1 << levels;
        boolean[] seen = new boolean[widths.length];
        for (AtlasLayout.Page page : pages) {
            assertEquals(1, Integer.bitCount(page.width()), "largeur " + page.width() + " pas une puissance de deux");
            assertTrue(page.width() <= 4096 && page.height() <= 4096, "page au-delà de 4096 (R-570)");
            assertEquals(0, page.height() % align, "hauteur " + page.height() + " pas multiple de " + align);
            assertEquals(levels, page.levels());
            List<AtlasLayout.Tile> tiles = page.tiles();
            for (AtlasLayout.Tile tile : tiles) {
                assertFalse(seen[tile.index()], "image " + tile.index() + " rangée deux fois");
                seen[tile.index()] = true;
                assertEquals(widths[tile.index()], tile.width());
                assertEquals(heights[tile.index()], tile.height());
                assertEquals(align, tile.margin(), "marge");
                assertEquals(0, tile.x() % align, "tuile non alignée en x");
                assertEquals(0, tile.y() % align, "tuile non alignée en y");
                assertEquals(0, tile.slotWidth() % align);
                assertEquals(0, tile.slotHeight() % align);
                assertTrue(tile.slotWidth() >= tile.width() + 2 * tile.margin(), "marge rognée en largeur");
                assertTrue(tile.slotHeight() >= tile.height() + 2 * tile.margin(), "marge rognée en hauteur");
                assertTrue(tile.x() + tile.slotWidth() <= page.width(), "tuile hors de la page");
                assertTrue(tile.y() + tile.slotHeight() <= page.height(), "tuile hors de la page");
            }
            for (int a = 0; a < tiles.size(); a++) {
                for (int b = a + 1; b < tiles.size(); b++) {
                    AtlasLayout.Tile p = tiles.get(a);
                    AtlasLayout.Tile q = tiles.get(b);
                    boolean apart = p.x() + p.slotWidth() <= q.x() || q.x() + q.slotWidth() <= p.x()
                            || p.y() + p.slotHeight() <= q.y() || q.y() + q.slotHeight() <= p.y();
                    assertTrue(apart, "tuiles " + p + " et " + q + " superposées");
                }
            }
        }
        for (int index = 0; index < seen.length; index++) {
            assertTrue(seen[index], "image " + index + " oubliée");
        }
    }

    @Test
    @DisplayName("Deux images de 16 sans mipmaps : marge d'un texel, côte à côte sur une rangée")
    void deuxImagesSansMipmaps() {
        List<AtlasLayout.Page> pages = AtlasLayout.pack(new int[] {16, 16}, new int[] {16, 16}, 0);

        assertEquals(1, pages.size());
        AtlasLayout.Page page = pages.get(0);
        assertEquals(64, page.width());
        assertEquals(18, page.height());
        assertEquals(List.of(
                        new AtlasLayout.Tile(0, 0, 0, 18, 18, 16, 16, 1),
                        new AtlasLayout.Tile(1, 18, 0, 18, 18, 16, 16, 1)),
                page.tiles());
    }

    @Test
    @DisplayName("Toute disposition : alignée sur 2^L, marges entières, sans chevauchement, ≤ 4096")
    void touteDispositionEstSaine() {
        Random random = new Random(122);
        for (int trial = 0; trial < 200; trial++) {
            int count = 1 + random.nextInt(40);
            int levels = random.nextInt(5);
            int[] widths = new int[count];
            int[] heights = new int[count];
            for (int index = 0; index < count; index++) {
                widths[index] = 1 + random.nextInt(256);
                heights[index] = 1 + random.nextInt(256);
            }
            assertSound(widths, heights, levels, AtlasLayout.pack(widths, heights, levels));
        }
    }

    @Test
    @DisplayName("Ce qui ne tient pas dans une page de 4096 passe à la suivante")
    void leDebordementPasseALaPageSuivante() {
        int[] sides = new int[300];
        Arrays.fill(sides, 256);
        List<AtlasLayout.Page> pages = AtlasLayout.pack(sides, sides, 4);

        assertEquals(2, pages.size(), "tuiles de 288 : 14 × 14 = 196 par page de 4096");
        assertEquals(196, pages.get(0).tiles().size());
        assertEquals(4096, pages.get(0).width());
        assertSound(sides, sides, 4, pages);
    }

    @Test
    @DisplayName("La même liste donne la même disposition")
    void laDispositionEstDeterministe() {
        int[] widths = {7, 256, 33, 100, 1, 64};
        int[] heights = {9, 31, 256, 100, 1, 64};
        assertEquals(AtlasLayout.pack(widths, heights, 3), AtlasLayout.pack(widths, heights, 3));
    }

    @Test
    @DisplayName("Une page reste à peu près carrée : sa largeur double tant qu'elle est plus haute que large")
    void unePageResteAPeuPresCarree() {
        int[] sides = {256, 256, 256};
        AtlasLayout.Page page = AtlasLayout.pack(sides, sides, 4).get(0);

        assertEquals(1024, page.width(), "tuiles de 288 : 512 n'en prend qu'une par rangée, haute de 864");
        assertEquals(288, page.height(), "à 1024, les trois tiennent sur une rangée");
    }

    @Test
    @DisplayName("Seules les images de 1 à 256 par côté entrent dans un atlas")
    void seulesLesPetitesImagesEntrent() {
        assertTrue(AtlasLayout.fits(256, 256));
        assertTrue(AtlasLayout.fits(1, 1));
        assertFalse(AtlasLayout.fits(257, 16));
        assertFalse(AtlasLayout.fits(16, 257));
        assertFalse(AtlasLayout.fits(0, 16));
        assertThrows(IllegalArgumentException.class, () -> AtlasLayout.pack(new int[] {512}, new int[] {16}, 0));
        assertThrows(IllegalArgumentException.class, () -> AtlasLayout.pack(new int[] {16}, new int[] {16}, 5));
        assertThrows(IllegalArgumentException.class, () -> AtlasLayout.pack(new int[] {16}, new int[] {}, 0));
    }

    @Test
    @DisplayName("Une région ramène [0,1] exactement sur l'image, marge exclue")
    void uneRegionRameneLImageASaPlace() {
        AtlasLayout.Tile tile = new AtlasLayout.Tile(0, 32, 64, 64, 48, 30, 14, 16);
        AtlasLayout.Page page = new AtlasLayout.Page(256, 128, 4, List.of(tile));
        TextureRegion region = page.region(tile, PAGE);

        assertEquals(PAGE, region.binding());
        assertEquals(48.0f / 256, region.u(0.0f));
        assertEquals(78.0f / 256, region.u(1.0f));
        assertEquals(80.0f / 128, region.v(0.0f));
        assertEquals(94.0f / 128, region.v(1.0f));
        assertEquals(63.0f / 256, region.u(0.5f));
    }

    @Test
    @DisplayName("Une texture individuelle occupe toute sa texture liée")
    void uneTextureIndividuelleOccupeTout() {
        TextureRegion whole = TextureRegion.whole(PAGE);
        for (float u : new float[] {0.0f, 0.25f, 1.0f}) {
            assertEquals(u, whole.u(u));
            assertEquals(u, whole.v(u));
        }
    }

    @Test
    @DisplayName("Aucune image : aucune page")
    void aucuneImageAucunePage() {
        assertTrue(AtlasLayout.pack(new int[0], new int[0], 4).isEmpty());
    }
}
