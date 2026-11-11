package dev.axion.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.axion.config.AxionConfig;
import dev.axion.config.ConfigSchema.Scope;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-162 D9 : {@code /axion dev reload} n'agit que si {@code content.dev_reload} l'autorise. */
class DevReloadTest {

    private static AxionConfig config(boolean devReload) {
        return new AxionConfig(Scope.COMMON, Map.of(DevReload.OPTION, devReload), List.of());
    }

    @Test
    @DisplayName("ADR-162 D9 : désactivée par défaut, la commande refuse et dit comment l'activer")
    void refuseSansLOption() {
        String refusal = DevReload.refusal(config(false));
        assertTrue(refusal != null && refusal.contains("content.dev_reload = true")
                && refusal.contains("-Daxion.content.dev_reload=true"), refusal);
    }

    @Test
    @DisplayName("ADR-162 D9 : l'option vraie autorise la commande")
    void autoriseeAvecLOption() {
        assertNull(DevReload.refusal(config(true)));
    }

    @Test
    @DisplayName("ADR-162 D9 : sans configuration chargée, la commande refuse")
    void refuseSansConfiguration() {
        assertEquals("AXION : configuration non chargée", DevReload.refusal(null));
    }
}
