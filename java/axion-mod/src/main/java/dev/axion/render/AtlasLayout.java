package dev.axion.render;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Disposition des petites textures d'un asset dans les pages de son atlas (C-26, ADR-122 T-c).
 *
 * <p>Chaque image reçoit une <b>tuile</b> : l'image entourée d'une marge de {@code 2^L} texels,
 * {@code L} étant le nombre de niveaux de mipmaps. La marge reproduit ce qu'échantillonnerait la
 * texture seule au-delà de son bord — texels enroulés en répétition, bord recopié en écrêtage
 * ({@link AtlasTexels}) —, si bien qu'un sommet à {@code u = 1}, un filtrage linéaire ou un
 * niveau de mipmap éloigné ne lisent jamais la tuile voisine : au niveau {@code L}, la marge vaut
 * encore un texel.
 *
 * <p>Les tuiles sont alignées sur {@code 2^L} et de côtés multiples de {@code 2^L} : au niveau
 * {@code k}, chacune occupe exactement les texels {@code (x >> k, y >> k)}, ses mipmaps se
 * calculent à part, comme ceux de la texture seule, et se recopient tels quels.
 *
 * <p>Rangement en rangées, des tuiles les plus hautes aux plus basses : la largeur d'une page est
 * la plus petite puissance de deux qui la garde à peu près carrée, sa hauteur un multiple de
 * {@code 2^L}, ses deux côtés ≤ 4096 (R-570). Ce qui ne tient pas dans une page de 4096 passe à
 * la suivante. Déterministe : la même liste donne la même disposition.
 */
public final class AtlasLayout {

    /** Côté maximal d'une image rangée dans un atlas (fiche C-26). */
    public static final int MAX_IMAGE_SIDE = 256;

    /** Côté maximal d'une page (R-570). */
    public static final int MAX_PAGE_SIDE = TextureRules.MAX_SIDE;

    private AtlasLayout() {}

    /**
     * La place d'une image dans sa page.
     *
     * @param index rang de l'image dans la liste rangée
     * @param x bord gauche de la tuile, marge comprise
     * @param y bord haut de la tuile, marge comprise
     * @param slotWidth largeur de la tuile, marge comprise
     * @param slotHeight hauteur de la tuile, marge comprise
     * @param width largeur de l'image
     * @param height hauteur de l'image
     * @param margin marge, en texels, de chaque côté de l'image
     */
    public record Tile(int index, int x, int y, int slotWidth, int slotHeight, int width, int height, int margin) {}

    /**
     * Une page de l'atlas.
     *
     * @param width largeur, puissance de deux
     * @param height hauteur, multiple de {@code 2^levels}
     * @param levels niveaux de mipmaps, base exclue
     * @param tiles tuiles de la page
     */
    public record Page(int width, int height, int levels, List<Tile> tiles) {

        /**
         * {@return où un mesh lit l'image d'une tuile, dans la page liée}
         *
         * @param tile tuile de cette page
         * @param binding la page, telle que ses types de rendu la lient
         */
        public TextureRegion region(Tile tile, TextureBinding binding) {
            return new TextureRegion(
                    binding,
                    (float) (tile.x() + tile.margin()) / width,
                    (float) (tile.y() + tile.margin()) / height,
                    (float) tile.width() / width,
                    (float) tile.height() / height);
        }
    }

    /**
     * {@return vrai si une image de ces dimensions peut entrer dans un atlas}
     *
     * @param width largeur de l'image
     * @param height hauteur de l'image
     */
    public static boolean fits(int width, int height) {
        return width >= 1 && height >= 1 && width <= MAX_IMAGE_SIDE && height <= MAX_IMAGE_SIDE;
    }

    /**
     * {@return la marge d'une tuile, en texels : {@code 2^levels}}
     *
     * @param levels niveaux de mipmaps, base exclue
     */
    public static int margin(int levels) {
        return 1 << levels;
    }

    /**
     * {@return les pages qui rangent ces images}
     *
     * @param widths largeur de chaque image
     * @param heights hauteur de chaque image
     * @param levels niveaux de mipmaps, base exclue, de 0 à 4
     * @throws IllegalArgumentException si une image n'entre pas dans un atlas, ou si les niveaux
     *     sont hors de {@code [0, 4]}
     */
    public static List<Page> pack(int[] widths, int[] heights, int levels) {
        if (widths.length != heights.length) {
            throw new IllegalArgumentException(widths.length + " largeurs pour " + heights.length + " hauteurs");
        }
        if (levels < 0 || levels > 4) {
            throw new IllegalArgumentException("niveaux de mipmaps hors de [0, 4] : " + levels);
        }
        int margin = margin(levels);
        List<Tile> slots = new ArrayList<>(widths.length);
        for (int index = 0; index < widths.length; index++) {
            if (!fits(widths[index], heights[index])) {
                throw new IllegalArgumentException("image de " + widths[index] + "×" + heights[index]
                        + " : au-delà de " + MAX_IMAGE_SIDE + " par côté, elle n'entre pas dans un atlas");
            }
            slots.add(new Tile(index, 0, 0,
                    slot(widths[index], margin), slot(heights[index], margin),
                    widths[index], heights[index], margin));
        }
        slots.sort(Comparator.comparingInt(Tile::slotHeight).reversed()
                .thenComparing(Comparator.comparingInt(Tile::slotWidth).reversed())
                .thenComparingInt(Tile::index));

        List<Page> pages = new ArrayList<>();
        List<Tile> remaining = slots;
        while (!remaining.isEmpty()) {
            int width = initialWidth(remaining);
            Shelves shelves = shelve(remaining, width);
            while (width < MAX_PAGE_SIDE && (!shelves.deferred.isEmpty() || shelves.height > width)) {
                width *= 2;
                shelves = shelve(remaining, width);
            }
            pages.add(new Page(width, shelves.height, levels, List.copyOf(shelves.placed)));
            remaining = shelves.deferred;
        }
        return List.copyOf(pages);
    }

    /** {@return un côté de tuile : l'image et ses deux marges, arrondis au multiple de la marge} */
    private static int slot(int side, int margin) {
        int raw = side + 2 * margin;
        return (raw + margin - 1) / margin * margin;
    }

    /** {@return la plus petite puissance de deux qui contient la plus large tuile et l'aire totale} */
    private static int initialWidth(List<Tile> tiles) {
        long area = 0;
        int widest = 1;
        for (Tile tile : tiles) {
            area += (long) tile.slotWidth() * tile.slotHeight();
            widest = Math.max(widest, tile.slotWidth());
        }
        int side = (int) Math.ceil(Math.sqrt((double) area));
        int width = Integer.highestOneBit(Math.max(widest, side) - 1) << 1;
        return Math.min(MAX_PAGE_SIDE, Math.max(1, width));
    }

    /**
     * Range des tuiles en rangées dans une page de cette largeur, sans dépasser la hauteur
     * maximale : ce qui n'y tient pas est reporté.
     */
    private static Shelves shelve(List<Tile> tiles, int width) {
        List<Tile> placed = new ArrayList<>();
        List<Tile> deferred = new ArrayList<>();
        int x = 0;
        int y = 0;
        int shelf = 0;
        for (Tile tile : tiles) {
            if (x + tile.slotWidth() > width) {
                int next = y + shelf;
                if (next + tile.slotHeight() > MAX_PAGE_SIDE) {
                    deferred.add(tile);
                    continue;
                }
                y = next;
                x = 0;
                shelf = 0;
            }
            placed.add(new Tile(tile.index(), x, y, tile.slotWidth(), tile.slotHeight(),
                    tile.width(), tile.height(), tile.margin()));
            x += tile.slotWidth();
            shelf = Math.max(shelf, tile.slotHeight());
        }
        return new Shelves(placed, deferred, y + shelf);
    }

    /** Résultat d'un rangement : tuiles posées, reportées, hauteur occupée. */
    private record Shelves(List<Tile> placed, List<Tile> deferred, int height) {}
}
