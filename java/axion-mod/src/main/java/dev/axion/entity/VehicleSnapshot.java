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
     * @param wear l'usure de son pneu, de 0 à 1 (ADR-176 D4)
     * @param material l'identifiant du matériau qu'elle touche (ADR-181), {@code null} en l'air ou sans matériau
     */
    public record Wheel(String node, boolean grounded, double compression, double spin, double steer,
            double slipLong, double slipLat, double wear, String material) implements WheelView {

        /** Une roue dont le matériau n'est pas connu. */
        public Wheel(String node, boolean grounded, double compression, double spin, double steer, double slipLong,
                double slipLat, double wear) {
            this(node, grounded, compression, spin, steer, slipLong, slipLat, wear, null);
        }

        /** Refuse un node absent. */
        public Wheel {
            Objects.requireNonNull(node, "node");
        }

        @Override
        public java.util.Optional<net.minecraft.resources.ResourceLocation> surface() {
            return java.util.Optional.ofNullable(material)
                    .map(net.minecraft.resources.ResourceLocation::tryParse);
        }

        /** Une roue au pneu neuf. */
        public Wheel(String node, boolean grounded, double compression, double spin, double steer, double slipLong,
                double slipLat) {
            this(node, grounded, compression, spin, steer, slipLong, slipLat, 0.0);
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
    private final java.util.function.ToDoubleFunction<String> sources;

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
        this.sources = null;
    }

    private VehicleSnapshot(VehicleSnapshot from, java.util.function.ToDoubleFunction<String> sources) {
        this.speed = from.speed;
        this.rpm = from.rpm;
        this.gear = from.gear;
        this.input = from.input;
        this.wheels = from.wheels;
        this.variableNames = from.variableNames;
        this.variableValues = from.variableValues;
        this.parameterNames = from.parameterNames;
        this.parameterValues = from.parameterValues;
        this.sources = Objects.requireNonNull(sources, "sources");
    }

    /**
     * {@return la même vue, qui lit les sources procédurales par {@code sources}} (ADR-173 D1).
     *
     * @param sources lit une source de l'ANNEXE A.4 par son nom, au début du tick ; lève
     *     {@link IllegalArgumentException} pour un nom inconnu
     */
    public VehicleSnapshot withSources(java.util.function.ToDoubleFunction<String> sources) {
        return new VehicleSnapshot(this, sources);
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

    @Override
    public double source(String name) {
        Objects.requireNonNull(name, "name");
        if (sources == null) {
            throw new UnsupportedOperationException("source « " + name + " » : cette vue n'a pas de trame");
        }
        return sources.applyAsDouble(name);
    }
}
