package dev.axion.forge;

import com.mojang.blaze3d.platform.GlUtil;
import dev.axion.AxionMod;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CAP-53, la mesure côté client : un vrai client se connecte au serveur de {@code tools/campagne/mesure.py --client},
 * qui le pose en spectateur devant la grille des véhicules ({@link MesurePerformance}) ; entre les marques « debut » et
 * « fin » que le serveur dit à chaque palier, il relève la durée de chaque image, synchronisation verticale coupée et
 * fréquence illimitée, puis écrit une ligne dans {@code mesure-client.jsonl}. Inerte sans
 * {@code -Daxion.mesure.client=true} ; contenu de développement, jamais livré.
 *
 * <p>Ses abonnés portent des noms à eux : Forge nomme leurs relais d'après le nom court de la classe, de la méthode et
 * de l'événement, et deux {@code Client} se les partagent ({@code EventSubscriberNamesTest}).
 */
public final class MesureClient {

    private static final Logger LOGGER = LoggerFactory.getLogger("AXION mesure");

    private static final boolean ACTIF = Boolean.getBoolean("axion.mesure.client");
    private static final String ADRESSE = "127.0.0.1:25599";
    private static final Pattern MARQUE = Pattern.compile("\\[mesure-client\\] (debut|fin|terminee) ?(\\d*)");
    /** Au-delà, le serveur n'a plus rien dit : le client s'arrête plutôt que de rester ouvert. */
    private static final long SILENCE_MAX_NS = 600L * 1_000_000_000L;

    private MesureClient() {
        throw new AssertionError("classe utilitaire, non instanciable");
    }

    /** Le côté client. */
    @Mod.EventBusSubscriber(modid = AxionMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
    public static final class Client {

        private static boolean connecte;
        private static boolean fini;
        private static long dernierMot = System.nanoTime();
        private static long derniereImage;
        private static int palier = -1;
        private static long debut;
        private static final List<Double> IMAGES = new ArrayList<>();
        private static final List<Double> TICKS = new ArrayList<>();
        /** Au-delà, une image est dite lente ; on compte combien d'entre elles ont porté un tick. */
        private static final double LENTE_MS = 15.0;
        private static long tickCommence;
        private static boolean tickDansLImage;
        private static int lentes;
        private static int lentesAvecTick;
        private static int imagesAvecTick;

        private Client() {}

        /** @param event un tick du client */
        @SubscribeEvent
        public static void mesurerAuTick(TickEvent.ClientTickEvent event) {
            if (!ACTIF || fini || event.phase != TickEvent.Phase.END) {
                return;
            }
            Minecraft minecraft = Minecraft.getInstance();
            if (!connecte && minecraft.getOverlay() == null && minecraft.screen instanceof TitleScreen titre) {
                connecte = true;
                LOGGER.info("[mesure-client] connexion à {}", ADRESSE);
                regler(minecraft);
                ConnectScreen.startConnecting(titre, minecraft, ServerAddress.parseString(ADRESSE),
                        new ServerData("mesure", ADRESSE, false), false);
            }
            if (System.nanoTime() - dernierMot > SILENCE_MAX_NS) {
                arreter(minecraft, "le serveur se tait");
            }
        }

        /** Les réglages de la mesure : rien ne bride ni ne cache les images. */
        private static void regler(Minecraft minecraft) {
            minecraft.options.framerateLimit().set(260);
            minecraft.options.enableVsync().set(false);
            minecraft.options.renderDistance().set(20);
            minecraft.options.hideGui = true;
        }

        /** @param event le début d'un tick du client, avant tout autre abonné */
        @SubscribeEvent(priority = EventPriority.HIGHEST)
        public static void ouvrirLeTick(TickEvent.ClientTickEvent event) {
            if (ACTIF && event.phase == TickEvent.Phase.START) {
                tickCommence = System.nanoTime();
                tickDansLImage = true;
            }
        }

        /** @param event la fin d'un tick du client, après tout autre abonné */
        @SubscribeEvent(priority = EventPriority.LOWEST)
        public static void fermerLeTick(TickEvent.ClientTickEvent event) {
            if (ACTIF && palier >= 0 && event.phase == TickEvent.Phase.END && tickCommence != 0) {
                TICKS.add((System.nanoTime() - tickCommence) / 1e6);
            }
        }

        /** @param event une image rendue */
        @SubscribeEvent
        public static void releverUneImage(TickEvent.RenderTickEvent event) {
            if (!ACTIF || fini || event.phase != TickEvent.Phase.END) {
                return;
            }
            long maintenant = System.nanoTime();
            if (palier >= 0 && derniereImage != 0) {
                double duree = (maintenant - derniereImage) / 1e6;
                IMAGES.add(duree);
                imagesAvecTick += tickDansLImage ? 1 : 0;
                if (duree > LENTE_MS) {
                    lentes++;
                    lentesAvecTick += tickDansLImage ? 1 : 0;
                }
            }
            derniereImage = maintenant;
            tickDansLImage = false;
        }

        /** @param event un message du serveur */
        @SubscribeEvent
        public static void message(ClientChatReceivedEvent.System event) {
            if (!ACTIF || fini) {
                return;
            }
            Matcher marque = MARQUE.matcher(event.getMessage().getString());
            if (!marque.find()) {
                return;
            }
            dernierMot = System.nanoTime();
            Minecraft minecraft = Minecraft.getInstance();
            switch (marque.group(1)) {
                case "debut" -> {
                    palier = Integer.parseInt(marque.group(2));
                    IMAGES.clear();
                    TICKS.clear();
                    lentes = 0;
                    lentesAvecTick = 0;
                    imagesAvecTick = 0;
                    debut = System.nanoTime();
                    derniereImage = 0;
                }
                case "fin" -> relever(minecraft);
                default -> arreter(minecraft, null);
            }
        }

        /** @param event l'écran qui s'ouvre : une déconnexion arrête la mesure */
        @SubscribeEvent
        public static void deconnexionDeLaMesure(ScreenEvent.Opening event) {
            if (ACTIF && !fini && connecte && event.getNewScreen() instanceof DisconnectedScreen ecran) {
                arreter(Minecraft.getInstance(),
                        "déconnecté : " + EssaiAddonsExiges.Client.raison(ecran).getString());
            }
        }

        private static void relever(Minecraft minecraft) {
            if (palier < 0 || IMAGES.isEmpty() || minecraft.level == null) {
                return;
            }
            double secondes = (System.nanoTime() - debut) / 1e9;
            List<Double> tries = new ArrayList<>(IMAGES);
            Collections.sort(tries);
            double moyenne = tries.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            int connues = 0;
            for (Entity entite : minecraft.level.entitiesForRendering()) {
                if (entite instanceof AxionEntity) {
                    connues++;
                }
            }
            Runtime memoire = Runtime.getRuntime();
            String ligne = String.format(Locale.ROOT,
                    "{\"vehicules\": %d, \"connues\": %d, \"images\": %d, \"secondes\": %.2f, \"image_ms_moyenne\": %.3f,"
                            + " \"image_ms_p50\": %.3f, \"image_ms_p95\": %.3f, \"image_ms_p99\": %.3f,"
                            + " \"image_ms_max\": %.3f, \"tas_mo\": %d, \"largeur\": %d, \"hauteur\": %d,"
                            + " \"ticks\": %d, \"tick_ms_moyen\": %.3f, \"tick_ms_p95\": %.3f, \"lentes\": %d,"
                            + " \"lentes_avec_tick\": %d, \"images_avec_tick\": %d,"
                            + " \"carte\": \"%s\"}%n",
                    palier, connues, tries.size(), secondes, moyenne, centile(tries, 0.50), centile(tries, 0.95),
                    centile(tries, 0.99), tries.get(tries.size() - 1),
                    (memoire.totalMemory() - memoire.freeMemory()) >> 20, minecraft.getWindow().getWidth(),
                    minecraft.getWindow().getHeight(), TICKS.size(),
                    TICKS.stream().mapToDouble(Double::doubleValue).average().orElse(0), centileDe(TICKS, 0.95), lentes,
                    lentesAvecTick, imagesAvecTick, GlUtil.getRenderer().replace('"', '\''));
            LOGGER.info("[mesure-client] releve {}", ligne.trim());
            try {
                Files.writeString(rapport(minecraft), ligne, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            } catch (IOException faute) {
                LOGGER.error("[mesure-client] rapport non écrit : {}", faute.toString());
            }
            // Après la fenêtre : ce que le client voyait, pour vérifier que les véhicules étaient à l'écran.
            Screenshot.grab(minecraft.gameDirectory, "mesure-" + palier + ".png", minecraft.getMainRenderTarget(),
                    message -> LOGGER.info("[mesure-client] {}", message.getString()));
            palier = -1;
        }

        private static Path rapport(Minecraft minecraft) {
            return minecraft.gameDirectory.toPath().resolve("mesure-client.jsonl");
        }

        private static double centileDe(List<Double> valeurs, double part) {
            if (valeurs.isEmpty()) {
                return 0;
            }
            List<Double> tries = new ArrayList<>(valeurs);
            Collections.sort(tries);
            return centile(tries, part);
        }

        private static double centile(List<Double> tries, double part) {
            return tries.get(Math.max(0, (int) Math.ceil(part * tries.size()) - 1));
        }

        private static void arreter(Minecraft minecraft, String faute) {
            fini = true;
            if (faute != null) {
                LOGGER.error("[mesure-client] arrêt : {}", faute);
            }
            if (minecraft.level != null) {
                minecraft.level.disconnect();
            }
            minecraft.stop();
        }
    }
}
