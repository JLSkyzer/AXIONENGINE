package dev.axion.entity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.axion.api.assembly.Modifier.Operation;
import dev.axion.api.assembly.Parameters;
import dev.axion.api.vehicle.ControllerOutput;
import dev.axion.api.vehicle.DriverInput;
import dev.axion.api.vehicle.VehicleController;
import dev.axion.definition.VehicleExtensions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-167 D9 : l'appel des contrôleurs, l'assemblage de leurs sorties, leur coût et leur désactivation. */
class ControllerRunnerTest {

    /** Une horloge que chaque contrôleur avance de ce qu'il « coûte ». */
    private final long[] now = {0};

    private final ControllerRunner runner = new ControllerRunner(new ControllerRunner.Limits(1_000, 3), () -> now[0]);

    /** Un véhicule à deux roues, la première glissant, une variable « mode » et le couple du moteur. */
    private static VehicleSnapshot vue() {
        return new VehicleSnapshot(12.5, 3200, 2, new DriverInput(0.8, 0, 0.1, 0, 0, 2),
                List.of(new VehicleSnapshot.Wheel("roue_g", true, 0.1, 40, 0.05, 0.3, 0.02),
                        new VehicleSnapshot.Wheel("roue_d", true, 0.1, 30, 0.05, 0.05, 0.01)),
                List.of("mode"), new double[] {1}, List.of(Parameters.ENGINE_TORQUE), new double[] {260});
    }

    private static VehicleExtensions.Controller controleur(String id, VehicleController controller) {
        return new VehicleExtensions.Controller(id, id.substring(0, id.indexOf(':')), controller);
    }

    /** Un contrôleur qui « coûte » ce temps-là. */
    private VehicleController coutant(long nanos, VehicleController then) {
        return (vue, sortie) -> {
            now[0] += nanos;
            then.control(vue, sortie);
        };
    }

    @Test
    @DisplayName("D9 : chacun lit le même état ; modificateurs à son nom, couples sommés, variables dans l'ordre")
    void sortiesAssemblees() {
        List<Double> lus = new ArrayList<>();
        VehicleController antipatinage = (vue, sortie) -> {
            lus.add(vue.parameter(Parameters.ENGINE_TORQUE));
            for (int roue = 0; roue < vue.wheels().size(); roue++) {
                sortie.wheelTorque(roue, vue.wheels().get(roue).slipLong() > 0.2 ? -50 : 0);
            }
            sortie.modify(Parameters.ENGINE_TORQUE, Operation.MULTIPLY_TOTAL, -0.1);
        };
        VehicleController hybride = (vue, sortie) -> {
            lus.add(vue.parameter(Parameters.ENGINE_TORQUE));
            sortie.wheelTorque(0, 100);
            sortie.wheelTorque(0, 120);
            sortie.wheelTorque(1, 120);
            sortie.variable("mode", 2);
        };
        ControllerRunner.Outcome sorties = runner.run(
                List.of(controleur("tc:anti", antipatinage), controleur("hy:moteur", hybride)), vue());
        assertEquals(List.of(260.0, 260.0), lus);
        assertEquals(1, sorties.modifiers().size());
        assertEquals("tc:anti", sorties.modifiers().get(0).cause(), "la cause est le contrôleur");
        assertArrayEquals(new float[] {70, 120}, sorties.torques(), "reposer remplace ; deux contrôleurs s'ajoutent");
        assertEquals(List.of(new ControllerRunner.Write("hy:moteur", "mode", 2)), sorties.writes());
        assertTrue(sorties.journal().isEmpty());
    }

    @Test
    @DisplayName("D9 : une sortie refusée lève dans le contrôleur, et ses sorties du tick sont écartées")
    void sortiesRefusees() {
        AtomicReference<ControllerOutput> garde = new AtomicReference<>();
        List<Class<? extends RuntimeException>> leves = new ArrayList<>();
        VehicleController maladroit = (vue, sortie) -> {
            garde.set(sortie);
            sortie.wheelTorque(0, 10);
            leves.add(assertThrows(IllegalArgumentException.class,
                    () -> sortie.modify("turbo:inconnu", Operation.ADD, 1)).getClass());
            leves.add(assertThrows(IllegalArgumentException.class,
                    () -> sortie.modify(Parameters.ENGINE_TORQUE, Operation.ADD, Double.NaN)).getClass());
            leves.add(assertThrows(IndexOutOfBoundsException.class, () -> sortie.wheelTorque(2, 1)).getClass());
            leves.add(assertThrows(IllegalArgumentException.class, () -> sortie.wheelTorque(1, 1e40)).getClass());
            leves.add(assertThrows(IllegalArgumentException.class, () -> sortie.variable("absente", 1)).getClass());
            sortie.modify("turbo:inconnu", Operation.ADD, 1);
        };
        ControllerRunner.Outcome sorties = runner.run(List.of(controleur("m:maladroit", maladroit)), vue());
        assertEquals(5, leves.size());
        assertArrayEquals(new float[] {0, 0}, sorties.torques(), "l'exception écarte tout ce qu'il avait écrit");
        assertEquals(1, sorties.journal().size());
        assertTrue(sorties.journal().get(0).contains("m:maladroit") && sorties.journal().get(0).contains("faute 1"),
                sorties.journal().get(0));
        assertThrows(IllegalStateException.class, () -> garde.get().wheelTorque(0, 1),
                "une sortie ne vaut que pendant l'appel");
    }

    @Test
    @DisplayName("R-1770 : des dépassements consécutifs du budget le désactivent pour la session, E-1011 le nomme")
    void desactiveParSonCout() {
        AtomicInteger appels = new AtomicInteger();
        VehicleController lent = coutant(5_000, (vue, sortie) -> appels.incrementAndGet());
        VehicleController sain = coutant(200, (vue, sortie) -> sortie.wheelTorque(1, 5));
        List<VehicleExtensions.Controller> deux = List.of(controleur("lent:x", lent), controleur("sain:y", sain));
        assertTrue(runner.run(deux, vue()).journal().isEmpty(), "un dépassement ne se dit pas seul");
        assertTrue(runner.run(deux, vue()).journal().isEmpty());
        ControllerRunner.Outcome troisieme = runner.run(deux, vue());
        assertEquals(1, troisieme.journal().size());
        String ligne = troisieme.journal().get(0);
        assertTrue(ligne.startsWith("E-1011 contrôleur lent:x (mod lent)") && ligne.contains("budget"), ligne);
        runner.run(deux, vue());
        assertEquals(3, appels.get(), "désactivé : il n'est plus appelé");
        assertArrayEquals(new float[] {0, 5}, runner.run(deux, vue()).torques(), "les autres roulent");
        ControllerRunner.Cost cout = runner.cost("lent:x").orElseThrow();
        assertTrue(cout.disabled());
        assertEquals(3, cout.calls());
        assertEquals(5_000, cout.worstNanos());
        assertEquals(5_000.0, cout.meanNanos());
        assertFalse(runner.cost("sain:y").orElseThrow().disabled());
        assertEquals(List.of("lent:x", "sain:y"), runner.costs().stream().map(ControllerRunner.Cost::id).toList());
    }

    @Test
    @DisplayName("R-1770 : un appel sain remet le compte à zéro ; des exceptions répétées désactivent aussi")
    void fautesConsecutives() {
        AtomicInteger appels = new AtomicInteger();
        VehicleController irregulier = (vue, sortie) -> now[0] += appels.incrementAndGet() % 3 == 0 ? 10 : 5_000;
        List<VehicleExtensions.Controller> un = List.of(controleur("irr:x", irregulier));
        for (int i = 0; i < 30; i++) {
            runner.run(un, vue());
        }
        assertFalse(runner.cost("irr:x").orElseThrow().disabled(), "jamais trois fautes de suite");

        VehicleController casse = (vue, sortie) -> {
            throw new IllegalStateException("cassé");
        };
        List<VehicleExtensions.Controller> autre = List.of(controleur("casse:z", casse));
        assertEquals(1, runner.run(autre, vue()).journal().size(), "la première exception se dit");
        assertEquals(0, runner.run(autre, vue()).journal().size(), "la suivante, dans la seconde, se tait");
        String desactive = runner.run(autre, vue()).journal().get(0);
        assertTrue(desactive.startsWith("E-1011 contrôleur casse:z (mod casse)") && desactive.contains("cassé"),
                desactive);
        assertTrue(runner.cost("casse:z").orElseThrow().reason().contains("exception"));
    }
}
