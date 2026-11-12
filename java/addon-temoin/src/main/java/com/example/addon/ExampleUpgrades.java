package com.example.addon;

import dev.axion.api.assembly.Assembly;
import dev.axion.api.assembly.Modifier;
import dev.axion.api.assembly.Parameters;

/**
 * An upgrade kit, fitted the way an upgrades mod does it: one cause in the mod's namespace, one modifier per parameter
 * it changes, and one call to take it all off. The addon guide quotes it (page "Tune a vehicle").
 */
public final class ExampleUpgrades {

    // --8<-- [start:cause]
    /** The stage 1 kit: one cause, in this mod's namespace. */
    public static final String STAGE_ONE = ExampleAddon.MOD_ID + ":stage_one";
    // --8<-- [end:cause]

    private ExampleUpgrades() {}

    // --8<-- [start:fit]
    /**
     * Fits the stage 1 kit: 25 % more engine torque, 25 % less mass. An assembly only has the parameters its
     * definition declares: a crate has a mass, but no engine. Server thread only.
     *
     * @param assembly the vehicle, or any assembly
     * @throws IllegalArgumentException if a value would leave its bounds: the kit is then not fitted at all
     */
    public static void fitStageOne(Assembly assembly) {
        Parameters parameters = assembly.parameters();
        try {
            if (parameters.has(Parameters.ENGINE_TORQUE)) {
                parameters.modify(new Modifier(STAGE_ONE, Parameters.ENGINE_TORQUE,
                        Modifier.Operation.MULTIPLY_BASE, 0.25));
            }
            if (parameters.has(Parameters.MASS)) {
                parameters.modify(new Modifier(STAGE_ONE, Parameters.MASS,
                        Modifier.Operation.MULTIPLY_TOTAL, -0.25));
            }
        } catch (IllegalArgumentException refused) {
            // All or nothing: a kit half fitted would be a kit nobody sold.
            parameters.remove(STAGE_ONE);
            throw refused;
        }
    }
    // --8<-- [end:fit]

    // --8<-- [start:remove]
    /**
     * Takes the stage 1 kit off, on every parameter it touched. The other mods' modifiers stay.
     *
     * @param assembly the vehicle, or any assembly
     */
    public static void removeStageOne(Assembly assembly) {
        assembly.parameters().remove(STAGE_ONE);
    }
    // --8<-- [end:remove]
}
