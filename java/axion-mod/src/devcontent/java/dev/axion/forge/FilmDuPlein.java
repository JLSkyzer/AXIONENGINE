package dev.axion.forge;

import dev.axion.AxionMod;
import dev.axion.net.AssemblyState;
import dev.axion.net.Interpolation;
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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Le film du plein (ADR-176 D1, D3) : le joueur au volant, la caméra et le tableau de bord du véhicule — sa jauge de
 * carburant, son voyant de moteur —, un petit réservoir qui se vide à l'accélérateur, la panne sèche, le plein par l'API,
 * le contact coupé puis remis, et le départ. Le film se juge sur ce que le client a reçu, écrit {@code termine.json} et
 * s'arrête. Inerte sans {@code -Daxion.film=plein} ; le lancement Gradle {@code runFilmPlein} la pose. Rien ici ne part
 * dans le mod livré.
 */
@Mod.EventBusSubscriber(modid = AxionMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class FilmDuPlein {

    private static final Logger LOGGER = LoggerFactory.getLogger("AXION film");
    private static final boolean ACTIF = "plein".equals(System.getProperty("axion.film"));
    private static final String MONDE = "axion-film-plein";
    /** La definition filmée : le véhicule à démarreur, un réservoir de quelques secondes et un tableau de bord. */
    private static final String DEFINITION = "axion:test/vehicule_jauge";

    /** Le sol du monde plat : la première couche d'air. */
    private static final int SOL = -60;

    private static final int DEMI_LARGEUR = 20;
    private static final int LONGUEUR = 440;

    /** Le départ du véhicule, son avant (-Z du modèle) tourné vers +Z. */
    private static final Vec3 DEPART = new Vec3(0.5, SOL + 0.5, 10.5);

    /** Délai maximal d'une phase d'attente. */
    private static final long ATTENTE_MAX_NS = TimeUnit.SECONDS.toNanos(180);

    private enum Phase { TITRE, CREATION, MONDE, DECOR, VEHICULE, ASSEOIR, TOURNAGE, FIN }

    /** Les étapes filmées, chacune avec sa durée au plus, en secondes. */
    private enum Etape {
        DEPART(2.5), ROULE(14.0), PANNE(10.0), PLEIN(3.0), REPART(5.0);

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

    private static Etape etape = Etape.DEPART;
    private static double debutEtape;
    private static boolean contactRemis;
    private static Tournage tournage;

    // Le jugement, sur ce que le client a reçu.
    private static float carburantMin = Float.POSITIVE_INFINITY;
    private static boolean arretVu;
    private static float carburantApresPlein;
    private static double vitesseApresPlein;

    private FilmDuPlein() {}

    private static Path dossier(Minecraft minecraft) {
        return minecraft.gameDirectory.toPath().resolve("axion-film");
    }

    /**
     * @param event frame du client
     */
    @SubscribeEvent
    public static void filmer(TickEvent.RenderTickEvent event) {
        if (!ACTIF) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (event.phase == TickEvent.Phase.START) {
            if (phase == Phase.TOURNAGE) {
                if (minecraft.mouseHandler.isMouseGrabbed()) {
                    minecraft.mouseHandler.releaseMouse();
                }
                // La caméra de poursuite du véhicule, et le tableau de bord, qui fait partie de l'interface.
                minecraft.options.setCameraType(CameraType.THIRD_PERSON_BACK);
                minecraft.options.hideGui = false;
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
    public static void veiller(TickEvent.ClientTickEvent event) {
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
                if (System.nanoTime() - debutPhase > TimeUnit.SECONDS.toNanos(45)) {
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
                if (assis && minecraft.player.getVehicle() instanceof AxionEntity && depuis(2.0)) {
                    tournage = new Tournage(dossier(minecraft).resolve("images"), "AXION ENGINE, ADR-176 D1-D3");
                    debutEtape = 0.0;
                    // Regard droit devant : la caméra de poursuite suit le véhicule.
                    minecraft.player.setYRot(0f);
                    minecraft.player.setXRot(10f);
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
        for (int x = -DEMI_LARGEUR; x <= DEMI_LARGEUR; x++) {
            for (int z = 0; z < LONGUEUR; z++) {
                boolean ligne = z >= 10 && z % 10 == 0;
                level.setBlockAndUpdate(new BlockPos(x, SOL - 1, z),
                        (ligne ? Blocks.WHITE_CONCRETE : Blocks.SMOOTH_STONE).defaultBlockState());
            }
        }
        // Des repères de bord de piste, tous les dix blocs : la vitesse se lit à l'image.
        for (int z = 10; z < LONGUEUR; z += 10) {
            level.setBlockAndUpdate(new BlockPos(-8, SOL, z), Blocks.YELLOW_CONCRETE.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(8, SOL, z), Blocks.YELLOW_CONCRETE.defaultBlockState());
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
        contact(true);
    }

    /** Le contact, la variable que la definition nomme, posée au serveur comme la touche du conducteur la poserait. */
    private static void contact(boolean mis) {
        vehiculeServeur.variables().set(vehiculeServeur.ignitionVariable(), mis ? 1.0 : 0.0, "film");
    }

    private static void tourner(Minecraft minecraft) {
        double secondes = (System.nanoTime() - tournage.debut()) / 1e9;
        Entity entite = minecraft.level.getEntity(idVehicule);
        if (!(entite instanceof AxionEntity vehicule)) {
            throw new IllegalStateException("le véhicule a disparu du client");
        }
        Interpolation.VehiclePose recu = vehicule.renderPose(minecraft.getFrameTime()).vehicle();
        boolean tourne = (recu.flags() & AssemblyState.Vehicle.RUNNING) != 0;
        float vitesse = recu.speed();
        double dansEtape = secondes - debutEtape;
        boolean avant = false;
        boolean frein = false;
        boolean arret = false;
        Etape suivante = null;
        switch (etape) {
            case DEPART -> {
                if (dansEtape >= etape.duree) {
                    suivante = Etape.ROULE;
                }
            }
            case ROULE -> {
                avant = true;
                carburantMin = Math.min(carburantMin, recu.storeRatio(vehicule.energySpec().fuelStore()));
                if (!tourne && recu.storeRatio(vehicule.energySpec().fuelStore()) <= 0.01f) {
                    arretVu = true;
                    suivante = Etape.PANNE;
                } else if (dansEtape >= etape.duree) {
                    throw new IllegalStateException("le réservoir n'est pas vide au bout de " + etape.duree + " s : "
                            + recu.storeRatio(vehicule.energySpec().fuelStore()));
                }
            }
            case PANNE -> {
                // L'accélérateur reste tenu un instant sans effet, puis le frein arrête la roue libre ; le plein se
                // fait à l'arrêt.
                avant = dansEtape < 2.0;
                frein = dansEtape >= 2.0;
                carburantMin = Math.min(carburantMin, recu.storeRatio(vehicule.energySpec().fuelStore()));
                if (dansEtape >= etape.duree) {
                    throw new IllegalStateException("pas arrêté au bout de " + etape.duree + " s : " + vitesse + " m/s");
                }
                if (dansEtape > 2.5 && vitesse < 0.3f) {
                    suivante = Etape.PLEIN;
                    auServeur(minecraft, () -> {
                        vehiculeServeur.refuel(100.0);
                        contact(false);
                    });
                }
            }
            case PLEIN -> {
                arret = true;
                carburantApresPlein = Math.max(carburantApresPlein, recu.storeRatio(vehicule.energySpec().fuelStore()));
                if (!contactRemis && dansEtape >= 1.2) {
                    contactRemis = true;
                    auServeur(minecraft, () -> contact(true));
                }
                if (dansEtape >= etape.duree) {
                    suivante = Etape.REPART;
                }
            }
            case REPART -> {
                avant = true;
                vitesseApresPlein = Math.max(vitesseApresPlein, vitesse);
                if (dansEtape >= etape.duree) {
                    minecraft.options.keyUp.setDown(false);
                    terminer(minecraft, tournage.echec() != null ? tournage.echec() : jugement());
                    return;
                }
            }
            default -> throw new IllegalStateException();
        }
        minecraft.options.keyUp.setDown(avant);
        minecraft.options.keyDown.setDown(frein);
        minecraft.options.keyJump.setDown(arret);
        if (tournage.imageAttendue()) {
            String valeurs = String.format(Locale.ROOT, "carburant %.0f %%   moteur %s   %.1f m/s",
                    recu.storeRatio(vehicule.energySpec().fuelStore()) * 100f, tourne ? "en marche" : "arrêté", vitesse);
            tournage.capturer(minecraft.getMainRenderTarget(), legende(), valeurs,
                    String.format(Locale.ROOT, "%.1f s", secondes));
        }
        if (suivante != null) {
            LOGGER.info("[film] {} -> {} à {} s : carburant {}, moteur {}", etape, suivante,
                    String.format(Locale.ROOT, "%.1f", secondes), recu.storeRatio(vehicule.energySpec().fuelStore()), tourne);
            etape = suivante;
            debutEtape = secondes;
        }
    }

    private static String legende() {
        return switch (etape) {
            case DEPART -> "Au volant, contact mis : le réservoir est plein (il ne tient ici que quelques secondes)";
            case ROULE -> "Accélérateur à fond : la jauge descend";
            case PANNE -> "Panne sèche : le moteur s'arrête, l'accélérateur ne donne plus rien ; il freine";
            case PLEIN -> contactRemis ? "Contact remis : le démarreur relance le moteur"
                    : "Plein fait par l'API ; contact coupé, comme après une panne sèche";
            case REPART -> "Il repart";
        };
    }

    private static String jugement() {
        boolean reussi = arretVu && carburantMin <= 0.01f && carburantApresPlein >= 0.95f && vitesseApresPlein > 3.0;
        return reussi ? null : String.format(Locale.ROOT, "plein : arrêt vu %b, carburant au plus bas %.3f, après le"
                + " plein %.3f, vitesse après le plein %.2f m/s", arretVu, carburantMin, carburantApresPlein,
                vitesseApresPlein);
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
        String bilan = String.format(Locale.ROOT, "{\"images\": %d, \"uniques\": %d, \"echec\": %s, \"carburant_min\":"
                        + " %.4f, \"carburant_apres_plein\": %.4f, \"vitesse_apres_plein\": %.2f}%n",
                tournage == null ? 0 : tournage.images(), tournage == null ? 0 : tournage.uniques(),
                faute == null ? "null" : "\"" + faute.replace('"', '\'') + "\"",
                carburantMin, carburantApresPlein, vitesseApresPlein);
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
