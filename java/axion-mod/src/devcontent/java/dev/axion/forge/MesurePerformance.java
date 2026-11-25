package dev.axion.forge;

import dev.axion.AxionMod;
import dev.axion.api.vehicle.DriverInput;
import dev.axion.physics.DegradationGauges;
import dev.axion.physics.StepBreakdown;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CAP-53, la mesure du parallélisme et des cœurs côté serveur : un serveur dédié sans joueur, des véhicules d'essai conduits
 * en cercle, par paliers de nombre ; à chaque palier, un temps d'établissement puis une fenêtre relevée. Inerte sans la
 * variable d'environnement {@code AXION_MESURE} (les paliers, séparés par des virgules) ; contenu de développement, jamais
 * livré. {@code tools/campagne/mesure.py} la lance, relève les threads du processus entre les deux marques du journal, et
 * écrit le rapport.
 */
@Mod.EventBusSubscriber(modid = AxionMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class MesurePerformance {

    private static final Logger LOGGER = LoggerFactory.getLogger("AXION mesure");

    private static final String PALIERS = System.getenv("AXION_MESURE");
    private static final String VEHICULE = "axion:test/vehicule";
    /** Le sol du monde plat : la première couche d'air. */
    private static final int SOL = -60;
    /** Les ticks d'établissement, puis ceux de la fenêtre relevée. */
    private static final int ETABLIR = 600;
    private static final int RELEVER = 600;
    /** Les ticks gardés au même palier après la fenêtre : le relevé des threads, pris du dehors, y trouve le même régime. */
    private static final int MARGE = 200;
    /** Un relevé des métriques natives tous les tant de ticks : l'export coûte, il ne se lit pas à chaque tick. */
    private static final int ECHANTILLON = 5;
    private static final int PAR_LIGNE = 12;
    private static final double ESPACE = 16.0;

    private static int[] paliers;
    private static int palier;
    private static int tick = -100;
    private static final List<AxionEntity> VEHICULES = new ArrayList<>();

    private static long muraleDebut;
    private static long cpuServeurDebut;
    private static long cpuProcessusDebut;
    private static long gcDebut;
    private static final List<Double> PAS = new ArrayList<>();
    private static final List<Double> INTEGRATION = new ArrayList<>();
    private static final List<Double> CONTACTS = new ArrayList<>();
    private static final List<Double> JOINTS = new ArrayList<>();
    private static final List<Double> VANILLE = new ArrayList<>();
    private static long colliders;
    private static int palierGouverneur;
    private static double parcours;

    private MesurePerformance() {}

    /** @param event un tick du serveur ; après le pas de la simulation */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void mesurer(TickEvent.ServerTickEvent event) {
        if (PALIERS == null || event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer serveur = event.getServer();
        ServerLevel monde = serveur.overworld();
        if (paliers == null) {
            paliers = Arrays.stream(PALIERS.split(",")).map(String::trim).mapToInt(Integer::parseInt).toArray();
            preparer(monde);
        }
        tick++;
        if (tick < 0) {
            return;
        }
        if (tick == 0) {
            completer(monde, paliers[palier]);
            LOGGER.info("[mesure] palier {} véhicules : établissement", paliers[palier]);
        }
        for (AxionEntity vehicule : VEHICULES) {
            ForgeAxionApi.view(vehicule).vehicle().ifPresent(commande -> commande.drive(
                    new DriverInput(0.3, 0, 0.45, 0, 0, 1)));
        }
        if (tick == ETABLIR) {
            commencer();
            LOGGER.info("[mesure] debut {} pid {}", paliers[palier], ProcessHandle.current().pid());
        } else if (tick > ETABLIR && tick <= ETABLIR + RELEVER) {
            relever(serveur, tick - ETABLIR);
            if (tick == ETABLIR + RELEVER) {
                LOGGER.info("[mesure] fin {}", paliers[palier]);
                ecrire(serveur);
            }
        } else if (tick == ETABLIR + RELEVER + MARGE) {
            palier++;
            tick = -1;
            if (palier == paliers.length) {
                LOGGER.info("[mesure] terminee");
                serveur.halt(false);
                paliers = new int[0];
                tick = Integer.MIN_VALUE / 2;
            }
        }
    }

    /** Force les chunks de la grille et y déclare les observateurs qui gardent la simulation éveillée. */
    private static void preparer(ServerLevel monde) {
        int demi = (int) (PAR_LIGNE * ESPACE / 2) + 24;
        for (int x = -demi; x <= demi; x += 16) {
            for (int z = -demi; z <= demi; z += 16) {
                monde.setChunkForced(x >> 4, z >> 4, true);
            }
        }
        for (int x = -64; x <= 64; x += 64) {
            for (int z = -64; z <= 64; z += 64) {
                ObservateursDeTest.declarer(monde, new Vec3(x, SOL + 1.0, z));
            }
        }
        monde.setWeatherParameters(20 * 60 * 60, 0, false, false);
    }

    /** Ajoute des véhicules jusqu'au palier : une grille de douze par ligne, un tous les seize blocs. */
    private static void completer(ServerLevel monde, int voulu) {
        while (VEHICULES.size() < voulu) {
            int rang = VEHICULES.size();
            double x = (rang % PAR_LIGNE - (PAR_LIGNE - 1) / 2.0) * ESPACE;
            double z = (rang / PAR_LIGNE - 5) * ESPACE;
            VEHICULES.add(AssembliesDeTest.poser(monde, VEHICULE, new Vec3(x, SOL + 0.3, z),
                    new float[] {0f, 0f, 0f, 1f}));
        }
    }

    private static void commencer() {
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        muraleDebut = System.nanoTime();
        cpuServeurDebut = threads.getCurrentThreadCpuTime();
        cpuProcessusDebut = cpuProcessus();
        gcDebut = gc();
        PAS.clear();
        INTEGRATION.clear();
        CONTACTS.clear();
        JOINTS.clear();
        VANILLE.clear();
        colliders = 0;
        palierGouverneur = 0;
        parcours = 0;
    }

    private static void relever(MinecraftServer serveur, int ticks) {
        // Le temps que vanilla compte à son tick, hors les fins de tick (où AXION simule).
        VANILLE.add(serveur.tickTimes[serveur.getTickCount() % 100] / 1e6);
        palierGouverneur = Math.max(palierGouverneur, AxionEntities.degradationLevel());
        for (AxionEntity vehicule : VEHICULES) {
            parcours += Math.hypot(vehicule.getX() - vehicule.xo, vehicule.getZ() - vehicule.zo);
        }
        if (ticks % ECHANTILLON != 0) {
            return;
        }
        String export = RuntimeAccess.get().metricsJson();
        StepBreakdown pas = export == null ? null : StepBreakdown.parse(export);
        if (pas != null) {
            PAS.add(pas.stepNs() / 1e6);
            INTEGRATION.add(pas.integrationNs() / 1e6);
            CONTACTS.add(pas.contactsNs() / 1e6);
            JOINTS.add(pas.jointsNs() / 1e6);
            colliders = pas.colliders();
        }
        DegradationGauges jauges = export == null ? null : DegradationGauges.parse(export);
        if (jauges == null && pas == null) {
            LOGGER.warn("[mesure] export des métriques illisible");
        }
    }

    private static void ecrire(MinecraftServer serveur) {
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        double murale = (System.nanoTime() - muraleDebut) / 1e9;
        double serveurCpu = (threads.getCurrentThreadCpuTime() - cpuServeurDebut) / 1e9;
        double processus = (cpuProcessus() - cpuProcessusDebut) / 1e9;
        String ligne = String.format(Locale.ROOT,
                "{\"vehicules\": %d, \"ticks\": %d, \"murale_s\": %.3f, \"cpu_thread_serveur_s\": %.3f,"
                        + " \"cpu_processus_s\": %.3f, \"gc_ms\": %d,"
                        + " \"tick_vanilla_ms\": %.3f, \"pas_ms_moyen\": %.3f, \"pas_ms_p95\": %.3f,"
                        + " \"integration_ms\": %.3f, \"contacts_ms\": %.3f, \"joints_ms\": %.3f,"
                        + " \"colliders\": %d, \"palier_gouverneur\": %d, \"vitesse_moyenne_m_s\": %.2f,"
                        + " \"coeurs_logiques\": %d}%n",
                paliers[palier], RELEVER, murale, serveurCpu, processus, gc() - gcDebut, moyenne(VANILLE),
                moyenne(PAS), p95(PAS), moyenne(INTEGRATION), moyenne(CONTACTS), moyenne(JOINTS), colliders,
                palierGouverneur, VEHICULES.isEmpty() ? 0.0 : parcours / VEHICULES.size() / (RELEVER / 20.0),
                Runtime.getRuntime().availableProcessors());
        LOGGER.info("[mesure] releve {}", ligne.trim());
        try {
            Path dossier = serveur.getServerDirectory().toPath().resolve("mesure");
            Files.createDirectories(dossier);
            Files.writeString(dossier.resolve("rapport.jsonl"), ligne, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException faute) {
            LOGGER.error("[mesure] rapport non écrit : {}", faute.toString());
        }
    }

    private static long cpuProcessus() {
        return ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean())
                .getProcessCpuTime();
    }

    private static long gc() {
        long total = 0;
        for (GarbageCollectorMXBean un : ManagementFactory.getGarbageCollectorMXBeans()) {
            total += Math.max(0, un.getCollectionTime());
        }
        return total;
    }

    private static double moyenne(List<Double> valeurs) {
        return valeurs.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    private static double p95(List<Double> valeurs) {
        if (valeurs.isEmpty()) {
            return 0;
        }
        List<Double> tries = new ArrayList<>(valeurs);
        tries.sort(Double::compare);
        return tries.get(Math.min(tries.size() - 1, (int) Math.ceil(0.95 * tries.size()) - 1));
    }
}
