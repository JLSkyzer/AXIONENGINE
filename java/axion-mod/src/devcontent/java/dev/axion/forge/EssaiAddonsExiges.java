package dev.axion.forge;

import dev.axion.AxionMod;
import dev.axion.content.RegisteredContent;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ADR-165 D3, essai de bout en bout : un vrai serveur dédié exige un addon que le vrai client n'a pas, et le client
 * affiche le refus. Inerte sans ses propriétés ; le contenu de développement ne part jamais dans le mod livré.
 *
 * <ul>
 *   <li>Le serveur ({@code runServer}), sous la variable d'environnement
 *       {@code AXION_ESSAI_ADDON_EXIGE=<modId>@<version>}, que Gradle transmet au jeu, ajoute cet addon à ceux qu'il
 *       exige, puis s'arrête seul au bout de {@link #DUREE_SERVEUR_S} secondes.</li>
 *   <li>Le client, sous {@code -Daxion.essai.deconnexion=true}, se connecte au serveur depuis l'écran titre, par le
 *       chemin de l'écran multijoueur ({@code --quickPlayMultiplayer} ouvre une connexion que Forge traite en vanilla,
 *       constaté le 2026-11-11), écrit au journal le texte de l'écran de déconnexion, tel que le joueur le lit, puis
 *       s'arrête.</li>
 * </ul>
 */
public final class EssaiAddonsExiges {

    private static final Logger LOGGER = LoggerFactory.getLogger("AXION essai");

    /** L'addon que le serveur exige en plus, {@code <modId>@<version>}, ou {@code null}. */
    private static final String EXIGE = System.getenv("AXION_ESSAI_ADDON_EXIGE");

    /** Vrai pour le client de l'essai. */
    private static final boolean DECONNEXION = Boolean.getBoolean("axion.essai.deconnexion");

    /** Le temps laissé au client pour se connecter, au-delà duquel le serveur d'essai s'arrête. */
    static final int DUREE_SERVEUR_S = 300;

    /** L'adresse du serveur de {@code runServer} : une adresse IPv4, comme la base de connaissances le recommande. */
    private static final String SERVEUR = "127.0.0.1:25599";

    private EssaiAddonsExiges() {
        throw new AssertionError("classe utilitaire, non instanciable");
    }

    /** Le serveur : l'addon exigé en plus, puis l'arrêt. */
    @Mod.EventBusSubscriber(modid = AxionMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class Serveur {

        private Serveur() {}

        /** Après l'enregistrement du contenu : l'addon de l'essai s'ajoute aux propriétaires. */
        @SubscribeEvent
        public static void fin(FMLLoadCompleteEvent event) {
            if (EXIGE == null) {
                return;
            }
            String[] parts = EXIGE.split("@", 2);
            Map<String, String> owners = new TreeMap<>(RegisteredContent.owners());
            owners.put(parts[0], parts.length > 1 ? parts[1] : "1.0.0");
            RegisteredContent.publishOwners(owners);
            LOGGER.info("[essai] addon exigé en plus : {}", EXIGE);
        }
    }

    /** L'arrêt du serveur d'essai, faute de quoi la tâche Gradle ne rendrait jamais la main. */
    @Mod.EventBusSubscriber(modid = AxionMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
    public static final class Arret {

        private static long debut;

        private Arret() {}

        @SubscribeEvent
        public static void tick(TickEvent.ServerTickEvent event) {
            if (EXIGE == null || event.phase != TickEvent.Phase.END) {
                return;
            }
            MinecraftServer serveur = event.getServer();
            long maintenant = System.nanoTime();
            if (debut == 0) {
                debut = maintenant;
            }
            if (maintenant - debut > DUREE_SERVEUR_S * 1_000_000_000L) {
                LOGGER.info("[essai] fin du serveur d'essai");
                serveur.halt(false);
            }
        }
    }

    /** Le client : le texte de l'écran de déconnexion, puis l'arrêt. */
    @Mod.EventBusSubscriber(modid = AxionMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
    public static final class Client {

        private static boolean connecte;

        /** Les ticks qui restent avant la capture de l'écran de déconnexion et l'arrêt ; 0 tant qu'il n'est pas là. */
        private static int avantCapture;

        private Client() {}

        /**
         * La connexion, une fois le client chargé : écran titre affiché, plus d'écran de chargement. Pendant le
         * chargement, le client ne répondait pas aux paquets de connexion de Forge (constaté le 2026-11-11). Puis,
         * l'écran de déconnexion venu, sa capture et l'arrêt.
         */
        @SubscribeEvent
        public static void tick(TickEvent.ClientTickEvent event) {
            Minecraft minecraft = Minecraft.getInstance();
            if (DECONNEXION && avantCapture > 0 && event.phase == TickEvent.Phase.END && --avantCapture == 0) {
                Screenshot.grab(minecraft.gameDirectory, "essai_addons_exiges.png", minecraft.getMainRenderTarget(),
                        message -> LOGGER.info("[essai] capture : {}", message.getString()));
                minecraft.stop();
                return;
            }
            if (!DECONNEXION || connecte || event.phase != TickEvent.Phase.END || minecraft.getOverlay() != null
                    || !(minecraft.screen instanceof TitleScreen titre)) {
                return;
            }
            connecte = true;
            ConnectScreen.startConnecting(titre, minecraft, ServerAddress.parseString(SERVEUR),
                    new ServerData("essai", SERVEUR, false), false);
        }

        @SubscribeEvent
        public static void ecran(ScreenEvent.Opening event) {
            if (!DECONNEXION || !(event.getNewScreen() instanceof DisconnectedScreen ecran)) {
                return;
            }
            LOGGER.info("[essai] écran de déconnexion : {}", raison(ecran).getString().replace('\n', '|'));
            // Une seconde d'écran, puis sa capture et l'arrêt.
            avantCapture = 20;
        }

        /** {@return la raison qu'affiche l'écran} Elle n'a pas d'accesseur : le champ, lu par réflexion. */
        private static Component raison(DisconnectedScreen ecran) {
            try {
                Field champ = DisconnectedScreen.class.getDeclaredField("reason");
                champ.setAccessible(true);
                return (Component) champ.get(ecran);
            } catch (ReflectiveOperationException faute) {
                return Component.literal("raison illisible : " + faute);
            }
        }
    }
}
