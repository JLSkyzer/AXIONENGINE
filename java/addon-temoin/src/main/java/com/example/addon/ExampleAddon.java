package com.example.addon;

import dev.axion.api.content.AxionContent;
import dev.axion.api.content.ContentProvider;
import dev.axion.api.content.DefinitionBuilder;
import net.minecraftforge.fml.InterModComms;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.InterModEnqueueEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * An AXION addon: an ordinary Forge mod that sends its content to AXION.
 *
 * <p>This mod is the witness addon of AXION ENGINE (CAP-49): it is compiled against {@code axion-api} only, like any
 * third-party addon, and the addon guide quotes it. Copy it, rename it, and replace its content with yours.
 */
@Mod(ExampleAddon.MOD_ID)
public final class ExampleAddon {

    /** The mod id, which is also the namespace of everything this mod declares: {@code example_addon:crate}. */
    public static final String MOD_ID = "example_addon";

    // --8<-- [start:constructor]
    public ExampleAddon() {
        // On Forge 47, this is how a mod reaches its event bus.
        FMLJavaModLoadingContext.get().getModEventBus()
                .addListener(ExampleAddon::sendContent);
    }
    // --8<-- [end:constructor]

    // --8<-- [start:provider]
    /** Sends AXION what this mod declares, through Forge's inter-mod messages. */
    private static void sendContent(InterModEnqueueEvent event) {
        InterModComms.sendTo(AxionContent.MOD_ID, AxionContent.REGISTER,
                () -> (ContentProvider) registrar -> {
                    // Everything under src/main/resources/axion/: definitions, models...
                    registrar.registerFolder("axion/");
                    // A definition can also be written in Java, with the builder.
                    registrar.register("heavy_crate",
                            DefinitionBuilder.rigidObject("example_addon:models/crate.gltf")
                                    .physics(physics -> physics.mass(400))
                                    .build());
                    // A vehicle controller, and the parameter it reads.
                    ExampleSpeedLimiter.register(registrar);
                });
    }
    // --8<-- [end:provider]
}
