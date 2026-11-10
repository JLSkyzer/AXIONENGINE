package dev.axion.forge.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.axion.AxionMod;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/**
 * Les touches de boîte d'un véhicule (ADR-159 D6) : monter et descendre d'un rapport, pour une boîte
 * {@code manual}. Réassignables dans les options du jeu ; le reste de la conduite passe par les touches de
 * déplacement du joueur.
 */
@Mod.EventBusSubscriber(modid = AxionMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class VehicleKeys {

    /** Catégorie des touches d'AXION dans les options. */
    static final String CATEGORY = "key.categories.axion";

    /** Rapport supérieur. */
    public static final KeyMapping GEAR_UP = new KeyMapping(
            "key.axion.gear_up", KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, CATEGORY);

    /** Rapport inférieur. */
    public static final KeyMapping GEAR_DOWN = new KeyMapping(
            "key.axion.gear_down", KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F, CATEGORY);

    private VehicleKeys() {}

    /**
     * Déclare les touches au jeu.
     *
     * @param event enregistrement des touches
     */
    @SubscribeEvent
    public static void register(RegisterKeyMappingsEvent event) {
        event.register(GEAR_UP);
        event.register(GEAR_DOWN);
    }

    /**
     * {@return le nombre d'appuis sur une touche depuis le dernier appel}
     *
     * @param key la touche
     */
    static int presses(KeyMapping key) {
        int presses = 0;
        while (key.consumeClick()) {
            presses++;
        }
        return presses;
    }
}
