package dev.axion.net;

import dev.axion.physics.BodyBounds;
import dev.axion.physics.BodyState;
import dev.axion.physics.BodyStateFlags;
import dev.axion.physics.CollectResult;
import dev.axion.physics.VehicleReport;
import dev.axion.physics.WheelReport;
import dev.axion.seat.VehicleInput;
import java.util.ArrayList;
import java.util.List;

/**
 * L'état d'un pas du monde prédit, mis en {@link AssemblyState} pour le rendu (ADR-168, 6c-C) : ce que le serveur
 * envoie dans ses snapshots, tiré du même {@code SIM_OUT} et par les mêmes règles — le corps racine pose l'assembly,
 * les autres corps en poses relatives à lui (ADR-151 D8), l'union de leurs emprises (ADR-120), les roues dans l'ordre
 * de la definition (ADR-159 D9), le motopropulseur avec l'entrée tenue (ADR-163 D1).
 */
public final class PredictedStates {

    private PredictedStates() {
        throw new AssertionError("non instanciable");
    }

    /**
     * {@return l'état de l'assembly à ce pas ; {@code null} si le pas ne rapporte pas son corps racine — endormie, ou
     * collecte en échec — ou si sa pose n'est pas finie}
     *
     * @param result la collecte du pas
     * @param handle le rang du handle de l'assembly
     * @param generation sa génération
     * @param rootNode le node de son corps racine
     * @param input l'entrée tenue au pas, bornée
     * @param variables les variables de contenu à porter, celles reçues du serveur
     */
    public static AssemblyState of(CollectResult result, int handle, int generation, int rootNode, VehicleInput input,
            AssemblyState.VariableValues variables) {
        List<BodyState> states = result.bodies();
        List<Integer> own = new ArrayList<>();
        int root = -1;
        for (int i = 0; i < states.size(); i++) {
            BodyState state = states.get(i);
            if (state.handleIndex() != handle || state.handleGeneration() != generation) {
                continue;
            }
            own.add(i);
            if (root < 0 && state.node() == rootNode) {
                root = i;
            }
        }
        if (root < 0) {
            return null;
        }
        BodyState body = states.get(root);
        double[] p = body.position();
        float[] q = body.rotation();
        List<AssemblyState.Body> others = new ArrayList<>(own.size());
        for (int rank : own) {
            if (rank != root) {
                BodyState other = states.get(rank);
                others.add(AssemblyState.Body.relative(other.node(), p, q, other.position(), other.rotation()));
            }
        }
        BodyBounds bounds = result.bounds().size() == states.size()
                ? BodyBounds.union(states, result.bounds(), own, p) : null;
        boolean bounded = bounds != null && bounds.isPlausible();
        try {
            return AssemblyState.of(p, q, body.linearVelocity(), body.angularVelocity(),
                            bounded ? bounds.min() : null, bounded ? bounds.max() : null,
                            (body.flags() & BodyStateFlags.SLEEPING) != 0)
                    .with(others, AssemblyState.JointBits.NONE)
                    .withWheels(wheels(result.wheels(), handle, generation))
                    .withVehicle(vehicle(result.vehicles(), handle, generation, input))
                    .withVariables(variables);
        } catch (IllegalArgumentException notFinite) {
            return null;
        }
    }

    private static AssemblyState.Wheels wheels(List<WheelReport> reports, int handle, int generation) {
        int count = 0;
        for (WheelReport wheel : reports) {
            if (wheel.handleIndex() == handle && wheel.handleGeneration() == generation && wheel.index() >= 0
                    && wheel.index() < AssemblyState.Wheels.MAX_WHEELS) {
                count = Math.max(count, wheel.index() + 1);
            }
        }
        if (count == 0) {
            return AssemblyState.Wheels.NONE;
        }
        float[] compression = new float[count];
        float[] spin = new float[count];
        float[] steer = new float[count];
        boolean[] ground = new boolean[count];
        for (WheelReport wheel : reports) {
            if (wheel.handleIndex() == handle && wheel.handleGeneration() == generation && wheel.index() >= 0
                    && wheel.index() < count) {
                compression[wheel.index()] = wheel.compression();
                spin[wheel.index()] = wheel.spinSpeed();
                steer[wheel.index()] = wheel.steerAngle();
                ground[wheel.index()] = wheel.onGround();
            }
        }
        return AssemblyState.Wheels.of(compression, spin, steer, ground);
    }

    private static AssemblyState.Vehicle vehicle(List<VehicleReport> reports, int handle, int generation,
            VehicleInput input) {
        for (VehicleReport report : reports) {
            if (report.handleIndex() == handle && report.handleGeneration() == generation) {
                return AssemblyState.Vehicle.of(report.rpm(), report.load(), report.gear(), report.flags(),
                        input.throttle(), input.brake(), report.steer(), input.handbrake());
            }
        }
        return AssemblyState.Vehicle.NONE;
    }
}
