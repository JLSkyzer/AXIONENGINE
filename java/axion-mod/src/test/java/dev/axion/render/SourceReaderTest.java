package dev.axion.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.axion.entity.VehicleSnapshot;
import dev.axion.api.vehicle.DriverInput;
import dev.axion.net.AssemblyState;
import dev.axion.net.Interpolation;
import java.util.List;
import java.util.function.ToDoubleFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-173 D1 : une source procédurale se lit par son nom, comme une piste la lit. */
class SourceReaderTest {

    private static final JsonObject DEFINITION = JsonParser.parseString("{\"schema\": 3, \"variables\": {"
            + "\"phares\": {\"type\": \"bool\"}, \"regime_cible\": {\"type\": \"float\", \"min\": 0, \"max\": 9000}},"
            + " \"wheels\": [{\"node\": \"roue_g\"}], \"seats\": [{\"name\": \"conducteur\"}]}").getAsJsonObject();

    private static ProceduralTracks.Frame trame() {
        return new ProceduralTracks.Frame(List.of(new Interpolation.WheelPose(0.4f, 3f, 0.1f, true)),
                new double[] {1.5}, new Interpolation.VehiclePose(4200f, 0.6f, 2, 2, 0.8f, 0f, -0.3f, 0f, 12f, 11f),
                new AssemblyState.VariableValues(2, 3L, new float[] {1f, 6500f}), new boolean[] {true}, 42.0,
                0.5f, 15f, 0.7f, new double[0]);
    }

    @Test
    @DisplayName("ADR-173 D1 : le monde, le véhicule, une roue, un siège et une variable, lus comme une piste les lit")
    void unNomSeLitCommeUnePiste() {
        SourceReader lecteur = new SourceReader(DEFINITION);
        ProceduralTracks.Frame trame = trame();
        assertEquals(0.5, lecteur.read("world.time", trame), 1e-6, "midi");
        assertEquals(15.0, lecteur.read("world.sky_light", trame), 1e-6);
        assertEquals(0.7, lecteur.read("world.rain", trame), 1e-6);
        assertEquals(4200.0, lecteur.read("engine.rpm", trame), 1e-3);
        assertEquals(0.4, lecteur.read("wheel.roue_g.compression", trame), 1e-6);
        assertEquals(1.0, lecteur.read("seat.conducteur.occupied", trame), 1e-6);
        assertEquals(6500.0, lecteur.read("var.regime_cible", trame), 1e-3);
        assertEquals(42.0, lecteur.read("time.seconds", trame), 1e-9);
        for (String source : List.of("world.time", "engine.rpm", "wheel.roue_g.compression", "var.phares")) {
            assertEquals(ProceduralTracks.probe(DEFINITION, source).read(trame), lecteur.read(source, trame), 0.0,
                    "la même valeur que la sonde d'une jauge : " + source);
        }
    }

    @Test
    @DisplayName("ADR-174 D2 : var.<nom> d'une variable par siège se lit au siège du lecteur ; var.<nom>.<siège> au siège nommé")
    void uneVariableParSiege() {
        JsonObject definition = JsonParser.parseString("{\"schema\": 7, \"variables\": {\"radio\": {\"type\":"
                + " \"int\", \"default\": 1, \"scope\": \"seat\"}}, \"seats\": [{\"name\": \"conducteur\"},"
                + " {\"name\": \"passager\"}]}").getAsJsonObject();
        ProceduralTracks.Frame trame = new ProceduralTracks.Frame(List.of(), new double[0],
                Interpolation.VehiclePose.NONE, AssemblyState.VariableValues.of(new double[] {0},
                        new double[][] {{4, 7}}), new boolean[] {true, true}, 0.0, 0f, 0f, 0f, new double[0]);
        ProceduralTracks.Probe lue = ProceduralTracks.probe(definition, "var.radio");
        assertEquals(1.0, lue.read(trame), "sans lecteur assis : le défaut");
        assertEquals(7.0, lue.read(trame.from(1)), "depuis le passager");
        assertEquals(4.0, ProceduralTracks.probe(definition, "var.radio.conducteur").read(trame.from(1)),
                "le siège nommé, d'où qu'on lise");
        assertEquals(null, ProceduralTracks.probe(definition, "var.radio.arriere"), "un siège inconnu");
    }

    @Test
    @DisplayName("ADR-173 D1 : un nom qui n'est pas une source, ou qui nomme ce que la definition n'a pas, lève")
    void unNomInconnuLeve() {
        SourceReader lecteur = new SourceReader(DEFINITION);
        for (String nom : List.of("meteo.pluie", "wheel.roue_d.compression", "var.klaxon", "seat.passager.occupied")) {
            IllegalArgumentException refus = assertThrows(IllegalArgumentException.class,
                    () -> lecteur.read(nom, trame()), nom);
            assertTrue(refus.getMessage().contains(nom) && refus.getMessage().contains("world.*"), refus.getMessage());
        }
    }

    @Test
    @DisplayName("ADR-173 D1 : la trame d'un instant se construit à la première source lue, une fois")
    void laTrameSeConstruitUneFois() {
        int[] construites = new int[1];
        ToDoubleFunction<String> instant = new SourceReader(DEFINITION).at(() -> {
            construites[0]++;
            return trame();
        });
        assertEquals(0, construites[0], "rien n'est lu avant la première source");
        instant.applyAsDouble("world.rain");
        instant.applyAsDouble("engine.rpm");
        instant.applyAsDouble("var.phares");
        assertEquals(1, construites[0]);
    }

    @Test
    @DisplayName("ADR-173 D1 : la vue d'un contrôleur lit les sources qu'on lui donne ; sans elles, elle le dit")
    void laVueDUnControleur() {
        VehicleSnapshot vue = new VehicleSnapshot(11.0, 4200.0, 2, DriverInput.NONE, List.of(), List.of(),
                new double[0], List.of(), new double[0]);
        assertThrows(UnsupportedOperationException.class, () -> vue.source("world.time"));
        VehicleSnapshot lue = vue.withSources(new SourceReader(DEFINITION).at(SourceReaderTest::trame));
        assertEquals(0.7, lue.source("world.rain"), 1e-6);
        assertEquals(11.0, lue.speed(), 0.0, "le reste de la vue est inchangé");
    }
}
