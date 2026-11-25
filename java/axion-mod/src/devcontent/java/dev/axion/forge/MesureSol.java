package dev.axion.forge;

import dev.axion.AxionMod;
import dev.axion.api.vehicle.DriverInput;
import dev.axion.lifecycle.AxionRuntime;
import dev.axion.physics.SimCommandStream;
import dev.axion.physics.StepBreakdown;
import dev.axion.world.DimensionId;
import dev.axion.world.WorldCollisionSource.CollisionShape;
import dev.axion.world.WorldCollisionSource.SectionTile;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ADR-180 D5, le coût des tuiles du sol : un serveur dédié sans joueur sur un monde généré (graine fixée) construit les
 * tuiles de trois sortes de sections — la surface (« plaine »), le sous-sol (« mine »), une ville bâtie par ce harnais
 * en l'air —, puis fait rouler des véhicules sur le terrain. Inerte sans {@code AXION_MESURE_SOL} ; contenu de
 * développement. Il ne lit que ce que les versions d'avant et d'après ADR-180 ont en commun (la palette par
 * réflexion), pour que {@code tools/campagne/mesure_sol.py} le rejoue sur les deux.
 */
@Mod.EventBusSubscriber(modid = AxionMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class MesureSol {

    private static final Logger LOGGER = LoggerFactory.getLogger("AXION mesure sol");

    private static final boolean ACTIVE = System.getenv("AXION_MESURE_SOL") != null;
    private static final String VEHICULE = "axion:test/vehicule";
    /** Demi-côté, en chunks, de l'aire mesurée autour de l'origine. */
    private static final int RAYON = 8;
    /** La ville : à cette abscisse en chunks, loin du reste, sur quatre sections de côté. */
    private static final int VILLE_X = 256;
    private static final int VILLE_COTE = 4;
    private static final int VILLE_SECTION_Y = 12;
    private static final int PASSES_ECHAUFFEMENT = 3;
    private static final int PASSES = 7;
    private static final int VEHICULES_ROULANTS = 32;
    private static final int ETABLIR = 400;
    private static final int RELEVER = 600;

    private static int tick = -1;
    private static final List<AxionEntity> VEHICULES = new ArrayList<>();
    private static final List<Double> PAS = new ArrayList<>();
    private static long colliders;
    private static long cpuDebut;
    private static long muraleDebut;

    private MesureSol() {}

    /** @param event un tick du serveur, après le pas de la simulation */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void mesurer(TickEvent.ServerTickEvent event) {
        if (!ACTIVE || event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer serveur = event.getServer();
        ServerLevel monde = serveur.overworld();
        tick++;
        if (tick == 0) {
            charger(monde);
        } else if (tick == 200) {
            batirLaVille(monde);
        } else if (tick == 260) {
            mesurerLesTuiles(serveur, monde);
        } else if (tick == 280) {
            poserLesVehicules(monde);
        } else if (tick > 280) {
            for (AxionEntity vehicule : VEHICULES) {
                ForgeAxionApi.view(vehicule).vehicle().ifPresent(
                        commande -> commande.drive(new DriverInput(0.3, 0, 0.45, 0, 0, 1)));
            }
            int depuis = tick - 280;
            if (depuis == ETABLIR) {
                ThreadMXBean threads = ManagementFactory.getThreadMXBean();
                cpuDebut = threads.getCurrentThreadCpuTime();
                muraleDebut = System.nanoTime();
            } else if (depuis > ETABLIR && depuis <= ETABLIR + RELEVER) {
                relever();
                if (depuis == ETABLIR + RELEVER) {
                    ecrireLePas(serveur);
                    histogramme(serveur);
                    LOGGER.info("[mesure sol] terminee");
                    serveur.halt(false);
                    tick = Integer.MIN_VALUE / 2;
                }
            }
        }
    }

    private static void charger(ServerLevel monde) {
        for (int x = -RAYON - 1; x <= RAYON; x++) {
            for (int z = -RAYON - 1; z <= RAYON; z++) {
                monde.setChunkForced(x, z, true);
            }
        }
        for (int x = 0; x < VILLE_COTE; x++) {
            for (int z = 0; z < VILLE_COTE; z++) {
                monde.setChunkForced(VILLE_X + x, z, true);
            }
        }
        monde.setWeatherParameters(20 * 60 * 60, 0, false, false);
        LOGGER.info("[mesure sol] aire forcée");
    }

    /** Une rue, des trottoirs, des maisons de matériaux différents, de la neige sur une part : la ville la plus mêlée. */
    private static void batirLaVille(ServerLevel monde) {
        BlockState[] murs = {Blocks.OAK_PLANKS.defaultBlockState(), Blocks.BRICKS.defaultBlockState(),
            Blocks.GLASS.defaultBlockState(), Blocks.WHITE_WOOL.defaultBlockState(),
            Blocks.SANDSTONE.defaultBlockState()};
        int base = VILLE_SECTION_Y * 16;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int bx = 0; bx < VILLE_COTE * 16; bx++) {
            for (int bz = 0; bz < VILLE_COTE * 16; bz++) {
                int x = VILLE_X * 16 + bx;
                boolean rue = bx % 16 < 4 || bz % 16 < 4;
                boolean trottoir = !rue && (bx % 16 == 4 || bz % 16 == 4);
                BlockState sol = rue ? Blocks.STONE.defaultBlockState()
                        : trottoir ? Blocks.SMOOTH_STONE_SLAB.defaultBlockState()
                        : (bx + bz) % 3 == 0 ? Blocks.DIRT.defaultBlockState() : Blocks.GRASS_BLOCK.defaultBlockState();
                monde.setBlock(pos.set(x, base, bz), sol, Block.UPDATE_CLIENTS);
                boolean maison = bx % 16 >= 7 && bx % 16 < 13 && bz % 16 >= 7 && bz % 16 < 13;
                if (maison) {
                    BlockState mur = murs[(bx / 16 + bz / 16) % murs.length];
                    for (int h = 1; h <= 6; h++) {
                        monde.setBlock(pos.set(x, base + h, bz), h % 3 == 0 ? Blocks.GLASS.defaultBlockState() : mur,
                                Block.UPDATE_CLIENTS);
                    }
                } else if (rue && (bx + 2 * bz) % 5 == 0) {
                    monde.setBlock(pos.set(x, base + 1, bz), Blocks.SNOW.defaultBlockState(), Block.UPDATE_CLIENTS);
                } else if (rue && (bx * 3 + bz) % 11 == 0) {
                    monde.setBlock(pos.set(x, base, bz), Blocks.ICE.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
        }
        LOGGER.info("[mesure sol] ville bâtie");
    }

    private static void mesurerLesTuiles(MinecraftServer serveur, ServerLevel monde) {
        AxionRuntime runtime = RuntimeAccess.get();
        long dimension = DimensionId.of(monde.dimension().location().toString());
        ForgeWorldCollisionSource source = new ForgeWorldCollisionSource(
                id -> id == dimension ? monde : null, runtime::blockMaterials, runtime::physicsMaterialIndex);
        Set<SectionPos> plaine = new LinkedHashSet<>();
        List<SectionPos> mine = new ArrayList<>();
        for (int x = -RAYON; x < RAYON; x++) {
            for (int z = -RAYON; z < RAYON; z++) {
                int haut = monde.getHeight(Heightmap.Types.MOTION_BLOCKING, x * 16 + 8, z * 16 + 8) - 1;
                int sy = SectionPos.blockToSectionCoord(haut);
                plaine.add(SectionPos.of(x, sy, z));
                plaine.add(SectionPos.of(x, sy - 1, z));
                for (int y = -2; y <= 0; y++) {
                    mine.add(SectionPos.of(x, y, z));
                }
            }
        }
        List<SectionPos> ville = new ArrayList<>();
        for (int x = 0; x < VILLE_COTE; x++) {
            for (int z = 0; z < VILLE_COTE; z++) {
                ville.add(SectionPos.of(VILLE_X + x, VILLE_SECTION_Y, z));
            }
        }
        for (var categorie : List.of(java.util.Map.entry("plaine", List.copyOf(plaine)),
                java.util.Map.entry("mine", mine), java.util.Map.entry("ville", ville))) {
            ecrire(serveur, tuiles(source, dimension, categorie.getKey(), categorie.getValue()));
        }
    }

    private static String tuiles(ForgeWorldCollisionSource source, long dimension, String nom,
            List<SectionPos> sections) {
        for (int passe = 0; passe < PASSES_ECHAUFFEMENT; passe++) {
            for (SectionPos s : sections) {
                source.tileOf(dimension, s.x(), s.y(), s.z());
            }
        }
        long[] durees = new long[sections.size()];
        long[][] passes = new long[PASSES][sections.size()];
        SectionTile[] lues = new SectionTile[sections.size()];
        for (int passe = 0; passe < PASSES; passe++) {
            for (int i = 0; i < sections.size(); i++) {
                SectionPos s = sections.get(i);
                long debut = System.nanoTime();
                lues[i] = source.tileOf(dimension, s.x(), s.y(), s.z());
                passes[passe][i] = System.nanoTime() - debut;
            }
        }
        for (int i = 0; i < sections.size(); i++) {
            long[] une = new long[PASSES];
            for (int passe = 0; passe < PASSES; passe++) {
                une[passe] = passes[passe][i];
            }
            Arrays.sort(une);
            durees[i] = une[PASSES / 2];
        }
        long boites = 0;
        int champs = 0;
        int melangees = 0;
        long materiaux = 0;
        long octets = 0;
        int vides = 0;
        for (SectionTile tuile : lues) {
            CollisionShape forme = tuile.collision();
            int[] palette = palette(forme);
            if (palette.length > 0) {
                melangees++;
                materiaux += palette.length;
            }
            SimCommandStream flux = new SimCommandStream();
            if (forme instanceof CollisionShape.Boxes b) {
                boites += b.boxes().length;
                vides += b.boxes().length == 0 ? 1 : 0;
                flux.setWorldCollision(dimension, new int[] {0, 0, 0}, b.boxes(), b.material());
                octets += palette.length == 0 ? 0 : 2L * palette.length + b.boxes().length;
            } else if (forme instanceof CollisionShape.Heightfield h) {
                champs++;
                flux.setWorldHeightfield(dimension, new int[] {0, 0, 0}, h.rows(), h.cols(), h.heights(), h.scale(),
                        h.material());
                octets += palette.length == 0 ? 0 : 2L * palette.length + (long) (h.rows() - 1) * (h.cols() - 1);
            }
            octets += flux.toBytes().length;
        }
        Arrays.sort(durees);
        double somme = Arrays.stream(durees).sum();
        return String.format(Locale.ROOT, "{\"mesure\": \"tuiles\", \"categorie\": \"%s\", \"sections\": %d,"
                + " \"vides\": %d, \"ms_moyen\": %.4f, \"ms_p50\": %.4f, \"ms_p95\": %.4f, \"ms_max\": %.4f,"
                + " \"boites\": %d, \"champs\": %d, \"melangees\": %d, \"materiaux_des_melangees\": %d,"
                + " \"octets\": %d}", nom, sections.size(), vides, somme / durees.length / 1e6,
                durees[durees.length / 2] / 1e6, durees[(int) Math.ceil(0.95 * durees.length) - 1] / 1e6,
                durees[durees.length - 1] / 1e6, boites, champs, melangees, materiaux, octets);
    }

    private static int[] palette(CollisionShape forme) {
        try {
            return (int[]) forme.getClass().getMethod("palette").invoke(forme);
        } catch (NoSuchMethodException avant) {
            return new int[0];
        } catch (ReflectiveOperationException faute) {
            throw new IllegalStateException(faute);
        }
    }

    /** Les véhicules sur le terrain, en grille de seize blocs, posés juste au-dessus du sol. */
    private static void poserLesVehicules(ServerLevel monde) {
        for (int rang = 0; rang < VEHICULES_ROULANTS; rang++) {
            double x = (rang % 8 - 3.5) * 16.0;
            double z = (rang / 8 - 1.5) * 16.0;
            int sol = monde.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) x, (int) z);
            VEHICULES.add(AssembliesDeTest.poser(monde, VEHICULE, new Vec3(x, sol + 1.0, z),
                    new float[] {0f, 0f, 0f, 1f}));
        }
        for (int x = -64; x <= 64; x += 64) {
            for (int z = -64; z <= 64; z += 64) {
                int sol = monde.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
                ObservateursDeTest.declarer(monde, new Vec3(x, sol + 1.0, z));
            }
        }
    }

    private static void relever() {
        if ((tick % 5) != 0) {
            return;
        }
        String export = RuntimeAccess.get().metricsJson();
        StepBreakdown pas = export == null ? null : StepBreakdown.parse(export);
        if (pas != null) {
            PAS.add(pas.stepNs() / 1e6);
            colliders = pas.colliders();
        }
    }

    private static void ecrireLePas(MinecraftServer serveur) {
        double cpu = (ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime() - cpuDebut) / 1e9;
        double murale = (System.nanoTime() - muraleDebut) / 1e9;
        List<Double> tries = new ArrayList<>(PAS);
        tries.sort(Double::compare);
        double moyen = tries.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double p95 = tries.isEmpty() ? 0 : tries.get((int) Math.ceil(0.95 * tries.size()) - 1);
        System.gc();
        Runtime java = Runtime.getRuntime();
        ecrire(serveur, String.format(Locale.ROOT, "{\"mesure\": \"pas\", \"vehicules\": %d, \"tps\": %.2f,"
                + " \"cpu_serveur_ms_tick\": %.3f, \"pas_ms_moyen\": %.3f, \"pas_ms_p95\": %.3f, \"colliders\": %d,"
                + " \"gouverneur\": %d, \"tas_java_mo\": %.1f}", VEHICULES.size(), RELEVER / murale,
                1000 * cpu / RELEVER, moyen, p95, colliders, AxionEntities.degradationLevel(),
                (java.totalMemory() - java.freeMemory()) / 1048576.0));
    }

    /** Les classes qui occupent le plus de tas après un ramasse-miettes complet, écrites à côté du relevé. */
    private static void histogramme(MinecraftServer serveur) {
        try {
            Object rendu = ManagementFactory.getPlatformMBeanServer().invoke(
                    new javax.management.ObjectName("com.sun.management:type=DiagnosticCommand"), "gcClassHistogram",
                    new Object[] {null}, new String[] {String[].class.getName()});
            String texte = String.valueOf(rendu).lines().limit(45)
                    .collect(java.util.stream.Collectors.joining(System.lineSeparator()));
            Path dossier = serveur.getServerDirectory().toPath().resolve("mesure-sol");
            Files.createDirectories(dossier);
            Files.writeString(dossier.resolve("histogramme.txt"), texte, StandardCharsets.UTF_8);
        } catch (javax.management.JMException | IOException faute) {
            LOGGER.error("[mesure sol] histogramme non écrit : {}", faute.toString());
        }
    }

    private static void ecrire(MinecraftServer serveur, String ligne) {
        LOGGER.info("[mesure sol] releve {}", ligne);
        try {
            Path dossier = serveur.getServerDirectory().toPath().resolve("mesure-sol");
            Files.createDirectories(dossier);
            Files.writeString(dossier.resolve("rapport.jsonl"), ligne + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException faute) {
            LOGGER.error("[mesure sol] rapport non écrit : {}", faute.toString());
        }
    }
}
