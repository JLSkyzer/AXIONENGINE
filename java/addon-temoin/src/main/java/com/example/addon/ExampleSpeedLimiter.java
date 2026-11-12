package com.example.addon;

import dev.axion.api.assembly.Modifier;
import dev.axion.api.assembly.Parameters;
import dev.axion.api.content.ContentRegistrar;
import dev.axion.api.vehicle.VehicleController;

/**
 * A vehicle controller: a speed limiter that cuts engine torque past a top speed. It reads the vehicle at the start of
 * the tick and writes, for this tick only, one modifier; its top speed is a parameter of this mod, which exists on any
 * vehicle the limiter is attached to. The addon guide quotes it (page "Tune a vehicle").
 */
public final class ExampleSpeedLimiter {

    // --8<-- [start:ids]
    /** The controller, in this mod's namespace: a definition names it, or the API attaches it. */
    public static final String CONTROLLER = ExampleAddon.MOD_ID + ":speed_limiter";

    /** Its top speed, a parameter of this mod: tuned like AXION's own, within its limits. */
    public static final String MAX_SPEED = ExampleAddon.MOD_ID + ":max_speed";
    // --8<-- [end:ids]

    private ExampleSpeedLimiter() {}

    // --8<-- [start:controller]
    /**
     * Past the top speed, takes engine torque away: all of it half a metre per second above. A pure function of what it
     * reads, with no hidden state: every limited vehicle can share it.
     */
    public static final VehicleController LIMITER = (vehicle, out) -> {
        if (!vehicle.hasParameter(Parameters.ENGINE_TORQUE)) {
            return; // No engine to limit.
        }
        double excess = vehicle.speed() - vehicle.parameter(MAX_SPEED);
        if (excess > 0) {
            out.modify(Parameters.ENGINE_TORQUE, Modifier.Operation.MULTIPLY_TOTAL, -Math.min(1.0, excess / 0.5));
        }
    };
    // --8<-- [end:controller]

    // --8<-- [start:register]
    /**
     * Registers the limiter and its top speed, during this mod's registration phase: 15 m/s unless tuned, between
     * 1 and 100 m/s.
     *
     * @param registrar this mod's registrar
     */
    static void register(ContentRegistrar registrar) {
        registrar.registerController("speed_limiter", LIMITER);
        registrar.registerParameter("max_speed", "m/s", 15.0, new Parameters.Range(1.0, true, 100.0, true));
    }
    // --8<-- [end:register]
}
