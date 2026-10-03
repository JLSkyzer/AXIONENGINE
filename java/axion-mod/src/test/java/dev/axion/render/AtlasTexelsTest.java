package dev.axion.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Texels des tuiles d'atlas : image, marge, recopie (ADR-122 T-c). */
class AtlasTexelsTest {

    /** Une image en mémoire. */
    private static final class ArrayPixels implements AtlasTexels.Pixels {
        private final int width;
        private final int height;
        private final int[] texels;

        ArrayPixels(int width, int height) {
            this.width = width;
            this.height = height;
            this.texels = new int[width * height];
        }

        static ArrayPixels random(int width, int height, Random random) {
            ArrayPixels image = new ArrayPixels(width, height);
            for (int at = 0; at < image.texels.length; at++) {
                image.texels[at] = random.nextInt();
            }
            return image;
        }

        @Override
        public int width() {
            return width;
        }

        @Override
        public int height() {
            return height;
        }

        @Override
        public int get(int x, int y) {
            if (x < 0 || y < 0 || x >= width || y >= height) {
                throw new IndexOutOfBoundsException("(" + x + ", " + y + ") hors de " + width + "×" + height);
            }
            return texels[y * width + x];
        }

        @Override
        public void set(int x, int y, int texel) {
            if (x < 0 || y < 0 || x >= width || y >= height) {
                throw new IndexOutOfBoundsException("(" + x + ", " + y + ") hors de " + width + "×" + height);
            }
            texels[y * width + x] = texel;
        }

        /**
         * {@return le niveau suivant : chaque texel, la moyenne par canal d'un bloc de 2×2} — un
         * filtre local, comme celui de {@code MipmapGenerator}, dont la propriété vérifiée ne
         * dépend pas.
         */
        ArrayPixels reduced() {
            ArrayPixels out = new ArrayPixels(width / 2, height / 2);
            for (int y = 0; y < out.height; y++) {
                for (int x = 0; x < out.width; x++) {
                    int texel = 0;
                    for (int shift = 0; shift < 32; shift += 8) {
                        int sum = (get(2 * x, 2 * y) >>> shift & 0xFF) + (get(2 * x + 1, 2 * y) >>> shift & 0xFF)
                                + (get(2 * x, 2 * y + 1) >>> shift & 0xFF) + (get(2 * x + 1, 2 * y + 1) >>> shift & 0xFF);
                        texel |= (sum / 4) << shift;
                    }
                    out.set(x, y, texel);
                }
            }
            return out;
        }
    }

    @Test
    @DisplayName("Hors de l'image, la répétition enroule et l'écrêtage ramène au bord")
    void laSourceEnrouleOuEcrete() {
        assertEquals(0, AtlasTexels.source(0, 4, false));
        assertEquals(3, AtlasTexels.source(-1, 4, false));
        assertEquals(0, AtlasTexels.source(4, 4, false));
        assertEquals(1, AtlasTexels.source(-7, 4, false));
        assertEquals(0, AtlasTexels.source(-1, 4, true));
        assertEquals(3, AtlasTexels.source(4, 4, true));
        assertEquals(2, AtlasTexels.source(2, 4, true));
    }

    @Test
    @DisplayName("Une tuile en écrêtage : l'image à la marge près, son bord recopié tout autour")
    void uneTuileEcreteeRecopieSonBord() {
        ArrayPixels image = ArrayPixels.random(3, 2, new Random(1));
        ArrayPixels tile = new ArrayPixels(8, 8);
        AtlasTexels.fill(image, tile, 2, true);

        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int expected = image.get(Math.max(0, Math.min(2, x - 2)), Math.max(0, Math.min(1, y - 2)));
                assertEquals(expected, tile.get(x, y), "texel (" + x + ", " + y + ")");
            }
        }
    }

    @Test
    @DisplayName("En répétition, chaque niveau de mipmap de la tuile, marge comprise, est celui de la texture seule")
    void enRepetitionLesMipmapsDeLaTuileSontCeuxDeLaTextureSeule() {
        Random random = new Random(122);
        for (int levels = 0; levels <= 4; levels++) {
            int margin = AtlasLayout.margin(levels);
            int width = margin * (1 + random.nextInt(4));
            int height = margin * (1 + random.nextInt(4));
            ArrayPixels alone = ArrayPixels.random(width, height, random);
            AtlasLayout.Tile tile = AtlasLayout.pack(new int[] {width}, new int[] {height}, levels).get(0).tiles().get(0);
            ArrayPixels slot = new ArrayPixels(tile.slotWidth(), tile.slotHeight());
            AtlasTexels.fill(alone, slot, tile.margin(), false);

            for (int level = 0; level <= levels; level++) {
                for (int y = 0; y < slot.height(); y++) {
                    for (int x = 0; x < slot.width(); x++) {
                        int expected = alone.get(
                                Math.floorMod(x - (margin >> level), alone.width()),
                                Math.floorMod(y - (margin >> level), alone.height()));
                        assertEquals(expected, slot.get(x, y),
                                "niveau " + level + " sur " + levels + ", texel (" + x + ", " + y + ")");
                    }
                }
                if (level < levels) {
                    alone = alone.reduced();
                    slot = slot.reduced();
                }
            }
        }
    }

    @Test
    @DisplayName("Une recopie pose l'image à sa place, et refuse de déborder")
    void uneRecopiePoseLImageASaPlace() {
        ArrayPixels from = ArrayPixels.random(2, 3, new Random(2));
        ArrayPixels to = new ArrayPixels(6, 5);
        AtlasTexels.copy(from, to, 4, 2);

        for (int y = 0; y < 5; y++) {
            for (int x = 0; x < 6; x++) {
                boolean inside = x >= 4 && y >= 2;
                assertEquals(inside ? from.get(x - 4, y - 2) : 0, to.get(x, y), "texel (" + x + ", " + y + ")");
            }
        }
        assertThrows(IllegalArgumentException.class, () -> AtlasTexels.copy(from, to, 5, 0));
        assertThrows(IllegalArgumentException.class, () -> AtlasTexels.copy(from, to, 0, 3));
        assertThrows(IllegalArgumentException.class, () -> AtlasTexels.copy(from, to, -1, 0));
    }
}
