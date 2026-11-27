package dev.axion.forge;

import dev.axion.api.assembly.Assembly;
import dev.axion.api.attach.AttachResult;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * {@code /data merge entity} sur une assembly vivante : Minecraft la relit depuis son NBT. Ce qu'un administrateur change
 * s'applique, et rien d'autre de son état ne se perd.
 */
@GameTestHolder("axion")
@PrefixGameTestTemplate(false)
public final class CommandeDataGameTests {

    /** Le dessus de la dalle du gabarit, en coordonnées du test. */
    private static final double DESSUS_DALLE = 2.0;

    private static final float[] DROIT = {0f, 0f, 0f, 1f};

    private CommandeDataGameTests() {}

    /** Réserves, gel, attache, siège et place tiennent à travers un {@code /data merge} qui nomme l'assembly. */
    @GameTest(template = "test/plancher_16", timeoutTicks = 200)
    public static void dataMergeGardeLEtatDUneAssemblyVivante(GameTestHelper helper) {
        ObservateursDeTest.declarer(helper, new Vec3(8.0, DESSUS_DALLE, 8.0));
        AxionEntity vehicule = AssembliesDeTest.poser(helper, "axion:test/vehicule_reservoir",
                new Vec3(4.5, DESSUS_DALLE + 0.6, 4.5), DROIT);
        AxionEntity a = AssembliesDeTest.poser(helper, "axion:test/maillon",
                new Vec3(11.5, DESSUS_DALLE + 0.05, 4.0), DROIT);
        AxionEntity b = AssembliesDeTest.poser(helper, "axion:test/maillon",
                new Vec3(11.5, DESSUS_DALLE + 0.05, 6.0), DROIT);
        MinecraftServer serveur = helper.getLevel().getServer();
        Vec3[] place = new Vec3[3];
        double[] carburant = new double[1];
        net.minecraft.world.entity.decoration.ArmorStand mannequin =
                net.minecraft.world.entity.EntityType.ARMOR_STAND.create(helper.getLevel());
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(vehicule.hasPhysicalBounds() && a.hasPhysicalBounds()
                        && b.hasPhysicalBounds() && vehicule.seatingKnown(), "simulés"))
                .thenExecute(() -> {
                    Assembly vue = ForgeAxionApi.view(vehicule);
                    vue.energy().stores().get(0).set(7.5);
                    helper.getLevel().addFreshEntity(mannequin);
                    helper.assertTrue(vehicule.reseat(mannequin, "conducteur"), "un occupant");
                    AttachResult corde = ForgeAxionApi.view(a).attachments()
                            .attach("avant", ForgeAxionApi.view(b), "arriere", "rope");
                    helper.assertTrue(corde.status() == AttachResult.Status.ATTACHED, "corde : " + corde.message());
                    ForgeAxionApi.view(b).setFrozen(true);
                })
                .thenIdle(20)
                .thenExecute(() -> {
                    place[0] = vehicule.position();
                    place[1] = a.position();
                    place[2] = b.position();
                    carburant[0] = vehicule.storeAmount(0);
                    for (AxionEntity cible : new AxionEntity[] {vehicule, a, b}) {
                        java.util.List<String> reponse = ParametresGameTests.commande(serveur,
                                "data merge entity " + cible.getUUID() + " {CustomName:'\"nommee\"'}");
                        helper.assertTrue(reponse.size() >= 1 && !reponse.get(0).contains("Nothing"), "réponse : " + reponse);
                    }
                })
                .thenIdle(20)
                .thenExecute(() -> {
                    helper.assertTrue(vehicule.hasCustomName(), "le nom est posé");
                    for (int i = 0; i < 3; i++) {
                        AxionEntity cible = new AxionEntity[] {vehicule, a, b}[i];
                        helper.assertTrue(cible.position().distanceTo(place[i]) < 0.1,
                                "elle reste à sa place : " + cible.position() + " pour " + place[i]);
                    }
                    helper.assertTrue(Math.abs(vehicule.storeAmount(0) - carburant[0]) < 0.05,
                            "le carburant tient : " + vehicule.storeAmount(0) + " pour " + carburant[0]);
                    helper.assertTrue(mannequin.getVehicle() == vehicule && vehicule.seatOf(mannequin) >= 0,
                            "l'occupant reste assis");
                    helper.assertTrue(ForgeAxionApi.view(a).attachments().list().size() == 1, "la corde tient");
                    helper.assertTrue(ForgeAxionApi.view(b).isFrozen(), "le gel tient");
                    CompoundTag sauvegarde = new CompoundTag();
                    a.saveWithoutId(sauvegarde);
                    helper.assertTrue(sauvegarde.getList("axion:atts", net.minecraft.nbt.Tag.TAG_COMPOUND).size() == 1,
                            "la corde reste sauvegardée : " + sauvegarde.get("axion:atts"));
                    mannequin.discard();
                    vehicule.discard();
                    a.discard();
                    b.discard();
                })
                .thenSucceed();
    }
}
