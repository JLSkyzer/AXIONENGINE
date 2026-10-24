package dev.axion.forge;

import dev.axion.net.AssemblyState;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * ADR-153 : la persistance d'une assembly de bout en bout — ce que son entité écrit au schéma 3, la
 * migration d'un NBT au schéma 2 (T-434) et un schéma postérieur gardé intact (T-433). Le format lui-même
 * est prouvé par {@code PersistedStateTest}.
 */
@GameTestHolder("axion")
@PrefixGameTestTemplate(false)
public final class PersistanceGameTests {

    /** Le dessus de la dalle du gabarit {@code plancher}, en coordonnées du test. */
    private static final double DESSUS_DALLE = 2.0;

    private static final float[] DROIT = {0f, 0f, 0f, 1f};

    private PersistanceGameTests() {}

    /**
     * ADR-153 D2 : la porte simulée, son gond grippé d'un quart de bloc, écrit son état au schéma 3 — la
     * position du corps racine, le battant en position monde, le gond et le désalignement commandé.
     */
    @GameTest(template = "test/plancher", timeoutTicks = 200)
    public static void lEtatSimuleSEcritAuSchema3(GameTestHelper helper) {
        ObservateursDeTest.declarer(helper, new Vec3(4.0, DESSUS_DALLE, 4.0));
        AxionEntity porte = AssembliesDeTest.poser(
                helper, "axion:test/joints/porte", new Vec3(3.5, DESSUS_DALLE + 0.05, 4.0), DROIT);
        boolean[] grippee = {false};
        helper.onEachTick(() -> {
            AssemblyState etat = porte.netState();
            if (!grippee[0] && etat != null && etat.bodies().size() == 1) {
                GrippagesDeTest.gripper(helper, porte, 0, 0.25f);
                grippee[0] = true;
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(grippee[0] && porte.jointBits().count() == 1
                    && porte.jointBits().flags(0) == AssemblyState.JointBits.JAMMED, "le gond est grippé");
            CompoundTag sauvegarde = porte.saveWithoutId(new CompoundTag());
            helper.assertTrue(sauvegarde.getInt("axion:v") == 3, "schéma " + sauvegarde.getInt("axion:v"));
            PersistedState lu = PersistedState.read(AssemblyTags.extract(sauvegarde))
                    .orElseThrow(() -> new IllegalStateException("aucun état lisible dans " + sauvegarde));
            helper.assertTrue(lu.position()[0] == porte.getX() && lu.position()[1] == porte.getY()
                    && lu.position()[2] == porte.getZ(), "axion:pos est la position du corps racine");
            helper.assertTrue(lu.sleeping() == porte.netState().sleeping(), "le sommeil est celui du dernier état");
            helper.assertTrue(lu.bodies().size() == 1, "un corps secondaire : " + lu.bodies().size());
            AxionEntity.BodyPose battant = porte.secondaryBodies().get(0);
            PersistedState.Body ecrit = lu.bodies().get(0);
            helper.assertTrue(ecrit.node() == battant.node() && ecrit.x() == battant.x() && ecrit.y() == battant.y()
                    && ecrit.z() == battant.z(), "le battant en position monde, au bit près");
            helper.assertTrue(lu.joints().size() == 1, "un joint : " + lu.joints().size());
            helper.assertTrue(!lu.joints().get(0).broken() && lu.joints().get(0).misalignment() == 0.25f,
                    "le gond, intact, grippé d'un quart de bloc : " + lu.joints().get(0));
        });
    }

    /**
     * T-434 : une entité sauvegardée au schéma 2 se relit liée à sa definition, au schéma 3, sans état
     * inventé ; le schéma 2 ne portait ni vitesses ni corps secondaires.
     */
    @GameTest(template = "test/plancher")
    public static void t434_unNbtAuSchema2SeMigreEtSeLie(GameTestHelper helper) {
        AxionEntity cube = AssembliesDeTest.poser(helper, "axion:test_cube", new Vec3(2.0, DESSUS_DALLE + 2.0, 2.0), DROIT);
        CompoundTag v2 = new CompoundTag();
        helper.assertTrue(cube.save(v2), "sauvegarde refusée");
        // Ce que M2 et M3 écrivaient : version, definition et orientation.
        v2.putInt("axion:v", 2);
        for (String cle : new String[] {PersistedState.POSITION, PersistedState.LINEAR, PersistedState.ANGULAR,
                PersistedState.FLAGS, PersistedState.BODIES, PersistedState.JOINTS}) {
            v2.remove(cle);
        }
        Entity relue = EntityType.loadEntityRecursive(v2, helper.getLevel(), entite -> entite);
        helper.assertTrue(relue instanceof AxionEntity, "la sauvegarde ne rend pas une assembly : " + relue);
        AxionEntity migree = (AxionEntity) relue;
        helper.assertTrue(!migree.isInert(), "assembly migrée inerte");
        helper.assertTrue("axion:test_cube".equals(migree.definitionId()), "definition " + migree.definitionId());
        CompoundTag v3 = migree.saveWithoutId(new CompoundTag());
        helper.assertTrue(v3.getInt("axion:v") == 3, "schéma réécrit " + v3.getInt("axion:v"));
        helper.assertTrue(v3.getLong("axion:def") == v2.getLong("axion:def"), "definition gardée");
        helper.assertTrue(v3.get("axion:rot").equals(v2.get("axion:rot")), "orientation gardée");
        helper.assertTrue(!v3.contains(PersistedState.LINEAR) && !v3.contains(PersistedState.BODIES),
                "aucun état inventé : " + v3);
        helper.succeed();
    }

    /** T-433 : une entité d'un schéma postérieur reste inerte, et sa sauvegarde réécrit ses clés intactes. */
    @GameTest(template = "test/plancher")
    public static void t433_unSchemaFuturResteInerteEtIntact(GameTestHelper helper) {
        AxionEntity cube = AssembliesDeTest.poser(helper, "axion:test_cube", new Vec3(2.0, DESSUS_DALLE + 2.0, 2.0), DROIT);
        CompoundTag v4 = new CompoundTag();
        helper.assertTrue(cube.save(v4), "sauvegarde refusée");
        v4.putInt("axion:v", 4);
        v4.putString("axion:inconnue", "écrite par une version future");
        v4.putByteArray(PersistedState.BODIES, new byte[] {1, 2, 3});
        Entity relue = EntityType.loadEntityRecursive(v4, helper.getLevel(), entite -> entite);
        helper.assertTrue(relue instanceof AxionEntity, "la sauvegarde ne rend pas une assembly : " + relue);
        AxionEntity future = (AxionEntity) relue;
        helper.assertTrue(future.isInert(), "un schéma postérieur se lie");
        CompoundTag reecrite = future.saveWithoutId(new CompoundTag());
        helper.assertTrue(AssemblyTags.extract(reecrite).equals(AssemblyTags.extract(v4)),
                "clés réécrites : " + AssemblyTags.extract(reecrite) + " au lieu de " + AssemblyTags.extract(v4));
        helper.succeed();
    }
}
