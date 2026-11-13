package dev.axion.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.axion.physics.BodyBounds;
import dev.axion.physics.BodyState;
import dev.axion.physics.CollectResult;
import dev.axion.physics.VehicleReport;
import dev.axion.physics.WheelReport;
import dev.axion.seat.VehicleInput;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-168, 6c-C : l'état d'un pas prédit, mis en snapshot par les règles du serveur. */
class PredictedStatesTest {

    private static final float[] IDENTITE = {0f, 0f, 0f, 1f};
    private static final int HANDLE = 7;
    private static final int GEN = 1;

    private static BodyState corps(int handle, int node, double x) {
        return new BodyState(handle, GEN, new double[] {x, 64, -10}, IDENTITE, new float[] {0f, 0f, -12f}, new float[3],
                0, node);
    }

    private static CollectResult collecte(List<BodyState> corps, List<BodyBounds> emprises, List<WheelReport> roues,
            List<VehicleReport> vehicules) {
        return new CollectResult(0, corps.size(), 0, 0, 0, 0, 0, 0, corps, emprises, List.of(), roues, vehicules,
                List.of(), List.of());
    }

    @Test
    @DisplayName("le corps racine pose l'assembly ; les autres en relatif ; l'union des emprises ; roues et moteur")
    void etat() {
        // Le corps racine (node 3) n'est pas le premier rapporté ; un corps d'une autre assembly s'intercale.
        List<BodyState> corps = List.of(corps(HANDLE, 5, 101), corps(9, 3, 500), corps(HANDLE, 3, 100));
        BodyBounds unite = new BodyBounds(new float[] {-0.5f, -0.5f, -0.5f}, new float[] {0.5f, 0.5f, 0.5f});
        List<WheelReport> roues = List.of(
                new WheelReport(HANDLE, GEN, 1, WheelReport.ON_GROUND, 30f, 0.2f, 0.1f, 0f, 0f),
                new WheelReport(9, GEN, 0, 0, 99f, 0f, 0f, 0f, 0f),
                new WheelReport(HANDLE, GEN, 0, 0, 31f, 0.1f, 0f, 0f, 0f));
        List<VehicleReport> moteurs = List.of(new VehicleReport(HANDLE, GEN, 3000f, 0.8f, 2, VehicleReport.RUNNING,
                0.1f));
        VehicleInput entree = new VehicleInput(1f, 0f, 0.3f, 0f, 0f, (byte) 0, 0);
        AssemblyState etat = PredictedStates.of(collecte(corps, List.of(unite, unite, unite), roues, moteurs), HANDLE,
                GEN, 3, entree, AssemblyState.VariableValues.NONE);
        assertNotNull(etat);
        assertEquals(100.0, etat.positionBlocks()[0], 1e-3);
        assertEquals(1, etat.bodies().size(), "le corps de l'autre assembly n'en est pas");
        assertEquals(5, etat.bodies().get(0).node());
        assertEquals(1.0, etat.bodies().get(0).offsetBlocks()[0], 1e-3);
        assertEquals(-0.5f, etat.boundsMin()[0], 1e-2);
        assertEquals(1.5f, etat.boundsMax()[0], 1e-2, "l'union couvre le second corps");
        assertEquals(2, etat.wheels().count());
        assertEquals(31f, etat.wheels().spin(0), 0.5f);
        assertTrue(etat.wheels().onGround(1));
        assertEquals(3000f, etat.vehicle().rpm(), 20f);
        assertEquals(1f, etat.vehicle().throttle(), 1e-2f, "l'entrée tenue au pas");
    }

    @Test
    @DisplayName("un pas qui ne rapporte pas le corps racine — endormi, ou collecte en échec — ne pose rien")
    void sansRacine() {
        VehicleInput entree = new VehicleInput(0f, 0f, 0f, 0f, 0f, (byte) 0, 0);
        assertNull(PredictedStates.of(collecte(List.of(corps(HANDLE, 5, 1)), List.of(), List.of(), List.of()), HANDLE,
                GEN, 3, entree, AssemblyState.VariableValues.NONE));
        assertNull(PredictedStates.of(collecte(List.of(), List.of(), List.of(), List.of()), HANDLE, GEN, 3, entree,
                AssemblyState.VariableValues.NONE));
    }
}
