package dev.axion.entity;

import dev.axion.api.vehicle.DriverInput;
import dev.axion.definition.VehicleExtensions;
import dev.axion.net.Interpolation;
import dev.axion.physics.AssemblyStateView;
import dev.axion.seat.VehicleInput;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * Les contrôleurs du véhicule que le client prédit (ADR-168 D6, ADR-167 D9, CAP-40) : appelés à chaque tick prédit et
 * à chaque tick rejoué, sur l'état du début du tick, comme le serveur les appelle sur le sien.
 *
 * <p>L'état du début d'un tick est celui de la fin du précédent. Après un pas prédit, la vue se bâtit de sa collecte,
 * comme le serveur bâtit la sienne ({@code AxionEntity.vehicleSnapshot}) : les rapports du moteur et des roues, la
 * vitesse du corps racine le long de son avant. Après une correction restaurée, seul le bloc du serveur existe : il se
 * lit, et la compression de chaque roue, que le bloc porte brute, se ramène à celle des rapports — relative à la pose
 * du modèle, {@code clamp(c, -1, 1) - settled}, {@code settled} relevé sur les pas prédits ({@link #settled}).
 * Les variables sont celles d'après l'entrée du tick, les paramètres ceux d'avant les sorties des contrôleurs.
 *
 * <p>Le contrat d'un contrôleur — une fonction de ce qu'il lit, sans état caché — le rend rembobinable : rejoué sur le
 * même état, il rend les mêmes sorties. Le budget et les fautes du lanceur restent propres au client.
 */
public final class PredictedControl {

    private final ControllerRunner runner;

    /**
     * @param limits le budget des contrôleurs ({@code [vehicle]})
     * @param clock l'horloge du budget, en nanosecondes
     */
    public PredictedControl(ControllerRunner.Limits limits, LongSupplier clock) {
        runner = new ControllerRunner(limits, clock);
    }

    /**
     * {@return la vue d'un tick, ou {@code null} si le bloc ne porte pas de véhicule, ou s'il manque une pièce}
     *
     * @param previous le bloc d'état de la fin du tick précédent
     * @param wheelNodes les nodes des roues, dans l'ordre de la definition
     * @param variableNames les noms des variables, dans l'ordre de la definition
     * @param variableValues leurs valeurs, après l'entrée du tick
     * @param table les paramètres du tick, avant les sorties des contrôleurs
     * @param input l'entrée du conducteur pour ce tick
     */
    public static VehicleSnapshot view(byte[] previous, float[] settled, List<String> wheelNodes,
            List<String> variableNames, double[] variableValues, AssemblyParameters table, VehicleInput input) {
        AssemblyStateView.Vehicle vehicle = AssemblyStateView.vehicle(previous);
        AssemblyStateView.Root root = AssemblyStateView.root(previous);
        if (vehicle == null || root == null || table == null || wheelNodes.isEmpty()) {
            return null;
        }
        List<VehicleSnapshot.Wheel> wheels = new ArrayList<>(wheelNodes.size());
        for (int i = 0; i < wheelNodes.size(); i++) {
            AssemblyStateView.Wheel wheel = i < vehicle.wheels().size() ? vehicle.wheels().get(i) : null;
            if (wheel == null) {
                wheels.add(new VehicleSnapshot.Wheel(wheelNodes.get(i), false, 0.0, 0.0, 0.0, 0.0, 0.0));
                continue;
            }
            float rest = settled != null && i < settled.length ? settled[i] : 0f;
            float compression = Math.max(-1f, Math.min(1f, wheel.compression())) - rest;
            wheels.add(new VehicleSnapshot.Wheel(wheelNodes.get(i), wheel.onGround(), compression, wheel.spin(),
                    wheel.steer(), wheel.slipLong(), wheel.slipLat()));
        }
        return snapshot(Interpolation.forwardSpeed(root.linear(), root.rotation()), vehicle.rpm(), vehicle.gear(),
                wheels, variableNames, variableValues, table, input);
    }

    /**
     * {@return la vue d'un tick bâtie de la collecte du pas précédent, comme le serveur bâtit la sienne ; {@code null}
     * si la collecte ne rapporte pas le corps racine ou le moteur du véhicule}
     *
     * @param result la collecte du pas précédent
     * @param handle le rang du handle du véhicule
     * @param generation sa génération
     * @param rootNode le node de son corps racine
     * @param wheelNodes les nodes des roues, dans l'ordre de la definition
     * @param variableNames les noms des variables
     * @param variableValues leurs valeurs, après l'entrée du tick
     * @param table les paramètres du tick, avant les sorties des contrôleurs
     * @param input l'entrée du conducteur pour ce tick
     */
    public static VehicleSnapshot view(dev.axion.physics.CollectResult result, int handle, int generation, int rootNode,
            List<String> wheelNodes, List<String> variableNames, double[] variableValues, AssemblyParameters table,
            VehicleInput input) {
        if (table == null || wheelNodes.isEmpty()) {
            return null;
        }
        dev.axion.physics.BodyState root = null;
        for (dev.axion.physics.BodyState state : result.bodies()) {
            if (state.handleIndex() == handle && state.handleGeneration() == generation && state.node() == rootNode) {
                root = state;
                break;
            }
        }
        dev.axion.physics.VehicleReport engine = null;
        for (dev.axion.physics.VehicleReport report : result.vehicles()) {
            if (report.handleIndex() == handle && report.handleGeneration() == generation) {
                engine = report;
            }
        }
        if (root == null || engine == null) {
            return null;
        }
        VehicleSnapshot.Wheel[] wheels = new VehicleSnapshot.Wheel[wheelNodes.size()];
        for (dev.axion.physics.WheelReport wheel : result.wheels()) {
            if (wheel.handleIndex() == handle && wheel.handleGeneration() == generation && wheel.index() >= 0
                    && wheel.index() < wheels.length) {
                wheels[wheel.index()] = new VehicleSnapshot.Wheel(wheelNodes.get(wheel.index()), wheel.onGround(),
                        wheel.compression(), wheel.spinSpeed(), wheel.steerAngle(), wheel.slipLong(), wheel.slipLat());
            }
        }
        List<VehicleSnapshot.Wheel> list = new ArrayList<>(wheels.length);
        for (int i = 0; i < wheels.length; i++) {
            list.add(wheels[i] != null ? wheels[i]
                    : new VehicleSnapshot.Wheel(wheelNodes.get(i), false, 0.0, 0.0, 0.0, 0.0, 0.0));
        }
        return snapshot(Interpolation.forwardSpeed(root.linearVelocity(), root.rotation()), engine.rpm(),
                engine.gear(), list, variableNames, variableValues, table, input);
    }

    /**
     * {@return la compression de repos de chaque roue, relevée d'un même pas : brute dans le bloc, relative dans les
     * rapports ; {@code null} s'il manque l'une ou l'autre} Constante d'un véhicule : celle de son modèle posé.
     *
     * @param state le bloc exporté du pas
     * @param result la collecte du même pas
     * @param handle le rang du handle du véhicule
     * @param generation sa génération
     */
    public static float[] settled(byte[] state, dev.axion.physics.CollectResult result, int handle, int generation) {
        AssemblyStateView.Vehicle vehicle = AssemblyStateView.vehicle(state);
        if (vehicle == null) {
            return null;
        }
        float[] settled = new float[vehicle.wheels().size()];
        boolean[] seen = new boolean[settled.length];
        for (dev.axion.physics.WheelReport wheel : result.wheels()) {
            if (wheel.handleIndex() == handle && wheel.handleGeneration() == generation && wheel.index() >= 0
                    && wheel.index() < settled.length) {
                float raw = Math.max(-1f, Math.min(1f, vehicle.wheels().get(wheel.index()).compression()));
                settled[wheel.index()] = raw - wheel.compression();
                seen[wheel.index()] = true;
            }
        }
        for (boolean wheel : seen) {
            if (!wheel) {
                return null;
            }
        }
        return settled;
    }

    private static VehicleSnapshot snapshot(double speed, float rpm, int gear, List<VehicleSnapshot.Wheel> wheels,
            List<String> variableNames, double[] variableValues, AssemblyParameters table, VehicleInput input) {
        double[] parameters = new double[table.tuning().size()];
        for (int i = 0; i < parameters.length; i++) {
            parameters[i] = table.uncontrolled(i);
        }
        DriverInput driver = new DriverInput(input.throttle(), input.brake(), input.steer(), input.clutch(),
                input.handbrake(), input.gear());
        return new VehicleSnapshot(speed, rpm, gear, driver, wheels, variableNames, variableValues,
                table.tuning().names(), parameters);
    }

    /**
     * {@return ce que les contrôleurs rendent pour ce tick}
     *
     * @param attached les contrôleurs du véhicule, dans leur ordre
     * @param view la vue du tick
     */
    public ControllerRunner.Outcome run(List<VehicleExtensions.Controller> attached, VehicleSnapshot view) {
        return runner.run(attached, view);
    }
}
