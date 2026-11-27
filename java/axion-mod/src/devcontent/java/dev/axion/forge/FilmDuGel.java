package dev.axion.forge;

import dev.axion.AxionMod;
import dev.axion.api.assembly.Transform;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Quaternionf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Le film du gel (ADR-183 D2 à D4) : un véhicule conduit est figé en pleine accélération, mené vers le haut comme par un
 * pont élévateur, rendu au moteur, puis téléporté avec son conducteur sur une autre piste. Une caméra extérieure le suit,
 * à côté de repères de hauteur. Le film se juge sur ce que le client a reçu, écrit {@code termine.json} et s'arrête.
 * Inerte sans {@code -Daxion.film=gel} ; le lancement Gradle {@code runFilmGel} la pose. Rien ici ne part dans le mod
 * livré.
 */
@Mod.EventBusSubscriber(modid = AxionMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class FilmDuGel {

    private static final Logger LOGGER = LoggerFactory.getLogger("AXION film");
    private static final boolean ACTIF = "gel".equals(System.getProperty("axion.film"));
    private static final String MONDE = "axion-film-gel";
    private static final String DEFINITION = System.getProperty("axion.film.definition", "axion:test/vehicule");

    /** Le sol du monde plat : la première couche d'air. */
    private static final int SOL = -60;

    private static final int DEMI_LARGEUR = 12;
    private static final int LONGUEUR = 96;
    /** La seconde piste, où la téléportation arrive. */
    private static final int DECALAGE = 120;

    /** Le départ du véhicule, son avant (-Z du modèle) tourné vers +Z. */
    private static final Vec3 DEPART = new Vec3(0.5, SOL + 0.5, 12.5);

    /** Ce que le pont élévateur monte, en blocs, et en combien de secondes. */
    private static final double HAUTEUR = 3.0;
    private static final double MONTEE_S = 3.0;

    /** Délai maximal d'une phase d'attente. */
    private static final long ATTENTE_MAX_NS = TimeUnit.SECONDS.toNanos(180);

    private enum Phase { TITRE, CREATION, MONDE, DECOR, VEHICULE, ASSEOIR, TOURNAGE, FIN }

    /** Les étapes filmées, chacune avec sa durée, en secondes. */
    private enum Etape {
        DEPART(2.0), ROULE(3.0), FIGE(3.0), MONTE(MONTEE_S + 2.0), LACHE(4.0), TELEPORTE(3.0), REPART(3.5);

        final double duree;

        Etape(double duree) {
            this.duree = duree;
        }
    }

    private static Phase phase = Phase.TITRE;
    private static long debutPhase = System.nanoTime();
    private static boolean dansUneEtape;
    private static volatile boolean decorPret;
    private static volatile int idVehicule = -1;
    private static volatile AxionEntity vehiculeServeur;
    private static volatile boolean assis;
    private static volatile RuntimeException fauteServeur;
    /** La pose du véhicule au serveur quand il a été figé. */
    private static volatile Vec3 posFigee;
    private static volatile Quaternionf rotFigee;

    private static Etape etape = Etape.DEPART;
    private static double debutEtape;
    private static boolean entree;
    private static Tournage tournage;
    private static Entity camera;
    private static Vec3 oeil;

    // Le jugement, sur ce que le client a reçu.
    private static Vec3 placeFigee;
    private static double deriveFigee;
    private static double monte;
    private static double retombe = Double.NaN;
    private static double arrivee;
    private static boolean assisApres;

    private FilmDuGel() {}

    private static Path dossier(Minecraft minecraft) {
        return minecraft.gameDirectory.toPath().resolve("axion-film");
    }

    /**
     * @param event frame du client
     */
    @SubscribeEvent
    public static void filmerLeGel(TickEvent.RenderTickEvent event) {
        if (!ACTIF) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (event.phase == TickEvent.Phase.START) {
            if (phase == Phase.TOURNAGE) {
                if (minecraft.mouseHandler.isMouseGrabbed()) {
                    minecraft.mouseHandler.releaseMouse();
                }
                cadrer(minecraft, event.renderTickTime);
            }
            return;
        }
        if (dansUneEtape) {
            return;
        }
        dansUneEtape = true;
        try {
            RuntimeException auServeur = fauteServeur;
            if (auServeur != null) {
                fauteServeur = null;
                throw new IllegalStateException("au serveur : " + auServeur.getMessage(), auServeur);
            }
            jouer(minecraft);
        } catch (RuntimeException faute) {
            LOGGER.error("le film a échoué en phase {}", phase, faute);
            terminer(minecraft, phase + " : " + faute);
        } finally {
            dansUneEtape = false;
        }
    }

    /**
     * Le film ne se joue jamais en pause.
     *
     * @param event tick du client
     */
    @SubscribeEvent
    public static void veillerAuGel(TickEvent.ClientTickEvent event) {
        if (ACTIF && event.phase == TickEvent.Phase.END) {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.level != null && minecraft.screen instanceof PauseScreen) {
                minecraft.setScreen(null);
            }
            minecraft.getToasts().clear();
            minecraft.gui.setOverlayMessage(net.minecraft.network.chat.Component.empty(), false);
        }
    }

    /** La caméra, à côté du véhicule, à hauteur fixe au-dessus de la piste : la montée se voit sur les repères. */
    private static void cadrer(Minecraft minecraft, float partiel) {
        Entity vehicule = minecraft.level == null ? null : minecraft.level.getEntity(idVehicule);
        if (camera == null || vehicule == null) {
            return;
        }
        Vec3 cible = vehicule.getPosition(partiel);
        Vec3 voulu = new Vec3(cible.x + 11.0, SOL + 4.0, cible.z - 4.0);
        oeil = oeil == null || oeil.distanceTo(voulu) > 20.0 ? voulu : oeil.add(voulu.subtract(oeil).scale(0.1));
        double pieds = oeil.y - camera.getEyeHeight();
        camera.setPos(oeil.x, pieds, oeil.z);
        camera.xo = camera.xOld = oeil.x;
        camera.yo = camera.yOld = pieds;
        camera.zo = camera.zOld = oeil.z;
        Vec3 vise = cible.add(0.0, 0.8, 0.0).subtract(oeil);
        float lacet = (float) Math.toDegrees(Math.atan2(-vise.x, vise.z));
        float tangage = (float) -Math.toDegrees(Math.atan2(vise.y, Math.hypot(vise.x, vise.z)));
        camera.setYRot(lacet);
        camera.setXRot(tangage);
        camera.yRotO = lacet;
        camera.xRotO = tangage;
        if (camera instanceof ArmorStand support) {
            support.yHeadRot = lacet;
            support.yHeadRotO = lacet;
        }
        minecraft.options.setCameraType(CameraType.FIRST_PERSON);
        minecraft.options.hideGui = true;
    }

    private static void passer(Phase suivante) {
        LOGGER.info("[film] {} -> {}", phase, suivante);
        phase = suivante;
        debutPhase = System.nanoTime();
    }

    private static boolean depuis(double secondes) {
        if (System.nanoTime() - debutPhase > ATTENTE_MAX_NS) {
            throw new IllegalStateException("attente trop longue");
        }
        return System.nanoTime() - debutPhase > (long) (secondes * 1e9);
    }

    private static void auServeur(Minecraft minecraft, Runnable tache) {
        minecraft.getSingleplayerServer().execute(() -> {
            try {
                tache.run();
            } catch (RuntimeException faute) {
                fauteServeur = faute;
            }
        });
    }

    private static void jouer(Minecraft minecraft) {
        switch (phase) {
            case TITRE -> {
                if (minecraft.getOverlay() == null && minecraft.level == null && minecraft.screen != null) {
                    Tournage.reglerLesOptions(minecraft);
                    passer(Phase.CREATION);
                }
            }
            case CREATION -> {
                Tournage.creerLeMonde(minecraft, MONDE);
                passer(Phase.MONDE);
            }
            case MONDE -> {
                if (minecraft.level != null && minecraft.player != null && minecraft.getSingleplayerServer() != null
                        && minecraft.getSingleplayerServer().isReady() && depuis(3.0)) {
                    auServeur(minecraft, () -> poserLeDecor(minecraft));
                    passer(Phase.DECOR);
                }
            }
            case DECOR -> {
                minecraft.player.getAbilities().flying = true;
                if (decorPret && depuis(4.0)) {
                    passer(Phase.VEHICULE);
                }
            }
            case VEHICULE -> {
                if (System.nanoTime() - debutPhase > TimeUnit.SECONDS.toNanos(60)) {
                    throw new IllegalStateException("definition " + DEFINITION + " indisponible : voir le journal");
                }
                minecraft.player.getAbilities().flying = true;
                auServeur(minecraft, () -> poserLeVehicule(minecraft));
                if (idVehicule >= 0 && minecraft.level.getEntity(idVehicule) != null && depuis(3.0)) {
                    auServeur(minecraft, () -> asseoirLeJoueur(minecraft));
                    passer(Phase.ASSEOIR);
                }
            }
            case ASSEOIR -> {
                // Un modèle lourd se prépare chez le client avant la première image.
                if (assis && minecraft.player.getVehicle() instanceof AxionEntity && depuis(6.0)) {
                    camera = new ArmorStand(EntityType.ARMOR_STAND, minecraft.level);
                    camera.setInvisible(true);
                    minecraft.setCameraEntity(camera);
                    tournage = new Tournage(dossier(minecraft).resolve("images"), "AXION ENGINE, ADR-183");
                    debutEtape = 0.0;
                    passer(Phase.TOURNAGE);
                }
            }
            case TOURNAGE -> tourner(minecraft);
            case FIN -> { }
            default -> throw new IllegalStateException();
        }
    }

    private static void poserLeDecor(Minecraft minecraft) {
        ServerLevel level = minecraft.getSingleplayerServer().overworld();
        level.setDayTime(6000);
        for (int decalage : new int[] {0, DECALAGE}) {
            for (int x = -DEMI_LARGEUR; x <= DEMI_LARGEUR; x++) {
                for (int z = 0; z < LONGUEUR; z++) {
                    boolean ligne = z % 8 == 0;
                    level.setBlockAndUpdate(new BlockPos(x + decalage, SOL - 1, z),
                            (ligne ? Blocks.WHITE_CONCRETE : decalage == 0 ? Blocks.SMOOTH_STONE
                                    : Blocks.RED_SANDSTONE).defaultBlockState());
                }
            }
            // Des repères de hauteur, de l'autre côté de la caméra : une couleur par bloc.
            for (int z = 8; z < LONGUEUR; z += 8) {
                for (int h = 0; h < 5; h++) {
                    level.setBlockAndUpdate(new BlockPos(decalage - 7, SOL + h, z),
                            (h % 2 == 0 ? Blocks.YELLOW_CONCRETE : Blocks.BLACK_CONCRETE).defaultBlockState());
                }
            }
        }
        minecraft.getSingleplayerServer().getPlayerList().getPlayers().get(0)
                .teleportTo(DEPART.x + 6.0, SOL + 2.0, DEPART.z);
        decorPret = true;
    }

    private static void poserLeVehicule(Minecraft minecraft) {
        if (idVehicule >= 0) {
            return;
        }
        ServerLevel level = minecraft.getSingleplayerServer().overworld();
        try {
            AxionEntity vehicule = AssembliesDeTest.poser(level, DEFINITION, DEPART, new float[] {0f, 1f, 0f, 0f});
            vehiculeServeur = vehicule;
            idVehicule = vehicule.getId();
        } catch (IllegalStateException pasEncore) {
            // La definition attend encore sa compilation : on repasse à la frame suivante.
        }
    }

    private static void asseoirLeJoueur(Minecraft minecraft) {
        ServerPlayer joueur = minecraft.getSingleplayerServer().getPlayerList().getPlayers().get(0);
        joueur.getAbilities().flying = false;
        assis = vehiculeServeur.seatingKnown() && vehiculeServeur.reseat(joueur, "conducteur");
        if (!assis) {
            throw new IllegalStateException("le joueur ne s'assoit pas au siège conducteur de " + DEFINITION);
        }
    }

    private static void tourner(Minecraft minecraft) {
        double secondes = (System.nanoTime() - tournage.debut()) / 1e9;
        Entity entite = minecraft.level.getEntity(idVehicule);
        if (!(entite instanceof AxionEntity vehicule)) {
            throw new IllegalStateException("le véhicule a disparu du client");
        }
        double dansEtape = secondes - debutEtape;
        Vec3 place = vehicule.position();
        boolean avant = false;
        switch (etape) {
            case DEPART, LACHE, TELEPORTE -> { }
            case ROULE, REPART, FIGE, MONTE -> avant = true;
            default -> throw new IllegalStateException();
        }
        if (!entree) {
            entree = true;
            entrer(minecraft);
        }
        switch (etape) {
            case FIGE -> {
                if (dansEtape > 0.6) {
                    if (placeFigee == null) {
                        placeFigee = place;
                    }
                    deriveFigee = Math.max(deriveFigee, place.distanceTo(placeFigee));
                }
            }
            case MONTE -> {
                double part = Math.min(1.0, dansEtape / MONTEE_S);
                double doux = part * part * (3.0 - 2.0 * part);
                auServeur(minecraft, () -> ForgeAxionApi.view(vehiculeServeur).setKinematicTransform(new Transform(
                        posFigee.add(0.0, HAUTEUR * doux, 0.0), rotFigee, new Vec3(1, 1, 1))));
                if (placeFigee != null) {
                    monte = Math.max(monte, place.y - placeFigee.y);
                }
            }
            case LACHE -> {
                if (dansEtape > 3.0 && placeFigee != null) {
                    retombe = place.y - placeFigee.y;
                }
            }
            case TELEPORTE, REPART -> {
                arrivee = place.x;
                assisApres = minecraft.player.getVehicle() == vehicule;
            }
            default -> { }
        }
        minecraft.options.keyUp.setDown(avant);
        if (tournage.imageAttendue()) {
            float vitesse = vehicule.renderPose(minecraft.getFrameTime()).vehicle().speed();
            // Posé, un véhicule s'écrase d'un rien sur sa suspension : « -0,0 » ne se lit pas.
            double hauteur = Math.max(0.0, place.y - SOL);
            tournage.capturer(minecraft.getMainRenderTarget(), legende(),
                    String.format(Locale.ROOT, "vitesse %.1f m/s   hauteur %.1f bloc   accélérateur %s", vitesse,
                            hauteur, avant ? "enfoncé" : "relâché"),
                    String.format(Locale.ROOT, "%.1f s", secondes));
        }
        if (dansEtape >= etape.duree) {
            LOGGER.info("[film] fin de {} à {} s : {}", etape, String.format(Locale.ROOT, "%.1f", secondes), place);
            if (etape == Etape.REPART) {
                minecraft.options.keyUp.setDown(false);
                terminer(minecraft, tournage.echec() != null ? tournage.echec() : jugement());
                return;
            }
            etape = Etape.values()[etape.ordinal() + 1];
            debutEtape = secondes;
            entree = false;
        }
    }

    /** Ce que l'entrée dans une étape demande au serveur. */
    private static void entrer(Minecraft minecraft) {
        switch (etape) {
            case FIGE -> auServeur(minecraft, () -> {
                ForgeAxionApi.view(vehiculeServeur).setFrozen(true);
                posFigee = vehiculeServeur.position();
                rotFigee = vehiculeServeur.orientation();
            });
            case LACHE -> auServeur(minecraft, () -> ForgeAxionApi.view(vehiculeServeur).setFrozen(false));
            case TELEPORTE -> auServeur(minecraft, () -> ForgeAxionApi.view(vehiculeServeur).teleport(new Transform(
                    new Vec3(DEPART.x + DECALAGE, SOL + 0.8, DEPART.z), new Quaternionf(0f, 1f, 0f, 0f),
                    new Vec3(1, 1, 1)), true));
            default -> { }
        }
    }

    private static String legende() {
        return switch (etape) {
            case DEPART -> "Figer, mener et téléporter un véhicule conduit";
            case ROULE -> "Le conducteur accélère";
            case FIGE -> "Figé en pleine accélération : il tient sa place, moteur en pause";
            case MONTE -> "Un mod le mène vers le haut, comme un pont élévateur";
            case LACHE -> "Rendu au moteur : il repart à l'arrêt, et retombe";
            case TELEPORTE -> "Téléporté sur une autre piste, son conducteur à bord";
            case REPART -> "Il repart";
        };
    }

    private static String jugement() {
        boolean reussi = deriveFigee < 0.2 && monte > HAUTEUR - 0.5 && retombe < 0.8
                && arrivee > DEPART.x + DECALAGE - 10 && assisApres;
        return reussi ? null : String.format(Locale.ROOT, "gel : dérive figée %.3f, montée %.2f, retombée %.2f,"
                + " arrivée x %.1f, à bord %b", deriveFigee, monte, retombe, arrivee, assisApres);
    }

    private static void terminer(Minecraft minecraft, String motif) {
        if (phase == Phase.FIN) {
            return;
        }
        passer(Phase.FIN);
        String faute = motif;
        if (tournage != null) {
            String ecriture = tournage.fermer();
            faute = ecriture != null ? ecriture : faute;
        }
        String bilan = String.format(Locale.ROOT, "{\"images\": %d, \"uniques\": %d, \"echec\": %s,"
                        + " \"derive_figee\": %.4f, \"monte\": %.2f, \"retombe\": %.2f, \"arrivee_x\": %.1f}%n",
                tournage == null ? 0 : tournage.images(), tournage == null ? 0 : tournage.uniques(),
                faute == null ? "null" : "\"" + faute.replace('"', '\'') + "\"",
                deriveFigee, monte, Double.isNaN(retombe) ? -1.0 : retombe, arrivee);
        try {
            Files.createDirectories(dossier(minecraft));
            Files.writeString(dossier(minecraft).resolve("termine.json"), bilan, StandardCharsets.UTF_8);
        } catch (IOException faute2) {
            LOGGER.error("bilan non écrit", faute2);
        }
        LOGGER.info("[film] bilan : {}", bilan.trim());
        minecraft.stop();
    }
}
