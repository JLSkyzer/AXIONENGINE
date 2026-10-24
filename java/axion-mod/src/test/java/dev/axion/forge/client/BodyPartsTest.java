package dev.axion.forge.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.axion.asset.GeometryTransfer;
import dev.axion.net.Interpolation;
import dev.axion.render.BodyRig;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.joml.Quaternionf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ADR-151 D9 : chaque mesh se dessine à la pose du corps qui porte son node, à sa transformation de repos dans
 * le repère de ce corps. Les valeurs attendues sont calculées à la main.
 */
class BodyPartsTest {

    private static final float S = (float) Math.sqrt(0.5);

    /** Deux racines : {@code a} à l'origine, {@code b} à deux blocs en x — l'asset {@code test/deux_corps}. */
    private static byte[] deuxRacines() {
        ByteBuffer buffer = ByteBuffer.allocate(8 + 80 * 2).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0, 2);
        for (int index = 0; index < 2; index++) {
            int at = 8 + 80 * index;
            buffer.putInt(at + 8, -1);
            buffer.putFloat(at + 12, index == 1 ? 2f : 0f);
            buffer.putFloat(at + 36, 1f);
            for (int c = 0; c < 3; c++) {
                buffer.putFloat(at + 40 + 4 * c, 1f);
            }
        }
        return buffer.array();
    }

    private static final GeometryTransfer.Draw A =
            new GeometryTransfer.Draw(0, 0, 0, new float[] {1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0});
    private static final GeometryTransfer.Draw B =
            new GeometryTransfer.Draw(1, 1, 0, new float[] {1, 0, 0, 0, 1, 0, 0, 0, 1, 2, 0, 0});

    /** La racine en (10, 64, 0), un quart de tour autour de y ; {@code b} relatif à elle, s'il est donné. */
    private static AssemblyPlacement racine(List<Interpolation.BodyPose> corps) {
        return new AssemblyPlacement(10, 64, 0, new Quaternionf(0f, S, 0f, S), 0xF000F0, corps);
    }

    private static final Interpolation.BodyPose B_PENCHE = new Interpolation.BodyPose(1, new double[] {2, -0.5, 0},
            new float[] {0f, 0f, (float) Math.sin(0.25), (float) Math.cos(0.25)});

    @Test
    @DisplayName("Un mesh du corps secondaire se pose à la pose de ce corps, dans son repère")
    void unMeshSuitSonCorps() {
        BodyParts corps = BodyParts.of(racine(List.of(B_PENCHE)), new BodyRig(deuxRacines(), 0));

        AssemblyPlacement a = corps.at(A);
        assertSame(corps.assembly(), a, "a appartient au corps racine");
        assertSame(A.model(), corps.model(A), "sa transformation de repos, telle quelle");

        AssemblyPlacement b = corps.at(B);
        // Le quart de tour autour de y mène x vers −z : (2, −0,5, 0) devient (0, −0,5, −2).
        assertEquals(10.0, b.x(), 1e-5);
        assertEquals(63.5, b.y(), 1e-5);
        assertEquals(-2.0, b.z(), 1e-5);
        float sin = (float) Math.sin(0.25);
        float cos = (float) Math.cos(0.25);
        Quaternionf q = b.rotation();
        assertArrayEquals(new float[] {S * sin, S * cos, S * sin, S * cos}, new float[] {q.x, q.y, q.z, q.w}, 1e-6f,
                "l'orientation racine, puis le demi-radian de b autour de z");
        assertEquals(0xF000F0, b.light(), "la lumière de l'assembly");
        assertArrayEquals(new float[] {1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0}, corps.model(B), 1e-6f,
                "b est à l'origine de son corps");
    }

    @Test
    @DisplayName("Sans corps secondaire ni corps connus, tout se dessine à la pose de l'assembly")
    void sansCorpsToutSuitLaRacine() {
        AssemblyPlacement seule = racine(List.of());
        BodyParts avantLeSnapshot = BodyParts.of(seule, new BodyRig(deuxRacines(), 0));
        assertSame(seule, avantLeSnapshot.at(B));
        assertSame(B.model(), avantLeSnapshot.model(B));

        BodyParts sansRig = BodyParts.of(racine(List.of(B_PENCHE)), null);
        assertSame(sansRig.assembly(), sansRig.at(B));
        assertSame(B.model(), sansRig.model(B));
    }
}
