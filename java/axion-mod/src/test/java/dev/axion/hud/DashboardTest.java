package dev.axion.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.axion.definition.VehicleDashboard;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-170 D4 : la mise en page du tableau de bord et ce qu'il écrit. */
class DashboardTest {

    private static VehicleDashboard.Gauge jauge(VehicleDashboard.Kind kind, VehicleDashboard.Anchor anchor,
            double offsetX, double offsetY, int size, String label) {
        return new VehicleDashboard.Gauge("g", kind, "vehicle.speed", 0, 10, label, null, anchor, offsetX, offsetY,
                size);
    }

    @Test
    @DisplayName("D4 : le défaut s'accroche en bas à droite, à la marge du bord")
    void defaut() {
        Dashboard.Layout layout = Dashboard.layout(new VehicleDashboard(true, 1, List.of(), List.of()), 480, 270);
        assertEquals(new Dashboard.Box(480 - Dashboard.MARGIN - Dashboard.DEFAULT_WIDTH,
                270 - Dashboard.MARGIN - Dashboard.DEFAULT_HEIGHT, Dashboard.DEFAULT_WIDTH, Dashboard.DEFAULT_HEIGHT),
                layout.standard());
        assertNull(Dashboard.layout(new VehicleDashboard(false, 1, List.of(), List.of()), 480, 270).standard());
    }

    @Test
    @DisplayName("D4 : une jauge sans décalage s'empile sur son ancre, vers le haut depuis le bas, jamais vers le centre"
            + " ; une jauge décalée se place où elle le dit")
    void rangement() {
        VehicleDashboard tableau = new VehicleDashboard(true, 1, List.of(
                jauge(VehicleDashboard.Kind.DIAL, VehicleDashboard.Anchor.BOTTOM_RIGHT, Double.NaN, Double.NaN, 40,
                        null),
                jauge(VehicleDashboard.Kind.DIAL, VehicleDashboard.Anchor.BOTTOM_RIGHT, Double.NaN, Double.NaN, 40,
                        null),
                jauge(VehicleDashboard.Kind.TEXT, VehicleDashboard.Anchor.TOP_LEFT, 5, 7, 30, "x")), List.of());
        Dashboard.Layout layout = Dashboard.layout(tableau, 480, 270);
        int bas = 270 - Dashboard.MARGIN;
        Dashboard.Box premiere = layout.gauges().get(0);
        assertEquals(480 - Dashboard.MARGIN - 40, premiere.x(), "au bord droit, pas vers le centre");
        assertEquals(bas - Dashboard.DEFAULT_HEIGHT - Dashboard.GAP - 40, premiere.y(), "au-dessus du défaut");
        Dashboard.Box seconde = layout.gauges().get(1);
        assertEquals(premiere.y() - Dashboard.GAP - 40, seconde.y(), "puis au-dessus de la première");
        assertEquals(new Dashboard.Box(Dashboard.MARGIN + 5, Dashboard.MARGIN + 7, 60,
                Dashboard.LINE + Dashboard.LINE), layout.gauges().get(2));
    }

    @Test
    @DisplayName("D6 : un widget s'empile sur son ancre après les jauges, à la taille qu'il demande")
    void widgets() {
        VehicleDashboard tableau = new VehicleDashboard(true, 1, List.of(
                jauge(VehicleDashboard.Kind.TEXT, VehicleDashboard.Anchor.BOTTOM_LEFT, Double.NaN, Double.NaN, 20,
                        null)), List.of("m:w"));
        Dashboard.Layout layout = Dashboard.layout(tableau, List.of(
                new Dashboard.Slot(VehicleDashboard.Anchor.BOTTOM_LEFT, 70, 30)), 480, 270);
        Dashboard.Box jauge = layout.gauges().get(0);
        assertEquals(new Dashboard.Box(Dashboard.MARGIN, jauge.y() - Dashboard.GAP - 30, 70, 30),
                layout.widgets().get(0), "au-dessus de la jauge de la même ancre");
    }

    @Test
    @DisplayName("D4 : la vitesse dans l'unité du joueur, le rapport, le régime, une valeur et son unité")
    void formats() {
        assertEquals("72 km/h", Dashboard.speed(20, "kmh"));
        assertEquals("45 mph", Dashboard.speed(20, "mph"));
        assertEquals("18 km/h", Dashboard.speed(-5, "kmh"), "la marche arrière s'affiche sans signe");
        assertEquals("R", Dashboard.gear(-1));
        assertEquals("N", Dashboard.gear(0));
        assertEquals("3", Dashboard.gear(3));
        assertEquals("3460", Dashboard.rpm(3456));
        assertEquals("1.5 bar", Dashboard.value(1.52, "bar"));
        assertEquals("120", Dashboard.value(119.6, null));
        assertEquals("—", Dashboard.value(Double.NaN, null), "une source sans valeur");
    }

    @Test
    @DisplayName("D4 : l'aiguille balaie de -120 à 120 degrés, bornée ; un voyant s'allume au seuil")
    void jauges() {
        assertEquals(-120, Dashboard.needle(0, 0, 10), 1e-9);
        assertEquals(0, Dashboard.needle(5, 0, 10), 1e-9);
        assertEquals(120, Dashboard.needle(30, 0, 10), 1e-9, "bornée au maximum");
        assertEquals(-120, Dashboard.needle(Double.NaN, 0, 10), 1e-9, "inconnue : au repos");
        assertTrue(Dashboard.lit(1, 0.5));
        assertFalse(Dashboard.lit(0, 0.5));
        assertFalse(Dashboard.lit(Double.NaN, 0.5));
    }
}
