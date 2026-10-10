package dev.axion.forge;

import dev.axion.AxionMod;
import dev.axion.lifecycle.AxionRuntime;
import java.io.IOException;
import java.nio.file.Path;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * La trace de la simulation (C-71) ouverte dès le démarrage d'un serveur, sous
 * {@code -Daxion.gametest.trace=<fichier>} : chaque corps, chaque contact et chaque tuile du monde,
 * tick par tick, pendant tout un passage des GameTests. C'est ce qui manque à un test intermittent
 * pour dire pourquoi il échoue — {@code /axion debug trace} ne s'ouvre qu'à la main, une fois le
 * serveur lancé.
 *
 * <p>Contenu de développement, jamais empaqueté (R-1790). Inerte sans la propriété.
 */
@Mod.EventBusSubscriber(modid = AxionMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class TraceDesGameTests {

    private static final Logger LOGGER = LoggerFactory.getLogger("axion.gametest");

    private static final String FICHIER = System.getProperty("axion.gametest.trace");

    private TraceDesGameTests() {}

    /**
     * Ouvre la trace au démarrage du serveur.
     *
     * @param event serveur démarré
     */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        AxionRuntime runtime = RuntimeAccess.get();
        if (FICHIER == null || runtime == null) {
            return;
        }
        try {
            runtime.startTrace(Path.of(FICHIER));
            LOGGER.info("AXION GameTests : trace de la simulation dans {}", FICHIER);
        } catch (IOException echec) {
            LOGGER.warn("AXION GameTests : trace impossible à ouvrir ({}) : {}", FICHIER, echec.getMessage());
        }
    }

    /**
     * Ferme la trace à l'arrêt du serveur.
     *
     * @param event serveur qui s'arrête
     */
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        AxionRuntime runtime = RuntimeAccess.get();
        if (FICHIER != null && runtime != null) {
            runtime.stopTrace();
        }
    }
}
