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

    private static boolean cacheDesactivePour(String dev, String cache) {
        Properties properties = new Properties();
        if (dev != null) {
            properties.setProperty(DevMode.PROPERTY, dev);
        }
        if (cache != null) {
            properties.setProperty(DevMode.ASSET_CACHE_PROPERTY, cache);
        }
        return DevMode.assetCacheDisabledIn(properties);
    }

    @Test
    @DisplayName("§31.3, R-2270 : le cache d'assets ne se désactive qu'en mode développeur, par false")
    void cacheDesactivableEnModeDeveloppeurSeulement() {
        assertTrue(cacheDesactivePour("true", "false"));
        assertTrue(cacheDesactivePour("TRUE", "FALSE"));
        // Sans le mode développeur, la propriété est ignorée : inerte en production.
        assertFalse(cacheDesactivePour(null, "false"));
        assertFalse(cacheDesactivePour("false", "false"));
        // En mode développeur, seul false le désactive.
        for (String autre : List.of("", "true", "0", "no", "off", " false")) {
            assertFalse(cacheDesactivePour("true", autre), "« " + autre + " » ne doit pas le désactiver");
        }
        assertFalse(cacheDesactivePour("true", null));
    }
}
