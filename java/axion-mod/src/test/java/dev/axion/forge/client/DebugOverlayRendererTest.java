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
        assertEquals(15, couleurs.size(), "quinze couleurs distinctes, seats (ADR-177) et supports (ADR-178) comprises");
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
                new DebugGeometry.Range(1, 1, 14, DebugGeometry.FRAME_BODY, 0, new float[] {0, 0, 0, 1, 0, 0}));
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

    @Test
    @org.junit.jupiter.api.DisplayName("sockets (ADR-171) : les trois axes du repère d'un socket, tournés comme lui, relatifs à la caméra")
    void lesAxesDUnSocket() {
        // Un quart de tour autour de y : x du socket vise -z du monde.
        float s = (float) Math.sqrt(0.5);
        dev.axion.seat.SeatPlacement.Pose socket = new dev.axion.seat.SeatPlacement.Pose(10.0, 64.0, -3.0,
                new float[] {0f, s, 0f, s});
        float[][] axes = DebugOverlayRenderer.socketAxes(socket, 10.0, 60.0, -3.0);
        float l = DebugOverlayRenderer.SOCKET_AXIS;
        org.junit.jupiter.api.Assertions.assertArrayEquals(new float[] {0f, 4f, 0f, 0f, 4f, -l}, axes[0], 1e-5f);
        org.junit.jupiter.api.Assertions.assertArrayEquals(new float[] {0f, 4f, 0f, 0f, 4f + l, 0f}, axes[1], 1e-5f);
        org.junit.jupiter.api.Assertions.assertArrayEquals(new float[] {0f, 4f, 0f, l, 4f, 0f}, axes[2], 1e-5f);
    }

    @Test
    @DisplayName("ADR-178 : l'overlay supports relie la place du joueur à l'origine du corps, et la marque d'une croix")
    void leSupport() {
        float[] segments = DebugOverlayRenderer.supportSegments(new double[] {10, 65, -3}, new double[] {10, 64, -3}, 10, 60, -3);
        assertEquals(4 * DebugGeometry.FLOATS_PER_SEGMENT, segments.length, "le lien et la croix");
        org.junit.jupiter.api.Assertions.assertArrayEquals(new float[] {0f, 5f, 0f, 0f, 4f, 0f},
                java.util.Arrays.copyOfRange(segments, 0, 6), 1e-5f, "de la place à l'origine");
        assertEquals(DebugOverlayRenderer.SUPPORT_COLOR, DebugOverlayRenderer.colorOf(DebugOverlays.Overlay.SUPPORTS, 0));
    }

    @Test
    @DisplayName("ADR-177 D1 : l'overlay seats dessine la boîte mise à l'échelle autour du point du siège, la boîte de collision qui ne change pas, et l'œil")
    void lesSieges() {
        dev.axion.seat.SeatPlacement.Pose siege = new dev.axion.seat.SeatPlacement.Pose(10.0, 64.0, -3.0,
                new float[] {0f, 0f, 0f, 1f});
        // Assis, à l'échelle 0,5 : les pieds se rapprochent du siège avec le reste du corps.
        float[][] petit = DebugOverlayRenderer.seatSegments(siege, new dev.axion.definition.Seating.Occupant(
                dev.axion.definition.Seating.Pose.SIT, 0.5f, null), 10.0, 60.0, -3.0);
        assertEquals(12 * DebugGeometry.FLOATS_PER_SEGMENT, petit[0].length, "douze arêtes à l'échelle");
        assertEquals(12 * DebugGeometry.FLOATS_PER_SEGMENT, petit[1].length, "douze arêtes de collision");
        assertEquals(3 * DebugGeometry.FLOATS_PER_SEGMENT, petit[2].length, "trois traits à l'œil");
        assertEquals(4f - 0.175f, min(petit[0], 1), 1e-5f, "pieds : 4 au-dessus de la caméra, moins 0,35 × 0,5");
        assertEquals(4f - 0.175f + 0.9f, max(petit[0], 1), 1e-5f, "tête : 1,8 × 0,5 plus haut");
        assertEquals(0.15f, max(petit[0], 0), 1e-5f, "demi-largeur 0,3 × 0,5");
        assertEquals(4f - 0.35f, min(petit[1], 1), 1e-5f, "collision : pieds à 0,35 sous le siège, sans échelle");
        assertEquals(4f - 0.35f + 1.8f, max(petit[1], 1), 1e-5f);
        assertEquals(4f + (1.62f - 0.35f) * 0.5f, petit[2][1], 1e-5f, "l'œil de Minecraft, multiplié par l'échelle");
        // Debout : les pieds au siège ; un œil déclaré se lit dans le repère du siège, tourné comme lui.
        float c = (float) Math.sqrt(0.5);
        dev.axion.seat.SeatPlacement.Pose tourne = new dev.axion.seat.SeatPlacement.Pose(0, 0, 0, new float[] {0f, c, 0f, c});
        float[][] debout = DebugOverlayRenderer.seatSegments(tourne, new dev.axion.definition.Seating.Occupant(
                dev.axion.definition.Seating.Pose.STAND, 2f, new float[] {1f, 0.75f, 0f}), 0, 0, 0);
        assertEquals(0f, min(debout[0], 1), 1e-5f, "debout : les pieds au point du siège");
        assertEquals(3.6f, max(debout[0], 1), 1e-5f);
        assertEquals(0f, debout[2][0] + DebugOverlayRenderer.EYE_MARK, 1e-4f, "x : un quart de tour envoie x sur -z, la croix est centrée");
        assertEquals(0.75f, debout[2][1], 1e-5f);
        assertEquals(-1f, debout[2][2], 1e-4f);
        assertEquals(DebugOverlayRenderer.SEAT_COLOR, DebugOverlayRenderer.colorOf(DebugOverlays.Overlay.SEATS, 0));
    }

    private static float min(float[] segments, int axis) {
        float lowest = Float.POSITIVE_INFINITY;
        for (int i = axis; i < segments.length; i += 3) {
            lowest = Math.min(lowest, segments[i]);
        }
        return lowest;
    }

    private static float max(float[] segments, int axis) {
        float highest = Float.NEGATIVE_INFINITY;
        for (int i = axis; i < segments.length; i += 3) {
            highest = Math.max(highest, segments[i]);
        }
        return highest;
    }

    @Test
    @DisplayName("ADR-174 D5 : l'overlay sensors dessine les douze arêtes d'une boîte, trois cercles d'une sphère, tournés comme le node")
    void lesCapteurs() {
        dev.axion.seat.SeatPlacement.Pose node = new dev.axion.seat.SeatPlacement.Pose(10.0, 64.0, -3.0,
                new float[] {0f, 0f, 0f, 1f});
        float[] boite = DebugOverlayRenderer.sensorSegments(node, false, new float[] {2f, 4f, 6f}, 10.0, 60.0, -3.0);
        assertEquals(12 * DebugGeometry.FLOATS_PER_SEGMENT, boite.length, "douze arêtes");
        for (int i = 0; i < boite.length; i += 3) {
            assertEquals(1f, Math.abs(boite[i]), 1e-5f, "x aux demi-tailles");
            assertEquals(2f, Math.abs(boite[i + 1] - 4f), 1e-5f, "y autour du node, à 4 de la caméra");
            assertEquals(3f, Math.abs(boite[i + 2]), 1e-5f);
        }
        float[] sphere = DebugOverlayRenderer.sensorSegments(node, true, new float[] {1.5f, 0f, 0f}, 10.0, 64.0, -3.0);
        assertEquals(3 * DebugOverlayRenderer.SENSOR_CIRCLE_SEGMENTS * DebugGeometry.FLOATS_PER_SEGMENT, sphere.length);
        for (int i = 0; i < sphere.length; i += 3) {
            float r = (float) Math.sqrt(sphere[i] * sphere[i] + sphere[i + 1] * sphere[i + 1] + sphere[i + 2] * sphere[i + 2]);
            assertEquals(1.5f, r, 1e-4f, "chaque point au rayon");
        }
        // Un quart de tour autour de y : la longueur x de la boîte passe sur z.
        float c = (float) Math.sqrt(0.5);
        float[] tournee = DebugOverlayRenderer.sensorSegments(new dev.axion.seat.SeatPlacement.Pose(0, 0, 0,
                new float[] {0f, c, 0f, c}), false, new float[] {2f, 4f, 6f}, 0, 0, 0);
        for (int i = 0; i < tournee.length; i += 3) {
            assertEquals(3f, Math.abs(tournee[i]), 1e-4f);
            assertEquals(1f, Math.abs(tournee[i + 2]), 1e-4f);
        }
        assertEquals(DebugOverlayRenderer.SENSOR_COLOR, DebugOverlayRenderer.colorOf(DebugOverlays.Overlay.SENSORS, 0));
    }
}
