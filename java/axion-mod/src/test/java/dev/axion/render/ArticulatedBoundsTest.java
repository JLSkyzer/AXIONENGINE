package dev.axion.render;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.axion.asset.GeometryTransfer;
import dev.axion.net.Interpolation;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ADR-157 D2 : la sphère d'une assembly aux corps articulés, chacun à sa pose, dans le repère de son corps
 * racine. Un cadre et une porte, deux meshes d'emprise {@code [0, 1]³} : le cadre à l'origine, la porte à
 * deux blocs en {@code x}, racine de son propre corps.
 */
class ArticulatedBoundsTest {

    private static final float EPS = 1e-5f;

    /** Le cadre et la porte : la porte, dessinée par son node 1, à deux blocs en {@code x} au repos. */
    private static GeometryTransfer geometrie() {
        byte[] bytes = TestGeometry.bytes(TestGeometry.white(0, 0), TestGeometry.white(0, 0));
        int vertices = 2 * 3;
        int draws = 16 + 2 * 48 + vertices * 48 + vertices * 4;
        // La translation du mat4x3 de la porte, à l'octet 16 + 9 × 4 de son RestDraw.
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putFloat(draws + 64 + 16 + 36, 2f);
        return GeometryTransfer.parse(bytes);
    }

    /** Une section {@code NODE} : le cadre à l'origine, la porte sous lui, à deux blocs en {@code x}. */
    private static BodySkeleton squelette() {
        ByteBuffer nodes = ByteBuffer.allocate(8 + 80 * 2).order(ByteOrder.LITTLE_ENDIAN);
        nodes.putInt(0, 2);
        for (int index = 0; index < 2; index++) {
            int at = 8 + 80 * index;
            nodes.putInt(at + 8, index - 1);
            nodes.putFloat(at + 12, index == 1 ? 2f : 0f);
            nodes.putFloat(at + 24 + 12, 1f);
            for (int c = 0; c < 3; c++) {
                nodes.putFloat(at + 40 + 4 * c, 1f);
            }
        }
        return BodySkeleton.of(nodes.array(), 0, new int[] {1});
    }

    private static List<Interpolation.BodyPose> porte(double[] offset, float[] rotation) {
        return List.of(new Interpolation.BodyPose(1, offset, rotation));
    }

    @Test
    @DisplayName("ADR-157 D2 : à la pose de repos, la sphère est celle de la boîte des deux meshes")
    void auRepos() {
        float[] sphere = ArticulatedBounds.of(geometrie(), squelette(), porte(new double[] {2, 0, 0}, new float[] {0, 0, 0, 1}));
        // x de 0 à 3, y et z de 0 à 1.
        assertArrayEquals(new float[] {1.5f, 0.5f, 0.5f, (float) Math.sqrt(1.5 * 1.5 + 0.25 + 0.25)}, sphere, EPS);
    }

    @Test
    @DisplayName("ADR-157 D2 : la porte tournée d'un demi-tour sur ses gonds passe de l'autre côté de son axe")
    void porteTournee() {
        // Un demi-tour autour de y, à sa place : ses coins (x, y, z) vont en (2 - x, y, -z).
        float[] sphere = ArticulatedBounds.of(geometrie(), squelette(), porte(new double[] {2, 0, 0}, new float[] {0, 1, 0, 0}));
        // x de 0 à 2, y de 0 à 1, z de -1 à 1.
        assertArrayEquals(new float[] {1f, 0.5f, 0f, 1.5f}, sphere, EPS);
    }

    @Test
    @DisplayName("ADR-157 D2 : la porte emportée loin du cadre élargit la sphère d'autant")
    void porteEmportee() {
        float[] sphere = ArticulatedBounds.of(geometrie(), squelette(), porte(new double[] {2, 0, 9}, new float[] {0, 0, 0, 1}));
        // x de 0 à 3, y de 0 à 1, z de 0 à 10.
        assertEquals(5f, sphere[2], EPS);
        assertEquals((float) Math.sqrt(1.5 * 1.5 + 0.25 + 25), sphere[3], EPS);
    }

    @Test
    @DisplayName("ADR-157 D2 : sans squelette, ou pour d'autres corps que les siens, aucune sphère")
    void sansSphere() {
        assertNull(ArticulatedBounds.of(geometrie(), null, porte(new double[] {2, 0, 0}, new float[] {0, 0, 0, 1})));
        assertNull(ArticulatedBounds.of(geometrie(), squelette(), List.of()), "un corps de moins");
        assertNull(ArticulatedBounds.of(geometrie(), squelette(), porte(new double[] {Double.NaN, 0, 0},
                new float[] {0, 0, 0, 1})), "une pose non finie");
    }

    @Test
    @DisplayName("ADR-157 D2 : l'emprise de chaque mesh se lit du transfert")
    void emprisesLues() {
        assertArrayEquals(new float[] {0, 0, 0, 1, 1, 1}, geometrie().meshBounds(1), EPS);
        assertNull(geometrie().meshBounds(2));
    }
}
