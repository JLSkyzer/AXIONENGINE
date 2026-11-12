package com.example.addon;

import dev.axion.api.assembly.Assembly;
import dev.axion.api.assembly.Modifier;
import dev.axion.api.assembly.Parameters;
import dev.axion.api.testing.AxionGameTests;
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
}
