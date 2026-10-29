package dev.axion.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.axion.asset.GeometryTransfer;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-154, tranche 2b : chaque dessin prend le mesh de son niveau, la pose de son node reste. */
class LeveledDrawsTest {

    private static final float[] POSE = {1, 0, 0, 0, 1, 0, 0, 0, 1, 4, 5, 6};

    private static final List<GeometryTransfer.Draw> REPOS = List.of(
            new GeometryTransfer.Draw(0, 1, 7, POSE), new GeometryTransfer.Draw(1, 2, 0, POSE));

    @Test
    @DisplayName("Le mesh de chaque dessin est remplacé, node, drapeaux et pose gardés")
    void meshesRemplaces() {
        List<GeometryTransfer.Draw> loin = LeveledDraws.of(REPOS, 6, new int[] {4, 5});
        assertEquals(4, loin.get(0).mesh());
        assertEquals(5, loin.get(1).mesh());
        assertEquals(1, loin.get(0).node());
        assertEquals(7, loin.get(0).nodeFlags());
        assertSame(POSE, loin.get(1).model());
    }

    @Test
    @DisplayName("Au niveau 0, ou en désaccord avec le natif, la liste au repos reste")
    void reposGarde() {
        assertSame(REPOS, LeveledDraws.of(REPOS, 6, new int[] {0, 1}), "niveau 0 : aucune copie");
        assertSame(REPOS, LeveledDraws.of(REPOS, 6, new int[] {4}), "pas un mesh par dessin");
        assertSame(REPOS, LeveledDraws.of(REPOS, 6, new int[] {4, 6}), "mesh hors de la géométrie");
        assertSame(REPOS, LeveledDraws.of(REPOS, 6, new int[] {-1, 5}), "mesh négatif");
    }

    @Test
    @DisplayName("ADR-155 : une assembly animée prend la matrice de chaque dessin, au mesh de son niveau")
    void matricesAnimees() {
        float[] a = {0, 1, 0, -1, 0, 0, 0, 0, 1, 1, 2, 3};
        float[] b = {1, 0, 0, 0, 1, 0, 0, 0, 1, 7, 8, 9};
        List<GeometryTransfer.Draw> anime = LeveledDraws.of(REPOS, 6, new int[] {0, 5}, new float[][] {a, b});
        assertSame(a, anime.get(0).model());
        assertSame(b, anime.get(1).model());
        assertEquals(0, anime.get(0).mesh(), "au niveau 0, le mesh du repos");
        assertEquals(5, anime.get(1).mesh());
        assertEquals(1, anime.get(0).node());

        // Des matrices qui ne sont pas une de douze flottants par dessin sont ignorées : le repos reste.
        assertSame(REPOS, LeveledDraws.of(REPOS, 6, new int[] {0, 1}, new float[][] {a}));
        assertSame(REPOS, LeveledDraws.of(REPOS, 6, new int[] {0, 1}, new float[][] {a, new float[11]}));
        assertSame(REPOS, LeveledDraws.of(REPOS, 6, new int[] {0, 1}, null));
    }
}
