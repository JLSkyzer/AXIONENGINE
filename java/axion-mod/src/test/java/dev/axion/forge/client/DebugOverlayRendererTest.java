package dev.axion.forge.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.axion.debug.DebugGeometry;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-121 — dessin de l'overlay {@code colliders} : sommets de lignes, directions, couleurs. */
class DebugOverlayRendererTest {

    private static final double EPS = 1e-5;

    /**
     * Sommets reçus. Le format des lignes est position, couleur, normale : tout autre élément
     * fait échouer le test.
     */
    private static final class Sommets implements VertexConsumer {
        final List<double[]> positions = new ArrayList<>();
        final List<int[]> couleurs = new ArrayList<>();
        final List<float[]> normales = new ArrayList<>();
        int termines;

        @Override
        public VertexConsumer vertex(double x, double y, double z) {
            positions.add(new double[] {x, y, z});
            return this;
        }

        @Override
        public VertexConsumer color(int r, int g, int b, int a) {
            couleurs.add(new int[] {r, g, b, a});
            return this;
        }

        @Override
        public VertexConsumer uv(float u, float v) {
            throw new AssertionError("une ligne n'a pas de coordonnées de texture");
        }

        @Override
        public VertexConsumer overlayCoords(int u, int v) {
            throw new AssertionError("une ligne n'a pas d'overlay");
        }

        @Override
        public VertexConsumer uv2(int u, int v) {
            throw new AssertionError("une ligne n'a pas de lumière");
        }

        @Override
        public VertexConsumer normal(float x, float y, float z) {
            normales.add(new float[] {x, y, z});
            return this;
        }

        @Override
        public void endVertex() {
            termines++;
        }

        @Override
        public void defaultColor(int r, int g, int b, int a) {
            throw new AssertionError("pas de couleur par défaut");
        }

        @Override
        public void unsetDefaultColor() {
            throw new AssertionError("pas de couleur par défaut");
        }
    }

    private static void assertVecteur(double[] attendu, double[] obtenu) {
        for (int i = 0; i < 3; i++) {
            assertEquals(attendu[i], obtenu[i], EPS, "composante " + i);
        }
    }

    private static void assertVecteur(float[] attendu, float[] obtenu) {
        for (int i = 0; i < 3; i++) {
            assertEquals(attendu[i], obtenu[i], EPS, "composante " + i);
        }
    }

    @Test
    @DisplayName("ADR-121 : chaque segment donne deux sommets, posés par la matrice, sa direction en normale")
    void deuxSommetsParSegment() {
        Sommets out = new Sommets();
        float[] segments = {
            0, 0, 0, 2, 0, 0,
            1, 1, 1, 1, 4, 1,
        };
        int emis = DebugOverlayRenderer.emit(
                out,
                new Matrix4f().translation(10, 20, 30),
                new Matrix3f(),
                new Vector3f(),
                segments,
                DebugOverlayRenderer.AWAKE);

        assertEquals(2, emis);
        assertEquals(4, out.termines);
        assertVecteur(new double[] {10, 20, 30}, out.positions.get(0));
        assertVecteur(new double[] {12, 20, 30}, out.positions.get(1));
        assertVecteur(new double[] {11, 21, 31}, out.positions.get(2));
        assertVecteur(new double[] {11, 24, 31}, out.positions.get(3));
        // Direction unitaire, la même aux deux extrémités : la longueur ne compte pas.
        assertVecteur(new float[] {1, 0, 0}, out.normales.get(0));
        assertVecteur(new float[] {1, 0, 0}, out.normales.get(1));
        assertVecteur(new float[] {0, 1, 0}, out.normales.get(2));
        assertVecteur(new float[] {0, 1, 0}, out.normales.get(3));
    }

    @Test
    @DisplayName("ADR-121 : la direction tourne avec le corps")
    void directionTourneAvecLeCorps() {
        Sommets out = new Sommets();
        float quart = (float) (Math.PI / 2);
        DebugOverlayRenderer.emit(
                out,
                new Matrix4f().rotationZ(quart),
                new Matrix3f().rotationZ(quart),
                new Vector3f(),
                new float[] {0, 0, 0, 2, 0, 0},
                DebugOverlayRenderer.AWAKE);

        assertVecteur(new double[] {0, 2, 0}, out.positions.get(1));
        assertVecteur(new float[] {0, 1, 0}, out.normales.get(0));
    }

    @Test
    @DisplayName("ADR-121 : un segment sans longueur est sauté, aucune normale n'est NaN")
    void segmentSansLongueur() {
        Sommets out = new Sommets();
        float[] segments = {
            1, 1, 1, 1, 1, 1,
            0, 0, 0, 0, 0, 3,
        };
        int emis = DebugOverlayRenderer.emit(
                out, new Matrix4f(), new Matrix3f(), new Vector3f(), segments, DebugOverlayRenderer.AWAKE);

        assertEquals(1, emis);
        assertEquals(2, out.termines);
        for (float[] normale : out.normales) {
            for (float composante : normale) {
                assertFalse(Float.isNaN(composante));
            }
        }
        assertVecteur(new float[] {0, 0, 1}, out.normales.get(0));
    }

    @Test
    @DisplayName("ADR-121 : les sommets portent la couleur du corps, opaque")
    void couleurDuCorps() {
        Sommets out = new Sommets();
        DebugOverlayRenderer.emit(
                out,
                new Matrix4f(),
                new Matrix3f(),
                new Vector3f(),
                new float[] {0, 0, 0, 1, 0, 0},
                DebugOverlayRenderer.STATIC);

        int rgb = DebugOverlayRenderer.STATIC;
        assertEquals(2, out.couleurs.size());
        for (int[] couleur : out.couleurs) {
            assertEquals((rgb >> 16) & 0xFF, couleur[0], 1);
            assertEquals((rgb >> 8) & 0xFF, couleur[1], 1);
            assertEquals(rgb & 0xFF, couleur[2], 1);
            assertEquals(255, couleur[3]);
        }
    }

    @Test
    @DisplayName("ADR-121 : la couleur dit le genre du corps d'abord, puis son sommeil")
    void couleurSelonLeCorps() {
        assertEquals(DebugOverlayRenderer.AWAKE, couleur(0));
        assertEquals(DebugOverlayRenderer.SLEEPING, couleur(DebugGeometry.BODY_SLEEPING));
        assertEquals(DebugOverlayRenderer.STATIC, couleur(DebugGeometry.BODY_STATIC));
        assertEquals(
                DebugOverlayRenderer.STATIC,
                couleur(DebugGeometry.BODY_STATIC | DebugGeometry.BODY_SLEEPING));
        assertEquals(
                DebugOverlayRenderer.KINEMATIC,
                couleur(DebugGeometry.BODY_KINEMATIC | DebugGeometry.BODY_SLEEPING));

        Set<Integer> distinctes = new HashSet<>(List.of(
                DebugOverlayRenderer.AWAKE,
                DebugOverlayRenderer.SLEEPING,
                DebugOverlayRenderer.STATIC,
                DebugOverlayRenderer.KINEMATIC));
        assertEquals(4, distinctes.size(), "quatre genres, quatre couleurs");
    }

    private static int couleur(int flags) {
        return DebugOverlayRenderer.colorOf(new DebugGeometry.Body(1, 1, flags, new float[0]));
    }
}
