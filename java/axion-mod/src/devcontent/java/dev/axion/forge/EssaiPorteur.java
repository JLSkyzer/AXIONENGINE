package dev.axion.forge;

import dev.axion.AxionMod;
import dev.axion.api.vehicle.DriverInput;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ADR-178, l'essai à deux processus du portage (CAP-14) : un vrai serveur dédié, qui juge les positions de son joueur comme en
 * ligne (« moved wrongly », « moved too quickly », expulsion « en train de voler »), un vrai client, une latence injectée par un
 * {@link RelaisLatence}. Le joueur, en survie, est debout sur un véhicule que le serveur conduit en cercle. Inerte sans ses
 * propriétés ; le contenu de développement ne part jamais dans le mod livré.
 *
 * <ul>
 *   <li>Le serveur ({@code runServer}), sous {@code AXION_ESSAI_PORTEUR=1}, pose un sol en altitude, le véhicule, lâche le joueur
 *       au-dessus, puis conduit {@value #CONDUITE} ticks. Il s'arrête quand le joueur part, ou au bout de
 *       {@link #DUREE_SERVEUR_S} secondes.</li>
 *   <li>Le client ({@code runEssaiClientPorteur}), sous {@code -Daxion.essai.porteur=<délai d'un sens, ms>}, se connecte par le
 *       relais, relève {@value #MESURE} ticks de portage dès que le véhicule part, écrit {@code rapport-porteur.txt}, puis
 *       s'arrête. Sous {@code -Daxion.essai.film}, il se filme à la troisième personne.</li>
 * </ul>
 */
public final class EssaiPorteur {

    private static final Logger LOGGER = LoggerFactory.getLogger("AXION essai");

    private static final boolean SERVEUR = "1".equals(System.getenv("AXION_ESSAI_PORTEUR"));
    /** Un second joueur, l'observateur ({@link EssaiObservateur}), regarde le porté : le serveur l'attend aussi. */
    private static final boolean DEUX = "1".equals(System.getenv("AXION_ESSAI_PORTEUR_DEUX"));
    private static final String OBSERVATEUR = "Observateur";
    /** Un véhicule d'essai au siège réglé : le porté s'y assoit au volant au lieu d'être lâché dessus (ADR-177). */
    private static final String SIEGE = System.getenv("AXION_ESSAI_SIEGE");
    /** La place où il s'assoit : au volant, la voiture reste ; ailleurs, le serveur la conduit. */
    private static final String PLACE = System.getenv().getOrDefault("AXION_ESSAI_PLACE", "conducteur");
    /**
     * Sous {@code AXION_ESSAI_VALEURS=1}, avec un siège : le serveur met le réservoir au quart, use la première roue à 0,4
     * et fait pleuvoir, puis dit ses valeurs, que l'observateur doit lire chez lui (ADR-176, ADR-182).
     */
    private static final boolean VALEURS = "1".equals(System.getenv("AXION_ESSAI_VALEURS"));
    /**
     * Sous {@code AXION_ESSAI_RETARD=1} : les deux processus disent au journal de quoi calculer le retard réel du porté face
     * à celui que le serveur lui prête ({@code tools/campagne/retard.py}, ADR-184).
     */
    private static final boolean RETARD = "1".equals(System.getenv("AXION_ESSAI_RETARD"));
    private static final long DELAI_MS = Long.getLong("axion.essai.porteur", -1L);
    /** La gigue du relais, en millisecondes : chaque morceau attend de 0 à tant de plus, au hasard. */
    private static final long GIGUE_MS = Long.getLong("axion.essai.gigue", 0L);
    private static final boolean FILM = Boolean.getBoolean("axion.essai.film");
    /**
     * ADR-183 D4 : au lieu de le conduire, le serveur fige le véhicule et le mène comme un ascenseur — vers le haut, un
     * palier, vers le bas, puis de côté. Au serveur sous {@code AXION_ESSAI_ASCENSEUR=1}, au client sous
     * {@code -Daxion.essai.ascenseur}.
     */
    private static final boolean ASCENSEUR = "1".equals(System.getenv("AXION_ESSAI_ASCENSEUR"));
    private static final boolean ASCENSEUR_CLIENT = Boolean.getBoolean("axion.essai.ascenseur");
    /** Les ticks de chaque étape : monter, tenir, descendre, aller de côté ; et la vitesse, en blocs par tick. */
    private static final int MONTE = 120;
    private static final int TIENT = 40;
    private static final int DESCEND = 120;
    private static final double PAS = 0.05;

    /**
     * ADR-183 D4 : le joueur debout au sol devant le véhicule, que le serveur fige et mène contre lui. Au serveur sous
     * {@code AXION_ESSAI_POUSSEE=1} ; le client, jamais porté, s'en va de lui-même, et seul le serveur juge.
     */
    private static final boolean POUSSEE = "1".equals(System.getenv("AXION_ESSAI_POUSSEE"));
    /** Les ticks de poussée, et la distance du joueur devant le véhicule au départ, en blocs. */
    private static final int POUSSE = 120;
    private static final double DEVANT = 4.0;

    private static final int DUREE_SERVEUR_S = 300;
    private static final String HOTE = "127.0.0.1";
    private static final int PORT = 25599;
    private static final int PORT_RELAIS = 25600;

    private static final int SOL_Y = 200;
    private static final int SOL_COTE = 64;
    private static final String VEHICULE = "axion:test/vehicule";

    /** Les ticks de conduite du serveur : vingt secondes, cinq fois les 80 ticks de l'expulsion « en train de voler ». */
    private static final int CONDUITE = 400;
    /** Les ticks que le client relève, dès que le véhicule part ; moins que la conduite, pour finir avant le freinage. */
    private static final int MESURE = 360;
    /** La commande de conduite, témoin compris : à mi-gaz, en cercle large. */
    private static final DriverInput CONDUIRE = new DriverInput(0.5, 0, 0.45, 0, 0, 1);

    /** Ce que la mesure exige : porté, resté à sa place, sur un vrai trajet. */
    private static final double PORTE_MIN = 0.95;
    private static final double DERIVE_MAX = 0.35;
    private static final double PARCOURS_MIN = 10.0;

    private EssaiPorteur() {
        throw new AssertionError("classe utilitaire, non instanciable");
    }

    /** Le serveur : le sol, le véhicule, le joueur dessus, la conduite, puis l'arrêt. */
    @Mod.EventBusSubscriber(modid = AxionMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
    public static final class Serveur {

        /** Ticks laissés au client pour charger, avant que le joueur soit lâché sur le véhicule ; puis avant le départ. */
        private static final int CHARGEMENT = 160;
        private static final int POSE = 60;

        private static long debut;
        private static ServerPlayer joueur;
        private static ServerPlayer spectateur;
        private static AxionEntity vehicule;
        private static int tick;
        private static int portes;
        private static double parcours;
        private static double parcoursSeul;
        private static Vec3 avant;
        private static Vec3 base;
        private static double joueurAuDepart;

        private Serveur() {}

        /** Le premier moteur électrique, tel que ses sources le disent ; rien sans lui. */
        private static String moteur(dev.axion.api.assembly.Assembly assembly) {
            try {
                return String.format(Locale.ROOT, ", moteur %.0f tr/min %.3f kW",
                        assembly.source("motor.arriere.rpm"), assembly.source("motor.arriere.power"));
            } catch (IllegalArgumentException sansMoteur) {
                return "";
            }
        }

        /** Où le plateau doit être après {@code t} ticks de conduite, depuis sa place de départ. */
        private static Vec3 ascension(int t) {
            double haut = PAS * Math.min(t, MONTE) - PAS * Math.max(0, Math.min(t - MONTE - TIENT, DESCEND));
            double cote = PAS * Math.max(0, t - MONTE - TIENT - DESCEND);
            return base.add(cote, haut, 0.0);
        }

        /**
         * Mène le véhicule figé vers le joueur, à {@link #PAS} bloc par tick, puis l'arrête ; dit de combien le joueur a
         * été déplacé, sa hauteur au-dessus du véhicule — poussé devant, ou monté dessus s'il s'y marche (ADR-178 D5) —
         * et de combien il y est encore enfoncé au serveur, qui le voit en retard de sa latence.
         */
        private static void pousser(int conduite) {
            var assembly = ForgeAxionApi.view(vehicule);
            if (base == null) {
                assembly.setFrozen(true);
                base = vehicule.position();
                joueurAuDepart = joueur.getX();
            }
            assembly.setKinematicTransform(new dev.axion.api.assembly.Transform(
                    base.add(PAS * Math.min(conduite, POUSSE), 0.0, 0.0), vehicule.orientation(), new Vec3(1, 1, 1)));
            Vec3 sortie = AssemblyCollision.pushOut(joueur);
            double enfonce = sortie == null ? 0.0 : sortie.length();
            if (conduite % 20 == 0 || conduite == POUSSE + 40) {
                LOGGER.info(String.format(Locale.ROOT, "[porteur] poussée : tick %d, véhicule à x %.2f, joueur à x %.2f,"
                        + " poussé de %.2f bloc, %.2f au-dessus du véhicule, enfoncé de %.2f bloc", conduite,
                        vehicule.getX(), joueur.getX(), joueur.getX() - joueurAuDepart, joueur.getY() - vehicule.getY(),
                        enfonce));
            }
        }

        /** Mène le plateau figé à sa place de ce tick, et compte le portage comme la conduite le fait. */
        private static void menerLAscenseur(int conduite) {
            var assembly = ForgeAxionApi.view(vehicule);
            if (base == null) {
                assembly.setFrozen(true);
                base = vehicule.position();
            }
            assembly.setKinematicTransform(new dev.axion.api.assembly.Transform(ascension(conduite),
                    vehicule.orientation(), new Vec3(1, 1, 1)));
            CarrySupports.Support support = CarrySupports.of(joueur);
            if (support != null && support.assembly() == vehicule) {
                portes++;
            }
            if (avant != null) {
                parcours += vehicule.position().distanceTo(avant);
            }
            avant = vehicule.position();
            if (conduite % 40 == 0) {
                LOGGER.info(String.format(Locale.ROOT, "[porteur] ascenseur %d : %s, joueur à %.2f au-dessus, à %.2f de"
                        + " côté, plateau à %s", conduite, support == null ? "sans support" : "corps " + support.body(),
                        joueur.getY() - vehicule.getY(),
                        Math.hypot(joueur.getX() - vehicule.getX(), joueur.getZ() - vehicule.getZ()), vehicule.position()));
            }
        }

        /**
         * Le sol, en damier de quatre blocs pour que la vitesse se voie, posé dès le premier tick : posé à l'arrivée du joueur,
         * sa lumière n'était pas encore calculée chez le client, et le film sortait noir.
         */
        private static void poserLeSol(ServerLevel monde) {
            for (int x = 0; x < SOL_COTE; x++) {
                for (int z = 0; z < SOL_COTE; z++) {
                    boolean clair = (x / 4 + z / 4) % 2 == 0;
                    monde.setBlockAndUpdate(new BlockPos(x, SOL_Y, z),
                            (clair ? Blocks.SMOOTH_STONE : Blocks.GRAY_CONCRETE).defaultBlockState());
                }
            }
        }

        /** @param event la fin d'un tick du serveur, avant le transport : la position telle que le porté l'a envoyée */
        @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.HIGHEST)
        public static void releverLeRecu(TickEvent.ServerTickEvent event) {
            if (SERVEUR && RETARD && event.phase == TickEvent.Phase.END && joueur != null && vehicule != null
                    && tick >= CHARGEMENT) {
                // Ce gestionnaire passe avant `mener`, qui compte le tick : le même numéro que son relevé.
                LOGGER.info("[retard] recu {} {} {} {}", tick + 1, joueur.getX(), joueur.getY(), joueur.getZ());
            }
        }

        /** @param event la fin d'un tick du serveur ; relevé après le transport (ServerCarry) */
        @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.LOWEST)
        public static void mener(TickEvent.ServerTickEvent event) {
            if (!SERVEUR || event.phase != TickEvent.Phase.END) {
                return;
            }
            MinecraftServer serveur = event.getServer();
            if (debut == 0) {
                debut = System.nanoTime();
                poserLeSol(serveur.overworld());
            }
            if (System.nanoTime() - debut > DUREE_SERVEUR_S * 1_000_000_000L
                    || (joueur != null && joueur.hasDisconnected())) {
                LOGGER.info("[porteur] fin du serveur d'essai : porté {} ticks sur {} de conduite, parcours {} blocs", portes,
                        Math.max(0, Math.min(tick - CHARGEMENT - POSE, CONDUITE)),
                        String.format(Locale.ROOT, "%.1f", parcours));
                serveur.halt(false);
                return;
            }
            ServerLevel monde = serveur.overworld();
            if (joueur == null) {
                ServerPlayer porte = null;
                ServerPlayer regard = null;
                for (ServerPlayer present : serveur.getPlayerList().getPlayers()) {
                    if (DEUX && OBSERVATEUR.equals(present.getGameProfile().getName())) {
                        regard = present;
                    } else {
                        porte = present;
                    }
                }
                if (porte == null || DEUX && regard == null) {
                    return;
                }
                if (regard != null) {
                    regard.setGameMode(GameType.SPECTATOR);
                    spectateur = regard;
                }
                monde.setWeatherParameters(20 * 60 * 20, 0, false, false);
                monde.setDayTime(6000);
                joueur = porte;
                joueur.setGameMode(GameType.SURVIVAL);
                joueur.teleportTo(monde, SOL_COTE / 2.0, SOL_Y + 1.0, SOL_COTE / 2.0 + 5.0, 180f, 15f);
                try {
                    vehicule = AssembliesDeTest.poser(monde, SIEGE == null ? VEHICULE : SIEGE,
                            new Vec3(SOL_COTE / 2.0 + 0.5, SOL_Y + 1.3, SOL_COTE / 2.0 + 0.5),
                            new float[] {0f, 0f, 0f, 1f});
                } catch (IllegalStateException refus) {
                    LOGGER.error("[porteur] ÉCHEC : véhicule non posé — {}", refus.getMessage());
                    serveur.halt(false);
                }
                return;
            }
            tick++;
            if (spectateur != null && vehicule != null) {
                // L'observateur suit le véhicule, de trois quarts et au-dessus.
                spectateur.teleportTo(monde, vehicule.getX() + 5.0, vehicule.getY() + 3.5, vehicule.getZ() + 6.0, 140f,
                        22f);
            }
            double[] gardee = RETARD && tick >= CHARGEMENT ? vehicule.carryPoses(0) : null;
            if (gardee != null) {
                CarrySupports.Support porte = CarrySupports.of(joueur);
                LOGGER.info("[retard] choisi {} {} {} {} {} {} {} {} {} {}", tick, porte == null ? -1 : porte.lag(),
                        dev.axion.forge.net.AxionNetwork.roundTripTicks(joueur.getUUID()), gardee[0], gardee[1],
                        gardee[2], gardee[3], gardee[4], gardee[5], gardee[6]);
            }
            // Le témoin : le véhicule conduit seul, loin du joueur, avec la même commande, puis freiné.
            if (tick >= 20 && tick < CHARGEMENT) {
                var seul = ForgeAxionApi.view(vehicule).vehicle().orElseThrow();
                seul.drive(tick < 80 ? CONDUIRE : new DriverInput(0, 1, 0, 0, 1, 0));
                if (tick < 80) {
                    if (avant != null) {
                        parcoursSeul += vehicule.position().subtract(avant).horizontalDistance();
                    }
                    avant = vehicule.position();
                } else if (tick == 80) {
                    LOGGER.info(String.format(Locale.ROOT, "[porteur] témoin : seul, le véhicule a parcouru %.2f blocs en 60"
                            + " ticks", parcoursSeul));
                    avant = null;
                }
            }
            if (tick == CHARGEMENT && SIEGE != null) {
                boolean assis = vehicule.reseat(joueur, PLACE);
                LOGGER.info("[porteur] joueur lâché au-dessus du véhicule : assis à la place {} {}", PLACE, assis);
                if (VALEURS) {
                    var assembly = ForgeAxionApi.view(vehicule);
                    var reservoir = assembly.energy().stores().get(0);
                    reservoir.set(reservoir.capacity() / 4);
                    assembly.vehicle().orElseThrow().setWear(vehicule.wheelNodes().get(0), 0.4);
                    monde.setWeatherParameters(0, 20 * 60 * 20, true, false);
                    // Un moteur électrique ne tourne que son contact mis, une variable du conducteur absent.
                    if (assembly.variables().names().contains("contact")) {
                        assembly.variables().setBoolean("contact", true);
                    }
                }
            } else if (tick == CHARGEMENT && POUSSEE) {
                joueur.teleportTo(monde, vehicule.getX() + DEVANT, SOL_Y + 1.0, vehicule.getZ(), 90f, 10f);
                LOGGER.info("[porteur] joueur lâché au sol devant le véhicule");
            } else if (tick == CHARGEMENT) {
                joueur.teleportTo(monde, vehicule.getX(), vehicule.getY() + 2.5, vehicule.getZ(), 180f, 15f);
                LOGGER.info("[porteur] joueur lâché au-dessus du véhicule");
            }
            int conduite = tick - CHARGEMENT - POSE;
            if (conduite < 0) {
                return;
            }
            var commande = ForgeAxionApi.view(vehicule).vehicle().orElseThrow();
            if (VALEURS && conduite % 20 == 0) {
                var reservoir = ForgeAxionApi.view(vehicule).energy().stores().get(0);
                LOGGER.info(String.format(Locale.ROOT, "[porteur] valeurs serveur : carburant %.3f, mouillé %.3f, usure %.3f",
                        reservoir.ratio(), ForgeAxionApi.view(vehicule).source("vehicle.wet"), vehicule.wheelWear(0))
                        + moteur(ForgeAxionApi.view(vehicule)));
            }
            // Assis au volant, le porté tient la voiture : un mod ne la conduit pas (ADR-167 D9), elle reste là.
            if (SIEGE != null && "conducteur".equals(PLACE)) {
                return;
            }
            if (POUSSEE) {
                if (conduite <= POUSSE + 40) {
                    pousser(conduite);
                }
                return;
            }
            if (ASCENSEUR) {
                if (conduite < CONDUITE) {
                    menerLAscenseur(conduite);
                }
                return;
            }
            if (conduite < CONDUITE) {
                commande.drive(CONDUIRE);
                CarrySupports.Support support = CarrySupports.of(joueur);
                if (support != null && support.assembly() == vehicule) {
                    portes++;
                }
                if (avant != null) {
                    parcours += vehicule.position().subtract(avant).horizontalDistance();
                }
                avant = vehicule.position();
                if (conduite % 40 == 0) {
                    LOGGER.info(String.format(Locale.ROOT, "[porteur] conduite %d : %s, écart horizontal %.2f bloc, joueur à"
                            + " %.2f au-dessus, véhicule à %.2f blocs parcourus", conduite, support == null ? "sans support"
                                    : "corps " + support.body() + ", retard " + support.lag(),
                            Math.hypot(joueur.getX() - vehicule.getX(), joueur.getZ() - vehicule.getZ()),
                            joueur.getY() - vehicule.getY(), parcours));
                }
            } else {
                commande.drive(new DriverInput(0, 1, 0, 0, 1, 0));
            }
        }
    }

    /** Le client : se connecter, se laisser porter, mesurer, se filmer, s'arrêter. */
    @Mod.EventBusSubscriber(modid = AxionMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
    public static final class Client {

        private static final int TENTATIVES_MAX = 3;
        /** Ticks en jeu laissés pour que le véhicule porte le joueur et parte ; au-delà, l'essai échoue. */
        private static final int ATTENTE_MAX = 900;

        private static RelaisLatence relais;
        private static Tournage tournage;
        private static int tentatives;
        private static boolean connecte;
        private static boolean fini;
        private static int enJeu;
        private static int mesures;
        private static int portes;
        private static double derive;
        private static double parcours;
        private static double[] place;
        private static int corps = -1;
        private static AxionEntity porteur;
        private static Vec3 avant;
        private static final List<String> lignes = new ArrayList<>();

        private Client() {}

        /** @param event le tick du client */
        @SubscribeEvent
        public static void porter(TickEvent.ClientTickEvent event) {
            if (DELAI_MS < 0 || fini || event.phase != TickEvent.Phase.END) {
                return;
            }
            Minecraft minecraft = Minecraft.getInstance();
            if (!connecte) {
                if (FILM && tentatives == 0) {
                    Tournage.reglerLesOptions(minecraft);
                }
                connecter(minecraft);
                return;
            }
            if (minecraft.player == null || minecraft.level == null) {
                return;
            }
            CarrySupports.Support support = CarrySupports.of(minecraft.player);
            if (porteur == null) {
                if (support != null) {
                    Vec3 la = support.assembly().position();
                    // Le départ : le véhicule qui porte le joueur s'est déplacé de plus de 5 cm en un tick.
                    // En ascenseur, il part vers le haut : tout déplacement compte.
                    if (avant != null && support.local() != null && (ASCENSEUR_CLIENT ? la.distanceTo(avant) > 0.02
                            : la.subtract(avant).horizontalDistance() > 0.05)) {
                        porteur = support.assembly();
                        corps = support.body();
                        place = support.local();
                        LOGGER.info("[porteur] client : le véhicule part, la mesure commence");
                    }
                    avant = la;
                } else {
                    avant = null;
                }
                if (++enJeu > ATTENTE_MAX) {
                    echec(minecraft, "jamais porté par un véhicule qui part, en " + ATTENTE_MAX / 20 + " s");
                }
                return;
            }
            if (FILM && tournage == null) {
                minecraft.options.setCameraType(CameraType.THIRD_PERSON_BACK);
                minecraft.player.setXRot(28f);
                tournage = new Tournage(minecraft.gameDirectory.toPath().resolve("axion-film").resolve("images"),
                        "AXION ENGINE, ADR-178");
            }
            mesures++;
            if (RETARD && support != null && support.assembly() == porteur && support.local() != null) {
                double[] sienne = support.local();
                LOGGER.info("[retard] envoye {} {} {} {} {} {}", minecraft.player.getX(), minecraft.player.getY(),
                        minecraft.player.getZ(), sienne[0], sienne[1], sienne[2]);
            }
            Vec3 la = porteur.position();
            parcours += ASCENSEUR_CLIENT ? la.distanceTo(avant) : la.subtract(avant).horizontalDistance();
            avant = la;
            if (support != null && support.assembly() == porteur && support.local() != null) {
                portes++;
                double[] local = support.local();
                if (support.body() == corps) {
                    derive = Math.max(derive, Math.sqrt(Math.pow(local[0] - place[0], 2) + Math.pow(local[1] - place[1], 2)
                            + Math.pow(local[2] - place[2], 2)));
                }
            }
            if (mesures % 40 == 0) {
                String ligne = String.format(Locale.ROOT, "tick %d : porté %d, dérive max %.3f bloc, parcours %.1f blocs",
                        mesures, portes, derive, parcours);
                lignes.add(ligne);
                LOGGER.info("[porteur] client : {}", ligne);
            }
            if (mesures == MESURE) {
                terminer(minecraft);
            }
        }

        /** @param event la fin d'une image */
        @SubscribeEvent
        public static void filmerLePortage(TickEvent.RenderTickEvent event) {
            if (!FILM || tournage == null || fini || event.phase != TickEvent.Phase.END || !tournage.imageAttendue()) {
                return;
            }
            String legende = String.format(Locale.ROOT,
                    "Un joueur debout sur un véhicule : serveur dédié, %d ms de latence par sens", DELAI_MS);
            String valeurs = String.format(Locale.ROOT, "porté %d ticks sur %d   dérive max %.3f bloc   parcours %.1f blocs",
                    portes, mesures, derive, parcours);
            double secondes = (System.nanoTime() - tournage.debut()) / 1e9;
            tournage.capturer(Minecraft.getInstance().getMainRenderTarget(), legende, valeurs,
                    String.format(Locale.ROOT, "%.1f s", secondes));
        }

        /** @param event l'écran qui s'ouvre : une déconnexion fait échouer l'essai, sauf l'expiration de la connexion */
        @SubscribeEvent
        public static void deconnexionDuPortage(ScreenEvent.Opening event) {
            if (DELAI_MS < 0 || fini || !(event.getNewScreen() instanceof DisconnectedScreen ecran)) {
                return;
            }
            net.minecraft.network.chat.Component raison = EssaiAddonsExiges.Client.raison(ecran);
            boolean expiree = raison.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents cle
                    && "disconnect.timeout".equals(cle.getKey());
            if (mesures == 0 && tentatives < TENTATIVES_MAX && expiree) {
                LOGGER.warn("[porteur] tentative de connexion {} expirée, nouvelle tentative", tentatives);
                fermerRelais();
                connecte = false;
                event.setNewScreen(new TitleScreen());
                return;
            }
            echec(Minecraft.getInstance(), "écran de déconnexion : " + ecran.getTitle().getString() + " — "
                    + raison.getString().replace('\n', '|'));
        }

        private static void connecter(Minecraft minecraft) {
            if (minecraft.getOverlay() != null || !(minecraft.screen instanceof TitleScreen titre)) {
                return;
            }
            connecte = true;
            tentatives++;
            String adresse = HOTE + ":" + PORT;
            if (DELAI_MS > 0) {
                try {
                    relais = new RelaisLatence(PORT_RELAIS, HOTE, PORT, DELAI_MS, GIGUE_MS);
                    adresse = HOTE + ":" + PORT_RELAIS;
                } catch (IOException faute) {
                    LOGGER.error("[porteur] relais de latence impossible : {}", faute.toString());
                }
            }
            LOGGER.info("[porteur] connexion à {} ({} ms par sens)", adresse, DELAI_MS);
            ConnectScreen.startConnecting(titre, minecraft, ServerAddress.parseString(adresse),
                    new ServerData("essai-porteur", adresse, false), false);
        }

        private static void terminer(Minecraft minecraft) {
            fini = true;
            double part = (double) portes / mesures;
            boolean vert = part >= PORTE_MIN && derive <= DERIVE_MAX && parcours >= PARCOURS_MIN;
            lignes.add(String.format(Locale.ROOT, "%s : porté %d ticks sur %d (au moins %.0f %%), dérive max %.3f bloc (au plus"
                    + " %.2f), parcours %.1f blocs (au moins %.0f), %d ms par sens, %d tentative(s)", vert ? "VERT" : "ROUGE",
                    portes, mesures, PORTE_MIN * 100, derive, DERIVE_MAX, parcours, PARCOURS_MIN, DELAI_MS, tentatives));
            ecrire(minecraft);
            fermerLeFilm(minecraft, null);
            arreter(minecraft);
        }

        private static void echec(Minecraft minecraft, String raison) {
            fini = true;
            lignes.add("ÉCHEC : " + raison);
            ecrire(minecraft);
            fermerLeFilm(minecraft, raison);
            arreter(minecraft);
        }

        private static void ecrire(Minecraft minecraft) {
            LOGGER.info("[porteur] {}", lignes.get(lignes.size() - 1));
            try {
                Files.write(minecraft.gameDirectory.toPath().resolve("rapport-porteur.txt"), lignes, StandardCharsets.UTF_8);
            } catch (IOException faute) {
                LOGGER.error("[porteur] rapport non écrit : {}", faute.toString());
            }
        }

        private static void fermerLeFilm(Minecraft minecraft, String motif) {
            if (tournage == null) {
                return;
            }
            String faute = tournage.fermer();
            faute = faute != null ? faute : motif;
            String bilan = String.format(Locale.ROOT, "{\"images\": %d, \"uniques\": %d, \"echec\": %s}%n",
                    tournage.images(), tournage.uniques(), faute == null ? "null" : "\"" + faute.replace('"', '\'') + "\"");
            tournage = null;
            Path chemin = minecraft.gameDirectory.toPath().resolve("axion-film").resolve("termine.json");
            try {
                Files.writeString(chemin, bilan, StandardCharsets.UTF_8);
            } catch (IOException faute2) {
                LOGGER.error("[porteur] bilan du film non écrit : {}", faute2.toString());
            }
        }

        private static void arreter(Minecraft minecraft) {
            if (minecraft.level != null) {
                minecraft.level.disconnect();
            }
            fermerRelais();
            minecraft.stop();
        }

        private static void fermerRelais() {
            if (relais != null) {
                try {
                    relais.close();
                } catch (IOException ignoree) {
                    // Le relais se ferme avec le processus.
                }
                relais = null;
            }
        }
    }
}
