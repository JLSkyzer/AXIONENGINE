package dev.axion.forge.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.axion.debug.DebugGeometry;
import dev.axion.debug.DebugOverlays;
import dev.axion.net.Interpolation;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ADR-121 — dessin de l'overlay {@code colliders} : sommets de lignes, directions, couleurs ; T-551 —
 * éteints, les overlays ne coûtent qu'un test de drapeau (R-800, R-2280).
 */
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

    @Test
    @DisplayName("T-551 : éteints, les overlays ne demandent rien au natif et ne gardent aucune géométrie")
    void eteintsSansNatif() {
        DebugOverlays overlays = new DebugOverlays();
        DebugOverlayRenderer renderer = new DebugOverlayRenderer(overlays);
        assertEquals(0L, overlays.mask());
        // Sans runtime ni client : un pas qui toucherait l'un ou l'autre échouerait ici.
        renderer.tick(null, null);
        assertFalse(renderer.hasNativeLoader(), "un lecteur natif a été créé, overlays éteints");
        assertTrue(renderer.geometry().ranges().isEmpty(), "une géométrie est restée, overlays éteints");
    }

    @Test
    @DisplayName("31.4 : network se dessine en Java ; son bit ne part pas au natif, qui refuse un overlay inconnu")
    void networkNePartPasAuNatif() {
        DebugOverlays overlays = new DebugOverlays();
        overlays.set(DebugOverlays.Overlay.NETWORK, true);
        assertTrue(overlays.mask() != 0L, "allumé, network ouvre la passe DEBUG");
        assertEquals(0L, overlays.nativeMask(), "le masque du natif ne porte pas network");
        assertEquals(32, DebugOverlays.Overlay.NETWORK.rank(), "son rang au §31.4");
        DebugOverlayRenderer renderer = new DebugOverlayRenderer(overlays);
        renderer.tick(null, null);
        assertFalse(renderer.hasNativeLoader(), "network seul ne demande rien au natif");
        overlays.set(DebugOverlays.Overlay.AABB, true);
        assertEquals(DebugOverlays.Overlay.AABB.bit(), overlays.nativeMask(), "les overlays natifs y restent");
    }

    @Test
    @DisplayName("31.4 : network trace l'emprise à la pose dessinée, au dernier état reçu, et le trait qui les relie")
    void networkTraceLesDeuxEmprisesEtLeTrait() {
        float[] min = {-0.5f, -0.5f, -0.5f};
        float[] max = {0.5f, 0.5f, 0.5f};
        float[] droit = {0f, 0f, 0f, 1f};
        Interpolation.Pose dessinee = new Interpolation.Pose(10, 64, 10, droit, min, max, Interpolation.Mode.INTERPOLATED);
        Interpolation.Pose recue = new Interpolation.Pose(12, 64, 10, droit, min, max, Interpolation.Mode.HELD);
        float[][] segments = DebugOverlayRenderer.networkSegments(dessinee, recue, 0, 60, 0);
        int un = DebugGeometry.FLOATS_PER_SEGMENT;
        assertEquals(13 * un, segments[0].length, "douze arêtes et le trait");
        assertEquals(12 * un, segments[1].length, "douze arêtes");
        assertArrayEquals(new float[] {10, 4, 10, 12, 4, 10},
                java.util.Arrays.copyOfRange(segments[0], 12 * un, 13 * un), 1e-5f, "le trait, relatif à la caméra");
        assertArrayEquals(new float[] {9.5f, 3.5f, 9.5f, 10.5f, 3.5f, 9.5f},
                java.util.Arrays.copyOfRange(segments[0], 0, un), 1e-5f, "une arête de l'emprise dessinée");
        assertArrayEquals(new float[] {11.5f, 3.5f, 9.5f, 12.5f, 3.5f, 9.5f},
                java.util.Arrays.copyOfRange(segments[1], 0, un), 1e-5f, "une arête de l'emprise reçue");
    }

    @Test
    @DisplayName("A1 d'ADR-121 : chaque overlay a sa légende et sa couleur ; colliders garde celle du corps")
    void chaqueOverlayASaCouleurEtSaLegende() {
        Set<Integer> couleurs = new HashSet<>();
        for (DebugOverlays.Overlay overlay : DebugOverlays.Overlay.values()) {
            assertFalse(DebugOverlayRenderer.legendOf(overlay).isBlank(), overlay.commandName());
            // colliders se colore par corps ; bodies et nodes, axe par axe.
            if (overlay != DebugOverlays.Overlay.COLLIDERS && overlay != DebugOverlays.Overlay.BODIES
                    && overlay != DebugOverlays.Overlay.NODES) {
                couleurs.add(DebugOverlayRenderer.colorOf(overlay, 0));
            }
        }
        assertEquals(12, couleurs.size(), "douze overlays sans corps ni axes à colorer, douze couleurs");
        assertEquals(
                DebugOverlayRenderer.STATIC,
                DebugOverlayRenderer.colorOf(DebugOverlays.Overlay.COLLIDERS, DebugGeometry.BODY_STATIC));
    }

    @Test
    @DisplayName("ADR-151 D7 : joints et limits prennent la couleur de l'état du joint, rompu d'abord")
    void lesJointsOntLaCouleurDeLeurEtat() {
        DebugOverlays.Overlay joints = DebugOverlays.Overlay.JOINTS;
        DebugOverlays.Overlay limits = DebugOverlays.Overlay.LIMITS;
        assertEquals(DebugOverlayRenderer.JOINT_COLOR, DebugOverlayRenderer.colorOf(joints, 0));
        assertEquals(DebugOverlayRenderer.LIMIT_COLOR, DebugOverlayRenderer.colorOf(limits, 0));
        assertEquals(DebugOverlayRenderer.JOINT_JAMMED_COLOR,
                DebugOverlayRenderer.colorOf(joints, DebugGeometry.JOINT_JAMMED));
        assertEquals(DebugOverlayRenderer.JOINT_JAMMED_COLOR,
                DebugOverlayRenderer.colorOf(limits, DebugGeometry.JOINT_JAMMED));
        assertEquals(DebugOverlayRenderer.JOINT_BROKEN_COLOR,
                DebugOverlayRenderer.colorOf(joints, DebugGeometry.JOINT_BROKEN));
        assertEquals(DebugOverlayRenderer.JOINT_BROKEN_COLOR,
                DebugOverlayRenderer.colorOf(joints, DebugGeometry.JOINT_BROKEN | DebugGeometry.JOINT_JAMMED),
                "rompu l'emporte sur grippé");
    }

    @Test
    @DisplayName("A1 d'ADR-121 : les trois axes de bodies, rouge x, vert y, bleu z")
    void lesAxesDeBodiesOntChacunLeurCouleur() {
        Sommets out = new Sommets();
        float[] axes = {0, 0, 0, 0.5f, 0, 0, 0, 0, 0, 0, 0.5f, 0, 0, 0, 0, 0, 0, 0.5f};
        int emis = DebugOverlayRenderer.emitRange(
                out,
                new Matrix4f(),
                new Matrix3f(),
                new Vector3f(),
                new DebugGeometry.Range(1, 1, DebugOverlays.Overlay.BODIES.rank(), DebugGeometry.FRAME_BODY, 0, axes));
        assertEquals(3, emis);
        for (int axe = 0; axe < 3; axe++) {
            int rgb = DebugOverlayRenderer.AXIS_COLORS[axe];
            int[] couleur = out.couleurs.get(2 * axe);
            assertEquals((rgb >> 16) & 0xFF, couleur[0], 1, "axe " + axe);
            assertEquals((rgb >> 8) & 0xFF, couleur[1], 1, "axe " + axe);
            assertEquals(rgb & 0xFF, couleur[2], 1, "axe " + axe);
        }
    }

    @Test
    @DisplayName("A1 d'ADR-121 : une plage d'un overlay que ce client ne connaît pas est sautée")
    void unRangInconnuEstSaute() {
        Sommets out = new Sommets();
        int emis = DebugOverlayRenderer.emitRange(
                out,
                new Matrix4f(),
                new Matrix3f(),
                new Vector3f(),
                new DebugGeometry.Range(1, 1, 7, DebugGeometry.FRAME_BODY, 0, new float[] {0, 0, 0, 1, 0, 0}));
        assertEquals(0, emis);
        assertEquals(0, out.termines);
    }

    private static int couleur(int flags) {
        return DebugOverlayRenderer.colorOf(flags);
    }

    @Test
    @DisplayName("ADR-154 : lod colore par niveau, borné ; culling sépare retenue et écartée")
    void couleursDuCulling() {
        assertEquals(DebugOverlayRenderer.LOD_COLORS[0], DebugOverlayRenderer.lodColor(0));
        assertEquals(DebugOverlayRenderer.LOD_COLORS[2], DebugOverlayRenderer.lodColor(2));
        assertEquals(DebugOverlayRenderer.LOD_COLORS[3], DebugOverlayRenderer.lodColor(7), "au-delà, la dernière");
        assertEquals(DebugOverlayRenderer.LOD_COLORS[0], DebugOverlayRenderer.lodColor(-1));
        assertTrue(DebugOverlayRenderer.CULLING_KEPT != DebugOverlayRenderer.CULLING_REJECTED);
    }

    @Test
    @DisplayName("ADR-154 : l'emprise d'une assembly, douze arêtes entre ses deux coins")
    void aretesDeLaBoite() {
        float[] aretes = DebugOverlayRenderer.boxEdges(-1, 0, 2, 1, 3, 4);
        assertEquals(12 * 6, aretes.length);
        for (int i = 0; i < aretes.length; i += 3) {
            assertTrue(aretes[i] == -1 || aretes[i] == 1, "x sur un coin");
            assertTrue(aretes[i + 1] == 0 || aretes[i + 1] == 3, "y sur un coin");
            assertTrue(aretes[i + 2] == 2 || aretes[i + 2] == 4, "z sur un coin");
        }
        for (int i = 0; i < aretes.length; i += 6) {
            int differents = (aretes[i] != aretes[i + 3] ? 1 : 0) + (aretes[i + 1] != aretes[i + 4] ? 1 : 0)
                    + (aretes[i + 2] != aretes[i + 5] ? 1 : 0);
            assertEquals(1, differents, "une arête suit un seul axe");
        }
    }
}
