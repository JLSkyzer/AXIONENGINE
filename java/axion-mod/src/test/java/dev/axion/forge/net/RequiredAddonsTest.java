package dev.axion.forge.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.axion.net.RequiredMod;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-165 D3 : le message qui refuse un client, traduisible, un addon par ligne. */
class RequiredAddonsTest {

    private static final List<RequiredMod.Mismatch> CAUSES = List.of(
            new RequiredMod.Mismatch("policemod", "2.1.0", ""),
            new RequiredMod.Mismatch("tracteurs", "1.4.0", "1.3.2"));

    private static TranslatableContents traduisible(Component component) {
        return (TranslatableContents) component.getContents();
    }

    @Test
    @DisplayName("ADR-165 D3 : le refus nomme chaque addon, la version attendue, et celle du client s'il en a une")
    void leRefusNommeChaqueAddon() {
        Component refus = RequiredAddons.refusal(CAUSES);
        assertEquals("axion.connection.required_addons", traduisible(refus).getKey());
        List<Component> lignes = refus.getSiblings();
        assertEquals(4, lignes.size(), "un saut de ligne, puis la ligne, pour chaque addon");
        assertEquals("axion.connection.addon_missing", traduisible(lignes.get(1)).getKey());
        assertArrayEquals(new Object[] {"policemod", "2.1.0"}, traduisible(lignes.get(1)).getArgs());
        assertEquals("axion.connection.addon_version", traduisible(lignes.get(3)).getKey());
        assertArrayEquals(new Object[] {"tracteurs", "1.4.0", "1.3.2"}, traduisible(lignes.get(3)).getArgs());
        assertEquals("policemod 2.1.0 absent, tracteurs 1.4.0 (client : 1.3.2)", RequiredAddons.describe(CAUSES));
    }

    @Test
    @DisplayName("ADR-160 D7, Présentation : les clés du refus sont traduites en anglais et en français")
    void lesClesSontTraduites() throws IOException {
        for (String langue : List.of("en_us", "fr_fr")) {
            JsonObject traductions;
            try (InputStream in = RequiredAddonsTest.class.getClassLoader()
                    .getResourceAsStream("assets/axion/lang/" + langue + ".json")) {
                assertNotNull(in, langue);
                traductions = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                        .getAsJsonObject();
            }
            for (String cle : List.of("axion.connection.required_addons", "axion.connection.addon_missing",
                    "axion.connection.addon_version", "axion.connection.required_controllers",
                    "axion.connection.controller_missing")) {
                assertTrue(traductions.has(cle), langue + " : " + cle);
            }
        }
    }

    @Test
    @DisplayName("ADR-167 Q7 : le refus nomme chaque contrôleur manquant, seul ou après les addons")
    void leRefusNommeLesControleurs() {
        Component seuls = RequiredAddons.refusal(List.of(), List.of("turbo:gestion"));
        assertEquals("axion.connection.required_controllers", traduisible(seuls).getKey());
        assertEquals("axion.connection.controller_missing", traduisible(seuls.getSiblings().get(1)).getKey());
        assertArrayEquals(new Object[] {"turbo:gestion"}, traduisible(seuls.getSiblings().get(1)).getArgs());

        Component ensemble = RequiredAddons.refusal(CAUSES, List.of("turbo:gestion", "abs:frein"));
        assertEquals("axion.connection.required_addons", traduisible(ensemble).getKey());
        List<Component> lignes = ensemble.getSiblings();
        assertEquals(4 + 2 + 4, lignes.size(), "les addons, puis l'en-tête des contrôleurs, puis un par ligne");
        assertEquals("axion.connection.required_controllers", traduisible(lignes.get(5)).getKey());
        assertArrayEquals(new Object[] {"abs:frein"}, traduisible(lignes.get(9)).getArgs());
        assertEquals("policemod 2.1.0 absent, tracteurs 1.4.0 (client : 1.3.2), contrôleur turbo:gestion absent,"
                + " contrôleur abs:frein absent", RequiredAddons.describe(CAUSES, List.of("turbo:gestion", "abs:frein")));
    }
}
