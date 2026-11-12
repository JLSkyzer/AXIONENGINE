package dev.axion.forge;

import com.mojang.authlib.GameProfile;
import dev.axion.AxionMod;
import dev.axion.api.assembly.Parameters;
import dev.axion.entity.AssemblyParameters;
import dev.axion.physics.SimCommandStream;
import dev.axion.seat.GearRange;
import dev.axion.seat.VehicleInput;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Les films des paramètres d'instance (ADR-167) : deux buggys identiques côte à côte, l'un réglé, partent de l'arrêt
 * pied au plancher. Le client, qui reçoit les modificateurs et les variables et non les valeurs, lit les mêmes nombres
 * que le serveur, au bit près — le bandeau les montre. Deux scènes :
 *
 * <ul>
 *   <li>{@code parametres} (5a) : le réglé l'est par {@code /axion params} — couple divisé par deux, 170 kg de
 *       charge — et accélère moins ;</li>
 *   <li>{@code sport} (5b) : un véhicule dont une commande liée bascule le mode sport — une variable qui règle couple,
 *       limiteur et adhérence — ; le réglé va plus loin. La definition vient de {@code axion.film.definition} : la
 *       Charger locale si elle est là, le buggy d'exemple sinon.</li>
 * </ul>
 *
 * <p>Deux conducteurs factices tiennent les volants, et passent les rapports d'une boîte manuelle ; le joueur filme,
 * hors du champ. Chaque étape s'enchaîne sur ce que le serveur ou le client constate, et le film se juge lui-même avant
 * de s'arrêter ({@code termine.json}). Inerte sans {@code -Daxion.film=parametres} ou {@code sport} ; les lancements
 * Gradle {@code runFilmParametres} et {@code runFilmSport} la posent. Rien ici ne part dans le mod livré.
 */
@Mod.EventBusSubscriber(modid = AxionMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class FilmDesParametres {

    private static final Logger LOGGER = LoggerFactory.getLogger("AXION film");

    /** Les scènes : la definition filmée, ce que le bandeau montre du réglé, et la signature. */
    private enum Scene {
        PARAMETRES("parametres", "axion:test/vehicule", List.of(Parameters.ENGINE_TORQUE, Parameters.MASS),
                "AXION ENGINE, ADR-167 5a"),
        SPORT("sport", "axion:example/buggy",
                List.of(Parameters.ENGINE_TORQUE, Parameters.ENGINE_MAX_RPM, Parameters.TIRES_GRIP),
                "AXION ENGINE, ADR-167 5b");

        final String cle;
        final String definition;
        final List<String> montres;
        final String signature;

        Scene(String cle, String definition, List<String> montres, String signature) {
            this.cle = cle;
            this.definition = definition;
            this.montres = montres;
            this.signature = signature;
        }

        static Scene de(String cle) {
            for (Scene scene : values()) {
                if (scene.cle.equals(cle)) {
                    return scene;
                }
            }
            return null;
        }
    }

    private static final Scene SCENE = Scene.de(System.getProperty("axion.film"));
    private static final boolean ACTIF = SCENE != null;
    private static final String MONDE = ACTIF ? "axion-film-" + SCENE.cle : "";
    private static final String DEFINITION = !ACTIF ? ""
            : SCENE == Scene.SPORT ? System.getProperty("axion.film.definition", SCENE.definition) : SCENE.definition;

    /** Le sol du monde plat : la première couche d'air. */
    private static final int SOL = -60;

    /** La piste : demi-largeur et longueur, en blocs. */
    private static final int DEMI_LARGEUR = 26;

    private static final int MUR = 200;

    /** Les départs, avant (-Z du modèle) tourné vers +Z : le réglé au premier plan, côté caméra ; le témoin au fond. */
    private static final Vec3 DEPART_REGLE = new Vec3(-2.5, SOL + 0.5, 10.5);

    private static final Vec3 DEPART_TEMOIN = new Vec3(3.5, SOL + 0.5, 10.5);

    private static final float[] VERS_PLUS_Z = {0f, 1f, 0f, 0f};

    /** Les deux réglages de la scène {@code parametres}, par la commande, comme un administrateur les taperait. */
    private static final String BRIDE = "set film:bride engine.torque multiply_total -0.5";

    private static final String CHARGE = "set film:charge mass add 170";

    /** La variable du mode sport, dans la scène {@code sport} ; sa commande liée se lit au siège. */
    private static final String VARIABLE_SPORT = "sport";

    /** La commande liée du mode sport, lue au siège du conducteur. */
    private static volatile int commandeSport;

    /** Le régime, en part du limiteur effectif, où un conducteur factice passe le rapport suivant. */
    private static final double PASSAGE = 0.9;

    private static final long ATTENTE_MAX_NS = TimeUnit.SECONDS.toNanos(180);

    private enum Phase { TITRE, CREATION, MONDE, DECOR, VEHICULES, ASSEOIR, REGLAGE, TOURNAGE, FIN }

    /** Les étapes filmées, et leur durée en secondes. */
    private enum Etape {
        ARRET(2.0), COURSE(8.0), BILAN(2.0);

        final double duree;

        Etape(double duree) {
            this.duree = duree;
        }
    }

    private static Phase phase = Phase.TITRE;
    private static long debutPhase = System.nanoTime();
    private static boolean dansUneEtape;
    private static volatile boolean decorPret;
    private static volatile boolean assis;
    private static volatile boolean regle;

    private static volatile AxionEntity regleServeur;
    private static volatile AxionEntity temoinServeur;
    private static volatile int idRegle = -1;
    private static volatile int idTemoin = -1;
    private static ServerPlayer piloteRegle;
    private static ServerPlayer piloteTemoin;

    private static Tournage tournage;
    private static Entity camera;
    private static double zCamera = Double.NaN;
    private static volatile Etape etape = Etape.ARRET;

    // Ce que le serveur mesure à chaque tick.
    private static volatile double vitesseRegle;
    private static volatile double vitesseTemoin;
    private static volatile double parcoursRegle;
    private static volatile double parcoursTemoin;
    private static double[] precedentRegle;
    private static double[] precedentTemoin;
    /** Le rapport que chaque conducteur demande : 0 pour une boîte automatique, qui choisit seule. */
    private static int rapportRegle;
    private static int rapportTemoin;
    /** Les valeurs effectives au serveur des paramètres montrés du réglé, en bits, pour les comparer au client. */
    private static volatile long[] bitsServeur = new long[0];

    // Le jugement : les valeurs que le client lit des paramètres montrés, du réglé et du témoin.
    private static double[] reglesClient = new double[0];
    private static double[] basesClient = new double[0];
    private static double[] temoinsClient = new double[0];
    private static String ecartMiroir;

    private static final List<String> MESURES = new ArrayList<>();

    private FilmDesParametres() {}

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

    private static boolean depuis(double secondes) {
        if (System.nanoTime() - debutPhase > ATTENTE_MAX_NS) {
            throw new IllegalStateException("attente trop longue");
        }
        return System.nanoTime() - debutPhase > (long) (secondes * 1e9);
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
                    minecraft.getSingleplayerServer().execute(() -> poserLeDecor(minecraft.getSingleplayerServer()));
                    passer(Phase.DECOR);
                }
            }
            case DECOR -> {
                minecraft.player.getAbilities().flying = true;
                if (decorPret && depuis(4.0)) {
                    passer(Phase.VEHICULES);
                }
            }
            case VEHICULES -> {
                // Une definition refusée ne viendra jamais : le film s'arrête et dit pourquoi, au lieu d'attendre.
                if (System.nanoTime() - debutPhase > TimeUnit.SECONDS.toNanos(45)) {
                    throw new IllegalStateException("definition " + DEFINITION + " indisponible : voir le journal");
                }
                minecraft.player.getAbilities().flying = true;
                minecraft.getSingleplayerServer().execute(() -> poserLesVehicules(minecraft.getSingleplayerServer()));
                if (idRegle >= 0 && idTemoin >= 0 && minecraft.level.getEntity(idRegle) != null
                        && minecraft.level.getEntity(idTemoin) != null && depuis(3.0)) {
                    minecraft.getSingleplayerServer().execute(() -> asseoir(minecraft.getSingleplayerServer()));
                    passer(Phase.ASSEOIR);
                }
            }
            case ASSEOIR -> {
                if (assis && depuis(2.0)) {
                    minecraft.getSingleplayerServer().execute(() -> regler(minecraft.getSingleplayerServer()));
                    passer(Phase.REGLAGE);
                }
            }
            case REGLAGE -> {
                // D8 : le client a reçu les modificateurs et les variables, et compose ; on ne filme qu'une fois
                // qu'il lit les valeurs du serveur.
                if (regle && depuis(1.0)) {
                    // Tant que le snapshot n'est pas arrivé, le client lit encore l'avant : on relit à chaque image.
                    ecartMiroir = null;
                    lireLeClient(minecraft);
                    if (reglageLu()) {
                        camera = new ArmorStand(EntityType.ARMOR_STAND, minecraft.level);
                        camera.setInvisible(true);
                        minecraft.setCameraEntity(camera);
                        placerLaCamera(minecraft, 0f);
                        tournage = new Tournage(dossier(minecraft).resolve("images"), SCENE.signature);
                        passer(Phase.TOURNAGE);
                    }
                }
            }
            case TOURNAGE -> tourner(minecraft);
            case FIN -> { }
            default -> throw new IllegalStateException();
        }
    }

    /** Une piste plate, une ligne blanche tous les dix blocs pour lire les distances, un mur au bout. */
    private static void poserLeDecor(MinecraftServer serveur) {
        ServerLevel level = serveur.overworld();
        level.setDayTime(6000);
        for (int x = -DEMI_LARGEUR; x <= DEMI_LARGEUR; x++) {
            for (int z = 0; z < MUR; z++) {
                boolean ligne = z >= 10 && z % 10 == 0;
                level.setBlockAndUpdate(new BlockPos(x, SOL - 1, z),
                        (ligne ? Blocks.WHITE_CONCRETE : Blocks.SMOOTH_STONE).defaultBlockState());
            }
            for (int y = 0; y < 4; y++) {
                level.setBlockAndUpdate(new BlockPos(x, SOL + y, MUR), Blocks.BRICKS.defaultBlockState());
            }
        }
        // Le joueur filme de loin, derrière la caméra : hors du champ, mais assez près pour que tout se charge.
        ServerPlayer joueur = serveur.getPlayerList().getPlayers().get(0);
        joueur.teleportTo(-34.0, SOL + 2.0, 60.0);
        decorPret = true;
    }

    private static void poserLesVehicules(MinecraftServer serveur) {
        if (idRegle >= 0) {
            return;
        }
        ServerLevel level = serveur.overworld();
        try {
            AxionEntity regleIci = AssembliesDeTest.poser(level, DEFINITION, DEPART_REGLE, VERS_PLUS_Z);
            AxionEntity temoinIci = AssembliesDeTest.poser(level, DEFINITION, DEPART_TEMOIN, VERS_PLUS_Z);
            regleServeur = regleIci;
            temoinServeur = temoinIci;
            idTemoin = temoinIci.getId();
            idRegle = regleIci.getId();
            RuntimeAccess.get().addCommandProvider(FilmDesParametres::conduireAuServeur);
        } catch (IllegalStateException pasEncore) {
            // La definition attend encore sa compilation : on repasse à la frame suivante.
        }
    }

    /** Un conducteur factice de Forge par buggy, au siège conducteur : il est tické par son véhicule. */
    private static void asseoir(MinecraftServer serveur) {
        piloteRegle = pilote(serveur, "regle");
        piloteTemoin = pilote(serveur, "temoin");
        assis = regleServeur.seatingKnown() && regleServeur.reseat(piloteRegle, "conducteur")
                && temoinServeur.reseat(piloteTemoin, "conducteur");
        if (!assis) {
            throw new IllegalStateException("les conducteurs ne s'assoient pas au siège conducteur de " + DEFINITION);
        }
    }

    private static ServerPlayer pilote(MinecraftServer serveur, String nom) {
        UUID uuid = UUID.nameUUIDFromBytes(("axion-film-" + SCENE.cle + ":" + nom).getBytes(StandardCharsets.UTF_8));
        ServerPlayer pilote = FakePlayerFactory.get(serveur.overworld(), new GameProfile(uuid, nom));
        pilote.stopRiding();
        pilote.getPersistentData().remove(AxionEntity.REMEMBERED_SEAT);
        return pilote;
    }

    /**
     * Règle le premier buggy : par la vraie commande, avec les droits du serveur (5a, D10) ; ou par la commande liée du
     * siège, comme la touche du conducteur, qui bascule la variable du mode sport (5b, D4).
     */
    private static void regler(MinecraftServer serveur) {
        AssemblyParameters table = regleServeur.parameters();
        if (SCENE == Scene.PARAMETRES) {
            String cible = "axion params " + regleServeur.getUUID() + " ";
            for (String reglage : new String[] {BRIDE, CHARGE}) {
                serveur.getCommands().performPrefixedCommand(serveur.createCommandSourceStack(), cible + reglage);
            }
            if (table == null || table.modifiers().size() != 2) {
                throw new IllegalStateException("/axion params n'a pas posé les deux modificateurs : "
                        + (table == null ? "paramètres absents" : table.modifiers()));
            }
        } else {
            int siege = regleServeur.seatOf(piloteRegle);
            int variable = regleServeur.variables().declared().indexOf(VARIABLE_SPORT);
            commandeSport = regleServeur.seatBindings(siege).stream()
                    .filter(binding -> binding.variable() == variable)
                    .mapToInt(dev.axion.definition.Seating.Binding::command)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(DEFINITION + " : aucune commande liée à la variable "
                            + VARIABLE_SPORT));
            regleServeur.receiveInput(piloteRegle, siege,
                    new VehicleInput(0f, 0f, 0f, 0f, 1f, (byte) 0, 1 << (commandeSport - 1)));
            table = regleServeur.parameters();
            int couple = table.tuning().indexOf(Parameters.ENGINE_TORQUE);
            if (!(table.value(couple) > table.tuning().get(couple).base())) {
                throw new IllegalStateException("la commande " + commandeSport + " n'a pas mis le mode sport : "
                        + table.applied());
            }
        }
        regle = true;
    }

    /**
     * {@return le rapport qu'un conducteur factice demande} Une boîte automatique choisit seule (0) ; dans une boîte
     * manuelle, il part en première et passe le suivant près du limiteur effectif — celui du mode sport compris.
     */
    private static int rapport(AxionEntity vehicule, int demande) {
        AssemblyParameters table = vehicule.parameters();
        GearRange boite = vehicule.gearRange();
        if (boite.max() <= 0 || table == null) {
            return 0;
        }
        if (demande < 1) {
            return 1;
        }
        int limiteur = table.tuning().indexOf(Parameters.ENGINE_MAX_RPM);
        float regime = vehicule.netState() == null ? 0f : vehicule.netState().vehicle().rpm();
        return demande < boite.max() && regime >= PASSAGE * table.value(limiteur) ? demande + 1 : demande;
    }

    /**
     * Chaque tick du serveur : les entrées des deux conducteurs — frein à main serré, puis pied au plancher —, la
     * vitesse et le parcours de chaque buggy, et les valeurs effectives du réglé. Rien ne part au natif d'ici.
     */
    private static SimCommandStream conduireAuServeur(long tick) {
        AxionEntity regleIci = regleServeur;
        AxionEntity temoinIci = temoinServeur;
        if (regleIci == null || temoinIci == null) {
            return new SimCommandStream();
        }
        if (assis) {
            boolean roule = phase == Phase.TOURNAGE && etape != Etape.ARRET;
            rapportRegle = roule ? rapport(regleIci, rapportRegle) : 0;
            rapportTemoin = roule ? rapport(temoinIci, rapportTemoin) : 0;
            regleIci.receiveInput(piloteRegle, regleIci.seatOf(piloteRegle), entree(roule, rapportRegle));
            temoinIci.receiveInput(piloteTemoin, temoinIci.seatOf(piloteTemoin), entree(roule, rapportTemoin));
        }
        double[] regleXyz = {regleIci.getX(), regleIci.getY(), regleIci.getZ()};
        double[] temoinXyz = {temoinIci.getX(), temoinIci.getY(), temoinIci.getZ()};
        if (precedentRegle != null) {
            vitesseRegle = Math.hypot(regleXyz[0] - precedentRegle[0], regleXyz[2] - precedentRegle[2]) * 20.0;
            vitesseTemoin = Math.hypot(temoinXyz[0] - precedentTemoin[0], temoinXyz[2] - precedentTemoin[2]) * 20.0;
        }
        precedentRegle = regleXyz;
        precedentTemoin = temoinXyz;
        parcoursRegle = regleXyz[2] - DEPART_REGLE.z;
        parcoursTemoin = temoinXyz[2] - DEPART_TEMOIN.z;
        AssemblyParameters table = regleIci.parameters();
        if (table != null) {
            long[] bits = new long[SCENE.montres.size()];
            for (int i = 0; i < bits.length; i++) {
                bits[i] = Double.doubleToRawLongBits(table.value(table.tuning().indexOf(SCENE.montres.get(i))));
            }
            bitsServeur = bits;
        }
        if (phase == Phase.TOURNAGE && tournage != null) {
            synchronized (MESURES) {
                MESURES.add(String.format(Locale.ROOT, "%.3f,%s,%.4f,%.3f,%d,%.4f,%.3f,%d",
                        (System.nanoTime() - tournage.debut()) / 1e9, etape, parcoursRegle, vitesseRegle, rapportRegle,
                        parcoursTemoin, vitesseTemoin, rapportTemoin));
            }
        }
        return new SimCommandStream();
    }

    /** {@return l'entrée d'un conducteur : frein à main serré à l'arrêt, puis pied au plancher sur ce rapport} */
    private static VehicleInput entree(boolean roule, int rapport) {
        return roule
                ? new VehicleInput(1f, 0f, 0f, 0f, 0f, (byte) rapport, 0)
                : new VehicleInput(0f, 0f, 0f, 0f, 1f, (byte) 0, 0);
    }

    /**
     * La caméra regarde la piste de côté, depuis -X, en surplomb et d'assez loin pour que les deux buggys tiennent dans
     * l'image même écartés, le réglé au premier plan. Elle suit le milieu des deux, sans à-coup.
     */
    private static void placerLaCamera(Minecraft minecraft, float partiel) {
        Entity regleClient = minecraft.level == null ? null : minecraft.level.getEntity(idRegle);
        Entity temoinClient = minecraft.level == null ? null : minecraft.level.getEntity(idTemoin);
        if (camera == null || regleClient == null || temoinClient == null) {
            return;
        }
        double milieu = (regleClient.getPosition(partiel).z + temoinClient.getPosition(partiel).z) / 2.0;
        zCamera = Double.isNaN(zCamera) ? milieu : zCamera + (milieu - zCamera) * 0.08;
        Vec3 cible = new Vec3(0.5, SOL + 0.6, zCamera + 2.0);
        Vec3 oeil = new Vec3(-22.0, SOL + 10.0, Math.min(zCamera + 2.0, MUR - 30.0));
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
        // Une entité vivante regarde par sa tête : sans elle, la vue reste figée.
        if (camera instanceof LivingEntity vivante) {
            vivante.yHeadRot = lacet;
            vivante.yHeadRotO = lacet;
            vivante.yBodyRot = lacet;
            vivante.yBodyRotO = lacet;
        }
    }

    /** Rien de l'extérieur ne touche au film : souris relâchée, vue de l'entité choisie, interface cachée. */
    private static void garderLaMain(Minecraft minecraft) {
        if (minecraft.mouseHandler.isMouseGrabbed()) {
            minecraft.mouseHandler.releaseMouse();
        }
        if (minecraft.options.getCameraType() != CameraType.FIRST_PERSON) {
            minecraft.options.setCameraType(CameraType.FIRST_PERSON);
        }
        minecraft.options.hideGui = true;
    }

    private static void tourner(Minecraft minecraft) {
        double secondes = (System.nanoTime() - tournage.debut()) / 1e9;
        double fin = 0.0;
        for (Etape une : Etape.values()) {
            fin += une.duree;
            if (secondes < fin) {
                etape = une;
                break;
            }
        }
        if (secondes >= fin) {
            terminer(minecraft, tournage.echec() != null ? tournage.echec() : jugement());
            return;
        }
        if (!tournage.imageAttendue()) {
            return;
        }
        lireLeClient(minecraft);
        String nom = SCENE == Scene.PARAMETRES ? "réglé" : "sport";
        String legende = switch (etape) {
            case ARRET -> SCENE == Scene.PARAMETRES
                    ? "Deux buggys identiques ; au premier plan, réglé par /axion params"
                    : "Deux véhicules identiques ; au premier plan, mode sport mis par sa touche (commande "
                            + commandeSport + ")";
            case COURSE -> SCENE == Scene.PARAMETRES
                    ? "Pied au plancher depuis l'arrêt : seul le buggy réglé accélère moins"
                    : "Pied au plancher depuis l'arrêt : le mode sport va plus loin";
            case BILAN -> SCENE == Scene.PARAMETRES
                    ? "Le client reçoit les modificateurs et compose : les valeurs du serveur, au bit près"
                    : "Le client reçoit la variable et compose : les valeurs du serveur, au bit près";
        };
        String valeurs = etape == Etape.ARRET && SCENE == Scene.PARAMETRES
                ? "/axion params … " + BRIDE + "     /axion params … " + CHARGE
                // + 0.0 : un parcours de -0.0 à l'arrêt s'écrit 0.
                : String.format(Locale.ROOT, "%s %.1f m/s, %.0f m    témoin %.1f m/s, %.0f m    au client : %s",
                        nom, vitesseRegle, parcoursRegle + 0.0, vitesseTemoin, parcoursTemoin + 0.0, montres());
        tournage.capturer(minecraft.getMainRenderTarget(), legende, valeurs,
                String.format(Locale.ROOT, "%.1f s", secondes));
    }

    /**
     * Ce que le client lit de ses buggys, à chaque image : le miroir des paramètres montrés du réglé, qui doit valoir au
     * bit près les valeurs du serveur, et celui du témoin, qui reste à sa base.
     */
    private static void lireLeClient(Minecraft minecraft) {
        if (!(minecraft.level.getEntity(idRegle) instanceof AxionEntity regleClient)
                || !(minecraft.level.getEntity(idTemoin) instanceof AxionEntity temoinClient)) {
            ecartMiroir = "un buggy a disparu du client";
            return;
        }
        AssemblyParameters miroir = regleClient.parameters();
        AssemblyParameters miroirTemoin = temoinClient.parameters();
        if (miroir == null || miroirTemoin == null) {
            ecartMiroir = "paramètres illisibles au client";
            return;
        }
        int n = SCENE.montres.size();
        double[] regles = new double[n];
        double[] bases = new double[n];
        double[] temoins = new double[n];
        long[] serveur = bitsServeur;
        for (int i = 0; i < n; i++) {
            int index = miroir.tuning().indexOf(SCENE.montres.get(i));
            regles[i] = miroir.value(index);
            bases[i] = miroir.tuning().get(index).base();
            temoins[i] = miroirTemoin.value(miroirTemoin.tuning().indexOf(SCENE.montres.get(i)));
            if (ecartMiroir == null && (serveur.length != n || Double.doubleToRawLongBits(regles[i]) != serveur[i])) {
                ecartMiroir = "le client lit « " + SCENE.montres.get(i) + " » à " + regles[i] + ", le serveur à "
                        + (serveur.length == n ? Double.longBitsToDouble(serveur[i]) : "?");
            }
        }
        reglesClient = regles;
        basesClient = bases;
        temoinsClient = temoins;
    }

    /** {@return vrai si le client lit le réglage du serveur : au bit près, et autre que la base} */
    private static boolean reglageLu() {
        return ecartMiroir == null && reglesClient.length > 0 && reglesClient[0] != basesClient[0];
    }

    /** {@return les paramètres montrés du réglé, lus au client : de leur base à leur valeur} */
    private static String montres() {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < reglesClient.length; i++) {
            String base = nombre(basesClient[i]);
            String valeur = nombre(reglesClient[i]);
            parts.add(switch (SCENE.montres.get(i)) {
                case Parameters.ENGINE_TORQUE -> "couple " + base + " → " + valeur + " N·m";
                case Parameters.MASS -> "masse " + base + " → " + valeur + " kg";
                case Parameters.ENGINE_MAX_RPM -> "limiteur " + base + " → " + valeur + " tr/min";
                case Parameters.TIRES_GRIP -> "adhérence ×" + base + " → ×" + valeur;
                default -> SCENE.montres.get(i) + " " + base + " → " + valeur;
            });
        }
        return String.join(", ", parts);
    }

    private static String nombre(double valeur) {
        return Double.isNaN(valeur) ? "?" : valeur == Math.rint(valeur)
                ? Long.toString((long) valeur)
                : String.format(Locale.ROOT, "%.1f", valeur);
    }

    /** {@return ce que le film prétend montrer et qui ne s'est pas produit, ou {@code null}} */
    private static String jugement() {
        if (ecartMiroir != null) {
            return ecartMiroir;
        }
        if (!(parcoursTemoin >= 5.0)) {
            return String.format(Locale.ROOT, "le témoin n'a parcouru que %.2f m", parcoursTemoin);
        }
        boolean attendu = SCENE == Scene.PARAMETRES
                ? parcoursRegle < 0.85 * parcoursTemoin
                : parcoursRegle > 1.05 * parcoursTemoin;
        if (!attendu) {
            return String.format(Locale.ROOT, "le réglé a parcouru %.2f m contre %.2f m pour le témoin", parcoursRegle,
                    parcoursTemoin);
        }
        if (SCENE == Scene.PARAMETRES && !(reglesClient[0] == basesClient[0] * 0.5
                && reglesClient[1] == basesClient[1] + 170.0)) {
            return "le client lit couple " + reglesClient[0] + " et masse " + reglesClient[1];
        }
        for (int i = 0; i < temoinsClient.length; i++) {
            if (temoinsClient[i] != basesClient[i]) {
                return "le témoin a « " + SCENE.montres.get(i) + " » à " + temoinsClient[i] + " au client : il n'est"
                        + " pas réglé";
            }
        }
        return null;
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
        String bilan = String.format(Locale.ROOT,
                "{\"scene\": \"%s\", \"images\": %d, \"uniques\": %d, \"echec\": %s, \"parcours_regle\": %.2f,"
                        + " \"parcours_temoin\": %.2f, \"regle_client\": \"%s\", \"capture_ms_mediane\": %.1f,"
                        + " \"capture_ms_p90\": %.1f}%n",
                SCENE.cle, tournage == null ? 0 : tournage.images(), tournage == null ? 0 : tournage.uniques(),
                faute == null ? "null" : "\"" + faute.replace('"', '\'') + "\"", parcoursRegle, parcoursTemoin,
                montres(), tournage == null ? 0.0 : tournage.quantileMs(0.5),
                tournage == null ? 0.0 : tournage.quantileMs(0.9));
        try {
            Files.createDirectories(dossier(minecraft));
            Files.writeString(dossier(minecraft).resolve("termine.json"), bilan, StandardCharsets.UTF_8);
            synchronized (MESURES) {
                Files.write(dossier(minecraft).resolve("serveur.csv"), MESURES, StandardCharsets.UTF_8);
            }
        } catch (IOException faute2) {
            LOGGER.error("bilan non écrit", faute2);
        }
        LOGGER.info("[film] bilan : {}", bilan.trim());
        minecraft.stop();
    }
}
