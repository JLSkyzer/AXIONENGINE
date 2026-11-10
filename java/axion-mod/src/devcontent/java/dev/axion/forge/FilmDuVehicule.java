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
import javax.imageio.ImageIO;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.ParticleStatus;
import net.minecraft.client.Screenshot;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
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
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Le film de l'avancée des véhicules : un client qui joue sa scène seul, se filme à 60 images par
 * seconde en lisant son framebuffer, et s'arrête (recette de {@code D:\BDC}, ADR-159).
 *
 * <p>Le joueur est assis au siège conducteur et conduit par les vraies touches du client, comme le banc
 * de rendu (ADR-158 D4) : démarrage, ligne droite sur des tapis, slalom, freinage, arrêt, marche arrière.
 * Chaque étape s'enchaîne sur ce que le serveur mesure, jamais sur une horloge écrite à l'avance, et le
 * bandeau affiche des valeurs lues en direct. Le joueur étant assis, la caméra est une entité du seul
 * client. Inerte sans {@code -Daxion.film} ; le lancement Gradle {@code runFilmVehicule} la pose. Rien ici
 * ne part dans le mod livré.
 */
@Mod.EventBusSubscriber(modid = AxionMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class FilmDuVehicule {

    private static final Logger LOGGER = LoggerFactory.getLogger("AXION film");
    private static final boolean ACTIF = System.getProperty("axion.film") != null;
    private static final String MONDE = "axion-film";

    /** Le sol du monde plat : la première couche d'air. */
    private static final int SOL = -60;

    /** Durée filmée au plus, en secondes. */
    private static final double DUREE = 32.0;

    /** La piste : demi-largeur et longueur, en blocs ; le mur du bout est à {@link #MUR}. */
    private static final int DEMI_LARGEUR = 26;

    private static final int MUR = 200;

    /** Le départ du véhicule, son avant (-Z du modèle) tourné vers +Z. */
    private static final Vec3 DEPART = new Vec3(0.5, SOL + 0.5, 10.5);

    /** Les tapis sous les roues, de ce Z à celui-ci. */
    private static final int TAPIS_DEBUT = 34;

    private static final int TAPIS_FIN = 56;

    /** Distance en ligne droite avant le slalom, en blocs. */
    private static final double LIGNE_DROITE = 70.0;

    /** Le cap visé de chaque côté du slalom. */
    private static final double CAP_SLALOM = Math.toRadians(35.0);

    /** L'horizon auquel le pilote du film juge le cap, en secondes. */
    private static final double ANTICIPATION_S = 0.4;
    /** Vitesse tenue pendant le slalom, en m/s. */
    private static final double VITESSE_SLALOM = 9.0;

    private static final long IMAGE_NS = 1_000_000_000L / 60L;
    /** L'écart toléré entre l'axe de la vue et le véhicule : au-delà, il sort de l'image ou s'y colle au bord. */
    private static final double CHAMP_TOLERE_DEG = 20.0;
    private static final int LARGEUR = 1280;
    private static final int HAUTEUR_JEU = 656;
    private static final int BANDEAU = 64;

    /** Délai maximal d'une phase d'attente. */
    private static final long ATTENTE_MAX_NS = TimeUnit.SECONDS.toNanos(180);

    private enum Phase { TITRE, CREATION, MONDE, DECOR, VEHICULE, ASSEOIR, TOURNAGE, FIN }

    /** Les étapes de la scène, chacune avec sa durée maximale, en secondes. */
    private enum Etape {
        ASSIS(2.0), LIGNE(16.0), SLALOM_GAUCHE(5.0), SLALOM_DROITE(5.0), REDRESSE(6.0), FREIN(7.0), ARRET(1.0),
        RECUL(3.5), FIN(Double.POSITIVE_INFINITY);

        final double duree;

        Etape(double duree) {
            this.duree = duree;
        }
    }

    private static Phase phase = Phase.TITRE;
    private static long debutPhase = System.nanoTime();
    private static boolean dansUneEtape;
    private static boolean decorPret;
    private static volatile int idVehicule = -1;
    private static volatile AxionEntity vehiculeServeur;
    private static volatile boolean assis;
    private static Entity camera;

    private static volatile Etape etape = Etape.ASSIS;
    private static double debutEtape;

    // Ce que le serveur mesure à chaque tick, lu par le client pour enchaîner ses étapes.
    private static volatile double vitesse;
    private static volatile double cap;
    /** La vitesse de lacet du véhicule au serveur, en rad/s, mesurée d'un tick au suivant. */
    private static volatile double vitesseDeLacet;
    private static volatile double xServeur = DEPART.x;
    private static volatile double zServeur = DEPART.z;
    private static double[] precedente;
    private static volatile boolean capotDevant;

    // Le jugement : ce que le film prétend montrer.
    private static double zDebutLigne = Double.NaN;
    private static double distanceAvant = Double.NaN;
    /**
     * Depuis quand le cap reste près de celui du départ : le freinage n'attend pas seulement que le cap y
     * repasse, il attend que la voiture ait cessé de tourner, sans quoi elle achève sa rotation en freinant.
     */
    private static double capStableDepuis = Double.NaN;
    /** Depuis quand le véhicule est arrêté au frein à main, à la dernière étape. */
    private static double arreteDepuis = Double.NaN;
    private static double capMin = Double.POSITIVE_INFINITY;
    private static double capMax = Double.NEGATIVE_INFINITY;
    private static double vitesseApresFrein = Double.NaN;
    private static double[] departRecul;
    private static double capRecul;
    private static double recul = Double.NaN;

    private static final java.util.List<String> MESURES_SERVEUR = new java.util.ArrayList<>();
    private static final java.util.List<String> MESURES_CLIENT = new java.util.ArrayList<>();

    /** La definition filmée. */
    private static final String DEFINITION = System.getProperty("axion.film.definition", "axion:test/vehicule");

    private static long debutFilm;
    private static int imagesEcrites;
    private static int imagesUniques;
    private static double xCamera = Double.NaN;
    /** Vrai quand l'image se tourne du siège conducteur, faux depuis la caméra qui suit le véhicule. */
    private static boolean vueConducteur;
    /** La première image capturée qui ne montre pas le véhicule depuis la caméra du film, ou {@code null}. */
    private static String cameraEgaree;
    /** Le joueur, dans le repère de la carrosserie dessinée, à la première image capturée. */
    private static Vector3f passagerInitial;
    private static double lacetPassagerInitial = Double.NaN;
    /** Ce que le joueur dessiné s'écarte le plus de cette place, en blocs, et son regard de cet angle, en degrés. */
    private static double ecartPassager;
    private static double ecartLacetPassager;
    private static double zCamera = Double.NaN;
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
                garderLaMain(minecraft);
                placerLaCamera(minecraft, event.renderTickTime);
                choisirLaVue(minecraft);
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
                    passer(Phase.VEHICULE);
                }
            }
            case VEHICULE -> {
                // Une definition refusée ne viendra jamais : inutile de laisser le jeu tourner à vide.
                if (System.nanoTime() - debutPhase > TimeUnit.SECONDS.toNanos(45)) {
                    throw new IllegalStateException("definition " + DEFINITION + " indisponible : voir le journal");
                }
                minecraft.player.getAbilities().flying = true;
                minecraft.getSingleplayerServer().execute(() -> poserLeVehicule(minecraft));
                if (idVehicule >= 0 && minecraft.level.getEntity(idVehicule) != null
                        && System.nanoTime() - debutPhase > TimeUnit.SECONDS.toNanos(3)) {
                    minecraft.getSingleplayerServer().execute(() -> asseoirLeJoueur(minecraft));
                    passer(Phase.ASSEOIR);
                }
            }
            case ASSEOIR -> {
                // ADR-158 D3 : le serveur assoit le joueur au siège conducteur, et le client le voit assis.
                garde();
                if (assis && minecraft.player.getVehicle() instanceof AxionEntity
                        && System.nanoTime() - debutPhase > TimeUnit.SECONDS.toNanos(2)) {
                    camera = new ArmorStand(EntityType.ARMOR_STAND, minecraft.level);
                    camera.setInvisible(true);
                    minecraft.setCameraEntity(camera);
                    regarderDevant(minecraft);
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
     * Une piste plate et large, des tapis d'un seizième de bloc sous les seules roues, un mur au bout, loin
     * de la scène. Pas de pente : un rayon vertical voit des marches plates, sans dénivelé.
     */
    private static void poserLeDecor(Minecraft minecraft) {
        ServerLevel level = minecraft.getSingleplayerServer().overworld();
        level.setDayTime(6000);
        for (int x = -DEMI_LARGEUR; x <= DEMI_LARGEUR; x++) {
            for (int z = 0; z < MUR; z++) {
                level.setBlockAndUpdate(new BlockPos(x, SOL - 1, z), Blocks.SMOOTH_STONE.defaultBlockState());
            }
            for (int y = 0; y < 4; y++) {
                level.setBlockAndUpdate(new BlockPos(x, SOL + y, MUR), Blocks.BRICKS.defaultBlockState());
            }
        }
        // La caisse passe au-dessus des tapis ; les roues, en x 0,5 ± 0,7 (buggy) ou ± 1,27 (Charger locale),
        // roulent dessus.
        for (int z = TAPIS_DEBUT; z < TAPIS_FIN; z += 3) {
            for (int x : new int[] {-1, 1}) {
                level.setBlockAndUpdate(new BlockPos(x, SOL, z), Blocks.WHITE_CARPET.defaultBlockState());
            }
        }
        // Des repères de 2 blocs en bord de piste, tous les 10 blocs : la vitesse se lit à l'image.
        for (int z = 10; z < MUR; z += 10) {
            level.setBlockAndUpdate(new BlockPos(-DEMI_LARGEUR, SOL, z), Blocks.YELLOW_CONCRETE.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(DEMI_LARGEUR, SOL, z), Blocks.YELLOW_CONCRETE.defaultBlockState());
        }
        minecraft.getSingleplayerServer().getPlayerList().getPlayers().get(0)
                .teleportTo(DEPART.x - 3.0, SOL + 2.0, DEPART.z);
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
            RuntimeAccess.get().addCommandProvider(FilmDuVehicule::mesurerAuServeur);
        } catch (IllegalStateException pasEncore) {
            // La definition attend encore sa compilation : on repasse à la frame suivante.
        }
    }

    /** ADR-158 D3, comme le banc : le joueur au siège conducteur, de force. */
    private static void asseoirLeJoueur(Minecraft minecraft) {
        ServerPlayer joueur = minecraft.getSingleplayerServer().getPlayerList().getPlayers().get(0);
        joueur.getAbilities().flying = false;
        assis = vehiculeServeur.seatingKnown() && vehiculeServeur.reseat(joueur, "conducteur");
        if (!assis) {
            throw new IllegalStateException("le joueur ne s'assoit pas au siège conducteur de " + DEFINITION);
        }
    }

    /**
     * Ce que le serveur mesure à chaque tick : vitesse, cap, position, et si le véhicule roule capot en avant.
     * Rien ne part au natif : la conduite passe par les touches du client.
     */
    private static SimCommandStream mesurerAuServeur(long tick) {
        AxionEntity cible = vehiculeServeur;
        if (cible != null) {
            double[] ici = {cible.getX(), cible.getY(), cible.getZ()};
            Vector3f avant = cible.bodyRotation().transform(new Vector3f(0f, 0f, -1f));
            double capMesure = Math.atan2(avant.x, avant.z);
            if (precedente != null) {
                vitesseDeLacet = Math.IEEEremainder(capMesure - cap, 2.0 * Math.PI) * 20.0;
            }
            cap = capMesure;
            xServeur = ici[0];
            zServeur = ici[2];
            if (precedente != null) {
                double vx = (ici[0] - precedente[0]) * 20.0;
                double vz = (ici[2] - precedente[2]) * 20.0;
                vitesse = Math.sqrt(vx * vx + vz * vz);
                // §6.5 : l'avant d'un véhicule est son -Z ; en ligne droite, il roule dans ce sens, vers +Z.
                if (etape == Etape.LIGNE && vitesse > 2.0) {
                    capotDevant = avant.z > 0.9f && vx * avant.x + vz * avant.z > 0.0;
                }
            }
            precedente = ici;
            synchronized (MESURES_SERVEUR) {
                MESURES_SERVEUR.add(String.format(Locale.ROOT, "%.3f,%s,%.4f,%.4f,%.4f,%.3f,%.4f",
                        (System.nanoTime() - debutFilm) / 1e9, etape, ici[0], ici[1] - SOL, ici[2], vitesse, cap));
            }
        }
        return new SimCommandStream();
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
        debutEtape = 0.0;
    }

    /**
     * La caméra suit le véhicule de côté, sans à-coup : une entité du seul client, posée à chaque frame à la
     * position interpolée du véhicule, du côté -X de la piste, jamais au-delà du mur du bout.
     */
    private static void placerLaCamera(Minecraft minecraft, float partiel) {
        Entity vehicule = minecraft.level == null ? null : minecraft.level.getEntity(idVehicule);
        if (camera == null || vehicule == null) {
            return;
        }
        Vec3 cible = vehicule.getPosition(partiel).add(0, 0.9, 0);
        xCamera = Double.isNaN(xCamera) ? cible.x : xCamera + (cible.x - xCamera) * 0.05;
        zCamera = Double.isNaN(zCamera) ? cible.z : zCamera + (cible.z - zCamera) * 0.06;
        Vec3 oeil = new Vec3(Math.max(xCamera - 9.0, -DEMI_LARGEUR + 1.0), SOL + 2.6,
                Math.min(zCamera + 5.5, MUR - 8.0));
        double piedsY = oeil.y - camera.getEyeHeight();
        camera.setPos(oeil.x, piedsY, oeil.z);
        camera.xo = camera.xOld = oeil.x;
        camera.yo = camera.yOld = piedsY;
        camera.zo = camera.zOld = oeil.z;
        double dx = cible.x - oeil.x;
        double dy = cible.y - oeil.y;
        double dz = cible.z - oeil.z;
        float lacet = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float tangage = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        camera.setYRot(lacet);
        camera.setXRot(tangage);
        camera.yRotO = lacet;
        camera.xRotO = tangage;
        // Une entité vivante regarde par sa tête : sans elle, la vue reste figée vers l'avant de la piste.
        if (camera instanceof net.minecraft.world.entity.LivingEntity vivante) {
            vivante.yHeadRot = lacet;
            vivante.yHeadRotO = lacet;
            vivante.yBodyRot = lacet;
            vivante.yBodyRotO = lacet;
        }
    }

    /**
     * Le film juge ce qu'il montre : chaque image capturée vient de la caméra du film et vise le véhicule. La vue
     * lue est celle de la frame qui vient d'être rendue, pas celle qu'on a voulu poser.
     */
    private static void verifierLaVue(Minecraft minecraft, Entity vehicule, double secondes) {
        if (cameraEgaree != null || vehicule == null) {
            return;
        }
        Camera vue = minecraft.gameRenderer.getMainCamera();
        Entity attendue = vueConducteur ? minecraft.player : camera;
        if (vue.getEntity() != attendue) {
            cameraEgaree = String.format(Locale.ROOT, "à %.1f s, la vue est celle de %s, pas de %s",
                    secondes, vue.getEntity(), attendue);
            return;
        }
        if (vueConducteur && vehicule instanceof AxionEntity assembly) {
            // L'œil du conducteur est dans l'habitacle : dans le repère de la carrosserie dessinée, au-dessus du
            // plancher, sous le toit, entre les flancs et les pare-chocs.
            AxionEntity.RenderPose dessinee = assembly.renderPose(minecraft.getFrameTime());
            Vector3f oeil = new Vector3f((float) (vue.getPosition().x - dessinee.x()),
                    (float) (vue.getPosition().y - dessinee.y()), (float) (vue.getPosition().z - dessinee.z()));
            dessinee.rotation().conjugate(new Quaternionf()).transform(oeil);
            if (Math.abs(oeil.x) > 1.4f || oeil.y < 0.5f || oeil.y > 2.2f || Math.abs(oeil.z) > 3.5f) {
                cameraEgaree = String.format(Locale.ROOT, "à %.1f s, l'œil du conducteur est hors de l'habitacle : %s",
                        secondes, oeil);
            }
            return;
        }
        Vec3 versLeVehicule = vehicule.position().add(0, 0.9, 0).subtract(vue.getPosition()).normalize();
        Vector3f regard = vue.getLookVector();
        double cosinus = versLeVehicule.x * regard.x() + versLeVehicule.y * regard.y() + versLeVehicule.z * regard.z();
        if (cosinus < Math.cos(Math.toRadians(CHAMP_TOLERE_DEG))) {
            cameraEgaree = String.format(Locale.ROOT, "à %.1f s, le véhicule est à %.0f° de l'axe de la vue",
                    secondes, Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, cosinus)))));
        }
    }

    /**
     * Le joueur assis suit-il sa place ? Sa position et son regard tels que Minecraft va les dessiner, entre ses
     * positions ancienne et courante, exprimés dans le repère de la carrosserie telle qu'elle est dessinée à cette
     * image : sans mouvement de souris, ils ne doivent pas changer, quelle que soit la vitesse.
     */
    private static void mesurerLePassager(Minecraft minecraft, AxionEntity vehicule) {
        LocalPlayer joueur = minecraft.player;
        if (joueur == null || joueur.getVehicle() != vehicule || !vehicule.interpolating()) {
            return;
        }
        float partiel = minecraft.getFrameTime();
        AxionEntity.RenderPose dessinee = vehicule.renderPose(partiel);
        Vector3f relatif = new Vector3f(
                (float) (Mth.lerp(partiel, joueur.xOld, joueur.getX()) - dessinee.x()),
                (float) (Mth.lerp(partiel, joueur.yOld, joueur.getY()) - dessinee.y()),
                (float) (Mth.lerp(partiel, joueur.zOld, joueur.getZ()) - dessinee.z()));
        dessinee.rotation().conjugate(new Quaternionf()).transform(relatif);
        Vector3f avant = dessinee.rotation().transform(new Vector3f(0f, 0f, -1f));
        double lacetVehicule = Math.toDegrees(Math.atan2(-avant.x, avant.z));
        double lacetRelatif = Mth.wrapDegrees(joueur.getViewYRot(partiel) - lacetVehicule);
        if (passagerInitial == null) {
            passagerInitial = relatif;
            lacetPassagerInitial = lacetRelatif;
            return;
        }
        ecartPassager = Math.max(ecartPassager, relatif.distance(passagerInitial));
        ecartLacetPassager = Math.max(ecartLacetPassager, Math.abs(Mth.wrapDegrees(lacetRelatif - lacetPassagerInitial)));
    }

    /**
     * Rien de l'extérieur ne touche au film : la souris relâchée ne tourne pas le joueur, la vue reste à la
     * première personne de l'entité choisie, l'interface cachée — un clic ou une touche dans la fenêtre pendant le
     * tournage ne fausse ni l'image ni ce que le film mesure.
     */
    private static void garderLaMain(Minecraft minecraft) {
        if (minecraft.mouseHandler.isMouseGrabbed()) {
            minecraft.mouseHandler.releaseMouse();
        }
        if (minecraft.options.getCameraType() != CameraType.FIRST_PERSON) {
            minecraft.options.setCameraType(CameraType.FIRST_PERSON);
        }
        minecraft.options.hideGui = true;
    }

    /** La ligne droite se voit du siège conducteur ; le reste, de la caméra qui suit le véhicule de côté. */
    private static void choisirLaVue(Minecraft minecraft) {
        vueConducteur = etape == Etape.LIGNE;
        Entity voulue = vueConducteur ? minecraft.player : camera;
        if (voulue != null && minecraft.getCameraEntity() != voulue) {
            minecraft.setCameraEntity(voulue);
        }
    }

    /** Le conducteur regarde droit devant, un peu vers le bas : la route et le tableau de bord. */
    private static void regarderDevant(Minecraft minecraft) {
        if (!(minecraft.level.getEntity(idVehicule) instanceof AxionEntity vehicule)) {
            return;
        }
        Vector3f avant = vehicule.renderPose(1f).rotation().transform(new Vector3f(0f, 0f, -1f));
        float lacet = (float) Math.toDegrees(Math.atan2(-avant.x, avant.z));
        LocalPlayer joueur = minecraft.player;
        joueur.setYRot(lacet);
        joueur.yRotO = lacet;
        joueur.setYHeadRot(lacet);
        joueur.yHeadRotO = lacet;
        joueur.yBodyRot = lacet;
        joueur.yBodyRotO = lacet;
        joueur.setXRot(3f);
        joueur.xRotO = 3f;
    }

    /** Les touches du client, comme les presserait un joueur. */
    private static void touches(Minecraft minecraft, boolean avant, boolean arriere, boolean gauche, boolean droite,
            boolean saut) {
        Options o = minecraft.options;
        o.keyUp.setDown(avant);
        o.keyDown.setDown(arriere);
        o.keyLeft.setDown(gauche);
        o.keyRight.setDown(droite);
        o.keyJump.setDown(saut);
    }

    /**
     * Braque vers le cap visé, à ± {@code tolerance} près : la touche de gauche fait grandir le cap. Le cap jugé
     * est celui qu'aura la voiture dans {@link #ANTICIPATION_S} à sa vitesse de lacet : des touches en tout ou
     * rien qui ne regardent que le cap présent contre-braquent trop tard, et la voiture oscille autour du cap.
     */
    private static boolean[] versLeCap(double vise, double tolerance) {
        double prevu = cap + vitesseDeLacet * ANTICIPATION_S;
        return new boolean[] {prevu < vise - tolerance, prevu > vise + tolerance};
    }

    /** Avance la scène d'une frame : l'étape courante presse ses touches, et passe à la suivante sur mesure. */
    private static void conduire(Minecraft minecraft, double secondes) {
        double dansLEtape = secondes - debutEtape;
        Etape suivante = null;
        switch (etape) {
            case ASSIS -> {
                touches(minecraft, false, false, false, false, false);
                suivante = dansLEtape >= etape.duree ? Etape.LIGNE : null;
            }
            case LIGNE -> {
                if (Double.isNaN(zDebutLigne)) {
                    zDebutLigne = zServeur;
                }
                boolean[] braque = versLeCap(0.0, Math.toRadians(2.0));
                touches(minecraft, true, false, braque[0], braque[1], false);
                if (zServeur - zDebutLigne >= LIGNE_DROITE) {
                    distanceAvant = zServeur - zDebutLigne;
                    suivante = Etape.SLALOM_GAUCHE;
                }
            }
            case SLALOM_GAUCHE -> {
                touches(minecraft, vitesse < VITESSE_SLALOM, false, true, false, false);
                suivante = cap >= CAP_SLALOM ? Etape.SLALOM_DROITE : null;
            }
            case SLALOM_DROITE -> {
                touches(minecraft, vitesse < VITESSE_SLALOM, false, false, true, false);
                suivante = cap <= -CAP_SLALOM ? Etape.REDRESSE : null;
            }
            case REDRESSE -> {
                boolean[] braque = versLeCap(0.0, Math.toRadians(3.0));
                touches(minecraft, vitesse < VITESSE_SLALOM, false, braque[0], braque[1], false);
                if (Math.abs(cap) >= Math.toRadians(5.0)) {
                    capStableDepuis = Double.NaN;
                } else if (Double.isNaN(capStableDepuis)) {
                    capStableDepuis = secondes;
                }
                suivante = !Double.isNaN(capStableDepuis) && secondes - capStableDepuis >= 0.6 ? Etape.FREIN : null;
            }
            case FREIN -> {
                touches(minecraft, false, true, false, false, false);
                suivante = vitesse < 0.3 ? Etape.ARRET : null;
            }
            case ARRET -> {
                touches(minecraft, false, false, false, false, false);
                if (dansLEtape >= etape.duree) {
                    vitesseApresFrein = vitesse;
                    departRecul = new double[] {xServeur, zServeur};
                    capRecul = cap;
                    suivante = Etape.RECUL;
                }
            }
            case RECUL -> {
                // ADR-159 D6 : en boîte automatique, la touche arrière tenue près de l'arrêt engage la marche
                // arrière, où elle accélère.
                touches(minecraft, false, true, false, false, false);
                if (dansLEtape >= etape.duree) {
                    // Le recul se compte à l'opposé du cap : vers -Z de la piste pour un cap nul.
                    recul = -((xServeur - departRecul[0]) * Math.sin(capRecul)
                            + (zServeur - departRecul[1]) * Math.cos(capRecul));
                    suivante = Etape.FIN;
                }
            }
            case FIN -> touches(minecraft, false, false, false, false, true);
            default -> throw new IllegalStateException();
        }
        if (etape == Etape.SLALOM_GAUCHE || etape == Etape.SLALOM_DROITE || etape == Etape.REDRESSE) {
            capMin = Math.min(capMin, cap);
            capMax = Math.max(capMax, cap);
        }
        if (suivante == null && dansLEtape > etape.duree && etape != Etape.ASSIS && etape != Etape.ARRET
                && etape != Etape.RECUL) {
            throw new IllegalStateException("l'étape " + etape + " ne s'achève pas en " + etape.duree + " s");
        }
        if (suivante != null) {
            LOGGER.info("[film] {} -> {} à {} s", etape, suivante, String.format(Locale.ROOT, "%.2f", secondes));
            etape = suivante;
            debutEtape = secondes;
        }
    }

    private static void tourner(Minecraft minecraft) {
        double secondes = (System.nanoTime() - debutFilm) / 1e9;
        // Le frein à main se montre jusqu'à l'arrêt, puis une seconde à l'arrêt ; six secondes au plus.
        if (etape == Etape.FIN && vitesse < 0.2) {
            arreteDepuis = Double.isNaN(arreteDepuis) ? secondes : arreteDepuis;
        } else {
            arreteDepuis = Double.NaN;
        }
        boolean acheve = etape == Etape.FIN
                && (secondes - arreteDepuis >= 1.0 || secondes - debutEtape >= 6.0);
        if (secondes >= DUREE || acheve) {
            touches(minecraft, false, false, false, false, false);
            terminer(minecraft, echec != null ? echec : jugement());
            return;
        }
        conduire(minecraft, secondes);
        Entity vehicule = minecraft.level.getEntity(idVehicule);
        if (vehicule instanceof AxionEntity assembly) {
            noter(assembly);
        }
        int attendues = (int) ((System.nanoTime() - debutFilm) / IMAGE_NS);
        if (attendues < imagesEcrites) {
            return;
        }
        verifierLaVue(minecraft, vehicule, secondes);
        if (vehicule instanceof AxionEntity assembly) {
            mesurerLePassager(minecraft, assembly);
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
        double parcouru = Math.hypot(xServeur - DEPART.x, zServeur - DEPART.z);
        String valeurs = String.format(Locale.ROOT, "%svitesse %.1f m/s   cap %+.0f°   à %.1f m du départ",
                vueConducteur ? "vue du conducteur   " : "", vitesse, Math.toDegrees(cap), parcouru);
        String legende = legende();
        String temps = String.format(Locale.ROOT, "%.1f s", secondes);
        Path dossierImages = dossier(minecraft).resolve("images");
        ecriture.execute(() -> ecrire(dossierImages, premiere, repetitions, pixels, largeur, hauteur, legende, valeurs, temps));
    }

    /** Position, hauteur et cap côté client, relevés tels quels : ce qu'on voit se compare à ce qui est simulé. */
    private static void noter(AxionEntity vehicule) {
        Vector3f avant = vehicule.bodyRotation().transform(new Vector3f(0f, 0f, -1f));
        synchronized (MESURES_CLIENT) {
            MESURES_CLIENT.add(String.format(Locale.ROOT, "%.3f,%s,%.4f,%.4f,%.4f,%.4f",
                    (System.nanoTime() - debutFilm) / 1e9, etape, vehicule.getX(), vehicule.getY() - SOL,
                    vehicule.getZ(), Math.atan2(avant.x, avant.z)));
        }
    }

    /** La légende de l'étape en cours : elle dit ce que les touches commandent à cet instant. */
    private static String legende() {
        return switch (etape) {
            case ASSIS -> "Au volant : le joueur est assis au siège conducteur, rien n'est pressé";
            case LIGNE -> zServeur >= TAPIS_DEBUT - 2 && zServeur < TAPIS_FIN + 2
                    ? "Touche avant : il accélère ; tapis de 6 cm sous les roues"
                    : "Touche avant : accélérateur, boîte automatique, ligne droite";
            case SLALOM_GAUCHE -> "Touche gauche : il braque à gauche";
            case SLALOM_DROITE -> "Touche droite : il braque à droite";
            case REDRESSE -> "Retour au cap de départ";
            case FREIN -> "Touche arrière : il freine";
            case ARRET -> "Arrêté";
            case RECUL -> "Touche arrière tenue à l'arrêt : la boîte passe en marche arrière";
            case FIN -> vitesse < 0.2 ? "Saut : frein à main serré, il est arrêté" : "Saut : frein à main serré";
        };
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
    private static String jugement() {
        if (cameraEgaree != null) {
            return cameraEgaree;
        }
        if (!(ecartPassager <= 0.02)) {
            return String.format(Locale.ROOT, "le joueur dessiné s'écarte de sa place de %.3f bloc", ecartPassager);
        }
        if (!(ecartLacetPassager <= 0.5)) {
            return String.format(Locale.ROOT, "le regard du joueur tourne de %.1f° par rapport au véhicule",
                    ecartLacetPassager);
        }
        if (!capotDevant) {
            return "le véhicule ne roule pas capot en avant";
        }
        if (!(distanceAvant >= LIGNE_DROITE)) {
            return "distance en marche avant : " + distanceAvant + " m";
        }
        if (!(capMax - capMin >= Math.toRadians(45.0))) {
            return String.format(Locale.ROOT, "le slalom ne change le cap que de %.0f°", Math.toDegrees(capMax - capMin));
        }
        if (!(vitesseApresFrein < 1.5)) {
            return "vitesse après le freinage : " + vitesseApresFrein + " m/s";
        }
        if (!(recul >= 1.0)) {
            return "marche arrière : " + recul + " m";
        }
        if (etape == Etape.FIN && !(vitesse < 0.2)) {
            return String.format(Locale.ROOT, "le frein à main laisse le véhicule à %.2f m/s", vitesse);
        }
        if (etape != Etape.FIN) {
            return "scène inachevée, à l'étape " + etape;
        }
        return null;
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
        String bilan = String.format(Locale.ROOT,
                "{\"images\": %d, \"uniques\": %d, \"echec\": %s, \"distance_avant\": %.2f, \"cap_slalom_deg\": %.1f,"
                        + " \"vitesse_apres_frein\": %.3f, \"recul\": %.2f, \"ecart_passager\": %.4f,"
                        + " \"ecart_lacet_passager_deg\": %.2f}%n",
                imagesEcrites, imagesUniques, faute == null ? "null" : "\"" + faute.replace('"', '\'') + "\"",
                distanceAvant, Math.toDegrees(capMax - capMin), vitesseApresFrein, recul, ecartPassager,
                ecartLacetPassager);
        try {
            Files.createDirectories(dossier(minecraft));
            Files.writeString(dossier(minecraft).resolve("termine.json"), bilan, StandardCharsets.UTF_8);
        } catch (IOException faute2) {
            LOGGER.error("bilan non écrit", faute2);
        }
        try {
            synchronized (MESURES_SERVEUR) {
                Files.write(dossier(minecraft).resolve("serveur.csv"), MESURES_SERVEUR, StandardCharsets.UTF_8);
            }
            synchronized (MESURES_CLIENT) {
                Files.write(dossier(minecraft).resolve("client.csv"), MESURES_CLIENT, StandardCharsets.UTF_8);
            }
        } catch (IOException faute3) {
            LOGGER.error("mesures non écrites", faute3);
        }
        LOGGER.info("[film] bilan : {}", bilan.trim());
        minecraft.stop();
    }
}
