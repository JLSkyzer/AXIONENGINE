package dev.axion.forge;

import dev.axion.asset.AssetSource;
import dev.axion.diag.JavaMetrics;
import dev.axion.lifecycle.AxionRuntime;
import dev.axion.persist.JournalRecord;
import dev.axion.persist.JournalStore;
import dev.axion.persist.JournalToken;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * 22.5 : le journal latéral de bout en bout — une assembly perdue sans suppression explicite restaurée
 * depuis le journal (cas 3), sans doublon (T-713d) — et la definition retirée puis rendue (T-713e). Le
 * format, la lecture tolérante et un journal illisible (T-713c) sont prouvés par {@code JournalStoreTest}
 * et {@code SideJournalTest}. Contenu de développement, jamais empaqueté (R-1790).
 */
@GameTestHolder("axion")
@PrefixGameTestTemplate(false)
public final class JournalGameTests {

    /** Le dessus de la dalle du gabarit {@code plancher}, en coordonnées du test. */
    private static final double DESSUS_DALLE = 2.0;

    private static final float[] DROIT = {0f, 0f, 0f, 1f};

    /** Une definition que seul le rechargement de T-713e apporte. */
    private static final String RETIREE = "axion:test/retiree_puis_rendue";

    private static final String CHEMIN = "axion:axion/definitions/test/retiree_puis_rendue.json";

    /** Le cube de test, sous un autre nom. */
    private static final byte[] DEFINITION =
            "{\"schema\": 1, \"asset\": \"axion:models/test/cube.gltf\", \"kind\": \"rigid_object\"}"
                    .getBytes(StandardCharsets.UTF_8);

    private JournalGameTests() {}

    private static long restaurations() {
        return JavaMetrics.GLOBAL.counter("axion.persist.journal_restored", "count").value();
    }

    private static JournalBridge journal(GameTestHelper helper) {
        JournalBridge bridge = JournalBridge.active();
        helper.assertTrue(bridge != null, "journal latéral hors service");
        return bridge;
    }

    /** {@return ce que le journal garde d'une assembly : ses clés axion:* à sa sauvegarde} */
    private static JournalRecord.Put entree(GameTestHelper helper, AxionEntity assembly) {
        CompoundTag sauvegarde = new CompoundTag();
        helper.assertTrue(assembly.save(sauvegarde), "sauvegarde refusée");
        return new JournalRecord.Put(assembly.getUUID(), assembly.level().dimension().location().toString(),
                assembly.getX(), assembly.getY(), assembly.getZ(), sauvegarde.getLong("axion:def"),
                JournalBridge.encode(AssemblyTags.extract(sauvegarde)));
    }

    /**
     * Cas 3 de 22.5.3 : une assembly que vanilla perd — sortie du monde sans suppression explicite, son
     * chunk toujours chargé — est recréée depuis le journal, à sa position, avec son UUID, liée à sa
     * definition ; et une seule fois (R-1748), même offerte de nouveau.
     */
    @GameTest(template = "test/plancher", timeoutTicks = 100)
    public static void uneAssemblyPerdueEstRestaureeDepuisLeJournal(GameTestHelper helper) {
        JournalBridge bridge = journal(helper);
        AxionEntity cube = AssembliesDeTest.poser(helper, "axion:test_cube", new Vec3(2.0, DESSUS_DALLE + 0.5, 2.0), DROIT);
        JournalRecord.Put entree = entree(helper, cube);
        UUID uuid = cube.getUUID();
        long avant = restaurations();
        // Ce que fait vanilla d'une entité qu'il ne connaît pas : elle sort du monde, sans être tuée.
        cube.setRemoved(Entity.RemovalReason.UNLOADED_TO_CHUNK);
        helper.assertTrue(helper.getLevel().getEntity(uuid) == null, "l'entité est encore dans le monde");
        bridge.planner().offer(List.of(entree));
        Entity[] restauree = {null};
        helper.onEachTick(() -> {
            if (restauree[0] == null && helper.getLevel().getEntity(uuid) != null) {
                restauree[0] = helper.getLevel().getEntity(uuid);
                // Offerte de nouveau : l'entité existe, rien n'est recréé.
                bridge.planner().offer(List.of(entree));
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(restauree[0] instanceof AxionEntity, "rien n'a été restauré");
            AxionEntity assembly = (AxionEntity) restauree[0];
            helper.assertTrue(assembly != cube && !assembly.isInert() && "axion:test_cube".equals(assembly.definitionId()),
                    "restaurée inerte ou d'une autre definition : " + assembly.definitionId());
            Vec3 enregistree = new Vec3(entree.x(), entree.y(), entree.z());
            helper.assertTrue(assembly.position().distanceTo(enregistree) < 1e-6,
                    "restaurée à " + assembly.position() + " au lieu de " + enregistree);
            helper.assertTrue(bridge.planner().pending() == 0, "encore en attente : " + bridge.planner().pending());
            helper.assertTrue(restaurations() == avant + 1, (restaurations() - avant) + " restaurations au lieu d'une");
            helper.assertTrue(helper.getLevel().getEntity(uuid) == assembly, "une autre entité porte l'UUID");
        });
    }

    /** T-713d : une assembly dont l'entité a survécu n'est jamais recréée depuis le journal (R-1748). */
    @GameTest(template = "test/plancher", timeoutTicks = 100)
    public static void t713d_uneAssemblyPresenteNEstPasRecreee(GameTestHelper helper) {
        JournalBridge bridge = journal(helper);
        AxionEntity cube = AssembliesDeTest.poser(helper, "axion:test_cube", new Vec3(2.0, DESSUS_DALLE + 0.5, 2.0), DROIT);
        // Jugé par l'UUID : le compteur des restaurations est commun aux tests du lot.
        bridge.planner().offer(List.of(entree(helper, cube)));
        helper.runAfterDelay(20, () -> {
            helper.assertTrue(helper.getLevel().getEntity(cube.getUUID()) == cube, "l'entité a été remplacée");
            int memeUuid = helper.getLevel()
                    .getEntities(AxionEntities.ASSEMBLY.get(), e -> e.getUUID().equals(cube.getUUID()))
                    .size();
            helper.assertTrue(memeUuid == 1, memeUuid + " entités de cet UUID");
            helper.succeed();
        });
    }

    /**
     * R-1745 : une assembly vue dans le monde pendant la session est oubliée par la restauration — déplacée
     * puis déchargée, elle ne serait sinon recréée en double à sa position d'avant.
     */
    @GameTest(template = "test/plancher", timeoutTicks = 100)
    public static void uneAssemblyVueDansLeMondeNEstPlusRestauree(GameTestHelper helper) {
        JournalBridge bridge = journal(helper);
        AxionEntity cube = AssembliesDeTest.poser(helper, "axion:test_cube", new Vec3(2.0, DESSUS_DALLE + 0.5, 2.0), DROIT);
        JournalRecord.Put entree = entree(helper, cube);
        CompoundTag sauvegarde = new CompoundTag();
        helper.assertTrue(cube.save(sauvegarde), "sauvegarde refusée");
        cube.setRemoved(Entity.RemovalReason.UNLOADED_TO_CHUNK);
        bridge.planner().offer(List.of(entree));
        // Elle revient dans le monde, puis en sort de nouveau sans être tuée, avant tout examen du journal.
        Entity revenue = EntityType.loadEntityRecursive(sauvegarde, helper.getLevel(), e -> e);
        helper.assertTrue(helper.getLevel().addFreshEntity(revenue), "l'assembly ne revient pas");
        revenue.setRemoved(Entity.RemovalReason.UNLOADED_TO_CHUNK);
        helper.runAfterDelay(20, () -> {
            helper.assertTrue(helper.getLevel().getEntity(entree.uuid()) == null, "restaurée alors qu'elle a été vue");
            helper.succeed();
        });
    }

    /** ADR-153 D6 : une assembly supprimée explicitement — tuée, retirée pour de bon — n'est jamais restaurée. */
    @GameTest(template = "test/plancher", timeoutTicks = 100)
    public static void uneAssemblySupprimeeNEstPasRestauree(GameTestHelper helper) {
        JournalBridge bridge = journal(helper);
        AxionEntity cube = AssembliesDeTest.poser(helper, "axion:test_cube", new Vec3(2.0, DESSUS_DALLE + 0.5, 2.0), DROIT);
        JournalRecord.Put entree = entree(helper, cube);
        bridge.planner().offer(List.of(entree));
        cube.discard();
        helper.runAfterDelay(20, () -> {
            helper.assertTrue(helper.getLevel().getEntity(entree.uuid()) == null, "restaurée après sa suppression");
            helper.succeed();
        });
    }

    /** R-1743 : la sauvegarde d'une assembly arrive au journal sur le disque, ses clés axion:* intactes. */
    @GameTest(template = "test/plancher", timeoutTicks = 200)
    public static void uneSauvegardeArriveAuJournal(GameTestHelper helper) {
        JournalBridge bridge = journal(helper);
        AxionEntity cube = AssembliesDeTest.poser(helper, "axion:test_cube", new Vec3(2.0, DESSUS_DALLE + 0.5, 2.0), DROIT);
        JournalRecord.Put attendue = entree(helper, cube);
        helper.succeedWhen(() -> {
            JournalRecord.Put lue;
            // Lu sur une copie : le fil du journal écrit dans le dossier pendant ce temps.
            try {
                Path copie = Files.createTempDirectory("axion-journal");
                if (Files.isDirectory(bridge.dir())) {
                    try (Stream<Path> segments = Files.list(bridge.dir())) {
                        for (Path segment : segments.toList()) {
                            Files.copy(segment, copie.resolve(segment.getFileName()));
                        }
                    }
                }
                try (JournalStore store = JournalStore.open(copie, Long.MAX_VALUE, Long.MAX_VALUE, message -> {})) {
                    lue = store.live().stream().filter(p -> p.uuid().equals(attendue.uuid())).findFirst().orElse(null);
                }
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            helper.assertTrue(lue != null, "pas encore au journal");
            helper.assertTrue(java.util.Arrays.equals(lue.blob(), attendue.blob()), "blob journalisé différent");
            helper.assertTrue(lue.definition() == attendue.definition() && lue.dimension().equals(attendue.dimension())
                    && lue.x() == attendue.x() && lue.y() == attendue.y() && lue.z() == attendue.z(),
                    "définition, dimension ou position journalisées différentes");
        });
    }

    /** ADR-153 D6 : le journal porte le jeton du monde, que le monde garde dans data/axion_registry.dat (R-1711). */
    @GameTest(template = "test/plancher")
    public static void leJournalPorteLeJetonDuMonde(GameTestHelper helper) throws IOException {
        JournalBridge bridge = journal(helper);
        String monde = AxionRegistryData.journalToken(helper.getLevel().getServer());
        String journal = Files.readString(bridge.dir().resolve(JournalToken.FILE), StandardCharsets.UTF_8);
        helper.assertTrue(!monde.isEmpty() && monde.equals(journal), "jeton du monde " + monde + ", du journal " + journal);
        // R-1711 : un seul SavedData d'AXION, axion_registry ; le nom vient du cahier des charges, pas du code.
        helper.getLevel().getServer().overworld().getDataStorage().save();
        Path data = helper.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("data");
        helper.assertTrue(Files.exists(data.resolve("axion_registry.dat")), "data/axion_registry.dat absent");
        try (Stream<Path> fichiers = Files.list(data)) {
            List<String> axion = fichiers.map(f -> f.getFileName().toString())
                    .filter(nom -> nom.startsWith("axion")).toList();
            helper.assertTrue(axion.equals(List.of("axion_registry.dat")), "SavedData d'AXION : " + axion);
        }
        helper.succeed();
    }

    /**
     * T-713e : une definition retirée rend l'assembly inerte, son NBT réécrit à l'identique ; rendue, elle
     * relie l'assembly sans perte (cas 2 de 22.5.3, R-1742).
     */
    // Un lot à lui : il recharge le registre des definitions, que d'autres tests lisent au même tick.
    @GameTest(template = "test/plancher", batch = "definition_retiree_puis_rendue")
    public static void t713e_uneDefinitionRetireePuisRendueNePerdRien(GameTestHelper helper) {
        AxionRuntime runtime = RuntimeAccess.get();
        helper.assertTrue(runtime != null && runtime.outcome() != null, "AXION n'est pas chargé");
        ResourceAssetSource courantes = new ResourceAssetSource(
                helper.getLevel().getServer().getResourceManager(),
                ResourceAssetSource.DEFINITIONS,
                path -> path.endsWith(".json"));
        AssetSource avecElle = new AssetSource() {
            @Override
            public List<String> list() {
                List<String> paths = new ArrayList<>(courantes.list());
                paths.add(CHEMIN);
                return paths;
            }

            @Override
            public byte[] read(String path) throws IOException {
                return CHEMIN.equals(path) ? DEFINITION.clone() : courantes.read(path);
            }
        };
        try {
            runtime.onDefinitionReload(avecElle);
            AxionEntity liee = AssembliesDeTest.poser(helper, RETIREE, new Vec3(2.0, DESSUS_DALLE + 0.5, 2.0), DROIT);
            CompoundTag avant = new CompoundTag();
            helper.assertTrue(liee.save(avant), "sauvegarde refusée");

            runtime.onDefinitionReload(courantes);
            AxionEntity inerte = (AxionEntity) EntityType.loadEntityRecursive(avant, helper.getLevel(), e -> e);
            helper.assertTrue(inerte.isInert(), "la definition retirée, l'assembly reste liée");
            CompoundTag pendant = new CompoundTag();
            helper.assertTrue(inerte.save(pendant), "sauvegarde de l'inerte refusée");
            helper.assertTrue(AssemblyTags.extract(pendant).equals(AssemblyTags.extract(avant)),
                    "NBT de l'inerte réécrit autrement : " + AssemblyTags.extract(pendant) + " au lieu de "
                            + AssemblyTags.extract(avant));

            runtime.onDefinitionReload(avecElle);
            AxionEntity rendue = (AxionEntity) EntityType.loadEntityRecursive(pendant, helper.getLevel(), e -> e);
            helper.assertTrue(!rendue.isInert() && RETIREE.equals(rendue.definitionId()),
                    "la definition rendue, l'assembly reste inerte : " + rendue.definitionId());
            liee.discard();
        } finally {
            runtime.onDefinitionReload(courantes);
        }
        helper.succeed();
    }
}
