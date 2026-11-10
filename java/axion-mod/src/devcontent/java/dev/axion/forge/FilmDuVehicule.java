package dev.axion.forge;

import com.mojang.blaze3d.platform.NativeImage;
import dev.axion.AxionMod;
import dev.axion.physics.SimCommandStream;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.ParticleStatus;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Le film de l'avancée des véhicules : un client qui joue sa scène seul, se filme à 60 images par
 * seconde en lisant son framebuffer, et s'arrête (recette de {@code D:\BDC}, ADR-159).
 *
 * <p>Inerte sans {@code -Daxion.film} ; le lancement Gradle {@code runFilmVehicule} la pose. Le bandeau
 * affiche des valeurs lues en direct, jamais écrites à l'avance. Rien ici ne part dans le mod livré.
 */
@Mod.EventBusSubscriber(modid = AxionMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class FilmDuVehicule {

    private static final Logger LOGGER = LoggerFactory.getLogger("AXION film");
    private static final boolean ACTIF = System.getProperty("axion.film") != null;
    private static final String MONDE = "axion-film";

    /** Le sol du monde plat : la première couche d'air. */
    private static final int SOL = -60;

    /** Durée filmée, en secondes. */
    private static final double DUREE = 20.0;

    private static final long IMAGE_NS = 1_000_000_000L / 60L;
    private static final int LARGEUR = 1280;
    private static final int HAUTEUR_JEU = 656;
    private static final int BANDEAU = 64;

    /** Délai maximal d'une phase d'attente. */
    private static final long ATTENTE_MAX_NS = TimeUnit.SECONDS.toNanos(180);

    private enum Phase { TITRE, CREATION, MONDE, DECOR, VEHICULES, TOURNAGE, FIN }

    private static Phase phase = Phase.TITRE;
    private static long debutPhase = System.nanoTime();
    private static boolean dansUneEtape;
    private static boolean decorPret;
    private static volatile int idVehicule = -1;
    private static volatile int idGare = -1;
    private static boolean pilierRetire;
    private static volatile AxionEntity vehiculeServeur;
    private static volatile boolean poussee;
    private static volatile boolean capotDevant;
    private static final java.util.List<String> MESURES_SERVEUR = new java.util.ArrayList<>();
    private static final java.util.List<String> MESURES_CLIENT = new java.util.ArrayList<>();
    private static double zMax = Double.NEGATIVE_INFINITY;
    private static double hauteurGareInitiale = Double.NaN;

    /** La poussée imposée, en newtons. */
    private static final float POUSSEE_N = Float.parseFloat(System.getProperty("axion.film.poussee", "1500"));

    /** La definition filmée. */
    private static final String DEFINITION = System.getProperty("axion.film.definition", "axion:test/vehicule");

    private static long debutFilm;
    private static int imagesEcrites;
    private static int imagesUniques;
    private static double zCamera = Double.NaN;
    private static Vec3 positionPrecedente;
    private static long nanosPrecedentes;
    private static double vitesse;
    private static ThreadPoolExecutor ecriture;
    private static volatile String echec;

    private FilmDuVehicule() {}

    private static Path dossier(Minecraft minecraft) {
        return minecraft.gameDirectory.toPath().resolve("axion-film");
    }

    /**
     * @param event frame du client
     */
    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (!ACTIF) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (event.phase == TickEvent.Phase.START) {
            if (phase == Phase.TOURNAGE) {
                placerLaCamera(minecraft, event.renderTickTime);
            }
            return;
        }
        if (dansUneEtape) {
            return;
        }
        dansUneEtape = true;
        try {
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
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (ACTIF && event.phase == TickEvent.Phase.END) {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.level != null && minecraft.screen instanceof PauseScreen) {
                minecraft.setScreen(null);
            }
            minecraft.getToasts().clear();
        }
    }

    private static void passer(Phase suivante) {
        LOGGER.info("[film] {} -> {}", phase, suivante);
        phase = suivante;
        debutPhase = System.nanoTime();
    }

    private static void garde() {
        if (System.nanoTime() - debutPhase > ATTENTE_MAX_NS) {
            throw new IllegalStateException("attente trop longue");
        }
    }

    private static void jouer(Minecraft minecraft) {
        switch (phase) {
            case TITRE -> {
                if (minecraft.getOverlay() == null && minecraft.level == null && minecraft.screen != null) {
                    reglerLesOptions(minecraft);
                    passer(Phase.CREATION);
                }
            }
            case CREATION -> {
                creerLeMonde(minecraft);
                passer(Phase.MONDE);
            }
            case MONDE -> {
                garde();
                if (minecraft.level != null && minecraft.player != null
                        && minecraft.getSingleplayerServer() != null
                        && minecraft.getSingleplayerServer().isReady()
                        && System.nanoTime() - debutPhase > TimeUnit.SECONDS.toNanos(3)) {
                    minecraft.getSingleplayerServer().execute(() -> poserLeDecor(minecraft));
                    passer(Phase.DECOR);
                }
            }
            case DECOR -> {
                garde();
                minecraft.player.getAbilities().flying = true;
                if (decorPret && System.nanoTime() - debutPhase > TimeUnit.SECONDS.toNanos(4)) {
                    passer(Phase.VEHICULES);
                }
            }
            case VEHICULES -> {
                // Une definition refusée ne viendra jamais : inutile de laisser le jeu tourner à vide.
                if (System.nanoTime() - debutPhase > TimeUnit.SECONDS.toNanos(45)) {
                    throw new IllegalStateException("definition " + DEFINITION + " indisponible : voir le journal");
                }
                minecraft.player.getAbilities().flying = true;
                minecraft.getSingleplayerServer().execute(() -> poserLesVehicules(minecraft));
                if (idVehicule >= 0 && minecraft.level.getEntity(idVehicule) != null
                        && minecraft.level.getEntity(idGare) != null) {
                    demarrerLeFilm(minecraft);
                    passer(Phase.TOURNAGE);
                }
            }
            case TOURNAGE -> tourner(minecraft);
            case FIN -> { }
            default -> throw new IllegalStateException();
        }
    }

    private static void reglerLesOptions(Minecraft minecraft) {
        Options options = minecraft.options;
        options.hideGui = true;
        options.fullscreen().set(false);
        minecraft.getWindow().setWindowed(LARGEUR, HAUTEUR_JEU);
        options.getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.MASTER).set(0.0);
        options.pauseOnLostFocus = false;
        options.tutorialStep = TutorialSteps.NONE;
        options.onboardAccessibility = false;
        options.fov().set(70);
        options.renderDistance().set(12);
        options.cloudStatus().set(CloudStatus.OFF);
        options.particles().set(ParticleStatus.MINIMAL);
        options.bobView().set(false);
        options.fovEffectScale().set(0.0);
        options.screenEffectScale().set(0.0);
        options.enableVsync().set(false);
        options.framerateLimit().set(260);
        minecraft.mouseHandler.releaseMouse();
    }

    private static void creerLeMonde(Minecraft minecraft) {
        GameRules regles = new GameRules();
        regles.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
        regles.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
        regles.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
        regles.getRule(GameRules.RULE_RANDOMTICKING).set(0, null);
        // Créatif, pas spectateur : le joueur doit rester un observateur de la physique (R-612).
        LevelSettings reglages = new LevelSettings(
                MONDE, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, regles, WorldDataConfiguration.DEFAULT);
        minecraft.createWorldOpenFlows().createFreshLevel(
                MONDE,
                reglages,
                new WorldOptions(0L, false, false),
                acces -> acces.registryOrThrow(Registries.WORLD_PRESET)
                        .getHolderOrThrow(WorldPresets.FLAT)
                        .value()
                        .createWorldDimensions());
    }

    /**
     * Une piste plate, des bosses d'un huitième de bloc, un mur au bout ; à côté du mur, un pilier qui
     * porte le véhicule garé. Pas de pente : un rayon vertical voit des marches plates, sans dénivelé.
     */
    private static void poserLeDecor(Minecraft minecraft) {
        ServerLevel level = minecraft.getSingleplayerServer().overworld();
        level.setDayTime(6000);
        for (int x = -7; x <= 7; x++) {
            for (int z = 0; z < 112; z++) {
                level.setBlockAndUpdate(new BlockPos(x, SOL - 1, z), Blocks.SMOOTH_STONE.defaultBlockState());
            }
            for (int z = 46; z < 66; z += 3) {
                // Un tapis de 1/16 de bloc, sous les seules roues : la caisse, à 8 cm du sol, passe au-dessus.
                if (x == -1 || x == 1) {
                    level.setBlockAndUpdate(new BlockPos(x, SOL, z), Blocks.WHITE_CARPET.defaultBlockState());
                }
            }
            for (int y = 0; y < 4; y++) {
                level.setBlockAndUpdate(new BlockPos(x, SOL + y, 112), Blocks.BRICKS.defaultBlockState());
            }
        }
        for (int x = 11; x <= 16; x++) {
            for (int z = 96; z <= 102; z++) {
                for (int y = 0; y < 3; y++) {
                    level.setBlockAndUpdate(new BlockPos(x, SOL + y, z), Blocks.BRICKS.defaultBlockState());
                }
            }
        }
        // Le joueur au milieu de la scène : les deux véhicules sont dans sa portée de suivi.
        minecraft.getSingleplayerServer().getPlayerList().getPlayers().get(0)
                .teleportTo(0.5, SOL + 8.0, 55.0);
        decorPret = true;
    }

    private static boolean poser(ServerLevel level, Vec3 ou, Consumer<AxionEntity> garder) {
        try {
            garder.accept(AssembliesDeTest.poser(level, DEFINITION, ou, new float[] {0f, 1f, 0f, 0f}));
            return true;
        } catch (IllegalStateException pasEncore) {
            return false;
        }
    }

    private static void poserLesVehicules(Minecraft minecraft) {
        if (idVehicule >= 0) {
            return;
        }
        ServerLevel level = minecraft.getSingleplayerServer().overworld();
        if (poser(level, new Vec3(13.5, SOL + 3.2, 99.5), a -> idGare = a.getId())) {
            poser(level, new Vec3(0.5, SOL + 10.5, 10.5), a -> {
                idVehicule = a.getId();
                vehiculeServeur = a;
            });
            RuntimeAccess.get().addCommandProvider(FilmDuVehicule::pousser);
        }
    }

    /** La poussée imposée du film, à chaque tick du serveur : le moteur arrive à la tranche 3. */
    private static SimCommandStream pousser(long tick) {
        SimCommandStream commandes = new SimCommandStream();
        AxionEntity cible = vehiculeServeur;
        if (cible != null) {
            noter(MESURES_SERVEUR, cible);
            // §6.5 : l'avant d'un véhicule est son -Z.
            capotDevant = cible.bodyRotation().transform(new org.joml.Vector3f(0f, 0f, -1f)).z > 0.9f;
        }
        if (poussee && cible != null) {
            commandes.applyImpulse(AssemblyRuntime.handleIndexOf(cible), AssemblyRuntime.GENERATION,
                    0f, 0f, POUSSEE_N * 0.05f);
        }
        return commandes;
    }

    private static void demarrerLeFilm(Minecraft minecraft) {
        try {
            Path images = dossier(minecraft).resolve("images");
            Files.createDirectories(images);
        } catch (IOException faute) {
            throw new UncheckedIOException(faute);
        }
        ecriture = new ThreadPoolExecutor(6, 6, 0L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(48),
                new ThreadPoolExecutor.CallerRunsPolicy());
        debutFilm = System.nanoTime();
    }

    /** La caméra suit le véhicule de côté, sans à-coup : posée à chaque frame, à sa position interpolée. */
    private static void placerLaCamera(Minecraft minecraft, float partiel) {
        LocalPlayer joueur = minecraft.player;
        Entity vehicule = minecraft.level == null ? null : minecraft.level.getEntity(idVehicule);
        if (joueur == null || vehicule == null) {
            return;
        }
        Vec3 cible = vehicule.getPosition(partiel).add(0, 0.6, 0);
        zCamera = Double.isNaN(zCamera) ? cible.z : zCamera + (cible.z - zCamera) * 0.06;
        // Jamais derrière le mur du bout de piste.
        Vec3 oeil = new Vec3(cible.x - 8.5, SOL + 2.4, Math.min(zCamera + 7.0, 104.0));
        joueur.getAbilities().flying = true;
        joueur.setDeltaMovement(Vec3.ZERO);
        double piedsY = oeil.y - joueur.getEyeHeight();
        joueur.setPos(oeil.x, piedsY, oeil.z);
        joueur.xo = joueur.xOld = oeil.x;
        joueur.yo = joueur.yOld = piedsY;
        joueur.zo = joueur.zOld = oeil.z;
        double dx = cible.x - oeil.x;
        double dy = cible.y - oeil.y;
        double dz = cible.z - oeil.z;
        float lacet = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float tangage = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        joueur.setYRot(lacet);
        joueur.setXRot(tangage);
        joueur.yRotO = lacet;
        joueur.xRotO = tangage;
    }

    private static void tourner(Minecraft minecraft) {
        double secondes = (System.nanoTime() - debutFilm) / 1e9;
        if (secondes >= DUREE) {
            terminer(minecraft, echec != null ? echec : jugement(minecraft));
            return;
        }
        poussee = secondes >= 3.5 && secondes < 7.5;
        if (secondes > 16.0 && !pilierRetire) {
            pilierRetire = true;
            minecraft.getSingleplayerServer().execute(() -> retirerLePilier(minecraft));
        }
        Entity vehicule = minecraft.level.getEntity(idVehicule);
        mesurer(vehicule);
        if (vehicule instanceof AxionEntity assembly) {
            noter(MESURES_CLIENT, assembly);
        }
        zMax = Math.max(zMax, vehicule.getZ());
        Entity garee = minecraft.level.getEntity(idGare);
        if (Double.isNaN(hauteurGareInitiale) && secondes > 3.0 && garee != null) {
            hauteurGareInitiale = garee.getY();
        }
        int attendues = (int) ((System.nanoTime() - debutFilm) / IMAGE_NS);
        if (attendues < imagesEcrites) {
            return;
        }
        int repetitions = attendues - imagesEcrites + 1;
        int premiere = imagesEcrites + 1;
        imagesEcrites += repetitions;
        imagesUniques++;
        int[] pixels;
        int largeur;
        int hauteur;
        try (NativeImage image = Screenshot.takeScreenshot(minecraft.getMainRenderTarget())) {
            pixels = image.makePixelArray();
            largeur = image.getWidth();
            hauteur = image.getHeight();
        }
        Entity gare = minecraft.level.getEntity(idGare);
        String valeurs = String.format(Locale.ROOT, "vitesse %.1f m/s   hauteur %.2f   garé : y %.2f",
                vitesse, vehicule.getY() - SOL, gare == null ? Double.NaN : gare.getY() - SOL);
        String legende = legende(secondes);
        String temps = String.format(Locale.ROOT, "%.1f s", secondes);
        Path dossierImages = dossier(minecraft).resolve("images");
        ecriture.execute(() -> ecrire(dossierImages, premiere, repetitions, pixels, largeur, hauteur, legende, valeurs, temps));
    }

    /** Hauteur et assiette, relevées telles quelles : ce qu'on voit doit pouvoir se comparer à ce qui est simulé. */
    private static void noter(java.util.List<String> mesures, AxionEntity vehicule) {
        org.joml.Vector3f avant = vehicule.bodyRotation().transform(new org.joml.Vector3f(0f, 0f, 1f));
        synchronized (mesures) {
            mesures.add(String.format(Locale.ROOT, "%.3f,%.4f,%.4f,%.5f",
                    (System.nanoTime() - debutFilm) / 1e9, vehicule.getZ(), vehicule.getY() - SOL, avant.y));
        }
    }

    private static void mesurer(Entity vehicule) {
        Vec3 ici = vehicule.position();
        long maintenant = System.nanoTime();
        if (positionPrecedente == null) {
            positionPrecedente = ici;
            nanosPrecedentes = maintenant;
        } else if (maintenant - nanosPrecedentes > 200_000_000L) {
            vitesse = ici.distanceTo(positionPrecedente) / ((maintenant - nanosPrecedentes) / 1e9);
            positionPrecedente = ici;
            nanosPrecedentes = maintenant;
        }
    }

    private static String legende(double secondes) {
        if (secondes < 3.5) {
            return "Chute de 10 blocs : quatre rayons, suspension, atterrissage";
        }
        if (secondes < 7.5) {
            return String.format(Locale.ROOT, "Poussée imposée de %.0f N (le moteur arrive après) : il roule droit", POUSSEE_N);
        }
        if (secondes < 16.0) {
            return "Sur sa lancée : tapis de 6 cm sous les roues, puis le mur";
        }
        return "Le pilier du véhicule garé disparaît : endormi, il se réveille et tombe";
    }

    private static void ecrire(Path dossier, int premiere, int repetitions, int[] argb, int largeur, int hauteur,
            String legende, String valeurs, String temps) {
        try {
            BufferedImage image = new BufferedImage(LARGEUR, HAUTEUR_JEU + BANDEAU, BufferedImage.TYPE_INT_RGB);
            int[] rgb = new int[argb.length];
            for (int i = 0; i < argb.length; i++) {
                int p = argb[i];
                // makePixelArray rend déjà de l'ARGB : l'échanger inversait rouge et bleu.
                rgb[i] = p & 0xFFFFFF;
            }
            // La fenêtre peut avoir une autre taille : l'image entière, mise à l'échelle.
            BufferedImage jeu = new BufferedImage(largeur, hauteur, BufferedImage.TYPE_INT_RGB);
            jeu.setRGB(0, 0, largeur, hauteur, rgb, 0, largeur);
            Graphics2D g = image.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(jeu, 0, 0, LARGEUR, HAUTEUR_JEU, null);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(new Color(18, 20, 26));
            g.fillRect(0, HAUTEUR_JEU, LARGEUR, BANDEAU);
            g.setColor(Color.WHITE);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 20));
            g.drawString(legende, 16, HAUTEUR_JEU + 26);
            g.setColor(new Color(170, 200, 235));
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 18));
            g.drawString(valeurs, 16, HAUTEUR_JEU + 52);
            g.drawString("AXION ENGINE, C-33   " + temps, LARGEUR - 280, HAUTEUR_JEU + 52);
            g.dispose();
            Path fichier = dossier.resolve(nom(premiere));
            ImageIO.write(image, "png", fichier.toFile());
            for (int k = 1; k < repetitions; k++) {
                Files.createLink(dossier.resolve(nom(premiere + k)), fichier);
            }
        } catch (IOException | RuntimeException faute) {
            echec = "écriture : " + faute;
            LOGGER.error("écriture d'une image", faute);
        }
    }

    private static String nom(int rang) {
        return String.format(Locale.ROOT, "f%06d.png", rang);
    }

    /** {@return ce que le film prétend montrer et qui ne s'est pas produit, ou {@code null}} */
    private static String jugement(Minecraft minecraft) {
        if (!capotDevant) {
            return "le véhicule ne roule pas capot en avant";
        }
        if (zMax < 100.0) {
            return "le véhicule n'a pas atteint le mur : z max " + zMax;
        }
        Entity garee = minecraft.level.getEntity(idGare);
        if (garee == null || !(garee.getY() < hauteurGareInitiale - 2.0)) {
            return "le véhicule garé n'est pas tombé";
        }
        return null;
    }

    private static void retirerLePilier(Minecraft minecraft) {
        ServerLevel level = minecraft.getSingleplayerServer().overworld();
        for (int x = 11; x <= 16; x++) {
            for (int z = 96; z <= 102; z++) {
                for (int y = 0; y < 3; y++) {
                    level.setBlockAndUpdate(new BlockPos(x, SOL + y, z), Blocks.AIR.defaultBlockState());
                }
            }
        }
    }

    private static void terminer(Minecraft minecraft, String motif) {
        if (phase == Phase.FIN) {
            return;
        }
        passer(Phase.FIN);
        String faute = motif;
        if (ecriture != null) {
            ecriture.shutdown();
            try {
                if (!ecriture.awaitTermination(120, TimeUnit.SECONDS)) {
                    faute = "écriture non terminée";
                }
            } catch (InterruptedException interrompu) {
                Thread.currentThread().interrupt();
            }
        }
        String bilan = String.format(Locale.ROOT, "{\"images\": %d, \"uniques\": %d, \"echec\": %s}%n", imagesEcrites,
                imagesUniques, faute == null ? "null" : "\"" + faute.replace('"', '\'') + "\"");
        try {
            Files.createDirectories(dossier(minecraft));
            Files.writeString(dossier(minecraft).resolve("termine.json"), bilan, StandardCharsets.UTF_8);
        } catch (IOException faute2) {
            LOGGER.error("bilan non écrit", faute2);
        }
        try {
            Files.write(dossier(minecraft).resolve("serveur.csv"), MESURES_SERVEUR, StandardCharsets.UTF_8);
            Files.write(dossier(minecraft).resolve("client.csv"), MESURES_CLIENT, StandardCharsets.UTF_8);
        } catch (IOException faute3) {
            LOGGER.error("mesures non écrites", faute3);
        }
        LOGGER.info("[film] bilan : {}", bilan.trim());
        minecraft.stop();
    }
}
