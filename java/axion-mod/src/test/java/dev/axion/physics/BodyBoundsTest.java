package dev.axion.physics;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

/**
 * Épingle le décodage de {@link BodyBounds} sur la disposition figée d'ADR-120
 * (24 octets, little-endian, {@code min} puis {@code max}). Les octets sont posés aux
 * offsets du natif ; si le décodeur lisait ailleurs, ce test échouerait.
 */
class BodyBoundsTest {

    @Test
    void tailleFigee() {
        assertEquals(24, BodyBounds.BYTES, "ADR-120 fige BodyBounds à 24 octets");
    }

    @Test
    void decodeLitLesCoinsAuxOffsetsFiges() {
        ByteBuffer buffer = ByteBuffer.allocate(BodyBounds.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putFloat(-0.5f); // min.x @0
        buffer.putFloat(0.0f); // min.y @4
        buffer.putFloat(-1.25f); // min.z @8
        buffer.putFloat(0.5f); // max.x @12
        buffer.putFloat(1.0f); // max.y @16
        buffer.putFloat(2.5f); // max.z @20

        BodyBounds bounds = BodyBounds.decode(buffer, 0);

        assertArrayEquals(new float[] {-0.5f, 0.0f, -1.25f}, bounds.min(), 0.0f);
        assertArrayEquals(new float[] {0.5f, 1.0f, 2.5f}, bounds.max(), 0.0f);
    }

    @Test
    void lesEmprisesSuiventLesEtatsAuMemeRang() {
        // SIM_OUT, schéma 1 : BodyState[2] puis BodyBounds[2]. L'emprise de rang 1 se lit à
        // 2 × 80 + 1 × 24 octets du début de la charge.
        ByteBuffer buffer = ByteBuffer.allocate(2 * BodyState.BYTES + 2 * BodyBounds.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        int rang1 = 2 * BodyState.BYTES + BodyBounds.BYTES;
        buffer.putFloat(rang1, -3.0f);
        buffer.putFloat(rang1 + 20, 7.0f);

        BodyBounds bounds = BodyBounds.decode(buffer, rang1);

        assertEquals(-3.0f, bounds.min()[0], 0.0f);
        assertEquals(7.0f, bounds.max()[2], 0.0f);
    }

    @Test
    void uneEmpriseValideEstPlausible() {
        assertTrue(new BodyBounds(new float[] {-0.5f, 0.0f, -0.5f}, new float[] {0.5f, 1.0f, 0.5f})
                .isPlausible());
        // La boîte nulle d'une emprise incalculable dit « aucune étendue » : elle passe.
        assertTrue(new BodyBounds(new float[3], new float[3]).isPlausible());
        // Aux bornes exactes.
        float m = BodyBounds.MAX_EXTENT;
        assertTrue(new BodyBounds(new float[] {-m, -m, -m}, new float[] {m, m, m}).isPlausible());
    }

    @Test
    void uneEmpriseAberranteEstRefusee() {
        float[] zero = {0.0f, 0.0f, 0.0f};
        float[] un = {1.0f, 1.0f, 1.0f};
        // Non finie.
        assertFalse(new BodyBounds(new float[] {Float.NaN, 0.0f, 0.0f}, un).isPlausible());
        assertFalse(new BodyBounds(zero, new float[] {1.0f, Float.POSITIVE_INFINITY, 1.0f})
                .isPlausible());
        // Coins inversés.
        assertFalse(new BodyBounds(un, zero).isPlausible());
        // Au-delà de ce qu'un asset valide peut atteindre.
        assertFalse(new BodyBounds(zero, new float[] {1.0f, 1.0f, BodyBounds.MAX_EXTENT + 1.0f})
                .isPlausible());
    }
}
