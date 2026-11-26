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
 * Le film des énergies (ADR-182) : un véhicule électrique accélère sur sa batterie, lève le pied et la recharge ; puis
 * un hybride, batterie basse, laisse son prolongateur la recharger à l'arrêt avant de repartir à l'électricité. Le film
 * se juge sur ce que le client a reçu, écrit {@code termine.json} et s'arrête. Inerte sans
 * {@code -Daxion.film=energies} ; le lancement Gradle {@code runFilmEnergies} la pose. Rien ici ne part dans le mod livré.
 */
@Mod.EventBusSubscriber(modid = AxionMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class FilmDesEnergies {

    private static final Logger LOGGER = LoggerFactory.getLogger("AXION film");
    private static final boolean ACTIF = "energies".equals(System.getProperty("axion.film"));
    private static final String MONDE = "axion-film-energies";
    private static final String ELECTRIQUE = "axion:test/vehicule_film_electrique";
    private static final String HYBRIDE = "axion:test/vehicule_film_hybride";

    /** Le sol du monde plat : la première couche d'air. */
    private static final int SOL = -60;

    private static final int DEMI_LARGEUR = 20;
    private static final int LONGUEUR = 440;

    /** Le départ du véhicule, son avant (-Z du modèle) tourné vers +Z. */
    private static final Vec3 DEPART = new Vec3(0.5, SOL + 0.5, 10.5);

    /** Délai maximal d'une phase d'attente. */
    private static final long ATTENTE_MAX_NS = TimeUnit.SECONDS.toNanos(180);

    private enum Phase { TITRE, CREATION, MONDE, DECOR, VEHICULE, ASSEOIR, TOURNAGE, CHANGEMENT, FIN }

    /** Les étapes filmées, chacune avec sa durée au plus, en secondes. */
    private enum Etape {
        DEPART(2.5), ACCELERE(12.0), PIED_LEVE(7.0), ARRET(8.0), CHARGE(14.0), ROULE_HYBRIDE(7.0);

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
    private static String definition = ELECTRIQUE;

    private static Etape etape = Etape.DEPART;
    private static double debutEtape;
    private static Tournage tournage;

    // Le jugement, sur ce que le client a reçu.
    private static float auBas = 1f;
    private static float recupere;
    private static boolean chargeALArret;
    private static float chargeAtteinte;
    private static double vitesseHybride;

    private FilmDesEnergies() {}

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
            if (phase == Phase.TOURNAGE || phase == Phase.CHANGEMENT) {
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
            // Le message « descendre » de Minecraft ne fait pas partie du film.
            minecraft.gui.setOverlayMessage(net.minecraft.network.chat.Component.empty(), false);
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
                    throw new IllegalStateException("definition " + definition + " indisponible : voir le journal");
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
                    if (tournage == null) {
                        tournage = new Tournage(dossier(minecraft).resolve("images"), "AXION ENGINE, ADR-182");
                    }
                    debutEtape = (System.nanoTime() - tournage.debut()) / 1e9;
                    // Regard droit devant : la caméra de poursuite suit le véhicule.
                    minecraft.player.setYRot(0f);
                    minecraft.player.setXRot(10f);
                    passer(Phase.TOURNAGE);
                }
            }
            case TOURNAGE -> tourner(minecraft);
            case CHANGEMENT -> {
                if (idVehicule >= 0 && minecraft.level.getEntity(idVehicule) != null && depuis(1.5)) {
                    auServeur(minecraft, () -> asseoirLeJoueur(minecraft));
                    passer(Phase.ASSEOIR);
                } else {
                    auServeur(minecraft, () -> poserLeVehicule(minecraft));
                }
            }
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
            AxionEntity vehicule = AssembliesDeTest.poser(level, definition, DEPART, new float[] {0f, 1f, 0f, 0f});
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
            throw new IllegalStateException("le joueur ne s'assoit pas au siège conducteur de " + definition);
        }
        if (definition.equals(HYBRIDE)) {
            // La batterie basse : le prolongateur démarrera au premier coup de frein.
            vehiculeServeur.setStore(0, 0.2 * vehiculeServeur.energySpec().capacity(0));
        }
    }

    /** Du véhicule électrique à l'hybride : le premier s'en va, le second se pose au départ. */
    private static void changerDeVehicule(Minecraft minecraft) {
        AxionEntity ancien = vehiculeServeur;
        definition = HYBRIDE;
        idVehicule = -1;
        assis = false;
        auServeur(minecraft, () -> {
            ancien.ejectPassengers();
            ancien.discard();
        });
        passer(Phase.CHANGEMENT);
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
        float batterie = recu.storeRatio(0);
        double dansEtape = secondes - debutEtape;
        boolean avant = false;
        boolean frein = false;
        boolean arret = false;
        Etape suivante = null;
        switch (etape) {
            case DEPART -> {
                arret = true;
                if (dansEtape >= etape.duree) {
                    suivante = Etape.ACCELERE;
                }
            }
            case ACCELERE -> {
                avant = true;
                auBas = Math.min(auBas, batterie);
                if (batterie < 0.45f || dansEtape >= etape.duree) {
                    suivante = Etape.PIED_LEVE;
                }
            }
            case PIED_LEVE -> {
                auBas = Math.min(auBas, batterie);
                recupere = Math.max(recupere, batterie - auBas);
                if (dansEtape >= etape.duree || (dansEtape > 2.0 && vitesse < 0.3f)) {
                    suivante = Etape.ARRET;
                }
            }
            case ARRET -> {
                frein = vitesse > 0.3f;
                arret = true;
                if (dansEtape > 1.5 && vitesse < 0.3f) {
                    minecraft.options.keyUp.setDown(false);
                    minecraft.options.keyDown.setDown(false);
                    etape = Etape.CHARGE;
                    changerDeVehicule(minecraft);
                    return;
                }
                if (dansEtape >= etape.duree) {
                    throw new IllegalStateException("pas arrêté au bout de " + etape.duree + " s : " + vitesse + " m/s");
                }
            }
            case CHARGE -> {
                // Un coup de frein réveille le véhicule, puis plus rien : le prolongateur charge à l'arrêt.
                frein = dansEtape < 0.15;
                chargeALArret |= tourne && vitesse < 0.3f;
                chargeAtteinte = Math.max(chargeAtteinte, batterie);
                if (chargeALArret && !tourne && dansEtape > 1.0) {
                    suivante = Etape.ROULE_HYBRIDE;
                } else if (dansEtape >= etape.duree) {
                    throw new IllegalStateException("le prolongateur n'a pas fini : " + batterie + ", " + tourne);
                }
            }
            case ROULE_HYBRIDE -> {
                avant = true;
                vitesseHybride = Math.max(vitesseHybride, vitesse);
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
        minecraft.options.keyJump.setDown(arret && etape != Etape.CHARGE);
        if (tournage.imageAttendue()) {
            String valeurs = definition.equals(ELECTRIQUE)
                    ? String.format(Locale.ROOT, "batterie %.0f %%   moteur %+.1f kW   %.1f m/s", batterie * 100f,
                            recu.motors().power(0), vitesse)
                    : String.format(Locale.ROOT, "batterie %.0f %%   essence %.0f %%   prolongateur %s   %.1f m/s",
                            batterie * 100f, recu.storeRatio(1) * 100f, tourne ? "en marche" : "arrêté", vitesse);
            tournage.capturer(minecraft.getMainRenderTarget(), legende(tourne), valeurs,
                    String.format(Locale.ROOT, "%.1f s", secondes));
        }
        if (suivante != null) {
            LOGGER.info("[film] {} -> {} à {} s : batterie {}, moteur {}", etape, suivante,
                    String.format(Locale.ROOT, "%.1f", secondes), batterie, tourne);
            etape = suivante;
            debutEtape = secondes;
        }
    }

    private static String legende(boolean tourne) {
        return switch (etape) {
            case DEPART -> "Un véhicule électrique : une batterie, un moteur sur les roues arrière";
            case ACCELERE -> "Accélérateur : la batterie paie la puissance du moteur";
            case PIED_LEVE -> "Pied levé : le moteur freine et rend l'énergie à la batterie";
            case ARRET -> "Frein";
            case CHARGE -> "Un hybride, batterie basse : le moteur à essence la recharge, roues à l'arrêt";
            case ROULE_HYBRIDE -> tourne ? "Sous 30 % en roulant, le prolongateur repart et recharge"
                    : "Chargé, le prolongateur s'arrête ; le véhicule repart à l'électricité";
        };
    }

    private static String jugement() {
        boolean reussi = auBas < 0.7f && recupere > 0.04f && chargeALArret && chargeAtteinte >= 0.65f
                && vitesseHybride > 3.0;
        return reussi ? null : String.format(Locale.ROOT, "énergies : au plus bas %.3f, récupéré %.3f, charge à l'arrêt %b,"
                + " atteinte %.3f, vitesse hybride %.2f m/s", auBas, recupere, chargeALArret, chargeAtteinte,
                vitesseHybride);
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
        String bilan = String.format(Locale.ROOT, "{\"images\": %d, \"uniques\": %d, \"echec\": %s, \"au_bas\": %.4f,"
                        + " \"recupere\": %.4f, \"charge_atteinte\": %.4f, \"vitesse_hybride\": %.2f}%n",
                tournage == null ? 0 : tournage.images(), tournage == null ? 0 : tournage.uniques(),
                faute == null ? "null" : "\"" + faute.replace('"', '\'') + "\"",
                auBas, recupere, chargeAtteinte, vitesseHybride);
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
