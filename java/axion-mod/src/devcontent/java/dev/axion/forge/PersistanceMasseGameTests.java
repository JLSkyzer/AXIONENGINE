package dev.axion.forge;

import com.mojang.logging.LogUtils;
import dev.axion.diag.JavaMetrics;
import dev.axion.persist.JournalRecord;
import dev.axion.persist.JournalStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.slf4j.Logger;

/**
 * T-436 : la sauvegarde de 10 000 assemblies, mesurée et affichée, sans seuil inventé ; aucun tick du
 * serveur n'attend le journal latéral. Contenu de développement, jamais empaqueté (R-1790).
 *
 * <p>Les 10 000 entités sont lues d'un même NBT et ne sont pas ajoutées au monde :
 * {@code limits.max_assemblies_per_dimension} en admet 4 096, et 10 000 corps simulés mesureraient la
 * physique, pas la sauvegarde. Ce qui est mesuré est ce qu'AXION fait à la sauvegarde d'une entité —
 * l'écriture de son NBT, et sa remise au journal sous budget — ; l'écriture des fichiers de région par
 * Minecraft n'en fait pas partie.
 */
@GameTestHolder("axion")
@PrefixGameTestTemplate(false)
public final class PersistanceMasseGameTests {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int ASSEMBLIES = 10_000;

    private static final double DESSUS_DALLE = 2.0;

    private static final float[] DROIT = {0f, 0f, 0f, 1f};

    private PersistanceMasseGameTests() {}

    private static long metrique(String nom, boolean compteur, String unite) {
        return compteur ? JavaMetrics.GLOBAL.counter(nom, unite).value() : JavaMetrics.GLOBAL.gauge(nom, unite).value();
    }

    /** {@return les UUID que le journal sur le disque garde vivants, lus sur une copie de son dossier} */
    private static Set<UUID> journalises(JournalBridge bridge) {
        try {
            Path copie = Files.createTempDirectory("axion-t436");
            try (Stream<Path> fichiers = Files.list(bridge.dir())) {
                for (Path fichier : fichiers.toList()) {
                    Files.copy(fichier, copie.resolve(fichier.getFileName()));
                }
            }
            Set<UUID> uuids = new HashSet<>();
            try (JournalStore store = JournalStore.open(copie, Long.MAX_VALUE, Long.MAX_VALUE, message -> {})) {
                for (JournalRecord.Put put : store.live()) {
                    uuids.add(put.uuid());
                }
            }
            return uuids;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * T-436 : 10 000 assemblies sauvegardées dans un même tick, comme à la sauvegarde d'un gros monde. Le
     * temps de sauvegarde est mesuré et journalisé ; le journal ne prend au thread autoritatif, tick après
     * tick, que ce que son budget laisse, et les 10 000 finissent sur le disque.
     */
    // Un lot à lui : sa mesure ne doit pas compter le travail des autres tests, ni les ralentir.
    @GameTest(template = "test/plancher", batch = "t436_sauvegarde_en_masse", timeoutTicks = 2400)
    public static void t436_dixMilleAssembliesSeSauvegardentSansFaireAttendreLeServeur(GameTestHelper helper) {
        JournalBridge bridge = JournalBridge.active();
        helper.assertTrue(bridge != null, "journal latéral hors service");
        AxionEntity modele = AssembliesDeTest.poser(helper, "axion:test_cube", new Vec3(2.0, DESSUS_DALLE + 0.5, 2.0), DROIT);
        CompoundTag gabarit = new CompoundTag();
        helper.assertTrue(modele.save(gabarit), "sauvegarde du modèle refusée");
        modele.discard();

        List<AxionEntity> assemblies = new ArrayList<>(ASSEMBLIES);
        Set<UUID> attendues = new HashSet<>();
        for (int index = 0; index < ASSEMBLIES; index++) {
            CompoundTag nbt = gabarit.copy();
            UUID uuid = UUID.randomUUID();
            nbt.putUUID("UUID", uuid);
            AxionEntity assembly = (AxionEntity) EntityType.loadEntityRecursive(nbt, helper.getLevel(), e -> e);
            helper.assertTrue(assembly != null && !assembly.isInert(), "assembly " + index + " non lue ou inerte");
            assemblies.add(assembly);
            attendues.add(uuid);
        }

        long depassementsAvant = metrique("axion.budget.persistence.journal_ns_per_tick.overruns", true, "count");
        long debut = System.nanoTime();
        for (AxionEntity assembly : assemblies) {
            assembly.save(new CompoundTag());
        }
        long sauvegardeNs = System.nanoTime() - debut;
        int differees = bridge.deferred();
        int enFile = bridge.queued() - differees;

        long tickDeSauvegarde = helper.getTick();
        long[] maxParTick = {0};
        int[] ticksDEcoulement = {0};
        long[] finEcoulement = {-1};
        helper.onEachTick(() -> {
            if (helper.getTick() <= tickDeSauvegarde + 1) {
                return; // la consommation du tick de la sauvegarde est publiée à sa fin
            }
            if (finEcoulement[0] < 0) {
                ticksDEcoulement[0]++;
                maxParTick[0] = Math.max(maxParTick[0],
                        metrique("axion.budget.persistence.journal_ns_per_tick.consumed", false, "ns"));
                if (bridge.queued() == 0) {
                    finEcoulement[0] = helper.getTick();
                }
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(finEcoulement[0] >= 0, "le journal écoule encore " + bridge.queued() + " sauvegardes");
            Set<UUID> ecrites = journalises(bridge);
            int manquantes = 0;
            for (UUID uuid : attendues) {
                if (!ecrites.contains(uuid)) {
                    manquantes++;
                }
            }
            helper.assertTrue(manquantes == 0, manquantes + " assemblies pas encore au journal sur le disque");
            long depassements = metrique("axion.budget.persistence.journal_ns_per_tick.overruns", true, "count")
                    - depassementsAvant;
            LOGGER.info(String.format(Locale.ROOT,
                    "AXION T-436 : %d assemblies sauvegardées en %.2f ms (%.2f µs chacune, NBT et remise au journal) ; "
                            + "%d copies différées par le budget au tick de la sauvegarde, %d remises au fil du journal ; tout écrit en %d ticks ; "
                            + "journal au plus %.1f µs par tick pendant l'écoulement (budget %d µs) ; "
                            + "%d dépassement(s) du budget, tick de la sauvegarde compris",
                    ASSEMBLIES, sauvegardeNs / 1e6, sauvegardeNs / 1e3 / ASSEMBLIES, differees, enFile, ticksDEcoulement[0],
                    maxParTick[0] / 1e3,
                    RuntimeAccess.get().outcome().config().getInt("persistence.journal_ns_per_tick") / 1000,
                    depassements));
        });
    }
}
