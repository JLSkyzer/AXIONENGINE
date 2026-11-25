package dev.axion.forge;

import dev.axion.AxionMod;
import dev.axion.forge.net.JoueurSimule;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Le film des occupants (ADR-177 D1, tranche 12a) : quatre buggys d'essai côte à côte, le même modèle, dont le siège règle
 * l'occupant différemment — à l'échelle 0,5, à celle de Minecraft, à 1,5, et debout. Trois joueurs simulés et le joueur local
 * y sont assis, tels qu'un client les dessine ; la dernière étape remet la caméra dans la tête du joueur local, assis au
 * premier buggy : son œil est celui que le siège règle, et le film le mesure contre l'œil de Minecraft.
 *
 * <p>Sous {@code -Daxion.film.charger} ({@code -PfilmCharger}), la scène montre la seconde convention sur la Charger locale
 * (hors du dépôt, faute de licence) : à sa taille réelle avec un occupant à 0,67, et agrandie ×1,5 avec un occupant à 1.
 *
 * <p>Inerte sans {@code -Daxion.film=occupants} ; le lancement Gradle {@code runFilmOccupants} la pose. Rien ici ne part dans le
 * mod livré. Le film se juge lui-même avant de s'arrêter ({@code termine.json}).
 */
@Mod.EventBusSubscriber(modid = AxionMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class FilmDesOccupants {

    private static final Logger LOGGER = LoggerFactory.getLogger("AXION film");

    private static final boolean ACTIF = "occupants".equals(System.getProperty("axion.film"));
    private static final String MONDE = "axion-film-occupants";
    private static final String SIGNATURE = "AXION ENGINE, ADR-177 D1";

    /** Le sol du monde plat : la première couche d'air. */
    private static final int SOL = -60;

    /** Les deux Charger locales au lieu des buggys d'essai. */
    private static final boolean CHARGER = Boolean.getBoolean("axion.film.charger");

    /** Les véhicules, de gauche à droite : la definition, et ce que le siège règle de son occupant. */
    private static final String[] DEFINITIONS = CHARGER
            ? new String[] {"axion:local/charger_reelle", "axion:local/charger"}
            : new String[] {"axion:test/vehicule_occupant_petit", "axion:test/vehicule",
                "axion:test/vehicule_occupant_grand", "axion:test/vehicule_occupant_debout"};

    private static final String[] LEGENDES = CHARGER
            ? new String[] {"à gauche : taille réelle, occupant à 0,67", "à droite : agrandie ×1,5, occupant à 1"}
            : new String[] {"échelle 0,5", "échelle 1 (Minecraft)", "échelle 1,5", "debout"};

    /** La caméra regarde vers +Z : +X est à sa gauche, et le premier véhicule à gauche de l'image. */
    private static final double[] X = CHARGER ? new double[] {4.2, -4.2} : new double[] {6.0, 2.0, -2.0, -6.0};

    /** Les Charger tournées d'un quart de tour : le côté de leur conducteur face à la caméra. */
    private static final float[] ROTATION = CHARGER ? new float[] {0f, -0.70710677f, 0f, 0.70710677f}
            : new float[] {0f, 0f, 0f, 1f};

    private static final double Z = 14.5;

    /** L'écart toléré entre l'œil mesuré et l'œil attendu, en blocs. */
    private static final double TOLERANCE = 0.03;

    /** Ce que l'œil de l'occupant du premier véhicule gagne ou perd sur celui de Minecraft : (1 − échelle) × 1,27 m. */
    private static final double BAISSE_ATTENDUE = (1.0 - (CHARGER ? 0.6667 : 0.5)) * (1.62 - 0.35);

    private static final long ATTENTE_MAX_NS = TimeUnit.SECONDS.toNanos(180);

    private enum Phase { TITRE, CREATION, MONDE, DECOR, VEHICULES, ASSEOIR, TOURNAGE, FIN }

    /** Les étapes filmées, leur durée en secondes. */
    private enum Etape {
        LARGE(3.0), GAUCHE(3.0), DROITE(3.0), OEIL(4.0);

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
    private static volatile RuntimeException fauteServeur;

    private static final int[] IDS = java.util.stream.IntStream.generate(() -> -1).limit(DEFINITIONS.length).toArray();
    private static final AxionEntity[] BUGGYS = new AxionEntity[DEFINITIONS.length];
    private static final JoueurSimule[] SIMULES = new JoueurSimule[DEFINITIONS.length];

    private static Tournage tournage;
    private static Entity camera;
    private static Etape etape = Etape.LARGE;
    private static boolean cameraDuJoueur;
    private static final List<String> MESURES = new ArrayList<>();

    // L'œil, mesuré à la dernière étape.
    private static double baisse = Double.NaN;
    private static double ecartHorizontal = Double.NaN;

    private FilmDesOccupants() {}

    /** @param event frame du client */
    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (!ACTIF) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (event.phase == TickEvent.Phase.START) {
            if (phase == Phase.TOURNAGE) {
                garderLaMain(minecraft);
                placerLaCamera(minecraft);
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

    /** @param event tick du client : le film ne se joue jamais en pause, et les joueurs simulés vivent */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (!ACTIF || event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null && minecraft.screen instanceof PauseScreen) {
            minecraft.setScreen(null);
        }
        minecraft.getToasts().clear();
        if (minecraft.getSingleplayerServer() != null && phase.ordinal() >= Phase.ASSEOIR.ordinal()
                && phase != Phase.FIN) {
            minecraft.getSingleplayerServer().execute(() -> {
                for (JoueurSimule simule : SIMULES) {
                    if (simule != null && simule.connecte()) {
                        simule.tick();
                    }
                }
            });
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
                    auServeur(minecraft, () -> poserLeDecor(minecraft.getSingleplayerServer()));
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
                    throw new IllegalStateException("une definition est indisponible : voir le journal");
                }
                minecraft.player.getAbilities().flying = true;
                auServeur(minecraft, () -> poserLesVehicules(minecraft.getSingleplayerServer()));
                boolean tous = true;
                for (int rang = 0; rang < IDS.length; rang++) {
                    tous &= IDS[rang] >= 0 && minecraft.level.getEntity(IDS[rang]) != null;
                }
                if (tous && depuis(3.0)) {
                    auServeur(minecraft, () -> asseoir(minecraft.getSingleplayerServer()));
                    passer(Phase.ASSEOIR);
                }
            }
            case ASSEOIR -> {
                // Les Charger locales sont lourdes : leur modele se compile chez le client en une dizaine de secondes, un
                // cube a sa place d'ici la.
                if (assis && depuis(CHARGER ? 25.0 : 3.0) && lesOccupantsSontLa(minecraft)) {
                    camera = new ArmorStand(EntityType.ARMOR_STAND, minecraft.level);
                    camera.setInvisible(true);
                    minecraft.setCameraEntity(camera);
                    placerLaCamera(minecraft);
                    tournage = new Tournage(dossier(minecraft).resolve("images"), SIGNATURE);
                    passer(Phase.TOURNAGE);
                }
            }
            case TOURNAGE -> tourner(minecraft);
            case FIN -> { }
            default -> throw new IllegalStateException();
        }
    }

    /** {@return vrai si le client voit chaque occupant assis dans son véhicule} */
    private static boolean lesOccupantsSontLa(Minecraft minecraft) {
        for (int rang = 0; rang < IDS.length; rang++) {
            if (!(minecraft.level.getEntity(IDS[rang]) instanceof AxionEntity buggy) || buggy.getPassengers().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** Une piste plate, sans rien d'autre : le fond du film est le ciel. */
    private static void poserLeDecor(MinecraftServer serveur) {
        ServerLevel level = serveur.overworld();
        level.setDayTime(6000);
        for (int x = -24; x <= 24; x++) {
            for (int z = -6; z < 40; z++) {
                level.setBlockAndUpdate(new BlockPos(x, SOL - 1, z), Blocks.SMOOTH_STONE.defaultBlockState());
            }
        }
        // Le joueur filme depuis son siège de la dernière étape : on le garde assez près pour que tout se charge.
        ServerPlayer joueur = serveur.getPlayerList().getPlayers().get(0);
        joueur.teleportTo(X[0], SOL + 0.5, Z - 3.0);
        decorPret = true;
    }

    private static void poserLesVehicules(MinecraftServer serveur) {
        if (IDS[0] >= 0) {
            return;
        }
        ServerLevel level = serveur.overworld();
        try {
            AxionEntity[] poses = new AxionEntity[DEFINITIONS.length];
            for (int rang = 0; rang < DEFINITIONS.length; rang++) {
                poses[rang] = AssembliesDeTest.poser(level, DEFINITIONS[rang], new Vec3(X[rang], SOL + 0.5, Z),
                        ROTATION);
            }
            for (int rang = 0; rang < poses.length; rang++) {
                BUGGYS[rang] = poses[rang];
                IDS[rang] = poses[rang].getId();
            }
        } catch (IllegalStateException pasEncore) {
            // Une definition attend encore sa compilation : on repasse à la frame suivante.
        }
    }

    /** Le joueur local au premier buggy ; un joueur simulé aux trois autres : le client les dessine tous. */
    private static void asseoir(MinecraftServer serveur) {
        ServerLevel level = serveur.overworld();
        ServerPlayer local = serveur.getPlayerList().getPlayers().get(0);
        boolean tous = BUGGYS[0].seatingKnown() && BUGGYS[0].reseat(local, "conducteur");
        for (int rang = 1; rang < BUGGYS.length; rang++) {
            SIMULES[rang] = JoueurSimule.rejoindre(level, "occupant" + rang, new Vec3(X[rang], SOL + 0.5, Z - 3.0));
            tous &= BUGGYS[rang].seatingKnown() && BUGGYS[rang].reseat(SIMULES[rang].joueur(), "conducteur");
        }
        if (!tous) {
            throw new IllegalStateException("les occupants ne s'assoient pas au siège conducteur des buggys d'essai");
        }
        assis = true;
    }

    /**
     * Trois plans de côté, puis la tête du joueur local : la caméra de l'étape, de loin, sur la rangée, puis sur chaque
     * moitié ; à la dernière, le joueur local regarde par ses yeux, et c'est la caméra de l'événement qui le place.
     */
    private static void placerLaCamera(Minecraft minecraft) {
        if (camera == null) {
            return;
        }
        boolean doitEtreLeJoueur = etape == Etape.OEIL;
        if (doitEtreLeJoueur != cameraDuJoueur) {
            cameraDuJoueur = doitEtreLeJoueur;
            minecraft.setCameraEntity(doitEtreLeJoueur ? minecraft.player : camera);
        }
        if (doitEtreLeJoueur) {
            if (CHARGER) {
                // Les Charger sont tournees vers +X : le conducteur regarde sa route.
                minecraft.player.setYRot(-90f);
                minecraft.player.yRotO = -90f;
                minecraft.player.setXRot(10f);
                minecraft.player.xRotO = 10f;
                minecraft.player.yHeadRot = -90f;
                minecraft.player.yHeadRotO = -90f;
            }
            return;
        }
        Vec3 oeil;
        Vec3 cible;
        switch (etape) {
            case GAUCHE -> {
                // Les Charger : de près, par la vitre du conducteur, à la taille de chacune.
                oeil = CHARGER ? new Vec3(X[0] - 1.4, SOL + 1.9, Z - 3.6) : new Vec3(4.0, SOL + 2.3, Z - 7.0);
                cible = CHARGER ? new Vec3(X[0] - 0.2, SOL + 1.05, Z) : new Vec3(4.0, SOL + 1.2, Z);
            }
            case DROITE -> {
                oeil = CHARGER ? new Vec3(X[1] - 2.0, SOL + 2.7, Z - 5.2) : new Vec3(-4.0, SOL + 2.3, Z - 7.0);
                cible = CHARGER ? new Vec3(X[1] - 0.3, SOL + 1.5, Z) : new Vec3(-4.0, SOL + 1.2, Z);
            }
            default -> {
                oeil = CHARGER ? new Vec3(0.0, SOL + 3.0, Z - 8.5) : new Vec3(0.0, SOL + 3.2, Z - 11.5);
                cible = new Vec3(0.0, SOL + 1.2, Z);
            }
        }
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
        if (camera instanceof LivingEntity vivante) {
            vivante.yHeadRot = lacet;
            vivante.yHeadRotO = lacet;
            vivante.yBodyRot = lacet;
            vivante.yBodyRotO = lacet;
        }
    }

    /** Rien de l'extérieur ne touche au film : souris relâchée, interface cachée. */
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
        if (etape == Etape.OEIL && secondes > Etape.LARGE.duree + Etape.GAUCHE.duree + Etape.DROITE.duree + 1.0) {
            mesurerLOeil(minecraft);
        }
        String legende = switch (etape) {
            case LARGE -> CHARGER ? "La même voiture de deux façons : à sa taille réelle, ou agrandie pour le joueur"
                    : "Quatre buggys, un seul modèle : à l'échelle 0,5, à celle de Minecraft, à 1,5, et debout";
            case GAUCHE -> CHARGER ? "Taille réelle (5,10 m) : le siège met le joueur à l'échelle 0,67"
                    : "À gauche, l'occupant à l'échelle 0,5 (le joueur local) et celui de Minecraft";
            case DROITE -> CHARGER ? "Agrandie ×1,5 : le joueur garde l'échelle de Minecraft"
                    : "À droite, l'occupant à l'échelle 1,5 et celui que son siège met debout";
            case OEIL -> CHARGER ? "Au volant de la Charger à taille réelle : l'œil du conducteur à 0,67"
                    : "Dans la tête du joueur local : l'œil de l'occupant à 0,5, plus bas que celui de Minecraft";
        };
        String valeurs = etape == Etape.OEIL && !Double.isNaN(baisse)
                ? String.format(Locale.ROOT, "œil plus bas de %.3f m que celui de Minecraft (attendu %.3f m), décalé de"
                        + " %.3f m", baisse, BAISSE_ATTENDUE, ecartHorizontal)
                : String.join("   ·   ", LEGENDES);
        tournage.capturer(minecraft.getMainRenderTarget(), legende, valeurs, String.format(Locale.ROOT, "%.1f s", secondes));
    }

    /**
     * La caméra du joueur local, vue de la dernière image : sa position, contre l'œil que Minecraft lui donnerait. Un
     * occupant à 0,5 voit de 0,5 × (1,62 − 0,35) m plus bas, sans bouger à l'horizontale.
     */
    private static void mesurerLOeil(Minecraft minecraft) {
        Vec3 vue = minecraft.gameRenderer.getMainCamera().getPosition();
        Vec3 vanilla = minecraft.player.getEyePosition(1.0f);
        baisse = vanilla.y - vue.y;
        ecartHorizontal = Math.hypot(vanilla.x - vue.x, vanilla.z - vue.z);
        MESURES.add(String.format(Locale.ROOT, "%.3f;%.3f;%.3f;%.3f;%.3f;%.3f", vue.x, vue.y, vue.z, vanilla.x, vanilla.y,
                vanilla.z));
    }

    private static String jugement() {
        if (Double.isNaN(baisse)) {
            return "l'œil du joueur local n'a pas été mesuré";
        }
        if (Math.abs(baisse - BAISSE_ATTENDUE) > TOLERANCE) {
            return String.format(Locale.ROOT, "l'œil est plus bas de %.3f m que celui de Minecraft, %.3f m attendus",
                    baisse, BAISSE_ATTENDUE);
        }
        if (ecartHorizontal > TOLERANCE) {
            return String.format(Locale.ROOT, "l'œil s'est décalé de %.3f m à l'horizontale", ecartHorizontal);
        }
        return null;
    }

    private static Path dossier(Minecraft minecraft) {
        return minecraft.gameDirectory.toPath().resolve("axion-film");
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
                "{\"scene\": \"occupants\", \"images\": %d, \"uniques\": %d, \"echec\": %s, \"baisse_oeil\": %.4f,"
                        + " \"baisse_attendue\": %.4f, \"ecart_horizontal\": %.4f, \"capture_ms_mediane\": %.1f,"
                        + " \"capture_ms_p90\": %.1f}%n",
                tournage == null ? 0 : tournage.images(), tournage == null ? 0 : tournage.uniques(),
                faute == null ? "null" : "\"" + faute.replace('"', '\'') + "\"", baisse, BAISSE_ATTENDUE,
                ecartHorizontal, tournage == null ? 0.0 : tournage.quantileMs(0.5),
                tournage == null ? 0.0 : tournage.quantileMs(0.9));
        try {
            Files.createDirectories(dossier(minecraft));
            Files.writeString(dossier(minecraft).resolve("termine.json"), bilan, StandardCharsets.UTF_8);
            Files.write(dossier(minecraft).resolve("oeil.csv"), MESURES, StandardCharsets.UTF_8);
        } catch (IOException faute2) {
            LOGGER.error("bilan non écrit", faute2);
        }
        LOGGER.info("[film] bilan : {}", bilan.trim());
        minecraft.stop();
    }
}
