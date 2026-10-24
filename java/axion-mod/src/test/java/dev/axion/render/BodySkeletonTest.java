package dev.axion.render;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ADR-151 D9 : le corps de chaque node et le repère de chaque corps, comme le natif les établit
 * ({@code Skeleton}). La section {@code NODE} est écrite ici à la main, à la disposition figée de
 * {@code NodeDesc} : 80 octets, le parent à l'octet 8, la transformation locale à l'octet 12.
 */
class BodySkeletonTest {

    private static final float R = (float) Math.sqrt(0.5);
    private static final float[] QUART_Z = {0f, 0f, R, R};
    private static final float[] QUART_Y = {0f, R, 0f, R};
    private static final float[] DROIT = {0f, 0f, 0f, 1f};

    /** Un node : parent ({@code -1} pour une racine), translation, rotation, échelle par axe. */
    private record Node(int parent, float[] t, float[] q, float[] s) {}

    private static Node node(int parent, float[] t, float[] q, float s) {
        return new Node(parent, t, q, new float[] {s, s, s});
    }

    private static byte[] section(Node... nodes) {
        ByteBuffer buffer = ByteBuffer.allocate(8 + 80 * nodes.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0, nodes.length);
        for (int index = 0; index < nodes.length; index++) {
            int at = 8 + 80 * index;
            Node node = nodes[index];
            buffer.putInt(at + 8, node.parent());
            for (int c = 0; c < 3; c++) {
                buffer.putFloat(at + 12 + 4 * c, node.t()[c]);
                buffer.putFloat(at + 40 + 4 * c, node.s()[c]);
            }
            for (int c = 0; c < 4; c++) {
                buffer.putFloat(at + 24 + 4 * c, node.q()[c]);
            }
        }
        return buffer.array();
    }

    /**
     * {@code socle} (racine, un quart de tour autour de z, échelle 2), {@code bras} sous lui (un bloc en x,
     * un quart de tour autour de y), {@code main} sous le bras, {@code pied} sous le socle, {@code autre} une
     * seconde racine, {@code etire} une échelle non uniforme sous le socle.
     */
    private static final byte[] NODES = section(
            node(-1, new float[] {0f, 1f, 0f}, QUART_Z, 2f),
            node(0, new float[] {1f, 0f, 0f}, QUART_Y, 1f),
            node(1, new float[] {0f, 0f, 1f}, DROIT, 1f),
            node(0, new float[] {0f, -1f, 0f}, DROIT, 1f),
            node(-1, new float[] {5f, 0f, 0f}, DROIT, 1f),
            new Node(0, new float[3], DROIT, new float[] {1f, 2f, 1f}));

    @Test
    @DisplayName("Un node appartient au corps de sa plus proche racine, sinon au corps racine")
    void chaqueNodeASonCorps() {
        BodySkeleton squelette = BodySkeleton.of(NODES, 0, new int[] {1});
        assertNotNull(squelette);
        assertEquals(2, squelette.bodies());
        int[] corps = new int[6];
        for (int node = 0; node < 6; node++) {
            corps[node] = squelette.bodyOf(node);
        }
        assertArrayEquals(new int[] {0, 1, 1, 0, 0, 0}, corps, "main suit le bras ; pied, autre et etire la racine");
        assertEquals(0, squelette.bodyOf(99), "hors de la table : le corps racine");
    }

    @Test
    @DisplayName("Le repère d'un corps est la pose de son node racine, l'échelle du parent dans sa translation")
    void leRepereDUnCorpsEstLaPoseDeSaRacine() {
        BodySkeleton squelette = BodySkeleton.of(NODES, 0, new int[] {1});
        assertArrayEquals(new float[] {0f, 0f, 0f, 0f, 0f, 0f, 1f}, squelette.frame(0), "le repère de l'asset");
        float[] bras = squelette.frame(1);
        // (0, 1, 0) + quart de tour en z de (1, 0, 0) × 2 = (0, 3, 0) ; rotation : z puis y.
        assertArrayEquals(new float[] {0f, 3f, 0f}, Arrays.copyOf(bras, 3), 1e-6f);
        assertArrayEquals(new float[] {-0.5f, 0.5f, 0.5f, 0.5f}, Arrays.copyOfRange(bras, 3, 7), 1e-6f,
                "quart de tour en z composé avec un quart de tour en y");
    }

    @Test
    @DisplayName("Dans le repère de son corps, la pose de repos d'un node redevient sa transformation par rapport au corps")
    void laPoseDeReposPasseDansLeRepereDuCorps() {
        BodySkeleton squelette = BodySkeleton.of(NODES, 0, new int[] {1});
        float[] repere = squelette.frame(1);
        org.joml.Quaternionf rotation = new org.joml.Quaternionf(repere[3], repere[4], repere[5], repere[6]);
        // Un node à (0, 0, 2) du bras, à l'échelle 2 : sa pose dans l'asset, composée par JOML.
        float[] modele = new float[12];
        for (int colonne = 0; colonne < 3; colonne++) {
            org.joml.Vector3f axe = new org.joml.Vector3f(colonne == 0 ? 2f : 0f, colonne == 1 ? 2f : 0f, colonne == 2 ? 2f : 0f);
            rotation.transform(axe);
            modele[3 * colonne] = axe.x;
            modele[3 * colonne + 1] = axe.y;
            modele[3 * colonne + 2] = axe.z;
        }
        org.joml.Vector3f origine = rotation.transform(new org.joml.Vector3f(0f, 0f, 2f)).add(repere[0], repere[1], repere[2]);
        modele[9] = origine.x;
        modele[10] = origine.y;
        modele[11] = origine.z;

        assertArrayEquals(new float[] {2f, 0f, 0f, 0f, 2f, 0f, 0f, 0f, 2f, 0f, 0f, 2f},
                squelette.modelInBody(1, modele), 1e-5f, "l'échelle et l'écart au bras, sans sa rotation");
        float[] racine = {1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 4f, 5f, 6f};
        org.junit.jupiter.api.Assertions.assertSame(racine, squelette.modelInBody(0, racine),
                "le repère du corps racine est celui de l'asset");
    }

    @Test
    @DisplayName("La racine du corps racine garde les siens, même sous une racine secondaire")
    void laRacineDuCorpsRacineGardeLesSiens() {
        BodySkeleton squelette = BodySkeleton.of(NODES, 2, new int[] {1});
        assertEquals(1, squelette.bodyOf(1));
        assertEquals(0, squelette.bodyOf(2), "main est la racine du corps racine, sous le bras");
    }

    @Test
    @DisplayName("Une section illisible, une racine hors table ou sur une chaîne rompue : rien, comme le natif")
    void ceQueLeNatifRefuseNeSeLitPas() {
        assertNull(BodySkeleton.of(NODES, 0, new int[] {6}), "racine hors de la table");
        assertNull(BodySkeleton.of(NODES, 0, new int[] {5}), "racine sous une échelle non uniforme");
        assertNotNull(BodySkeleton.of(NODES, 0, new int[] {4}), "une seconde racine de l'asset");
        assertNull(BodySkeleton.of(Arrays.copyOf(NODES, NODES.length - 1), 0, new int[] {1}), "tronquée");
        assertNull(BodySkeleton.of(null, 0, new int[0]));
        assertNull(BodySkeleton.of(section(node(1, new float[3], DROIT, 1f), node(-1, new float[3], DROIT, 1f)), 0,
                new int[] {1}), "un parent après son enfant");
        assertNotNull(BodySkeleton.of(NODES, 0, new int[0]), "un seul corps");
    }
}
