package dev.axion.forge;

import dev.axion.AxionMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoader;
import net.minecraftforge.fml.ModLoadingWarning;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Essai : ce que le joueur voit à la fin du chargement. Le premier écran, une fois le chargement fini — l'écran des
 * avertissements de Forge s'il y en a, sinon l'écran titre —, est écrit au journal avec les avertissements de
 * chargement, puis capturé, et le jeu s'arrête. Sert à prouver l'avertissement de version d'API (ADR-166 D1).
 *
 * <p>Inerte sans {@code -Daxion.essai.capture.demarrage=true} ({@code ./gradlew runEssaiCaptureDemarrage}) ; le
 * contenu de développement ne part jamais dans le mod livré.
 */
@Mod.EventBusSubscriber(modid = AxionMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class EssaiCaptureDemarrage {

    private static final Logger LOGGER = LoggerFactory.getLogger("AXION essai");

    private static final boolean ACTIF = Boolean.getBoolean("axion.essai.capture.demarrage");

    /** Le temps laissé à l'écran pour s'afficher, avant la capture. */
    private static final int ATTENTE_TICKS = 40;

    private static int vu = -1;

    private EssaiCaptureDemarrage() {}

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!ACTIF || event.phase != TickEvent.Phase.END || minecraft.getOverlay() != null || minecraft.screen == null) {
            return;
        }
        if (vu < 0) {
            vu = 0;
            LOGGER.info("[essai] premier écran : {} — « {} »", minecraft.screen.getClass().getSimpleName(),
                    minecraft.screen.getTitle().getString());
            for (ModLoadingWarning avertissement : ModLoader.get().getWarnings()) {
                LOGGER.info("[essai] avertissement de chargement : {}", avertissement.formatToString());
            }
            return;
        }
        if (++vu == ATTENTE_TICKS) {
            Screenshot.grab(minecraft.gameDirectory, "essai_demarrage.png", minecraft.getMainRenderTarget(),
                    message -> LOGGER.info("[essai] capture : {}", message.getString()));
            minecraft.stop();
        }
    }
}
