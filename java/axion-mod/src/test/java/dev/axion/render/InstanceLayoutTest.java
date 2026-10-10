package dev.axion.render;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Le bloc d'instance du §19.4 et la transformation d'un mesh posé (ADR-127 §2). */
class InstanceLayoutTest {

    private static final float EPS = 1e-6f;

    /** {@code mat4x3} colonne-major : identité, translation donnée. */
    private static float[] repos(float tx, float ty, float tz) {
        return new float[] {1, 0, 0, 0, 1, 0, 0, 0, 1, tx, ty, tz};
    }

    private static final float S = (float) Math.sqrt(0.5);

    @Test
    @DisplayName("Sans rotation ni repos, la matrice n'est que la translation relative à la caméra")
    void sansRotationLaTranslationSeule() {
        float[] lignes = InstanceLayout.modelRows(1, 2, 3, 0, 0, 0, 1, repos(0, 0, 0));
        assertArrayEquals(new float[] {1, 0, 0, 1, 0, 1, 0, 2, 0, 0, 1, 3}, lignes, EPS);
    }

    @Test
    @DisplayName("Un quart de tour autour de y envoie +x sur -z, la translation de repos comprise")
    void unQuartDeTourAutourDeY() {
        // Quaternion de 90° autour de +y : (0, sin 45°, 0, cos 45°).
        float[] lignes = InstanceLayout.modelRows(0, 0, 0, 0, S, 0, S, repos(1, 0, 0));
        // Colonne 0 (image de +x) : (0, 0, -1) ; translation : R (1, 0, 0) = (0, 0, -1).
        assertEquals(0, lignes[0], EPS);
        assertEquals(0, lignes[4], EPS);
        assertEquals(-1, lignes[8], EPS);
        assertEquals(0, lignes[3], EPS);
        assertEquals(0, lignes[7], EPS);
        assertEquals(-1, lignes[11], EPS);
    }

    @Test
    @DisplayName("Composée en double, la translation relative tient loin de l'origine du monde")
    void loinDeLOrigineLaTranslationTient() {
        // 30 000 000,25 n'existe pas en float : la différence, elle, est exacte.
        double corps = 30_000_000.25;
        double camera = 30_000_000.0;
        float[] lignes = InstanceLayout.modelRows(corps - camera, 0, 0, 0, 0, 0, 1, repos(0, 0, 0));
        assertEquals(0.25f, lignes[3], 0.0f);
        assertTrue((float) corps - (float) camera != 0.25f, "en float, le décalage se perdrait");
    }

    @Test
    @DisplayName("Une échelle négative est un miroir ; une rotation n'en est pas un")
    void unMiroirSeVoitAuDeterminant() {
        float[] miroir = {-1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0};
        assertTrue(InstanceLayout.mirrors(InstanceLayout.modelRows(0, 0, 0, 0, 0, 0, 1, miroir)));
        assertFalse(InstanceLayout.mirrors(InstanceLayout.modelRows(0, 0, 0, 0, S, 0, S, repos(2, 0, 0))));
    }

    @Test
    @DisplayName("Le bloc fait 88 octets, lumière dépaquetée en bloc puis ciel, réserves à zéro")
    void leBlocFait88Octets() {
        ByteBuffer bloc = ByteBuffer.allocate(InstanceLayout.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        float[] lignes = InstanceLayout.modelRows(1, 2, 3, 0, 0, 0, 1, repos(0, 0, 0));
        int lumiere = (14 * 16) | (9 * 16) << 16;
        InstanceLayout.write(bloc, lignes, new float[] {1, 0.5f, 0.25f, 1}, lumiere, 7);

        assertEquals(InstanceLayout.BYTES, bloc.position());
        assertEquals(1.0f, bloc.getFloat(InstanceLayout.MODEL_OFFSET + 3 * 4), 0.0f);
        assertEquals(0.5f, bloc.getFloat(InstanceLayout.TINT_OFFSET + 4), 0.0f);
        assertEquals(14 * 16, bloc.getInt(InstanceLayout.LIGHTMAP_OFFSET));
        assertEquals(9 * 16, bloc.getInt(InstanceLayout.LIGHTMAP_OFFSET + 4));
        assertEquals(0, bloc.getInt(InstanceLayout.PALETTE_OFFSET));
        assertEquals(0, bloc.getInt(InstanceLayout.DEFORM_OFFSET));
        assertEquals(0, bloc.getInt(InstanceLayout.DECAL_OFFSET));
        assertEquals(7, bloc.getInt(InstanceLayout.FLAGS_OFFSET));
    }

    @Test
    @DisplayName("Les champs du §19.4 se suivent sans trou jusqu'à 88 octets")
    void lesChampsSeSuivent() {
        assertEquals(InstanceLayout.MODEL_OFFSET + 12 * 4, InstanceLayout.TINT_OFFSET);
        assertEquals(InstanceLayout.TINT_OFFSET + 4 * 4, InstanceLayout.LIGHTMAP_OFFSET);
        assertEquals(InstanceLayout.LIGHTMAP_OFFSET + 2 * 4, InstanceLayout.PALETTE_OFFSET);
        assertEquals(InstanceLayout.FLAGS_OFFSET + 4, InstanceLayout.BYTES);
    }

    @Test
    @DisplayName("Un tampon gros-boutiste, ou une transformation de repos tronquée, est refusé")
    void lesEntreesBancalesSontRefusees() {
        ByteBuffer gros = ByteBuffer.allocate(InstanceLayout.BYTES);
        assertThrows(IllegalArgumentException.class,
                () -> InstanceLayout.write(gros, new float[12], new float[4], 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> InstanceLayout.modelRows(0, 0, 0, 0, 0, 0, 1, new float[9]));
    }
}
