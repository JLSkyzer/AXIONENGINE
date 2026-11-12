package com.example.addon;

import dev.axion.api.assembly.Assembly;
import dev.axion.api.assembly.Modifier;
import dev.axion.api.assembly.Parameters;
import dev.axion.api.testing.AxionGameTests;
import dev.axion.api.vehicle.DriverInput;
import dev.axion.api.vehicle.VehicleControl;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * The addon's GameTests: they run in a real game server with `./gradlew runGameTestServer`, and never in a player's
 * game. AXION provides the helpers and the slab the tests stand on.
 */
@GameTestHolder(ExampleAddon.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ExampleAddonGameTests {

    private ExampleAddonGameTests() {}

    // --8<-- [start:test]
    /** The JSON crate is loaded, falls onto the slab and comes to rest. */
    @GameTest(template = AxionGameTests.DALLE, timeoutTicks = 200)
    public static void crateFallsAndRests(GameTestHelper helper) {
        AxionGameTests.assertLoaded(helper, "example_addon:crate");
        Assembly crate = AxionGameTests.spawn(helper, "example_addon:crate",
                new Vec3(4.5, AxionGameTests.DESSUS_DALLE + 3, 4.5));
        AxionGameTests.succeedWhenAtRest(helper, crate);
    }
    // --8<-- [end:test]

    /** The crate written in Java with the builder is loaded too. */
    @GameTest(template = AxionGameTests.DALLE)
    public static void heavyCrateIsLoaded(GameTestHelper helper) {
        AxionGameTests.assertLoaded(helper, "example_addon:heavy_crate");
        helper.succeed();
    }

    // --8<-- [start:upgrade]
    /** The stage 1 kit lightens the crate; another mod cannot take it out of its range; the kit comes off. */
    @GameTest(template = AxionGameTests.DALLE, timeoutTicks = 100)
    public static void stageOneKitFitsAndComesOff(GameTestHelper helper) {
        AxionGameTests.assertLoaded(helper, "example_addon:upgradable_crate");
        Assembly crate = AxionGameTests.spawn(helper, "example_addon:upgradable_crate",
                new Vec3(4.5, AxionGameTests.DESSUS_DALLE + 1, 4.5));
        Parameters parameters = crate.parameters();
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(parameters.has(Parameters.MASS), "not compiled yet"))
                .thenExecute(() -> {
                    ExampleUpgrades.fitStageOne(crate);
                    helper.assertTrue(parameters.value(Parameters.MASS) == 150.0, "200 kg, 25 % lighter");
                    try {
                        // (200 - 100) x 0.75 = 75 kg: below the 100 kg the definition allows.
                        parameters.modify(new Modifier("other_mod:hollow", Parameters.MASS,
                                Modifier.Operation.ADD, -100));
                        helper.fail("75 kg is out of the definition's range");
                    } catch (IllegalArgumentException refused) {
                        helper.assertTrue(parameters.value(Parameters.MASS) == 150.0, "refused, never clamped");
                    }
                    ExampleUpgrades.removeStageOne(crate);
                    helper.assertTrue(parameters.value(Parameters.MASS) == 200.0, "back to the definition's mass");
                })
                .thenSucceed();
    }
    // --8<-- [end:upgrade]

    // --8<-- [start:ballast]
    /** A variable of the sheet tunes the mass: each ballast step adds 40 kg, and the kit composes with it. */
    @GameTest(template = AxionGameTests.DALLE, timeoutTicks = 100)
    public static void ballastTunesTheMass(GameTestHelper helper) {
        Assembly crate = AxionGameTests.spawn(helper, "example_addon:upgradable_crate",
                new Vec3(4.5, AxionGameTests.DESSUS_DALLE + 1, 4.5));
        Parameters parameters = crate.parameters();
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(parameters.has(Parameters.MASS), "not compiled yet"))
                .thenExecute(() -> {
                    crate.variables().set("ballast", 2);
                    helper.assertTrue(parameters.value(Parameters.MASS) == 280.0, "200 kg + 2 x 40 kg");
                    ExampleUpgrades.fitStageOne(crate);
                    helper.assertTrue(parameters.value(Parameters.MASS) == 210.0, "(200 + 80) x 0.75");
                    ExampleUpgrades.removeStageOne(crate);
                    crate.variables().set("ballast", 0);
                    helper.assertTrue(parameters.value(Parameters.MASS) == 200.0, "back to the sheet's mass");
                })
                .thenSucceed();
    }
    // --8<-- [end:ballast]

    // --8<-- [start:limiter]
    /**
     * The speed limiter on AXION's example buggy (loaded when {@code content.examples} is on, as on AXION's test
     * server), driven flat out by this test: past the top speed, the engine is cut.
     */
    @GameTest(template = AxionGameTests.DALLE, timeoutTicks = 200)
    public static void speedLimiterCapsTheBuggy(GameTestHelper helper) {
        AxionGameTests.assertLoaded(helper, "axion:example/buggy");
        // Its four wheels on the slab, room ahead (its front is -Z).
        Assembly buggy = AxionGameTests.spawn(helper, "axion:example/buggy",
                new Vec3(4.5, AxionGameTests.DESSUS_DALLE, 6.0));
        double[] fastest = {0};
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(buggy.parameters().has(Parameters.ENGINE_TORQUE),
                        "not compiled yet"))
                .thenExecute(() -> {
                    helper.assertTrue(!buggy.parameters().has(ExampleSpeedLimiter.MAX_SPEED), "no limiter, no top speed");
                    VehicleControl vehicle = buggy.vehicle().orElseThrow();
                    vehicle.attach(ExampleSpeedLimiter.CONTROLLER);
                    // Its parameter now exists on this buggy: 15 m/s, tuned down to 1 m/s, its lowest, so that the
                    // slab is enough.
                    buggy.parameters().modify(new Modifier(ExampleAddon.MOD_ID + ":slow_zone",
                            ExampleSpeedLimiter.MAX_SPEED, Modifier.Operation.ADD, -14));
                    // Flat out in first gear (the buggy's gearbox is manual), the parking brake released.
                    vehicle.drive(new DriverInput(1, 0, 0, 0, 0, 1));
                })
                .thenExecuteFor(60, () -> fastest[0] = Math.max(fastest[0],
                        buggy.vehicle().orElseThrow().state().speed()))
                .thenExecute(() -> {
                    var state = buggy.vehicle().orElseThrow().state();
                    helper.assertTrue(fastest[0] > 0.8, "it drives: " + fastest[0] + " m/s, " + state.rpm() + " rpm,"
                            + " gear " + state.gear() + ", " + state.input() + ", grounded "
                            + state.wheels().stream().filter(wheel -> wheel.grounded()).count());
                    // Without the limiter, it passes 3 m/s in these three seconds.
                    helper.assertTrue(fastest[0] < 1.0 + 0.75, "capped near 1 m/s: " + fastest[0] + " m/s");
                    buggy.vehicle().orElseThrow().drive(new DriverInput(0, 0, 0, 0, 1, 0)); // Handbrake on.
                })
                .thenSucceed();
    }
    // --8<-- [end:limiter]
}
