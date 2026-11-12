package dev.axion.bench;

import dev.axion.api.assembly.Modifier;
import dev.axion.api.assembly.Parameters;
import dev.axion.api.vehicle.DriverInput;
import dev.axion.api.vehicle.VehicleController;
import dev.axion.api.vehicle.WheelView;
import dev.axion.definition.VehicleExtensions;
import dev.axion.entity.ControllerRunner;
import dev.axion.entity.VehicleSnapshot;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Le coût d'un appel de contrôleur de véhicule (ADR-167 D9, R-1770) : de quoi fixer, sur une mesure et non une
 * estimation, le budget d'un appel ({@code vehicle.controller_budget_ns}) et les fautes tolérées.
 *
 * <p>Le contrôleur mesuré est un antipatinage, le travail ordinaire d'un contrôleur : il lit le glissement de chacune
 * des quatre roues et un paramètre, pose un couple par roue, un modificateur du couple du moteur et une variable. Il
 * passe par {@link ControllerRunner}, comme en jeu : la mesure de son coût, l'assemblage des sorties et la vue du début
 * du tick en font partie.
 *
 * <p>Exécution : {@code ./gradlew :axion-mod:jmh -Pjmh.args="VehicleControllerCost"} (aucun natif n'est nécessaire).
 * Le mode {@code SampleTime} donne la distribution des appels, percentiles compris.
 */
@State(Scope.Benchmark)
@BenchmarkMode({Mode.SampleTime, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class VehicleControllerCost {

    /** Un antipatinage : au-delà de son seuil de glissement, il retire du couple à la roue et au moteur. */
    static final VehicleController ANTIPATINAGE = (vue, sortie) -> {
        double seuil = vue.parameter("banc:seuil");
        double pire = 0;
        List<WheelView> roues = vue.wheels();
        for (int roue = 0; roue < roues.size(); roue++) {
            double glissement = Math.abs(roues.get(roue).slipLong());
            pire = Math.max(pire, glissement);
            sortie.wheelTorque(roue, glissement > seuil ? -40.0 * (glissement - seuil) / seuil : 0.0);
        }
        if (pire > seuil) {
            sortie.modify(Parameters.ENGINE_TORQUE, Modifier.Operation.MULTIPLY_TOTAL, -Math.min(0.5, pire - seuil));
        }
        sortie.variable("antipatinage", pire > seuil ? 1 : 0);
    };

    private ControllerRunner runner;
    private List<VehicleExtensions.Controller> controllers;
    private VehicleSnapshot view;

    /** Un runner au budget large, pour que rien ne se désactive pendant la mesure. */
    @Setup
    public void setUp() {
        runner = new ControllerRunner(new ControllerRunner.Limits(Long.MAX_VALUE, Integer.MAX_VALUE), System::nanoTime);
        controllers = List.of(new VehicleExtensions.Controller("banc:antipatinage", "banc", ANTIPATINAGE));
        view = new VehicleSnapshot(18.0, 4200, 3, new DriverInput(1, 0, 0.05, 0, 0, 3),
                List.of(new VehicleSnapshot.Wheel("ag", true, 0.1, 60, 0.05, 0.04, 0.01),
                        new VehicleSnapshot.Wheel("ad", true, 0.1, 61, 0.05, 0.05, 0.01),
                        new VehicleSnapshot.Wheel("rg", true, 0.12, 75, 0.0, 0.32, 0.02),
                        new VehicleSnapshot.Wheel("rd", true, 0.12, 70, 0.0, 0.18, 0.02)),
                List.of("antipatinage"), new double[] {0},
                List.of(Parameters.ENGINE_TORQUE, "banc:seuil"), new double[] {260, 0.15});
    }

    /** {@return les sorties d'un tick, rendues pour que JMH ne supprime pas l'appel} */
    @Benchmark
    public ControllerRunner.Outcome oneTick() {
        return runner.run(controllers, view);
    }
}
