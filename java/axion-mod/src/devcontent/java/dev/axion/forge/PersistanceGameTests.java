package dev.axion.forge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.axion.net.AssemblyState;
import java.util.Arrays;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.joml.Quaternionf;

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

    /** L'angle d'une rotation relative autour de y, en radians. */
    private static double autourDeY(float[] q) {
        return 2 * Math.atan2(q[1], q[3]);
    }

    /** Sauvegarde l'assembly, la retire, et ajoute au monde l'entité relue de sa sauvegarde. */
    private static AxionEntity recharger(GameTestHelper helper, AxionEntity assembly, CompoundTag sauvegarde) {
        helper.assertTrue(assembly.save(sauvegarde), "sauvegarde refusée");
        assembly.discard();
        Entity relue = EntityType.loadEntityRecursive(sauvegarde, helper.getLevel(), entite -> entite);
        helper.assertTrue(relue instanceof AxionEntity, "la sauvegarde ne rend pas une assembly : " + relue);
        helper.assertTrue(helper.getLevel().addFreshEntity(relue), "l'assembly rechargée n'entre pas dans le monde");
        return (AxionEntity) relue;
    }

    /**
     * {@return les corps qu'a réveillés la gestion d'activité depuis le démarrage ({@code axion.sim.woken})}
     * Une assembly rechargée naît endormie (22.4) : c'est cette gestion qui la réveille à portée d'un
     * observateur.
     */
    private static long reveils() {
        String json = RuntimeAccess.get().metricsJson();
        if (json == null) {
            return -1;
        }
        for (JsonElement metrique : JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("metrics")) {
            JsonObject objet = metrique.getAsJsonObject();
            if (objet.has("name") && "axion.sim.woken".equals(objet.get("name").getAsString())) {
                return objet.get("value").getAsLong();
            }
        }
        return -1;
    }

    /** {@return vrai si le corps a des vitesses nulles} */
    private static boolean immobile(AxionEntity.BodyPose corps) {
        for (float[] vitesse : new float[][] {corps.linear(), corps.angular()}) {
            for (float composante : vitesse) {
                if (composante != 0f) {
                    return false;
                }
            }
        }
        return true;
    }

        /** Le corps secondaire sauvegardé, ou une erreur du test. */
    private static PersistedState.Body sauve(CompoundTag sauvegarde) {
        PersistedState etat = PersistedState.read(AssemblyTags.extract(sauvegarde))
                .orElseThrow(() -> new IllegalStateException("aucun état lisible dans " + sauvegarde));
        return etat.bodies().get(0);
    }

    /**
     * T-430 : la porte ouverte par son servo et grippée, sauvegardée puis rechargée, reprend dès son premier
     * pas le battant où il était, son gond grippé — sans repasser par sa pose de repos.
     */
    @GameTest(template = "test/plancher", timeoutTicks = 300)
    public static void t430_laPorteRechargeeReprendSonBattantEtSonGrippage(GameTestHelper helper) {
        ObservateursDeTest.declarer(helper, new Vec3(4.0, DESSUS_DALLE, 4.0));
        AxionEntity porte = AssembliesDeTest.poser(
                helper, "axion:test/joints/porte", new Vec3(3.5, DESSUS_DALLE + 0.05, 4.0), DROIT);
        CompoundTag sauvegarde = new CompoundTag();
        AxionEntity[] rechargee = {null};
        Object[] premier = {null, null};
        long[] reveils = {-1, -1};
        int[] etape = {0};
        helper.onEachTick(() -> {
            AssemblyState etat = porte.netState();
            if (etape[0] == 0 && etat != null && etat.bodies().size() == 1
                    && Math.abs(autourDeY(etat.bodies().get(0).quaternion()) - 1.2) < 0.05) {
                GrippagesDeTest.gripper(helper, porte, 0, 0.25f);
                etape[0] = 1;
            } else if (etape[0] == 1 && porte.jointBits().count() == 1
                    && porte.jointBits().flags(0) == AssemblyState.JointBits.JAMMED) {
                reveils[0] = reveils();
                rechargee[0] = recharger(helper, porte, sauvegarde);
                etape[0] = 2;
            } else if (etape[0] == 2 && rechargee[0].netState() != null) {
                // Le premier état rapporté après le rechargement : plus tard, le servo l'aurait ramené.
                premier[0] = rechargee[0].secondaryBodies().get(0);
                premier[1] = rechargee[0].jointBits();
                reveils[1] = reveils();
                etape[0] = 3;
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(etape[0] == 3, "étape " + etape[0]);
            AxionEntity.BodyPose battant = (AxionEntity.BodyPose) premier[0];
            PersistedState.Body avant = sauve(sauvegarde);
            double ecart = Math.sqrt(Math.pow(battant.x() - avant.x(), 2) + Math.pow(battant.y() - avant.y(), 2)
                    + Math.pow(battant.z() - avant.z(), 2));
            helper.assertTrue(ecart < 0.02, "le battant repris où il était, à " + ecart + " bloc");
            AssemblyState.JointBits bits = (AssemblyState.JointBits) premier[1];
            helper.assertTrue(bits.count() == 1 && bits.flags(0) == AssemblyState.JointBits.JAMMED,
                    "le gond grippé dès le premier pas : " + bits);
            PersistedState.Joint gond = PersistedState.read(AssemblyTags.extract(
                    rechargee[0].saveWithoutId(new CompoundTag()))).orElseThrow().joints().get(0);
            helper.assertTrue(gond.misalignment() == 0.25f, "le désalignement gardé : " + gond);
            helper.assertTrue(reveils[0] >= 0 && reveils[1] > reveils[0],
                    "née endormie, réveillée par la gestion d'activité : " + reveils[0] + " → " + reveils[1]);
        });
    }

    /**
     * T-430 : la charnière fragile rompue, son second corps posé sur la dalle, sauvegardée puis rechargée,
     * reste rompue, et ce corps reste où il était posé — il ne revient pas pendre au gond.
     */
    @GameTest(template = "test/plancher", timeoutTicks = 300)
    public static void t430_laCharniereRompueRechargeeResteRompue(GameTestHelper helper) {
        ObservateursDeTest.declarer(helper, new Vec3(4.0, DESSUS_DALLE, 4.0));
        helper.setBlock(BlockPos.containing(4.0, DESSUS_DALLE, 4.0), Blocks.STONE);
        AxionEntity charniere = AssembliesDeTest.poser(
                helper, "axion:test/charniere_fragile", new Vec3(4.5, DESSUS_DALLE + 1.0, 4.5), DROIT);
        CompoundTag sauvegarde = new CompoundTag();
        AxionEntity[] rechargee = {null};
        Object[] premier = {null, null};
        int[] etape = {0};
        helper.onEachTick(() -> {
            AssemblyState etat = charniere.netState();
            // Rompue, l'assembly a deux îlots : la racine peut dormir quand b glisse encore. b se sauvegarde
            // immobile, ses vitesses nulles comme celles d'un corps endormi.
            if (etape[0] == 0 && etat != null && etat.sleeping() && charniere.jointBits().count() == 1
                    && charniere.jointBits().flags(0) == AssemblyState.JointBits.BROKEN
                    && immobile(charniere.secondaryBodies().get(0))) {
                rechargee[0] = recharger(helper, charniere, sauvegarde);
                etape[0] = 1;
            } else if (etape[0] == 1 && rechargee[0].netState() != null) {
                premier[0] = rechargee[0].secondaryBodies().get(0);
                premier[1] = rechargee[0].jointBits();
                etape[0] = 2;
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(etape[0] == 2, "étape " + etape[0]);
            AxionEntity.BodyPose b = (AxionEntity.BodyPose) premier[0];
            PersistedState.Body avant = sauve(sauvegarde);
            double ecart = Math.sqrt(Math.pow(b.x() - avant.x(), 2) + Math.pow(b.y() - avant.y(), 2)
                    + Math.pow(b.z() - avant.z(), 2));
            helper.assertTrue(ecart < 0.02, "b repris sur la dalle, à " + ecart + " bloc de sa pose sauvegardée");
            AssemblyState.JointBits bits = (AssemblyState.JointBits) premier[1];
            helper.assertTrue(bits.count() == 1 && bits.flags(0) == AssemblyState.JointBits.BROKEN,
                    "le joint rompu dès le premier pas : " + bits);
        });
    }

    private static AxionEntity.BodyPose pose(int node, float[] linear, float[] angular) {
        return new AxionEntity.BodyPose(node, 1.0, 2.0, 3.0, new Quaternionf(), linear, angular);
    }

    /**
     * ADR-153 D4 : un corps que la gestion d'activité endort ({@code SUSPENDED}) a des vitesses gelées que
     * son état rapporte nulles ; l'entité garde pour la sauvegarde celles d'avant, la racine comme un corps
     * secondaire. Endormi naturellement, un corps est immobile : ses vitesses sont nulles.
     */
    @GameTest(template = "test/plancher")
    public static void unCorpsSuspenduGardeSesVitessesPourLaSauvegarde(GameTestHelper helper) {
        AxionEntity assembly = AxionEntities.ASSEMBLY.get().create(helper.getLevel());
        helper.assertTrue(assembly != null, "entité créée");
        float[] lineaire = {1.5f, -2f, 0.25f};
        float[] angulaire = {0f, 0.5f, 0f};
        float[] nul = new float[3];
        assembly.updateNetState(lineaire, angulaire, false, false);
        assembly.setSecondaryBodies(List.of(pose(3, lineaire, angulaire)), List.of(false));

        assembly.updateNetState(nul, nul, true, true);
        assembly.setSecondaryBodies(List.of(pose(3, nul, nul)), List.of(true));
        PersistedState suspendue = assembly.persistedState();
        helper.assertTrue(Arrays.equals(suspendue.linear(), lineaire) && Arrays.equals(suspendue.angular(), angulaire),
                "la racine suspendue garde ses vitesses : " + Arrays.toString(suspendue.linear()));
        PersistedState.Body corps = suspendue.bodies().get(0);
        helper.assertTrue(Arrays.equals(corps.linear(), lineaire) && Arrays.equals(corps.angular(), angulaire),
                "le corps secondaire suspendu garde les siennes : " + Arrays.toString(corps.linear()));

        assembly.updateNetState(nul, nul, true, false);
        assembly.setSecondaryBodies(List.of(pose(3, nul, nul)), List.of(false));
        PersistedState endormie = assembly.persistedState();
        helper.assertTrue(Arrays.equals(endormie.linear(), nul) && Arrays.equals(endormie.bodies().get(0).linear(), nul),
                "endormie naturellement, immobile");
        helper.succeed();
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
