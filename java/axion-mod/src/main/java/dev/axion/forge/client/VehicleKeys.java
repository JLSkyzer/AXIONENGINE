package dev.axion.forge.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.axion.AxionMod;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/**
 * Les touches de boîte d'un véhicule (ADR-159 D6) : monter et descendre d'un rapport, pour une boîte
 * {@code manual} ; et les huit commandes génériques (ADR-163 D2), que les {@code bindings} d'un siège lient à des
 * variables. Réassignables dans les options du jeu ; le reste de la conduite passe par les touches de déplacement du
 * joueur. Forge n'accepte de touche qu'au démarrage, avant que les definitions n'arrivent : les commandes sont donc
 * génériques, et chaque definition dit ce qu'elle en fait.
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

    /** Touches par défaut des commandes 1 à 8 (ADR-163 Q1) : G, H, J, K, Y, U, B, N, libres dans le jeu de base. */
    private static final int[] COMMAND_KEYS = {
        GLFW.GLFW_KEY_G, GLFW.GLFW_KEY_H, GLFW.GLFW_KEY_J, GLFW.GLFW_KEY_K,
        GLFW.GLFW_KEY_Y, GLFW.GLFW_KEY_U, GLFW.GLFW_KEY_B, GLFW.GLFW_KEY_N,
    };

    /** Les commandes génériques 1 à 8 (ADR-163 D2), au rang {@code commande - 1}. */
    public static final List<KeyMapping> COMMANDS = commands();

    private static List<KeyMapping> commands() {
        List<KeyMapping> commands = new ArrayList<>(COMMAND_KEYS.length);
        for (int rank = 0; rank < COMMAND_KEYS.length; rank++) {
            commands.add(new KeyMapping("key.axion.command_" + (rank + 1), KeyConflictContext.IN_GAME,
                    InputConstants.Type.KEYSYM, COMMAND_KEYS[rank], CATEGORY));
        }
        return List.copyOf(commands);
    }

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
        COMMANDS.forEach(event::register);
    }

    /** {@return les commandes tenues, un bit par commande : le bit 0 pour la commande 1} */
    static int heldCommands() {
        int held = 0;
        for (int rank = 0; rank < COMMANDS.size(); rank++) {
            if (COMMANDS.get(rank).isDown()) {
                held |= 1 << rank;
            }
        }
        return held;
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
