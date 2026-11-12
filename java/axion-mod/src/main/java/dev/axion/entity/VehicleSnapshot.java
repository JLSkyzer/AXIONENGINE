package dev.axion.entity;

import dev.axion.api.vehicle.DriverInput;
import dev.axion.api.vehicle.VehicleView;
import dev.axion.api.vehicle.WheelView;
import java.util.List;
import java.util.Objects;

/**
 * L'état d'un véhicule au début d'un tick (ADR-167 D9, R-651) : ce que ses contrôleurs lisent et ce que
 * {@code VehicleControl.state()} rend. Figé à sa construction ; logique pure, sans Forge.
 *
 * <p>Les paramètres y sont composés <strong>sans</strong> les sorties des contrôleurs : chaque contrôleur lit la même
 * valeur, quel que soit leur ordre.
 */
public final class VehicleSnapshot implements VehicleView {

    /**
     * Une roue au début du tick.
     *
     * @param node son node dans le modèle
     * @param grounded elle touche le sol
     * @param compression sa compression, en fraction du débattement
     * @param spin sa vitesse de rotation, en rad/s
     * @param steer son braquage, en radians
     * @param slipLong son glissement longitudinal
     * @param slipLat son glissement latéral, en radians
     */
    public record Wheel(String node, boolean grounded, double compression, double spin, double steer,
            double slipLong, double slipLat) implements WheelView {

        /** Refuse un node absent. */
        public Wheel {
            Objects.requireNonNull(node, "node");
        }
    }

    private final double speed;
    private final double rpm;
    private final int gear;
    private final DriverInput input;
    private final List<WheelView> wheels;
    private final List<String> variableNames;
    private final double[] variableValues;
    private final List<String> parameterNames;
    private final double[] parameterValues;

    /**
     * @param speed sa vitesse le long de son avant, en m/s
     * @param rpm le régime de son moteur
     * @param gear le rapport engagé
     * @param input l'entrée qui le conduit
     * @param wheels ses roues, dans l'ordre de la definition
     * @param variableNames les noms de ses variables, dans l'ordre de la definition
     * @param variableValues leurs valeurs, dans le même ordre
     * @param parameterNames les noms de ses paramètres
     * @param parameterValues leurs valeurs composées sans les sorties des contrôleurs, dans le même ordre
     */
    public VehicleSnapshot(double speed, double rpm, int gear, DriverInput input, List<? extends WheelView> wheels,
            List<String> variableNames, double[] variableValues, List<String> parameterNames,
            double[] parameterValues) {
        if (variableNames.size() != variableValues.length || parameterNames.size() != parameterValues.length) {
            throw new IllegalArgumentException("autant de valeurs que de noms attendues");
        }
        this.speed = speed;
        this.rpm = rpm;
        this.gear = gear;
        this.input = Objects.requireNonNull(input, "input");
        this.wheels = List.copyOf(wheels);
        this.variableNames = List.copyOf(variableNames);
        this.variableValues = variableValues.clone();
        this.parameterNames = List.copyOf(parameterNames);
        this.parameterValues = parameterValues.clone();
    }

    @Override
    public double speed() {
        return speed;
    }

    @Override
    public double rpm() {
        return rpm;
    }

    @Override
    public int gear() {
        return gear;
    }

    @Override
    public DriverInput input() {
        return input;
    }

    @Override
    public List<WheelView> wheels() {
        return wheels;
    }

    @Override
    public double variable(String name) {
        int index = variableNames.indexOf(Objects.requireNonNull(name, "name"));
        if (index < 0) {
            throw new IllegalArgumentException("variable « " + name + " » inconnue de ce véhicule ; il a : "
                    + String.join(", ", variableNames));
        }
        return variableValues[index];
    }

    /** {@return vrai si la definition déclare cette variable} */
    boolean hasVariable(String name) {
        return variableNames.contains(name);
    }

    @Override
    public double parameter(String name) {
        int index = parameterNames.indexOf(Objects.requireNonNull(name, "name"));
        if (index < 0) {
            throw new IllegalArgumentException("paramètre « " + name + " » inconnu de ce véhicule ; il a : "
                    + String.join(", ", parameterNames));
        }
        return parameterValues[index];
    }

    @Override
    public boolean hasParameter(String name) {
        return parameterNames.contains(name);
    }
}
