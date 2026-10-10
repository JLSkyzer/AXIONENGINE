package dev.axion.render;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * §19.5, ADR-127 §5 — la lumière synthétisée des surfaces du backend natif, celle qui rend l'assembly
 * « éclairée » de T-474.
 */
class WorldLightTest {

    private static final float EPS = 1e-5f;

    /** {@code ClientLevel.getSkyDarken} sans pluie ni orage, d'après l'heure du ciel. */
    private static float skyDarken(float timeOfDay) {
        float f = (float) Math.cos(timeOfDay * 2.0 * Math.PI) * 2.0f + 0.2f;
        return Math.max(0.0f, Math.min(1.0f, f)) * 0.8f + 0.2f;
    }

    private static float max(float[] color) {
        return Math.max(color[0], Math.max(color[1], color[2]));
    }

    @Test
    @DisplayName("§19.5 : à midi, le soleil est au zénith et donne toute sa lumière")
    void midi() {
        WorldLight.Light light = WorldLight.direct(0.0f, skyDarken(0.0f), 1.0f, 0.0f, true);
        assertArrayEquals(new float[] {0.0f, 1.0f, 0.0f}, light.direction(), EPS);
        assertArrayEquals(WorldLight.SUN, light.color(), EPS);
    }

    @Test
    @DisplayName("§19.5 : le soleil se lève à l'est et se couche à l'ouest, comme le ciel de Minecraft")
    void courseDuSoleil() {
        // Minecraft : l'est est +X, l'ouest -X.
        float[] coucher = WorldLight.direct(0.25f, skyDarken(0.25f), 1.0f, 0.0f, true).direction();
        assertEquals(-1.0f, coucher[0], EPS);
        assertEquals(0.0f, coucher[1], EPS);
        float[] matin = WorldLight.direct(0.875f, skyDarken(0.875f), 1.0f, 0.0f, true).direction();
        assertTrue(matin[0] > 0.0f && matin[1] > 0.0f, matin[0] + ", " + matin[1]);
    }

    @Test
    @DisplayName("§19.5 : à minuit, la lune est au zénith ; sa lumière suit sa phase et s'éteint sous la pluie")
    void minuit() {
        WorldLight.Light pleine = WorldLight.direct(0.5f, skyDarken(0.5f), 1.0f, 0.0f, true);
        assertArrayEquals(new float[] {0.0f, 1.0f, 0.0f}, pleine.direction(), EPS);
        assertArrayEquals(WorldLight.MOON, pleine.color(), EPS);

        WorldLight.Light demi = WorldLight.direct(0.5f, skyDarken(0.5f), 0.5f, 0.0f, true);
        assertEquals(WorldLight.MOON[2] * 0.5f, demi.color()[2], EPS);

        assertEquals(0.0f, max(WorldLight.direct(0.5f, skyDarken(0.5f), 1.0f, 1.0f, true).color()), EPS);
    }

    @Test
    @DisplayName("§19.5 : la relève du soleil par la lune se fait sans saut de lumière")
    void releveContinue() {
        // Le soleil s'éteint quand cos θ atteint -0,1 : de part et d'autre, presque rien.
        float heure = (float) (Math.acos(WorldLight.SUN_OFF) / (2.0 * Math.PI));
        float avant = heure - 1e-4f;
        float apres = heure + 1e-4f;
        WorldLight.Light soleil = WorldLight.direct(avant, skyDarken(avant), 1.0f, 0.0f, true);
        WorldLight.Light lune = WorldLight.direct(apres, skyDarken(apres), 1.0f, 0.0f, true);
        assertTrue(soleil.direction()[1] > -0.11f && soleil.direction()[1] < 0.0f, "soleil sous l'horizon");
        assertTrue(lune.direction()[1] > 0.0f, "lune au-dessus de l'horizon");
        assertTrue(max(soleil.color()) < 1e-2f, "soleil " + max(soleil.color()));
        assertTrue(max(lune.color()) < 1e-2f, "lune " + max(lune.color()));
    }

    @Test
    @DisplayName("§19.5 : une dimension sans ciel n'a pas de lumière directe")
    void sansCiel() {
        assertEquals(0.0f, max(WorldLight.direct(0.0f, 1.0f, 1.0f, 0.0f, false).color()), 0.0f);
    }

    @Test
    @DisplayName("R-1511 : l'hémisphère ambiant répartit la lumière du ciel au sol, sa moyenne fixe")
    void hemisphere() {
        float[] brouillard = {1.0f, 1.0f, 1.0f, 1.0f};
        WorldLight.Hemisphere midi = WorldLight.hemisphere(0.5, 0.5, 0.5, brouillard);
        float ciel = (float) Math.pow(0.5, 2.2);
        float sol = WorldLight.GROUND_ALBEDO;
        float moyenne = (ciel + sol) / 2.0f;
        assertEquals(WorldLight.AMBIENT * ciel / moyenne, midi.sky(), EPS);
        assertEquals(WorldLight.AMBIENT * sol / moyenne, midi.ground(), EPS);
        assertEquals(WorldLight.AMBIENT, (midi.sky() + midi.ground()) / 2.0f, EPS);
    }

    @Test
    @DisplayName("R-1511 : la nuit n'assombrit pas l'hémisphère — c'est la lightmap qui le fait, une fois")
    void hemisphereSansIntensite() {
        float[] brouillard = {0.8f, 0.85f, 1.0f, 1.0f};
        WorldLight.Hemisphere jour = WorldLight.hemisphere(0.47, 0.65, 1.0, brouillard);
        // Le même ciel et le même brouillard, assombris d'un même facteur, comme la nuit les rend.
        float f = 0.1f;
        WorldLight.Hemisphere nuit = WorldLight.hemisphere(
                0.47 * f, 0.65 * f, 1.0 * f, new float[] {0.8f * f, 0.85f * f, 1.0f * f, 1.0f});
        assertEquals(jour.sky(), nuit.sky(), EPS);
        assertEquals(jour.ground(), nuit.ground(), EPS);
        assertTrue(jour.sky() > jour.ground(), jour.sky() + " > " + jour.ground());
    }

    @Test
    @DisplayName("R-1511 : un ciel et un brouillard noirs donnent un hémisphère sans direction")
    void hemisphereNoir() {
        WorldLight.Hemisphere noir = WorldLight.hemisphere(0.0, 0.0, 0.0, new float[] {0.0f, 0.0f, 0.0f, 1.0f});
        assertEquals(WorldLight.AMBIENT, noir.sky(), 0.0f);
        assertEquals(WorldLight.AMBIENT, noir.ground(), 0.0f);
    }
}
