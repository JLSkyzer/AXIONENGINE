package dev.axion.forge;

import dev.axion.AxionMod;
import dev.axion.forge.client.AxionRenderPass;
import dev.axion.forge.client.GlStateCheck;
import dev.axion.render.BackendSelection;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.ParticleStatus;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Banc de rendu client (ADR-126) : vérifie mécaniquement les tests de rendu de M3 — T-470 rendu
 * vanilla inchangé, T-471 aucune erreur GL sur 10 000 frames, T-472 état GL restauré après chaque
 * passe, T-473 aucun appel GL hors du render thread, T-474 assembly visible, orientée, éclairée.
 *
 * <p>Inerte sans {@code -Daxion.rendertest} ; le lancement Gradle {@code runRenderTest} le pose.
 * Il crée un monde plat neuf, fige la scène, joue son scénario frame après frame, écrit
 * {@code axion-rendertest/rapport.json} et ses captures, puis arrête le client ; la tâche Gradle
 * échoue si le rapport manque ou n'est pas vert.
 *
 * <p>Toute comparaison se fait dans un même passage : jamais contre une image de référence, qui
 * dépendrait du pilote (ADR-126 §4).
 *
 * <p>Contenu de développement, jamais empaqueté (R-1790). Render thread seul, sauf ce qu'il confie
 * au serveur intégré.
 */
@Mod.EventBusSubscriber(modid = AxionMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class BancDeRendu {

    /** Le journal du banc : il n'est pas compté parmi les erreurs d'AXION. */
    private static final Logger LOGGER = LoggerFactory.getLogger("axion.banc");

    private static final boolean ACTIF = System.getProperty("axion.rendertest") != null;

    private static final String MONDE = "axion-rendertest";

    /** T-471. */
    private static final int FRAMES_T471 = 10_000;

    /** Frames comptées sans AXION avant T-471 : les erreurs qui ne seraient pas les siennes. */
    private static final int FRAMES_SANS_AXION = 300;

    /** Au-delà, le banc s'arrête en rouge plutôt que de tourner sans fin. */
    private static final long DUREE_MAX_MS = 40 * 60_000L;

    /** Écart toléré entre la boîte d'une silhouette et celle de sa projection, en pixels. */
    private static final int ECART_BOITE = 2;

    /**
     * Une scène est stable quand {@value} relevés de suite, espacés de {@link #TICKS_ENTRE_RELEVES}
     * ticks de jeu, sont identiques à leur précédent. Un délai fixe ne suffit pas : des sections se
     * recompilent après coup — la lumière d'un bloc posé, une vue nouvelle quand la caméra tourne —,
     * et le troisième passage en CI a capturé une scène qui changeait encore. Et l'espacement se
     * compte en ticks, non en frames : Minecraft ne change d'état qu'au tick — lightmap comprise —,
     * et sur une machine rapide, des relevés espacés de quelques frames tenaient dans un seul tick
     * (premier passage local : une nuit capturée encore éclairée, une section lointaine mise à jour
     * juste après les relevés).
     */
    private static final int RELEVES_EGAUX = 5;

    private static final int TICKS_ENTRE_RELEVES = 2;

    /** Au-delà, une scène qui ne se stabilise pas fait échouer le banc. */
    private static final int STABILITE_MAX_S = 180;

    /** Assemblies posées derrière la caméra : toutes les passes, opaque, découpe, translucide, émission. */
    private static final List<String> DERRIERE = List.of(
            "axion:test_cube",
            "axion:test/materiaux/atlas",
            "axion:test/materiaux/cube_texture",
            "axion:test/materiaux/grille",
            "axion:test/materiaux/multi_materiaux",
            "axion:test/materiaux/panneau_emissif",
            "axion:test/materiaux/ressource",
            "axion:test/materiaux/sol_repete",
            "axion:test/materiaux/vitre");

    private static final String CUBE = "axion:test_cube";
    private static final float[] IDENTITE = {0f, 0f, 0f, 1f};
    /** Un huitième de tour autour de y : {@code [0, sin 22,5°, 0, cos 22,5°]}. */
    private static final float[] HUITIEME_DE_TOUR = {0f, 0.38268343f, 0f, 0.9238795f};

    /** Un pas du scénario, joué à chaque frame jusqu'à ce qu'il rende vrai. */
    @FunctionalInterface
    private interface Pas {
        boolean jouer(Minecraft minecraft);
    }

    private record Etape(String nom, Pas pas) {}

    /** Matrices et caméra d'une frame, relevées au stage {@code AFTER_ENTITIES}. */
    private record Vue(Matrix4f projection, Matrix4f pose, Vec3 camera) {}

    private static final List<Etape> SCENARIO = new ArrayList<>();
    private static final RapportDuBanc RAPPORT = new RapportDuBanc(
            List.of("backend", "T-470", "T-474", "T-471", "T-472", "T-473", "erreurs AXION"));

    /**
     * Le backend que le lancement impose ({@code -Daxion.render.backend}) : c'est lui qui doit
     * dessiner, sans quoi un repli silencieux ferait passer un backend pour l'autre (ADR-127 §7).
     */
    private static final String BACKEND_DEMANDE = System.getProperty("axion.render.backend");

    private static JournalDuBanc journal;
    private static int courante;
    private static int framesDansLEtape;
    private static long debutEtape;
    private static long debut;
    private static boolean fini;

    /**
     * Vrai pendant qu'une étape se joue. Minecraft rend lui-même des frames depuis certaines
     * actions — créer un monde boucle sur l'écran de chargement jusqu'à ce que le serveur soit
     * prêt —, et chacune de ces frames rappelle {@link #onRenderTick} : sans ce garde, l'étape se
     * rejouerait depuis elle-même. Constaté au premier passage en CI : un second
     * {@code createFreshLevel}, et le verrou du monde déjà pris par le premier.
     */
    private static boolean dansUneEtape;

    /** Ticks du client depuis le lancement : l'horloge où Minecraft change d'état. */
    private static long ticks;

    private static int surface;
    private static boolean cameraFixee;
    private static float lacet;
    private static float tangage;
    private static Vue derniereVue;
    private static AxionEntity cube;
    private static int cubePrecedent;

    private static CapturesDuBanc.Capture avec;
    private static CapturesDuBanc.Capture sans;
    private static AxionRenderPass.FrameStats passesAvec;
    private static CapturesDuBanc.Capture fond;
    private static CapturesDuBanc.Capture droit;
    private static Vue vueDroit;
    private static CapturesDuBanc.Capture tourne;
    private static Vue vueTourne;
    private static CapturesDuBanc.Capture minuit;

    private static boolean compter;
    private static long erreursFrames;
    private static long framesComptees;
    private static long erreursSansAxion;
    private static long erreursPassesAvant;
    private static long erreursAvantPassesAvant;

    private BancDeRendu() {}

    /**
     * Début de frame : la caméra reste là où le scénario la veut, quoi que fasse la souris. Fin de
     * frame : l'étape courante est jouée, l'image de la frame étant complète.
     *
     * @param event frame du client
     */
    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (!ACTIF || fini) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (event.phase == TickEvent.Phase.START) {
            fixerLaCamera(minecraft);
            return;
        }
        if (dansUneEtape) {
            return;
        }
        dansUneEtape = true;
        try {
            jouerLaFrame(minecraft);
        } finally {
            dansUneEtape = false;
        }
    }

    private static void jouerLaFrame(Minecraft minecraft) {
        if (SCENARIO.isEmpty()) {
            debut = System.currentTimeMillis();
            debutEtape = System.nanoTime();
            journal = JournalDuBanc.installer();
            scenario();
        }
        if (compter) {
            erreursFrames += viderErreursGl();
            framesComptees++;
        }
        Etape etape = SCENARIO.get(courante);
        try {
            if (System.currentTimeMillis() - debut > DUREE_MAX_MS) {
                throw new IllegalStateException("le banc a dépassé " + DUREE_MAX_MS / 60_000 + " min");
            }
            framesDansLEtape++;
            if (etape.pas().jouer(minecraft)) {
                LOGGER.info("AXION banc de rendu : « {} » fait", etape.nom());
                courante++;
                framesDansLEtape = 0;
                debutEtape = System.nanoTime();
                if (courante == SCENARIO.size()) {
                    terminer(minecraft, null);
                }
            }
        } catch (RuntimeException echec) {
            LOGGER.error("AXION banc de rendu : échec à l'étape « {} »", etape.nom(), echec);
            terminer(minecraft, "étape « " + etape.nom() + " » : " + echec);
        }
    }

    /**
     * Compte les ticks du client : la stabilité d'une scène se mesure à cette horloge.
     *
     * @param event tick du client
     */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (ACTIF && event.phase == TickEvent.Phase.END) {
            ticks++;
        }
    }

    /**
     * Relève les matrices de la frame, pour projeter ce qu'on attend à l'écran (T-474).
     *
     * @param event étape du rendu du niveau
     */
    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (ACTIF && !fini && event.getStage() == RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            derniereVue = new Vue(
                    new Matrix4f(event.getProjectionMatrix()),
                    new Matrix4f(event.getPoseStack().last().pose()),
                    event.getCamera().getPosition());
        }
    }

    private static void scenario() {
        jusqua("le jeu prêt", 600, mc -> mc.getOverlay() == null && mc.level == null && mc.screen != null);
        une("créer le monde", BancDeRendu::creerLeMonde);
        jusqua("le monde chargé", 600, mc -> mc.level != null && mc.player != null
                && mc.getSingleplayerServer() != null && mc.screen == null);
        une("figer les réglages", BancDeRendu::reglerLesOptions);
        serveur("mettre en place la scène", BancDeRendu::mettreEnPlace);
        une("regarder au nord", mc -> viser(180f, 15f));
        jusqua("la scène prête", 600, BancDeRendu::scenePrete);
        une("juger le backend", mc -> jugerBackend());
        stabiliser("la scène vanilla");

        // T-470 : la même scène, avec puis sans les passes d'AXION, puis de nouveau avec.
        une("capturer avec AXION", mc -> {
            avec = capturer(mc, "t470_avec.png");
            passesAvec = AxionRenderPass.lastFrame();
        });
        une("couper AXION", mc -> AxionRenderPass.setPassesEnabled(false));
        frames(3);
        une("capturer sans AXION", mc -> sans = capturer(mc, "t470_sans.png"));
        une("rétablir AXION", mc -> AxionRenderPass.setPassesEnabled(true));
        frames(3);
        une("juger T-470", mc -> jugerT470(capturer(mc, "t470_encore.png")));

        // T-474 : un cube devant la caméra, droit puis tourné, de jour puis de nuit.
        une("regarder à l'ouest", mc -> viser(90f, 20f));
        stabiliser("la vue à l'ouest");
        une("capturer le fond", mc -> fond = capturer(mc, "t474_fond.png"));
        serveur("poser le cube droit", s -> cube = AssembliesDeTest.poser(s.overworld(), CUBE, positionCube(), IDENTITE));
        jusqua("le cube droit rendu", 120, mc -> rendu(mc, DERRIERE.size() + 1, cube.getId()));
        stabiliser("le cube droit");
        une("capturer le cube droit", mc -> {
            droit = capturer(mc, "t474_droit.png");
            vueDroit = derniereVue;
        });
        serveur("tourner le cube d'un huitième de tour", s -> {
            cubePrecedent = cube.getId();
            cube.discard();
            cube = AssembliesDeTest.poser(s.overworld(), CUBE, positionCube(), HUITIEME_DE_TOUR);
        });
        jusqua("le cube tourné rendu", 120, mc -> mc.level.getEntity(cubePrecedent) == null
                && rendu(mc, DERRIERE.size() + 1, cube.getId()));
        stabiliser("le cube tourné");
        une("capturer le cube tourné", mc -> {
            tourne = capturer(mc, "t474_tourne.png");
            vueTourne = derniereVue;
        });
        serveur("passer à minuit", s -> commandes(s, "time set 18000"));
        jusqua("la nuit tombée", 60, mc -> mc.level.getDayTime() % 24_000L == 18_000L);
        stabiliser("la nuit");
        une("capturer de nuit", mc -> minuit = capturer(mc, "t474_minuit.png"));
        serveur("revenir à midi", s -> commandes(s, "time set 6000"));
        jusqua("le jour revenu", 60, mc -> mc.level.getDayTime() % 24_000L == 6_000L);
        une("juger T-474", mc -> jugerT474());

        // T-471 : des frames sans AXION d'abord, pour savoir ce qui n'est pas de lui.
        une("compter sans AXION", mc -> {
            AxionRenderPass.setPassesEnabled(false);
            viderErreursGl();
            erreursFrames = 0;
            framesComptees = 0;
            compter = true;
        });
        frames(FRAMES_SANS_AXION);
        une("compter avec AXION", mc -> {
            erreursSansAxion = erreursFrames;
            erreursFrames = 0;
            framesComptees = 0;
            erreursPassesAvant = GlStateCheck.errors();
            erreursAvantPassesAvant = GlStateCheck.errorsBeforePasses();
            AxionRenderPass.setPassesEnabled(true);
        });
        frames(FRAMES_T471);
        une("juger T-471", mc -> {
            compter = false;
            jugerT471();
        });
        une("juger T-472, T-473 et le journal", mc -> {
            jugerT472();
            jugerT473();
        });
    }

    // --- Mise en place ---------------------------------------------------------------------------

    private static void creerLeMonde(Minecraft minecraft) {
        GameRules regles = new GameRules();
        regles.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
        regles.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
        regles.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
        // Aucun tick aléatoire : ni feuille qui se fane, ni herbe qui pousse dans la scène.
        regles.getRule(GameRules.RULE_RANDOMTICKING).set(0, null);
        // Spectateur : ni main à l'écran, ni observateur pour la physique (R-612) — les corps
        // dorment où ils sont posés, et la scène ne bouge pas.
        LevelSettings reglages = new LevelSettings(
                MONDE, GameType.SPECTATOR, false, Difficulty.PEACEFUL, true, regles, WorldDataConfiguration.DEFAULT);
        minecraft.createWorldOpenFlows().createFreshLevel(
                MONDE,
                reglages,
                new WorldOptions(0L, false, false),
                acces -> acces.registryOrThrow(Registries.WORLD_PRESET)
                        .getHolderOrThrow(WorldPresets.FLAT)
                        .value()
                        .createWorldDimensions());
    }

    private static void reglerLesOptions(Minecraft minecraft) {
        Options options = minecraft.options;
        options.hideGui = true;
        options.pauseOnLostFocus = false;
        options.tutorialStep = TutorialSteps.NONE;
        options.fov().set(70);
        options.renderDistance().set(4);
        options.cloudStatus().set(CloudStatus.OFF);
        options.particles().set(ParticleStatus.MINIMAL);
        options.entityShadows().set(false);
        options.bobView().set(false);
        options.fovEffectScale().set(0.0);
        options.screenEffectScale().set(0.0);
        options.enableVsync().set(false);
        options.framerateLimit().set(260);
        minecraft.getToasts().clear();
        minecraft.mouseHandler.releaseMouse();
    }

    /** Sur le thread du serveur : décor, heure, caméra, assemblies derrière elle. */
    private static void mettreEnPlace(MinecraftServer serveur) {
        ServerLevel monde = serveur.overworld();
        surface = monde.getHeight(Heightmap.Types.MOTION_BLOCKING, 0, 0);
        commandes(serveur, "time set 6000", "weather clear", "tp @a 0.5 " + surface + " 0.5 180 15");
        // Le décor vanilla de T-470, au nord : opaque, découpé, translucide. Rien d'animé, et aucune
        // source de lumière de bloc : Minecraft fait vaciller au hasard, à chaque tick, l'éclairage
        // qu'elles donnent (LightTexture.tick, Math.random) — au quatrième passage en CI, le
        // glowstone du décor changeait l'image d'une capture à l'autre.
        String[] blocs = {
            "stone", "glass", "oak_leaves[persistent=true]", "white_stained_glass", "red_concrete", "iron_bars", "cobweb"
        };
        for (int i = 0; i < blocs.length; i++) {
            commandes(serveur, "setblock " + (i - 3) + " " + surface + " -5 minecraft:" + blocs[i]);
        }
        commandes(serveur,
                "setblock -1 " + (surface + 1) + " -5 minecraft:tinted_glass",
                "setblock 1 " + (surface + 1) + " -5 minecraft:blue_stained_glass");
        // Derrière la caméra, au sud : hors de vue au nord comme à l'ouest.
        for (int i = 0; i < DERRIERE.size(); i++) {
            AssembliesDeTest.poser(monde, DERRIERE.get(i), new Vec3(1.5 + 2 * i, surface, 6.5), IDENTITE);
        }
    }

    private static boolean scenePrete(Minecraft minecraft) {
        AxionRenderPass.FrameStats frame = AxionRenderPass.lastFrame();
        return minecraft.levelRenderer.hasRenderedAllChunks()
                && minecraft.player.position().distanceToSqr(0.5, surface, 0.5) < 1e-4
                && minecraft.level.getBlockState(new BlockPos(-3, surface, -5)).is(Blocks.STONE)
                && minecraft.level.getBlockState(new BlockPos(1, surface + 1, -5)).is(Blocks.BLUE_STAINED_GLASS)
                && frame.assemblies() == DERRIERE.size()
                && frame.withAsset() == DERRIERE.size();
    }

    /** {@return vrai si l'assembly {@code id} est au monde du client, et toutes dessinées avec leur asset} */
    private static boolean rendu(Minecraft minecraft, int attendues, int id) {
        AxionRenderPass.FrameStats frame = AxionRenderPass.lastFrame();
        return minecraft.level.getEntity(id) != null
                && frame.assemblies() == attendues
                && frame.withAsset() == attendues;
    }

    private static Vec3 positionCube() {
        return new Vec3(-3.5, surface, 0.5);
    }

    private static void viser(float nouveauLacet, float nouveauTangage) {
        lacet = nouveauLacet;
        tangage = nouveauTangage;
        cameraFixee = true;
    }

    private static void fixerLaCamera(Minecraft minecraft) {
        if (cameraFixee && minecraft.player != null) {
            minecraft.player.setYRot(lacet);
            minecraft.player.yRotO = lacet;
            minecraft.player.setXRot(tangage);
            minecraft.player.xRotO = tangage;
            minecraft.player.setYHeadRot(lacet);
            minecraft.player.yHeadRotO = lacet;
        }
    }

    // --- Jugements -------------------------------------------------------------------------------

    private static void jugerBackend() {
        BackendSelection.Kind actif = AxionRenderPass.activeBackend();
        BackendSelection.Kind attendu = switch (BACKEND_DEMANDE == null ? "auto" : BACKEND_DEMANDE) {
            case "native" -> BackendSelection.Kind.NATIVE;
            case "vanilla" -> BackendSelection.Kind.VANILLA;
            default -> null;
        };
        RAPPORT.noter("backend", actif != null && (attendu == null || actif == attendu), String.format(Locale.ROOT,
                "backend %s actif, %s demandé", actif, BACKEND_DEMANDE == null ? "auto" : BACKEND_DEMANDE));
    }

    private static void jugerT470(CapturesDuBanc.Capture encore) {
        int instables = CapturesDuBanc.masque(avec, encore).nombre();
        int ecarts = CapturesDuBanc.masque(avec, sans).nombre();
        boolean dessinees = passesAvec.assemblies() == DERRIERE.size() && passesAvec.withAsset() == DERRIERE.size();
        RAPPORT.noter("T-470", dessinees && instables == 0 && ecarts == 0, String.format(Locale.ROOT,
                "%d assembly(s) dessinée(s) hors champ avec leur asset ; %d pixel(s) différent(s) avec et sans"
                        + " les passes d'AXION, %d entre deux captures avec (scène stable : 0 attendu)",
                passesAvec.withAsset(), ecarts, instables));
    }

    private static void jugerT474() {
        CapturesDuBanc.Masque masqueDroit = CapturesDuBanc.masque(fond, droit);
        CapturesDuBanc.Masque masqueTourne = CapturesDuBanc.masque(fond, tourne);
        int[] attenduDroit = boiteAttendue(vueDroit, IDENTITE, droit.largeur(), droit.hauteur());
        int[] attenduTourne = boiteAttendue(vueTourne, HUITIEME_DE_TOUR, tourne.largeur(), tourne.hauteur());
        int ecartDroit = ecart(masqueDroit, attenduDroit);
        int ecartTourne = ecart(masqueTourne, attenduTourne);
        boolean visible = masqueDroit.nombre() >= aire(attenduDroit) / 2 && ecartDroit <= ECART_BOITE;
        boolean orientee = masqueTourne.nombre() >= aire(attenduTourne) / 2 && ecartTourne <= ECART_BOITE;

        // Éclairée : le dessus et les côtés visibles du cube tourné n'ont pas la même luminance,
        // et la nuit l'assombrit.
        double[] faces = luminancesDesFaces(tourne, vueTourne);
        boolean ombree = faces.length > 1;
        for (int i = 1; i < faces.length; i++) {
            ombree &= Math.abs(faces[0] - faces[i]) >= 4.0;
        }
        double jour = CapturesDuBanc.luminance(tourne, masqueTourne);
        double nuit = CapturesDuBanc.luminance(minuit, masqueTourne);
        boolean assombrie = nuit < 0.8 * jour;

        RAPPORT.noter("T-474", visible && orientee && ombree && assombrie, String.format(Locale.ROOT,
                "droit : %d px, boîte %s pour %s attendue (écart %d) ; tourné : %d px, boîte %s pour %s attendue"
                        + " (écart %d) ; luminance du dessus puis des côtés %s ; midi %.1f, minuit %.1f",
                masqueDroit.nombre(), Arrays.toString(masqueDroit.boite()), Arrays.toString(attenduDroit), ecartDroit,
                masqueTourne.nombre(), Arrays.toString(masqueTourne.boite()), Arrays.toString(attenduTourne),
                ecartTourne, Arrays.toString(arrondir(faces)), jour, nuit));
    }

    private static void jugerT471() {
        long pendantLesPasses = GlStateCheck.errors() - erreursPassesAvant;
        long avantLesPasses = GlStateCheck.errorsBeforePasses() - erreursAvantPassesAvant;
        long total = erreursFrames + pendantLesPasses + avantLesPasses;
        RAPPORT.noter("T-471", framesComptees >= FRAMES_T471 && total == 0, String.format(Locale.ROOT,
                "%d frame(s) avec AXION, %d erreur(s) GL (%d dans ses passes) ; %d frame(s) sans AXION avant,"
                        + " %d erreur(s)",
                framesComptees, total, pendantLesPasses, FRAMES_SANS_AXION, erreursSansAxion));
    }

    private static void jugerT472() {
        boolean actif = GlStateCheck.ENABLED && GlStateCheck.checks() > 0;
        RAPPORT.noter("T-472", actif && GlStateCheck.differences() == 0 && GlStateCheck.errors() == 0,
                String.format(Locale.ROOT,
                        "contrôle %s, %d passe(s) contrôlée(s), %d état(s) non restauré(s), %d erreur(s) GL"
                                + " dans les passes",
                        GlStateCheck.ENABLED ? "actif" : "INACTIF (axion.debug.gl absent)",
                        GlStateCheck.checks(), GlStateCheck.differences(), GlStateCheck.errors()));
    }

    private static void jugerT473() {
        List<String> horsDuFil = journal.horsDuFil();
        RAPPORT.noter("T-473", horsDuFil.isEmpty(), horsDuFil.isEmpty()
                ? "aucun appel GL signalé hors du render thread"
                : String.join(" | ", horsDuFil.subList(0, Math.min(5, horsDuFil.size()))));
        List<String> erreurs = journal.erreursAxion();
        RAPPORT.noter("erreurs AXION", erreurs.isEmpty(), erreurs.isEmpty()
                ? "aucune erreur d'AXION au journal"
                : erreurs.size() + " erreur(s), dont : " + erreurs.get(0));
    }

    // --- Projection ------------------------------------------------------------------------------

    /** {@return la boîte, en pixels inclus, que couvre le cube posé en {@link #positionCube()}} */
    private static int[] boiteAttendue(Vue vue, float[] rotation, int largeur, int hauteur) {
        Quaternionf q = new Quaternionf(rotation[0], rotation[1], rotation[2], rotation[3]);
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        // Le maillage du cube de test : [-0,5 ; 0,5] × [0 ; 1] × [-0,5 ; 0,5], origine au bas.
        for (float dx : new float[] {-0.5f, 0.5f}) {
            for (float dy : new float[] {0f, 1f}) {
                for (float dz : new float[] {-0.5f, 0.5f}) {
                    Vector3f coin = q.transform(new Vector3f(dx, dy, dz));
                    float[] ecran = projeter(vue, positionCube().add(coin.x, coin.y, coin.z), largeur, hauteur);
                    minX = Math.min(minX, ecran[0]);
                    minY = Math.min(minY, ecran[1]);
                    maxX = Math.max(maxX, ecran[0]);
                    maxY = Math.max(maxY, ecran[1]);
                }
            }
        }
        // Un pixel est couvert si son centre l'est.
        return new int[] {
            (int) Math.ceil(minX - 0.5f), (int) Math.ceil(minY - 0.5f),
            (int) Math.floor(maxX - 0.5f), (int) Math.floor(maxY - 0.5f)
        };
    }

    /** {@return la position à l'écran d'un point du monde, en pixels depuis le coin haut gauche} */
    private static float[] projeter(Vue vue, Vec3 point, int largeur, int hauteur) {
        Vector4f v = new Vector4f(
                (float) (point.x - vue.camera().x),
                (float) (point.y - vue.camera().y),
                (float) (point.z - vue.camera().z),
                1f);
        vue.pose().transform(v);
        vue.projection().transform(v);
        return new float[] {(v.x / v.w * 0.5f + 0.5f) * largeur, (0.5f - v.y / v.w * 0.5f) * hauteur};
    }

    /**
     * {@return la luminance au centre du dessus, puis de chaque face latérale tournée vers la
     * caméra, du cube tourné}
     */
    private static double[] luminancesDesFaces(CapturesDuBanc.Capture capture, Vue vue) {
        Quaternionf q = new Quaternionf(HUITIEME_DE_TOUR[0], HUITIEME_DE_TOUR[1], HUITIEME_DE_TOUR[2], HUITIEME_DE_TOUR[3]);
        Vector3f[] normales = {
            new Vector3f(0, 1, 0), new Vector3f(1, 0, 0), new Vector3f(-1, 0, 0), new Vector3f(0, 0, 1), new Vector3f(0, 0, -1)
        };
        List<Double> luminances = new ArrayList<>();
        for (Vector3f normale : normales) {
            Vector3f n = q.transform(new Vector3f(normale));
            Vector3f centre = q.transform(new Vector3f(normale).mul(0.5f)).add(0f, 0.5f, 0f);
            Vec3 point = positionCube().add(centre.x, centre.y, centre.z);
            Vec3 versCamera = vue.camera().subtract(point);
            if (n.x * versCamera.x + n.y * versCamera.y + n.z * versCamera.z <= 0) {
                continue;
            }
            float[] ecran = projeter(vue, point, capture.largeur(), capture.hauteur());
            luminances.add(CapturesDuBanc.luminanceAutour(capture, (int) ecran[0], (int) ecran[1], 2));
        }
        return luminances.stream().mapToDouble(Double::doubleValue).toArray();
    }

    /** {@return le plus grand écart, en pixels, entre la boîte d'un masque et la boîte attendue} */
    private static int ecart(CapturesDuBanc.Masque masque, int[] attendue) {
        if (masque.nombre() == 0) {
            return Integer.MAX_VALUE;
        }
        int[] mesuree = masque.boite();
        int pire = 0;
        for (int i = 0; i < 4; i++) {
            pire = Math.max(pire, Math.abs(mesuree[i] - attendue[i]));
        }
        return pire;
    }

    private static int aire(int[] boite) {
        return Math.max(0, boite[2] - boite[0] + 1) * Math.max(0, boite[3] - boite[1] + 1);
    }

    private static double[] arrondir(double[] valeurs) {
        double[] arrondies = new double[valeurs.length];
        for (int i = 0; i < valeurs.length; i++) {
            arrondies[i] = Math.round(valeurs[i] * 10) / 10.0;
        }
        return arrondies;
    }

    // --- Outils ----------------------------------------------------------------------------------

    private static CapturesDuBanc.Capture capturer(Minecraft minecraft, String nom) {
        return CapturesDuBanc.capturer(minecraft, dossier(minecraft).resolve(nom));
    }

    private static Path dossier(Minecraft minecraft) {
        return minecraft.gameDirectory.toPath().resolve("axion-rendertest");
    }

    /** {@return les erreurs GL en attente, qu'on vide} */
    private static int viderErreursGl() {
        int erreurs = 0;
        while (erreurs < 64 && GL11.glGetError() != GL11.GL_NO_ERROR) {
            erreurs++;
        }
        return erreurs;
    }

    /** Exécute des commandes avec les droits du serveur, sans écho. Sur le thread du serveur. */
    private static void commandes(MinecraftServer serveur, String... lignes) {
        CommandSourceStack source = serveur.createCommandSourceStack().withSuppressedOutput();
        for (String ligne : lignes) {
            serveur.getCommands().performPrefixedCommand(source, ligne);
        }
    }

    private static void terminer(Minecraft minecraft, String echec) {
        fini = true;
        compter = false;
        if (echec != null) {
            RAPPORT.noter("banc", false, echec);
        }
        RAPPORT.ecrire(
                dossier(minecraft).resolve("rapport.json"),
                (System.currentTimeMillis() - debut) / 1000.0,
                journal == null ? List.of() : journal.erreursAxion());
        LOGGER.info("AXION banc de rendu : rapport {}, arrêt du client", RAPPORT.vert() ? "vert" : "ROUGE");
        minecraft.stop();
    }

    // --- Construction du scénario ----------------------------------------------------------------

    private static void etape(String nom, Pas pas) {
        SCENARIO.add(new Etape(nom, pas));
    }

    private static void une(String nom, Consumer<Minecraft> action) {
        etape(nom, mc -> {
            action.accept(mc);
            return true;
        });
    }

    private static void frames(int nombre) {
        etape(nombre + " frame(s)", mc -> framesDansLEtape >= nombre);
    }

    /** Attend que l'image ne change plus : voir {@link #RELEVES_EGAUX}. */
    private static void stabiliser(String quoi) {
        CapturesDuBanc.Capture[] precedent = new CapturesDuBanc.Capture[1];
        int[] egaux = new int[1];
        int[] differents = new int[1];
        long[] dernierReleve = {Long.MIN_VALUE};
        etape("stabiliser " + quoi, mc -> {
            if (dernierReleve[0] != Long.MIN_VALUE && ticks - dernierReleve[0] < TICKS_ENTRE_RELEVES) {
                return false;
            }
            dernierReleve[0] = ticks;
            CapturesDuBanc.Capture releve = CapturesDuBanc.capturer(mc, null);
            int ecarts = precedent[0] == null ? -1 : CapturesDuBanc.masque(precedent[0], releve).nombre();
            precedent[0] = releve;
            if (ecarts == 0) {
                egaux[0]++;
            } else {
                egaux[0] = 0;
                differents[0] = ecarts;
            }
            if (egaux[0] >= RELEVES_EGAUX) {
                return true;
            }
            if (System.nanoTime() - debutEtape > STABILITE_MAX_S * 1_000_000_000L) {
                throw new IllegalStateException("l'image change encore au bout de " + STABILITE_MAX_S
                        + " s (" + differents[0] + " pixel(s) au dernier écart)");
            }
            return false;
        });
    }

    private static void jusqua(String nom, int secondes, Predicate<Minecraft> condition) {
        etape(nom, mc -> {
            if (condition.test(mc)) {
                return true;
            }
            if (System.nanoTime() - debutEtape > secondes * 1_000_000_000L) {
                throw new IllegalStateException("toujours pas au bout de " + secondes + " s");
            }
            return false;
        });
    }

    /** Confie une action au thread du serveur intégré, et attend qu'elle soit faite. */
    private static void serveur(String nom, Consumer<MinecraftServer> action) {
        @SuppressWarnings("unchecked")
        CompletableFuture<Void>[] tache = new CompletableFuture[1];
        etape(nom, mc -> {
            MinecraftServer serveur = mc.getSingleplayerServer();
            if (tache[0] == null) {
                tache[0] = serveur.submit(() -> action.accept(serveur));
            }
            if (!tache[0].isDone()) {
                if (System.nanoTime() - debutEtape > 60_000_000_000L) {
                    throw new IllegalStateException("le serveur n'a pas répondu en 60 s");
                }
                return false;
            }
            tache[0].join();
            return true;
        });
    }
}
