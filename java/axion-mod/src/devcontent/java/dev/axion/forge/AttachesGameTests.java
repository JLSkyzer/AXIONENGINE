package dev.axion.forge;

import dev.axion.api.assembly.Assembly;
import dev.axion.api.attach.AttachResult;
import dev.axion.api.vehicle.DriverInput;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * ADR-171 : les attaches de bout en bout, sur le serveur et la vraie physique — l'API, le graphe, les commandes au
 * natif et ses rapports. Leurs règles fines sont prouvées au banc ({@code ax-physics}, {@code AttachmentGraphTest}).
 *
 * <p>Le maillon de test ({@code axion:test/maillon}) est un cube d'un bloc à deux sockets, {@code avant} en +x et
 * {@code arriere} en -x, à 0,6 bloc de son centre ; ses sockets acceptent {@code hitch}, {@code rope} et {@code rigid}.
 */
@GameTestHolder("axion")
@PrefixGameTestTemplate(false)
public final class AttachesGameTests {

    /** Le dessus de la dalle du gabarit, en coordonnées du test. */
    private static final double DESSUS_DALLE = 2.0;

    private static final float[] DROIT = {0f, 0f, 0f, 1f};

    private static final String MAILLON = "axion:test/maillon";

    private AttachesGameTests() {}

    private static Assembly vue(AxionEntity entite) {
        return ForgeAxionApi.view(entite);
    }

    private static void attendu(GameTestHelper helper, AttachResult resultat, AttachResult.Status statut) {
        helper.assertTrue(resultat.status() == statut, "attendu " + statut + ", reçu " + resultat.status() + " : "
                + resultat.message());
    }

    /**
     * T-890 : le buggy tire un maillon à la corde — l'attache tient, la corde se tend, et le maillon suit le buggy
     * qui avance.
     */
    @GameTest(template = "test/plancher_16", timeoutTicks = 300)
    public static void t890_LeMaillonSuitLeBuggyALaCorde(GameTestHelper helper) {
        ObservateursDeTest.declarer(helper, new Vec3(8.0, DESSUS_DALLE, 8.0));
        // Le buggy part vers -Z avec dix blocs de plancher devant lui, au tiers des gaz : allégé à sa masse de 340 kg
        // (ADR-151, correctif de D2), plein gaz, il sortait du plancher — dans un chunk que le serveur de GameTests ne
        // fait pas tourner — avant que la corde, qui s'étire, ait tiré le maillon de deux blocs.
        AxionEntity buggy = AssembliesDeTest.poser(helper, "axion:example/buggy", new Vec3(8.0, DESSUS_DALLE + 0.3,
                10.0), DROIT);
        AxionEntity maillon = AssembliesDeTest.poser(helper, MAILLON, new Vec3(8.0, DESSUS_DALLE + 0.05, 14.5), DROIT);
        double[] depart = new double[2];
        helper.runAfterDelay(20, () -> {
            attendu(helper, vue(buggy).attachments().attach("attelage", vue(maillon), "avant", "rope"),
                    AttachResult.Status.ATTACHED);
            depart[0] = maillon.position().z;
            depart[1] = buggy.position().z;
            vue(buggy).vehicle().orElseThrow().drive(new DriverInput(0.3, 0, 0, 0, 0, 1));
        });
        helper.runAfterDelay(25, () -> helper.succeedWhen(() -> {
            helper.assertTrue(vue(buggy).attachments().list().size() == 1, "la corde tient");
            double tire = depart[0] - maillon.position().z;
            helper.assertTrue(tire > 2.0, "le maillon suit le buggy vers l'avant : " + tire + " blocs ; le buggy a avancé"
                    + " de " + (depart[1] - buggy.position().z) + " ; la corde : " + vue(buggy).attachments().list()
                    + " ; maillon endormi " + maillon.netSleeping());
            vue(buggy).vehicle().orElseThrow().drive(DriverInput.NONE);
        }));
    }

    /** T-891 : une corde fragile, ancrée au-dessus du maillon qui pèse sur elle, rompt et l'attache disparaît. */
    @GameTest(template = "test/plancher_16", timeoutTicks = 200)
    public static void t891_UneCordeFragileRompt(GameTestHelper helper) {
        ObservateursDeTest.declarer(helper, new Vec3(8.0, DESSUS_DALLE, 8.0));
        AxionEntity maillon = AssembliesDeTest.poser(helper, "axion:test/maillon_fragile",
                new Vec3(8.0, DESSUS_DALLE + 0.05, 8.0), DROIT);
        BlockPos ancre = helper.absolutePos(new BlockPos(8, (int) DESSUS_DALLE + 4, 8));
        boolean[] posee = new boolean[1];
        helper.runAfterDelay(20, () -> {
            attendu(helper, vue(maillon).attachments().anchor("avant", ancre.getX(), ancre.getY(), ancre.getZ()),
                    AttachResult.Status.ATTACHED);
            posee[0] = true;
        });
        helper.runAfterDelay(22, () -> helper.succeedWhen(() -> {
            helper.assertTrue(posee[0], "ancrée");
            helper.assertTrue(vue(maillon).attachments().list().isEmpty(),
                    "la corde de 50 N a rompu sous un maillon de 20 kg : " + vue(maillon).attachments().list()
                            + " ; maillon à y " + maillon.position().y + ", endormi " + maillon.netSleeping());
        }));
    }

    /**
     * ADR-171 Q9 (CAP-37) : un ensemble à portée d'un joueur garde chargés les chunks de tous ses membres — une remorque
     * ne reste pas seule au bord d'un chunk.
     */
    @GameTest(template = "test/plancher_16", timeoutTicks = 200)
    public static void adr171_UnEnsembleTenuGardeLesChunksDeSesMembres(GameTestHelper helper) {
        ObservateursDeTest.declarer(helper, new Vec3(8.0, DESSUS_DALLE, 8.0));
        AxionEntity a = AssembliesDeTest.poser(helper, MAILLON, new Vec3(3.0, DESSUS_DALLE + 0.05, 8.0), DROIT);
        AxionEntity b = AssembliesDeTest.poser(helper, MAILLON, new Vec3(13.0, DESSUS_DALLE + 0.05, 8.0), DROIT);
        dev.axion.forge.net.JoueurSimule[] joueur = new dev.axion.forge.net.JoueurSimule[1];
        helper.runAfterDelay(20, () -> {
            attendu(helper, vue(a).attachments().attach("avant", vue(b), "arriere", "rope"),
                    AttachResult.Status.ATTACHED);
            joueur[0] = dev.axion.forge.net.JoueurSimule.rejoindre(helper.getLevel(), "ensemble",
                    helper.absoluteVec(new Vec3(3.0, DESSUS_DALLE, 6.0)));
        });
        helper.onEachTick(() -> {
            if (joueur[0] != null) {
                joueur[0].tick();
            }
        });
        helper.runAfterDelay(25, () -> helper.succeedWhen(() -> {
            List<net.minecraft.world.level.ChunkPos> tenus = AttachmentRuntime.current().heldChunks();
            helper.assertTrue(tenus.contains(a.chunkPosition()) && tenus.contains(b.chunkPosition()),
                    "les chunks des deux maillons sont tenus : " + tenus + " ; " + a.chunkPosition() + ", "
                            + b.chunkPosition());
            joueur[0].quitter();
        }));
    }

    /**
     * ADR-171, 8e : une assembly que Minecraft cesse de suivre sans la détruire — son chunk n'est plus au niveau où ses
     * entités tournent — perd son corps natif, et ses attaches avec lui. Elles sont suspendues, puis rétablies quand
     * elle est suivie de nouveau et que le natif l'a recréée ; avant, elles restaient crues vivantes sans aucun joint.
     */
    @GameTest(template = "test/plancher_16", timeoutTicks = 200)
    public static void adr171_UneAttacheSurvitALaFinDuSuiviDUneExtremite(GameTestHelper helper) {
        ObservateursDeTest.declarer(helper, new Vec3(8.0, DESSUS_DALLE, 8.0));
        AxionEntity a = AssembliesDeTest.poser(helper, MAILLON, new Vec3(6.0, DESSUS_DALLE + 0.05, 8.0), DROIT);
        AxionEntity b = AssembliesDeTest.poser(helper, MAILLON, new Vec3(7.2, DESSUS_DALLE + 0.05, 8.0), DROIT);
        helper.startSequence()
                .thenIdle(20)
                .thenExecute(() -> attendu(helper, vue(a).attachments().attach("avant", vue(b), "arriere", "rope"),
                        AttachResult.Status.ATTACHED))
                .thenIdle(5)
                // Ce que Minecraft fait quand le chunk de b cesse d'être suivi, b existant toujours.
                .thenExecute(() -> net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(
                        new net.minecraftforge.event.entity.EntityLeaveLevelEvent(b, helper.getLevel())))
                .thenExecute(() -> {
                    helper.assertTrue(!b.isRemoved(), "b existe toujours");
                    helper.assertTrue(AttachmentRuntime.current().suspended(a) == 1,
                            "son corps parti, l'attache est suspendue, pas crue vivante");
                })
                .thenExecute(() -> net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(
                        new net.minecraftforge.event.entity.EntityJoinLevelEvent(b, helper.getLevel())))
                .thenWaitUntil(() -> helper.assertTrue(vue(a).attachments().list().size() == 1
                                && AttachmentRuntime.current().suspended(a) == 0,
                        "suivie de nouveau et recréée, l'attache est rétablie : " + vue(a).attachments().list()
                                + " ; b simulée " + b.simulated() + ", a simulée " + a.simulated() + ", gardées par a "
                                + a.attachmentRecords() + ", suspendues " + AttachmentRuntime.current().suspended(a)))
                .thenSucceed();
    }

    /**
     * ADR-171, 8e : une attache posée dans le tick où ses deux extrémités apparaissent, avant que le natif ne les crée,
     * attend leurs corps au lieu d'être refusée puis oubliée.
     */
    @GameTest(template = "test/plancher_16", timeoutTicks = 100)
    public static void adr171_UneAttachePoseeALApparitionAttendSesCorps(GameTestHelper helper) {
        ObservateursDeTest.declarer(helper, new Vec3(8.0, DESSUS_DALLE, 8.0));
        AxionEntity a = AssembliesDeTest.poser(helper, MAILLON, new Vec3(6.0, DESSUS_DALLE + 0.05, 8.0), DROIT);
        AxionEntity b = AssembliesDeTest.poser(helper, MAILLON, new Vec3(7.2, DESSUS_DALLE + 0.05, 8.0), DROIT);
        // Au premier tick où leurs sockets sont connus, avant que le natif ne les ait créés : le cas à prouver.
        boolean[] posee = new boolean[1];
        boolean[] avantLeurCorps = new boolean[1];
        helper.onEachTick(() -> {
            if (!posee[0] && !a.sockets().sockets().isEmpty() && !b.sockets().sockets().isEmpty()) {
                avantLeurCorps[0] = !a.simulated() && !b.simulated();
                attendu(helper, vue(a).attachments().attach("avant", vue(b), "arriere", "rope"),
                        AttachResult.Status.ATTACHED);
                posee[0] = true;
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(posee[0], "posée");
            helper.assertTrue(avantLeurCorps[0], "posée avant que le natif ne crée les maillons, comme voulu");
            List<dev.axion.api.attach.Attachments.Attachment> liste = vue(a).attachments().list();
            helper.assertTrue(liste.size() == 1 && liste.get(0).length() > 0,
                    "l'attache tient, et le natif la rapporte : " + liste);
        });
    }

    /** T-892 : trois maillons en chaîne ne se referment pas en boucle — E-8030. */
    @GameTest(template = "test/plancher_16", timeoutTicks = 100)
    public static void t892_UnCycleEstRefuse(GameTestHelper helper) {
        ObservateursDeTest.declarer(helper, new Vec3(8.0, DESSUS_DALLE, 8.0));
        List<AxionEntity> maillons = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            maillons.add(AssembliesDeTest.poser(helper, MAILLON, new Vec3(5.0 + 1.2 * i, DESSUS_DALLE + 0.05, 8.0),
                    DROIT));
        }
        helper.runAfterDelay(20, () -> {
            attendu(helper, vue(maillons.get(0)).attachments().attach("avant", vue(maillons.get(1)), "arriere", "rope"),
                    AttachResult.Status.ATTACHED);
            attendu(helper, vue(maillons.get(1)).attachments().attach("avant", vue(maillons.get(2)), "arriere", "rope"),
                    AttachResult.Status.ATTACHED);
            attendu(helper, vue(maillons.get(2)).attachments().attach("avant", vue(maillons.get(0)), "arriere", "rope"),
                    AttachResult.Status.CYCLE);
            attendu(helper, vue(maillons.get(0)).attachments().attach("avant", vue(maillons.get(2)), "arriere", "rope"),
                    AttachResult.Status.SOCKET_BUSY);
            helper.succeed();
        });
    }

    /** Sauvegarde une assembly, la décharge comme un chunk le ferait, et rend sa sauvegarde. */
    private static net.minecraft.nbt.CompoundTag decharger(GameTestHelper helper, AxionEntity entite) {
        net.minecraft.nbt.CompoundTag sauvegarde = new net.minecraft.nbt.CompoundTag();
        helper.assertTrue(entite.save(sauvegarde), "sauvegarde refusée");
        entite.remove(net.minecraft.world.entity.Entity.RemovalReason.UNLOADED_TO_CHUNK);
        return sauvegarde;
    }

    /** Recharge une assembly depuis sa sauvegarde, comme son chunk revenu. */
    private static AxionEntity recharger(GameTestHelper helper, net.minecraft.nbt.CompoundTag sauvegarde) {
        net.minecraft.world.entity.Entity lue = net.minecraft.world.entity.EntityType.loadEntityRecursive(
                sauvegarde, helper.getLevel(), e -> e);
        helper.assertTrue(lue instanceof AxionEntity, "la sauvegarde ne rend pas une assembly");
        helper.assertTrue(helper.getLevel().addFreshEntity(lue), "l'assembly relue n'entre pas");
        return (AxionEntity) lue;
    }

    /**
     * T-890b, T-894 (R-692) : l'autre extrémité déchargée, l'attache est suspendue, pas supprimée, et revient avec
     * elle ; l'assembly qui l'a posée, déchargée puis rechargée, la rétablit depuis sa sauvegarde ({@code axion:atts}).
     */
    @GameTest(template = "test/plancher_16", timeoutTicks = 300)
    public static void t894_UneAttacheSeGardeEtSeRetablit(GameTestHelper helper) {
        ObservateursDeTest.declarer(helper, new Vec3(8.0, DESSUS_DALLE, 8.0));
        AxionEntity a = AssembliesDeTest.poser(helper, MAILLON, new Vec3(6.0, DESSUS_DALLE + 0.05, 8.0), DROIT);
        AxionEntity[] b = {AssembliesDeTest.poser(helper, MAILLON, new Vec3(7.2, DESSUS_DALLE + 0.05, 8.0), DROIT)};
        AxionEntity[] relue = new AxionEntity[1];
        net.minecraft.nbt.CompoundTag[] sauvegarde = new net.minecraft.nbt.CompoundTag[1];
        helper.startSequence()
                .thenIdle(20)
                .thenExecute(() -> attendu(helper, vue(a).attachments().attach("avant", vue(b[0]), "arriere", "rope"),
                        AttachResult.Status.ATTACHED))
                .thenIdle(5)
                .thenExecute(() -> sauvegarde[0] = decharger(helper, b[0]))
                .thenWaitUntil(() -> {
                    helper.assertTrue(vue(a).attachments().list().isEmpty(), "l'autre partie, l'attache se défait");
                    helper.assertTrue(AttachmentRuntime.current().suspended(a) == 1, "suspendue, pas supprimée");
                })
                .thenExecute(() -> b[0] = recharger(helper, sauvegarde[0]))
                .thenWaitUntil(() -> helper.assertTrue(vue(a).attachments().list().size() == 1,
                        "T-890b : l'autre revenue, l'attache est rétablie"))
                .thenExecute(() -> {
                    net.minecraft.nbt.CompoundTag gardee = decharger(helper, a);
                    helper.assertTrue(gardee.getList(AssemblyTags.ATTACHMENTS, 10).size() == 1,
                            "axion:atts : " + gardee.get(AssemblyTags.ATTACHMENTS));
                    relue[0] = recharger(helper, gardee);
                })
                .thenWaitUntil(() -> {
                    List<dev.axion.api.attach.Attachments.Attachment> liste = vue(relue[0]).attachments().list();
                    helper.assertTrue(liste.size() == 1, "T-894 : relue, l'assembly rétablit son attache");
                    helper.assertTrue("rope".equals(liste.get(0).kind()) && liste.get(0).other().isPresent()
                            && liste.get(0).other().get().equals(b[0].getUUID()), "la même : " + liste.get(0));
                })
                .thenSucceed();
    }

    /**
     * T-893 : neuf maillons attachés en rotule de proche en proche — huit attaches, la chaîne la plus longue permise ;
     * une neuvième est refusée, et les huit tiennent leurs sockets ensemble.
     */
    @GameTest(template = "test/plancher_16", timeoutTicks = 200)
    public static void t893_UneChaineDeHuitTient(GameTestHelper helper) {
        ObservateursDeTest.declarer(helper, new Vec3(8.0, DESSUS_DALLE, 8.0));
        List<AxionEntity> maillons = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            maillons.add(AssembliesDeTest.poser(helper, MAILLON, new Vec3(2.0 + 1.2 * i, DESSUS_DALLE + 0.05, 8.0),
                    DROIT));
        }
        helper.runAfterDelay(20, () -> {
            for (int i = 0; i < 8; i++) {
                attendu(helper, vue(maillons.get(i)).attachments().attach("avant", vue(maillons.get(i + 1)), "arriere",
                        "hitch"), AttachResult.Status.ATTACHED);
            }
            attendu(helper, vue(maillons.get(8)).attachments().attach("avant", vue(maillons.get(9)), "arriere",
                    "hitch"), AttachResult.Status.CHAIN);
        });
        helper.runAfterDelay(60, () -> helper.succeedWhen(() -> {
            for (int i = 0; i < 8; i++) {
                List<dev.axion.api.attach.Attachments.Attachment> liste = vue(maillons.get(i)).attachments().list();
                helper.assertTrue(!liste.isEmpty(), "le maillon " + i + " est attaché");
                for (dev.axion.api.attach.Attachments.Attachment attache : liste) {
                    helper.assertTrue(attache.distance() < 0.1, "maillon " + i + ", écart " + attache.distance());
                }
            }
        }));
    }
}
