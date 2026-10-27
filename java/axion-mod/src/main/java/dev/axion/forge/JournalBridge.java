package dev.axion.forge;

import com.mojang.logging.LogUtils;
import dev.axion.diag.JavaMetrics;
import dev.axion.persist.JournalBudget;
import dev.axion.persist.JournalRecord;
import dev.axion.persist.JournalStore;
import dev.axion.persist.JournalToken;
import dev.axion.persist.RestorePlanner;
import dev.axion.persist.SideJournal;
import dev.axion.persist.WorldId;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

/**
 * Le journal latéral branché sur le serveur (22.5.2, 22.5.3, ADR-153 D6).
 *
 * <p>À chaque sauvegarde d'une AxionEntity, une copie de ses clés {@code axion:*} part au journal ; à sa
 * suppression explicite — tuée, retirée pour de bon —, un {@code REMOVE} ; jamais au déchargement d'un
 * chunk. À chaque tick, la restauration examine les assemblies que le journal garde vivantes : celle dont
 * le chunk a ses entités chargées sans entité de cet UUID est recréée à sa position, endormie, et
 * consignée au rapport {@code restaurations.log}.
 *
 * <p>Absent en mode {@code DISABLED} et quand {@code persistence.journal_enabled} est faux : ni lu ni
 * écrit. Thread autoritatif, sauf le fil du journal.
 */
final class JournalBridge {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** En deçà, le journal ne se compacte pas : compacter un petit journal à chaque sauvegarde ne gagne rien. */
    static final long COMPACTION_FLOOR = 1L << 20;

    /** Assemblies examinées par tick pour la restauration. */
    static final int RESTORE_CHECKS_PER_TICK = 64;

    /** Le journal en service, ou {@code null}. */
    private static volatile JournalBridge active;

    private final MinecraftServer server;
    private final SideJournal journal;
    private final RestorePlanner planner = new RestorePlanner();
    private final Path dir;
    private final long budgetNs;
    private final JavaMetrics.Budget budget = JavaMetrics.GLOBAL.budget("persistence.journal_ns_per_tick", "ns");
    private final JavaMetrics.Metric restored = JavaMetrics.GLOBAL.counter("axion.persist.journal_restored", "count");
    private final JavaMetrics.Metric deferredGauge = JavaMetrics.GLOBAL.gauge("axion.persist.journal_deferred", "count");

    /**
     * Une sauvegarde à remettre au journal : les clés de l'entité, gardées par référence jusqu'à leur copie.
     * Elles ne changent qu'à la sauvegarde suivante, qui remplace une sauvegarde différée : la copie faite
     * plus tard est celle de l'état sauvegardé.
     */
    private record Save(UUID uuid, String dimension, double x, double y, double z, CompoundTag stored) {}

    /** Le temps pris au thread autoritatif, et les sauvegardes différées au-delà du budget. */
    private final JournalBudget<Save> spending;

    private boolean offered;
    private boolean overrunReported;

    private JournalBridge(MinecraftServer server, Path dir, long maxBytes, long budgetNs) {
        this.server = server;
        this.dir = dir;
        this.budgetNs = budgetNs;
        this.spending = new JournalBudget<>(budgetNs, System::nanoTime);
        this.journal = SideJournal.start(
                () -> JournalStore.open(dir, maxBytes, COMPACTION_FLOOR, message -> LOGGER.warn("AXION : {}", message)),
                message -> LOGGER.warn("AXION : {}", message));
    }

    /**
     * Met le journal du monde en service.
     *
     * @param gameDir dossier du jeu ; le journal vit sous {@code <gameDir>/axion/world/<worldId>}
     * @param maxBytes {@code persistence.journal_max_bytes}
     * @param budgetNs {@code persistence.journal_ns_per_tick}
     */
    static void start(MinecraftServer server, Path gameDir, long maxBytes, long budgetNs) {
        Path level = server.getWorldPath(LevelResource.ROOT);
        String canonical;
        try {
            canonical = level.toRealPath().toString();
        } catch (IOException e) {
            canonical = level.toAbsolutePath().normalize().toString();
        }
        String worldId = WorldId.of(canonical, server.getWorldData().worldGenOptions().seed());
        Path dir = gameDir.resolve("axion").resolve("world").resolve(worldId);
        if (!Files.isDirectory(dir)) {
            LOGGER.info("AXION : aucun journal latéral pour ce monde ({}) : rien à restaurer", worldId);
        }
        // Le journal d'un autre monde de même chemin et de même graine est mis de côté (décision de Killian).
        try {
            JournalToken.reconcile(dir, JournalWorldToken.of(server), System.currentTimeMillis(),
                    message -> LOGGER.warn("AXION : {}", message));
        } catch (IOException e) {
            LOGGER.warn("AXION : jeton du journal latéral illisible ou non écrit, le journal est lu tel quel : {}",
                    e.toString());
        }
        active = new JournalBridge(server, dir, maxBytes, budgetNs);
    }

    /** Remet au journal les sauvegardes différées, écrit ce qui attend et arrête le journal. */
    static void stop() {
        JournalBridge bridge = active;
        active = null;
        if (bridge == null) {
            return;
        }
        bridge.spending.drainAll().forEach(bridge::submit);
        if (!bridge.journal.close(30_000)) {
            LOGGER.warn("AXION : le journal latéral n'a pas fini d'écrire dans le délai ; "
                    + "ce qui n'est pas écrit le sera à la prochaine sauvegarde de chaque assembly");
        }
    }

    /** {@return le journal en service, ou {@code null}} */
    static JournalBridge active() {
        return active;
    }

    /**
     * Une AxionEntity se sauvegarde : une copie de ses clés part au journal. Au-delà du budget du tick
     * ({@code persistence.journal_ns_per_tick}), la copie est différée aux ticks suivants (R-1744).
     */
    static void saved(AxionEntity entity, CompoundTag stored) {
        JournalBridge bridge = active;
        if (bridge == null || entity.level().isClientSide()) {
            return;
        }
        Save save = new Save(entity.getUUID(), entity.level().dimension().location().toString(),
                entity.getX(), entity.getY(), entity.getZ(), stored);
        bridge.spending.save(save.uuid(), save, bridge::submit);
    }

    /** Copie les clés d'une sauvegarde et les remet au journal, qui les encode sur son fil. */
    private void submit(Save save) {
        CompoundTag copy = save.stored().copy();
        journal.put(save.uuid(), save.dimension(), save.x(), save.y(), save.z(),
                AssemblyTags.definition(copy).orElse(0L), () -> encode(copy));
    }

    /** Une AxionEntity est supprimée explicitement : elle ne sera pas restaurée. */
    static void removed(UUID uuid) {
        JournalBridge bridge = active;
        if (bridge != null) {
            bridge.spending.cancel(uuid, () -> bridge.journal.remove(uuid));
            bridge.planner.forget(uuid);
        }
    }

    /** Une AxionEntity entre dans le monde : son NBT fait foi, le journal ne la recrée pas. */
    static void seen(UUID uuid) {
        JournalBridge bridge = active;
        if (bridge != null) {
            bridge.planner.forget(uuid);
        }
    }

    /** {@return les clés {@code axion:*} en NBT binaire} */
    static byte[] encode(CompoundTag tag) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            NbtIo.write(tag, out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    /** Fin de tick : les sauvegardes différées dans ce qui reste du budget, une au moins ; puis la restauration. */
    void tick() {
        long spentNs = spending.endTick(this::submit);
        deferredGauge.set(spending.deferred());
        if (budget.report(spentNs, budgetNs) && !overrunReported) {
            overrunReported = true;
            LOGGER.warn("AXION : le journal latéral a pris {} ns au thread autoritatif en un tick, au-delà de "
                    + "persistence.journal_ns_per_tick ({} ns) ; les suivants sont comptés dans "
                    + "axion.budget.persistence.journal_ns_per_tick.overruns", spentNs, budgetNs);
        }
        if (!offered && journal.loaded().isDone()) {
            offered = true;
            List<JournalRecord.Put> live = journal.loaded().join();
            if (!live.isEmpty()) {
                LOGGER.info("AXION : journal latéral, {} assemblies connues ; celles dont l'entité a disparu seront restaurées",
                        live.size());
            }
            planner.offer(live);
        }
        for (RestorePlanner.Outcome outcome : planner.tick(world, RESTORE_CHECKS_PER_TICK)) {
            JournalRecord.Put put = outcome.put();
            if (outcome.restored()) {
                restored.increment();
                LOGGER.info("AXION : assembly {} restaurée depuis le journal latéral, {} ({}, {}, {})", put.uuid(),
                        put.dimension(), put.x(), put.y(), put.z());
                report(put);
            } else {
                LOGGER.warn("AXION : assembly {} du journal latéral non restaurée, {} ({}, {}, {}) : le monde l'a refusée",
                        put.uuid(), put.dimension(), put.x(), put.y(), put.z());
            }
        }
    }

    /** {@return la restauration, pour les tests} */
    RestorePlanner planner() {
        return planner;
    }

    /** {@return le dossier du journal de ce monde} */
    Path dir() {
        return dir;
    }

    private void report(JournalRecord.Put put) {
        String line = Instant.now() + " restaurée " + put.uuid() + " definition " + Long.toHexString(put.definition())
                + " " + put.dimension() + " " + put.x() + " " + put.y() + " " + put.z() + System.lineSeparator();
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("restaurations.log"), line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOGGER.warn("AXION : rapport de restauration non écrit : {}", e.toString());
        }
    }

    private ServerLevel level(String dimension) {
        ResourceLocation id = ResourceLocation.tryParse(dimension);
        return id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
    }

    private final RestorePlanner.World world = new RestorePlanner.World() {
        @Override
        public boolean exists(UUID uuid) {
            for (ServerLevel level : server.getAllLevels()) {
                if (level.getEntity(uuid) != null) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean entitiesLoaded(String dimension, double x, double z) {
            ServerLevel level = level(dimension);
            return level != null
                    && level.areEntitiesLoaded(ChunkPos.asLong((int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4));
        }

        @Override
        public boolean restore(JournalRecord.Put put) {
            AuthoritativeThread.require();
            ServerLevel level = level(put.dimension());
            if (level == null) {
                return false;
            }
            CompoundTag tag;
            try {
                tag = NbtIo.read(new DataInputStream(new ByteArrayInputStream(put.blob())));
            } catch (IOException e) {
                LOGGER.warn("AXION : blob illisible pour l'assembly {} du journal latéral : {}", put.uuid(), e.toString());
                return false;
            }
            tag.putString("id", EntityType.getKey(AxionEntities.ASSEMBLY.get()).toString());
            tag.putUUID("UUID", put.uuid());
            ListTag pos = new ListTag();
            pos.add(DoubleTag.valueOf(put.x()));
            pos.add(DoubleTag.valueOf(put.y()));
            pos.add(DoubleTag.valueOf(put.z()));
            tag.put("Pos", pos);
            Entity entity = EntityType.loadEntityRecursive(tag, level, loaded -> loaded);
            return entity != null && level.addFreshEntity(entity);
        }
    };
}
