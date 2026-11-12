package com.example.addon;

import dev.axion.api.assembly.Assembly;
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
}
