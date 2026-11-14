package dev.axion.entity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.axion.api.assembly.Parameters;
import dev.axion.definition.Tuning;
import dev.axion.definition.VehicleExtensions;
import dev.axion.physics.AssemblyStateView;
import dev.axion.seat.VehicleInput;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ADR-168 D6 : les contrôleurs du client lisent l'état du début du tick dans le bloc exporté — celui que le serveur a lu
 * — et rendent ses sorties ; le bloc se décode selon la disposition de {@code world::state}.
 */
class PredictedControlTest {

    /** Un bloc de véhicule à un corps, sans joint, deux roues ; le moteur à 300 rad/s, en deuxième. */
    static byte[] bloc(boolean vehicule) {
        int roues = vehicule ? 2 : 0;
        ByteBuffer b = ByteBuffer.allocate(16 + 64 + (vehicule ? 56 + roues * 32 : 0)).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short) 1).putShort((short) (vehicule ? 1 : 0)).putShort((short) 1).putShort((short) 0)
                .putShort((short) roues);
        b.position(16);
        b.putDouble(10).putDouble(64).putDouble(-5);
        // Tourné d'un demi-tour autour de Y : l'avant, son -Z, regarde vers +Z.
        b.putFloat(0f).putFloat(1f).putFloat(0f).putFloat(0f);
        b.putFloat(0f).putFloat(0f).putFloat(12f).putFloat(0f).putFloat(0f).putFloat(0f);
        if (vehicule) {
            for (float v : new float[] {1f, 0f, 0f, 0f, 0f}) {
                b.putFloat(v);
            }
            b.put((byte) 2).put((byte) 0).put((byte) 1).put((byte) 0);
            b.putFloat(300f);
            b.put((byte) 2).put((byte) 2).put((byte) 0).put((byte) 0);
            for (int i = 0; i < 5; i++) {
                b.putFloat(0f);
            }
            b.putInt(0);
            for (int i = 0; i < roues; i++) {
                b.putFloat(40f + i).putFloat(0.25f).putFloat(0.1f * i).putFloat(3000f).putFloat(0.05f).putFloat(0.02f)
                        .putFloat(-7f * i).put((byte) (i == 0 ? 1 : 0)).put(new byte[3]);
            }
        }
        return b.array();
    }

    private static AssemblyParameters table() {
        return new AssemblyParameters(new Tuning(List.of(new Tuning.Parameter(Parameters.ENGINE_TORQUE, "N·m", 260,
                new Parameters.Range(0, true, Double.POSITIVE_INFINITY, false),
                new Parameters.Range(200, true, 420, true)))));
    }

    @Test
    @DisplayName("le bloc se lit : régime comme le natif le rapporte, rapport, roues, corps racine ; sans véhicule, rien")
    void decodage() {
        AssemblyStateView.Vehicle vehicule = AssemblyStateView.vehicle(bloc(true));
        assertEquals(300f * (60.0f / 6.2831855f), vehicule.rpm(), 0f, "au bit près, en f32");
        assertEquals(2, vehicule.gear());
        assertEquals(2, vehicule.wheels().size());
        assertEquals(41f, vehicule.wheels().get(1).spin());
        assertTrue(vehicule.wheels().get(0).onGround());
        assertEquals(0.05f, vehicule.wheels().get(0).slipLong());
        assertEquals(-7f, vehicule.wheels().get(1).torque(), "le couple posé par les contrôleurs");
        assertArrayEquals(new float[] {0f, 0f, 12f}, AssemblyStateView.root(bloc(true)).linear());
        assertNull(AssemblyStateView.vehicle(bloc(false)), "une assembly qui n'est pas un véhicule");
        assertNull(AssemblyStateView.vehicle(new byte[8]), "un bloc tronqué");
    }

    @Test
    @DisplayName("la vue se bâtit comme au serveur, et un contrôleur y rend ses sorties")
    void vueEtSorties() {
        VehicleSnapshot vue = PredictedControl.view(bloc(true), new float[] {0.1f, 0.1f}, List.of("av", "ar"),
                List.of("sport"), new double[] {1}, table(), new VehicleInput(1f, 0f, 0f, 0f, 0f, (byte) 2, 0));
        assertEquals(0.25f - 0.1f, (float) vue.wheels().get(0).compression(), "relative à la pose du modèle");
        assertEquals(12.0, vue.speed(), 1e-6, "12 m/s le long de son avant, tourné d'un demi-tour");
        assertEquals(2, vue.gear());
        assertEquals("ar", vue.wheels().get(1).node());
        assertEquals(1.0, vue.input().throttle());
        assertEquals(1.0, vue.variable("sport"));
        assertEquals(260.0, vue.parameter(Parameters.ENGINE_TORQUE), "les paramètres sans sortie de contrôleur");
        assertNull(PredictedControl.view(bloc(false), null, List.of("av"), List.of(), new double[0], table(),
                VehicleInput.NONE), "pas de véhicule dans le bloc : pas de vue");

        PredictedControl controle = new PredictedControl(new ControllerRunner.Limits(50_000_000L, 3), System::nanoTime);
        VehicleExtensions.Controller abs = new VehicleExtensions.Controller("essai:abs", "essai", (vehicle, out) -> {
            if (vehicle.speed() > 10) {
                out.wheelTorque(1, -50);
                out.variable("sport", 0);
            }
        });
        ControllerRunner.Outcome sorties = controle.run(List.of(abs), vue);
        assertEquals(-50f, sorties.torques()[1]);
        assertEquals(1, sorties.writes().size());
        assertEquals("sport", sorties.writes().get(0).variable());
        // Rejoué sur la même vue, il rend les mêmes sorties (CAP-40).
        assertArrayEquals(sorties.torques(), controle.run(List.of(abs), vue).torques());
    }
}
