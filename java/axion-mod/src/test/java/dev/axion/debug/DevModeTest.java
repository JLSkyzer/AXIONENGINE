package dev.axion.debug;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T-702 — R-2270 : le mode développeur (§31.3) est inerte sans {@code -Daxion.dev=true}. */
class DevModeTest {

    private static boolean activePour(String value) {
        Properties properties = new Properties();
        properties.setProperty(DevMode.PROPERTY, value);
        return DevMode.isEnabledIn(properties);
    }

    @Test
    @DisplayName("T-702 : sans la propriété, le mode développeur est éteint")
    void eteintSansPropriete() {
        assertFalse(DevMode.isEnabledIn(new Properties()));
    }

    @Test
    @DisplayName("T-702 : seul true l'allume, sans égard à la casse")
    void seulTrueAllume() {
        assertTrue(activePour("true"));
        assertTrue(activePour("TRUE"));
        for (String autre : List.of("", "1", "yes", "on", "false", " true")) {
            assertFalse(activePour(autre), "« " + autre + " » ne doit pas l'allumer");
        }
    }
}
