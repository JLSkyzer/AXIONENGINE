package dev.axion.render;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.axion.net.AssemblyState;
import dev.axion.net.Interpolation;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-174 D4 : le poids qu'une source pilote, évalué au client à chaque image. */
class LayerWeightsTest {

    private static final String DEFINITION = "{\"schema\": 7, \"variables\": {\"ouverture\": {\"type\": \"float\","
            + " \"min\": -1, \"max\": 2}}, \"animations\": {"
            + "\"porte\": {\"clip\": \"ouvre\", \"layer\": 2, \"weight_source\": \"var.ouverture\"},"
            + " \"roule\": {\"clip\": \"roule\"}}}";

    private static ProceduralTracks.Frame trame(float ouverture) {
        return new ProceduralTracks.Frame(List.of(), new double[0], Interpolation.VehiclePose.NONE,
                new AssemblyState.VariableValues(1, 1L, new float[] {ouverture}), new boolean[0], 0.0, 0f, 0f, 0f);
    }

    @Test
    @DisplayName("ADR-174 D4 : la source pilote le poids de sa couche, ramenée dans [0, 1] ; les autres couches n'en ont pas")
    void laSourcePiloteSaCouche() {
        LayerWeights poids = LayerWeights.of(JsonParser.parseString(DEFINITION).getAsJsonObject(), note -> {});
        assertFalse(poids.isEmpty());
        assertArrayEquals(new float[] {Float.NaN, Float.NaN, 0.25f, Float.NaN}, poids.evaluate(trame(0.25f)));
        assertEquals(1f, poids.evaluate(trame(1.7f))[2], "au-delà de 1 : plein");
        assertEquals(0f, poids.evaluate(trame(-0.5f))[2], "en dessous de 0 : nul");
        assertTrue(LayerWeights.partial(poids.evaluate(trame(0.25f))), "un poids partiel");
        assertFalse(LayerWeights.partial(poids.evaluate(trame(1f))), "plein");
        assertFalse(LayerWeights.partial(null));
    }

    @Test
    @DisplayName("ADR-174 D4 : avant le schéma 7, ou sans weight_source, aucun poids piloté ; une source inconnue se journalise")
    void sansPoidsPilote() {
        JsonObject six = JsonParser.parseString(DEFINITION).getAsJsonObject();
        six.addProperty("schema", 6);
        assertSame(LayerWeights.NONE, LayerWeights.of(six, note -> {}));
        assertSame(LayerWeights.NONE, LayerWeights.of(JsonParser.parseString(
                "{\"schema\": 7, \"animations\": {\"a\": {\"clip\": \"x\"}}}").getAsJsonObject(), note -> {}));
        List<String> journal = new ArrayList<>();
        assertSame(LayerWeights.NONE, LayerWeights.of(JsonParser.parseString(
                "{\"schema\": 7, \"animations\": {\"a\": {\"weight_source\": \"var.absente\"}}}").getAsJsonObject(),
                journal::add));
        assertEquals(1, journal.size(), journal.toString());
        assertTrue(journal.get(0).contains("var.absente") && journal.get(0).contains("poids du serveur"), journal.get(0));
    }
}
