package dev.axion.forge;

import dev.axion.AxionMod;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.LoggerFactory;

/**
 * Un client de développement qui ne capture jamais la souris : sous {@code -Daxion.souris.libre=true}, Minecraft ne
 * l'enferme plus dans sa fenêtre. Piloté à distance en plein écran, le poste y resterait bloqué. Rien ici ne part
 * dans le mod livré ; les lancements Gradle qui posent la propriété sont dans {@code build.gradle}.
 *
 * <p>{@code MouseHandler.grabMouse()} ne capture que si la fenêtre se dit active ({@code Minecraft.isWindowActive}) :
 * elle est donc tenue pour inactive à chaque tick, à chaque image, et juste avant qu'un écran se ferme — la fermeture
 * d'un écran est ce qui fait capturer. Cet état ne sert sinon qu'à la pause au focus perdu, coupée dans ces lancements
 * ({@code pauseOnLostFocus:false}), et à tourner la vue à la souris, qu'aucun film ni banc ne lit. Deux filets en plus :
 * un clic hors de tout écran n'arrive pas au jeu, et une capture qui passerait quand même est relâchée à l'image.
 */
@Mod.EventBusSubscriber(modid = AxionMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class SourisLibre {

    private static final boolean ACTIVE = Boolean.getBoolean("axion.souris.libre");

    static {
        if (ACTIVE) {
            LoggerFactory.getLogger("AXION dev").info("souris libre : le client ne la capture pas");
        }
    }

    private SourisLibre() {}

    /** Avant et après chaque image. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void image(TickEvent.RenderTickEvent event) {
        liberer();
    }

    /** Avant et après chaque tick du client. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void tick(TickEvent.ClientTickEvent event) {
        liberer();
    }

    /** Un écran qui se ferme fait capturer la souris juste après : la fenêtre se dit inactive avant. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void fermeture(ScreenEvent.Closing event) {
        liberer();
    }

    /** Un clic dans le monde ferait capturer la souris : il n'arrive pas au jeu. Les écrans le reçoivent. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void clic(InputEvent.MouseButton.Pre event) {
        if (ACTIVE && Minecraft.getInstance().screen == null) {
            event.setCanceled(true);
        }
    }

    private static void liberer() {
        if (!ACTIVE) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.setWindowActive(false);
        if (minecraft.mouseHandler.isMouseGrabbed()) {
            minecraft.mouseHandler.releaseMouse();
        }
    }
}
