package dev.axion.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.axion.config.AxionConfig;
import dev.axion.config.ConfigSchema;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Le temps de rendu du client (21.7) : retard d'interpolation du serveur, pauses du monde solo exclues. */
class RenderClockTest {

    private static final long MS = 1_000_000L;

    static AxionConfig config(Map<String, Object> changes) {
        Map<String, Object> values = new HashMap<>();
        ConfigSchema.COMMON_OPTIONS.forEach(option -> values.put(option.path(), option.defaultValue()));
        values.putAll(changes);
        return new AxionConfig(ConfigSchema.Scope.COMMON, values, List.of());
    }

    @Test
    @DisplayName("21.7 : le rendu se tient interpolation_delay_ms derrière le temps du serveur estimé")
    void leRenduSuitLeServeurAvecRetard() {
        RenderClock horloge = new RenderClock(100);
        assertFalse(horloge.known());
        assertTrue(Double.isNaN(horloge.renderMillis(0)));
        horloge.onFrame(1000 * MS, false);
        assertEquals(1000 * MS, horloge.frameNanos(), "les passes d'une image se datent de l'image");
        // Le tick 20 (1000 ms du serveur) arrive à 1100 ms : rendu à 1000 − 100 ms.
        horloge.onSnapshot(20, 1100 * MS);
        assertTrue(horloge.known());
        assertEquals(900, horloge.renderMillis(1100 * MS), 1e-6);
        assertEquals(1000, horloge.renderMillis(1200 * MS), 1e-6);
        assertEquals(1200, horloge.localMillis(1200 * MS), 1e-6);
    }

    @Test
    @DisplayName("Le retard est celui que le serveur annonce ; faute de handshake, celui du client")
    void leRetardEstCeluiDuServeur() {
        AxionConfig client = config(Map.of("net.interpolation_delay_ms", 40L));
        AxionConfig serveur = config(Map.of("net.interpolation_delay_ms", 250L));
        ServerHandshake handshake = ServerHandshake.of(serveur, RegistryContent.of(Map.of(), Map.of(), Map.of()),
                new DetProfile(0x0123_4567_89AB_CDEFL, 1, true));
        RenderClock annonce = RenderClock.of(handshake, client);
        annonce.onSnapshot(20, 1000 * MS);
        assertEquals(1000 - 250, annonce.renderMillis(1000 * MS), 1e-6);
        RenderClock repli = RenderClock.of(null, client);
        repli.onSnapshot(20, 1000 * MS);
        assertEquals(1000 - 40, repli.renderMillis(1000 * MS), 1e-6);
    }

    @Test
    @DisplayName("Une pause du jeu suspend le temps de rendu et le temps local, puis tout reprend où il en était")
    void unePauseNeDurePas() {
        RenderClock horloge = new RenderClock(100);
        horloge.onFrame(1000 * MS, false);
        horloge.onSnapshot(20, 1100 * MS);
        assertEquals(1100, horloge.renderMillis(1300 * MS), 1e-6);
        horloge.onFrame(1300 * MS, true);
        assertEquals(1100, horloge.renderMillis(1500 * MS), 1e-6, "en pause, le rendu ne bouge plus");
        assertEquals(1300, horloge.localMillis(1500 * MS), 1e-6, "ni le temps local, qui mesure les corrections");
        horloge.onFrame(1700 * MS, true);
        horloge.onFrame(2300 * MS, false);
        assertEquals(1100, horloge.renderMillis(2300 * MS), 1e-6, "la reprise repart d'où la pause l'a laissé");
        assertEquals(1200, horloge.renderMillis(2400 * MS), 1e-6);
        assertEquals(1400, horloge.localMillis(2400 * MS), 1e-6);
        // Le serveur intégré a dormi aussi : son tick suivant arrive avec le même retard, une seconde plus tard.
        horloge.onSnapshot(24, 2300 * MS);
        assertEquals(1300, horloge.renderMillis(2500 * MS), 1e-6, "aucun saut à la reprise des snapshots");
        // Plus tard, les arrivées d'avant la pause sont sorties de la fenêtre : celles d'après, ramenées au
        // temps local, donnent le même retard.
        horloge.onSnapshot(70, 4600 * MS);
        assertEquals(3500, horloge.renderMillis(4700 * MS), 1e-6, "les arrivées se datent hors des pauses");
    }
}
