package dev.axion.forge;

import dev.axion.AxionMod;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ADR-178, l'essai du portage à deux joueurs : un second client regarde, en spectateur, le joueur que l'essai du
 * portage ({@link EssaiPorteur}) porte sur un véhicule. Il relève, de son côté, ce qu'il voit de ce joueur pendant que
 * le véhicule roule : l'animation de ses jambes — un joueur porté qui ne bouge pas ne doit pas marcher —, l'écart de
 * sa place par rapport au véhicule à chaque tick, et, à chaque image, la dérive de sa place dessinée dans le corps
 * dessiné (ADR-184). {@code -Daxion.essai.film=true} le filme, la caméra suivant le véhicule dessiné. Inerte sans
 * {@code -Daxion.essai.observateur=true} ; contenu de développement.
 */
public final class EssaiObservateur {

    private static final Logger LOGGER = LoggerFactory.getLogger("AXION essai");

    private static final boolean ACTIF = Boolean.getBoolean("axion.essai.observateur");
    private static final String ADRESSE = "127.0.0.1:25599";
    private static final boolean FILM = Boolean.getBoolean("axion.essai.film");
    /** Les ticks relevés dès que le véhicule porte le joueur et roule. */
    private static final int MESURE = 200;
    /** Ticks en jeu laissés pour que le joueur porté et le véhicule roulant paraissent ; au-delà, l'essai échoue. */
    private static final int ATTENTE_MAX = 1200;
    /** Un joueur porté immobile : ses jambes au plus à cette vitesse d'animation, sur 1. */
    private static final double MARCHE_MAX = 0.1;
    /** Sa place sur le véhicule varie au plus de tant de blocs, au tick comme à l'image (ADR-184). */
    private static final double ECART_MAX = 0.35;

    private EssaiObservateur() {
        throw new AssertionError("classe utilitaire, non instanciable");
    }

    /** Le client observateur. */
    @Mod.EventBusSubscriber(modid = AxionMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
    public static final class Observateur {

        private static boolean connecte;
        private static boolean fini;
        private static int enJeu;
        private static int mesures;
        private static double marche;
        private static double marcheMax;
        private static double ecartMax;
        private static int assis;
        private static double distanceDepart;
        private static double[] placeDessinee;
        private static double deriveDessinee;
        private static int images;
        private static int listes;
        private static Tournage tournage;
        private static Vec3 avant;
        private static final List<String> lignes = new ArrayList<>();

        private Observateur() {}

        /** @param event le tick du client observateur */
        @SubscribeEvent
        public static void observerLePortage(TickEvent.ClientTickEvent event) {
            if (!ACTIF || fini || event.phase != TickEvent.Phase.END) {
                return;
            }
            Minecraft minecraft = Minecraft.getInstance();
            if (!connecte) {
                if (minecraft.getOverlay() == null && minecraft.screen instanceof TitleScreen titre) {
                    connecte = true;
                    // La capture montre la scène seule, sans astuces ni interface.
                    minecraft.options.hideGui = true;
                    minecraft.options.tutorialStep = net.minecraft.client.tutorial.TutorialSteps.NONE;
                    if (FILM) {
                        Tournage.reglerLesOptions(minecraft);
                    }
                    ConnectScreen.startConnecting(titre, minecraft, ServerAddress.parseString(ADRESSE),
                            new ServerData("essai-observateur", ADRESSE, false), false);
                }
                return;
            }
            if (minecraft.player == null || minecraft.level == null) {
                return;
            }
            Player porte = null;
            for (Player autre : minecraft.level.players()) {
                if (autre != minecraft.player && !autre.isSpectator()) {
                    porte = autre;
                }
            }
            AxionEntity vehicule = null;
            for (Entity entite : minecraft.level.entitiesForRendering()) {
                if (entite instanceof AxionEntity assembly && porte != null
                        && (vehicule == null || assembly.distanceToSqr(porte) < vehicule.distanceToSqr(porte))) {
                    vehicule = assembly;
                }
            }
            boolean roule = vehicule != null && avant != null
                    && vehicule.position().subtract(avant).horizontalDistance() > 0.05;
            avant = vehicule == null ? null : vehicule.position();
            double distance = vehicule == null ? Double.MAX_VALUE
                    : Math.hypot(porte.getX() - vehicule.getX(), porte.getZ() - vehicule.getZ());
            if (mesures == 0) {
                // Le départ : le joueur assis dans le véhicule, ou près de lui, au-dessus, et le véhicule qui roule.
                boolean assisDedans = porte != null && vehicule != null && porte.getVehicle() == vehicule;
                if (!assisDedans && (!roule || distance > 3.0 || porte.getY() <= vehicule.getY())) {
                    if (++enJeu > ATTENTE_MAX) {
                        terminer(minecraft, "aucun joueur porté par un véhicule qui roule, en " + ATTENTE_MAX / 20 + " s");
                    }
                    return;
                }
                distanceDepart = distance;
                LOGGER.info("[observateur] le véhicule roule, la mesure commence");
                if (FILM) {
                    tournage = new Tournage(minecraft.gameDirectory.toPath().resolve("axion-film").resolve("images"),
                            "AXION ENGINE, ADR-184");
                }
            }
            mesures++;
            assis += porte.getVehicle() == vehicule ? 1 : 0;
            listes += vehicule.carriedAt(System.nanoTime()).containsKey(porte.getId()) ? 1 : 0;
            float jambes = porte.walkAnimation.speed();
            marche += jambes;
            marcheMax = Math.max(marcheMax, jambes);
            ecartMax = Math.max(ecartMax, Math.abs(distance - distanceDepart));
            if (mesures == MESURE / 2) {
                Screenshot.grab(minecraft.gameDirectory, "observateur.png", minecraft.getMainRenderTarget(),
                        message -> LOGGER.info("[observateur] {}", message.getString()));
            }
            if (mesures == MESURE) {
                AxionEntity.RenderPose pose = vehicule.renderPose(1f);
                if (pose.vehicle() != null && !pose.wheels().isEmpty()) {
                    lignes.add(String.format(Locale.ROOT, "valeurs vues : carburant %.3f, mouillé %.3f, usure %.3f,"
                            + " moteur %.0f tr/min %.3f kW", pose.vehicle().store0(), pose.vehicle().wet(),
                            pose.wheels().get(0).wear(), pose.vehicle().motors().rpm(0),
                            pose.vehicle().motors().power(0)));
                }
                terminer(minecraft, null);
            }
        }

        /** @param event le début d'une image filmée : la caméra suit le véhicule dessiné, sans les sauts d'un tick */
        @SubscribeEvent(priority = EventPriority.LOWEST)
        public static void suivreLeVehicule(TickEvent.RenderTickEvent event) {
            Minecraft minecraft = Minecraft.getInstance();
            if (tournage == null || fini || event.phase != TickEvent.Phase.START || minecraft.player == null
                    || minecraft.level == null) {
                return;
            }
            for (Entity entite : minecraft.level.entitiesForRendering()) {
                if (entite instanceof AxionEntity assembly) {
                    AxionEntity.RenderPose pose = assembly.renderPose(event.renderTickTime);
                    var camera = minecraft.player;
                    camera.setPos(pose.x() + 3.5, pose.y() + 1.5, pose.z() + 4.0);
                    camera.xo = camera.xOld = camera.getX();
                    camera.yo = camera.yOld = camera.getY();
                    camera.zo = camera.zOld = camera.getZ();
                    camera.setYRot(139f);
                    camera.yRotO = 139f;
                    camera.setXRot(20f);
                    camera.xRotO = 20f;
                    return;
                }
            }
        }

        /** @param event la fin d'une image : la place dessinée du porté dans le corps dessiné, pendant la mesure */
        @SubscribeEvent
        public static void mesurerALImage(TickEvent.RenderTickEvent event) {
            Minecraft minecraft = Minecraft.getInstance();
            if (!ACTIF || fini || mesures == 0 || event.phase != TickEvent.Phase.END || minecraft.level == null) {
                return;
            }
            Player porte = null;
            for (Player autre : minecraft.level.players()) {
                if (autre != minecraft.player && !autre.isSpectator()) {
                    porte = autre;
                }
            }
            for (Entity entite : minecraft.level.entitiesForRendering()) {
                if (porte != null && entite instanceof AxionEntity assembly && assembly.distanceToSqr(porte) < 36.0) {
                    double[] poses = assembly.framePoses(assembly.renderPose(event.renderTickTime));
                    if (poses == null) {
                        continue;
                    }
                    double[] place = dev.axion.physics.BodyFrame.toLocal(poses, 0, porte.getX(), porte.getY(), porte.getZ());
                    if (placeDessinee == null) {
                        placeDessinee = place;
                    }
                    images++;
                    deriveDessinee = Math.max(deriveDessinee, Math.sqrt(Math.pow(place[0] - placeDessinee[0], 2)
                            + Math.pow(place[1] - placeDessinee[1], 2) + Math.pow(place[2] - placeDessinee[2], 2)));
                }
            }
            if (tournage != null && porte != null && tournage.imageAttendue()) {
                tournage.capturer(minecraft.getMainRenderTarget(),
                        "Un joueur debout sur un véhicule, vu par un autre joueur (serveur dédié, 100 ms de latence par sens)",
                        String.format(Locale.ROOT, "jambes %.2f   dérive dessinée sur le véhicule %.3f bloc au plus",
                                porte.walkAnimation.speed(), deriveDessinee),
                        String.format(Locale.ROOT, "%.1f s", (System.nanoTime() - tournage.debut()) / 1e9));
            }
        }

        /** @param event l'écran qui s'ouvre : une déconnexion avant la fin fait échouer l'essai */
        @SubscribeEvent
        public static void deconnexionDeLObservateur(ScreenEvent.Opening event) {
            if (ACTIF && !fini && connecte && event.getNewScreen() instanceof DisconnectedScreen ecran) {
                terminer(Minecraft.getInstance(), "déconnecté : " + EssaiAddonsExiges.Client.raison(ecran).getString());
            }
        }

        private static void terminer(Minecraft minecraft, String faute) {
            fini = true;
            double moyenne = mesures == 0 ? 0.0 : marche / mesures;
            boolean juste = moyenne <= MARCHE_MAX && ecartMax <= ECART_MAX && deriveDessinee <= ECART_MAX;
            String ligne = faute != null ? "ÉCHEC : " + faute : String.format(Locale.ROOT,
                    "%s : vu par un autre joueur sur %d ticks, jambes du porté à %.3f en moyenne (au plus %.2f) et %.3f au"
                            + " plus, écart de sa place %.3f bloc au plus, dérive dessinée %.3f bloc sur %d images (au plus"
                            + " %.2f), listé %d ticks, assis dans le véhicule %d ticks",
                    juste ? "VERT" : "ROUGE", mesures, moyenne, MARCHE_MAX, marcheMax, ecartMax, deriveDessinee, images,
                    ECART_MAX, listes, assis);
            lignes.add(ligne);
            LOGGER.info("[observateur] {}", ligne);
            try {
                Files.write(minecraft.gameDirectory.toPath().resolve("rapport-observateur.txt"), lignes,
                        StandardCharsets.UTF_8);
            } catch (IOException echec) {
                LOGGER.error("[observateur] rapport non écrit : {}", echec.toString());
            }
            if (tournage != null) {
                fermerLeFilm(minecraft, faute);
            }
            if (minecraft.level != null) {
                minecraft.level.disconnect();
            }
            minecraft.stop();
        }

        private static void fermerLeFilm(Minecraft minecraft, String motif) {
            String faute = tournage.fermer();
            faute = faute != null ? faute : motif;
            String bilan = String.format(Locale.ROOT, "{\"images\": %d, \"uniques\": %d, \"echec\": %s}%n",
                    tournage.images(), tournage.uniques(), faute == null ? "null" : "\"" + faute.replace('"', '\'') + "\"");
            tournage = null;
            try {
                Files.writeString(minecraft.gameDirectory.toPath().resolve("axion-film").resolve("termine.json"), bilan,
                        StandardCharsets.UTF_8);
            } catch (IOException echec) {
                LOGGER.error("[observateur] bilan du film non écrit : {}", echec.toString());
            }
        }
    }
}
