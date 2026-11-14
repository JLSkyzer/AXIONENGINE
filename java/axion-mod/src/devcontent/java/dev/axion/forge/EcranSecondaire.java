package dev.axion.forge;

import dev.axion.AxionMod;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Un client de développement qui s'ouvre sur l'écran secondaire : sous {@code -Daxion.ecran.secondaire=true}, la
 * fenêtre se centre, à sa première image, sur le premier écran qui n'est pas le principal. Les jeux qu'un agent lance
 * laissent ainsi l'écran principal à Killian. Sans écran secondaire, ou en plein écran, rien ne bouge. Rien ici ne part
 * dans le mod livré ; les lancements Gradle qui posent la propriété sont dans {@code build.gradle}.
 */
@Mod.EventBusSubscriber(modid = AxionMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class EcranSecondaire {

    private static final Logger LOGGER = LoggerFactory.getLogger("AXION dev");
    private static final boolean ACTIVE = Boolean.getBoolean("axion.ecran.secondaire");
    private static boolean place;

    private EcranSecondaire() {}

    /** À la première image : sur le fil du rendu, seul à toucher la fenêtre GLFW. */
    @SubscribeEvent
    public static void image(TickEvent.RenderTickEvent event) {
        if (!ACTIVE || place || event.phase != TickEvent.Phase.START) {
            return;
        }
        place = true;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getWindow().isFullscreen()) {
            LOGGER.info("écran secondaire : fenêtre en plein écran, laissée où elle est");
            return;
        }
        long principal = GLFW.glfwGetPrimaryMonitor();
        PointerBuffer ecrans = GLFW.glfwGetMonitors();
        if (ecrans == null) {
            return;
        }
        for (int i = 0; i < ecrans.limit(); i++) {
            long ecran = ecrans.get(i);
            if (ecran == principal) {
                continue;
            }
            int[] x = new int[1];
            int[] y = new int[1];
            int[] largeur = new int[1];
            int[] hauteur = new int[1];
            GLFW.glfwGetMonitorWorkarea(ecran, x, y, largeur, hauteur);
            long fenetre = minecraft.getWindow().getWindow();
            int[] l = new int[1];
            int[] h = new int[1];
            GLFW.glfwGetWindowSize(fenetre, l, h);
            int gauche = x[0] + Math.max(0, (largeur[0] - l[0]) / 2);
            int haut = y[0] + Math.max(32, (hauteur[0] - h[0]) / 2);
            GLFW.glfwSetWindowPos(fenetre, gauche, haut);
            LOGGER.info("écran secondaire : fenêtre posée en ({}, {}) sur l'écran {}", gauche, haut,
                    GLFW.glfwGetMonitorName(ecran));
            return;
        }
        LOGGER.info("écran secondaire : aucun, la fenêtre reste sur l'écran principal");
    }
}
