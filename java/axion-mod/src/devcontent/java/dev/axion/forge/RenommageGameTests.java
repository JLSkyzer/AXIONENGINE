package dev.axion.forge;

import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** ADR-183 D5 (CAP-34) : {@code /axion rename} nomme une assembly comme toute entité, et le nom se garde. */
@GameTestHolder("axion")
@PrefixGameTestTemplate(false)
public final class RenommageGameTests {

    /** Le dessus de la dalle du gabarit {@code plancher}, en coordonnées du test. */
    private static final double DESSUS_DALLE = 2.0;

    private RenommageGameTests() {}

    /** Le nom est posé, affiché, relu de la sauvegarde ; une entité qui n'est pas une assembly est refusée. */
    @GameTest(template = "test/plancher", timeoutTicks = 100)
    public static void adr183_laCommandeRenommeUneAssemblyEtLeNomSeGarde(GameTestHelper helper) {
        ObservateursDeTest.declarer(helper, new Vec3(4.0, DESSUS_DALLE, 4.0));
        AxionEntity assembly = AssembliesDeTest.poser(helper, "axion:test/siege", new Vec3(4.5, DESSUS_DALLE, 4.5),
                new float[] {0f, 0f, 0f, 1f});
        Pig cochon = EntityType.PIG.create(helper.getLevel());
        MinecraftServer serveur = helper.getLevel().getServer();
        helper.startSequence().thenExecute(() -> {
            cochon.setNoAi(true);
            cochon.moveTo(assembly.getX() + 2.0, assembly.getY(), assembly.getZ(), 0f, 0f);
            helper.getLevel().addFreshEntity(cochon);
            List<String> reponse =
                    ParametresGameTests.commande(serveur, "axion rename " + assembly.getUUID() + " Charger rouge");
            helper.assertTrue(assembly.getCustomName() != null
                    && "Charger rouge".equals(assembly.getCustomName().getString()) && assembly.isCustomNameVisible(),
                    "nommée et affichée : " + assembly.getCustomName() + " ; " + reponse);
            helper.assertTrue(reponse.size() == 1 && reponse.get(0).contains("Charger rouge"), "réponse : " + reponse);
            CompoundTag sauvegarde = assembly.saveWithoutId(new CompoundTag());
            AxionEntity relue = AxionEntities.ASSEMBLY.get().create(helper.getLevel());
            relue.load(sauvegarde);
            helper.assertTrue(relue.getCustomName() != null
                    && "Charger rouge".equals(relue.getCustomName().getString()) && relue.isCustomNameVisible(),
                    "relue de la sauvegarde : " + relue.getCustomName());
            List<String> refus = ParametresGameTests.commande(serveur, "axion rename " + cochon.getUUID() + " Non");
            helper.assertTrue(refus.size() == 1 && refus.get(0).contains("n'est pas une assembly")
                    && cochon.getCustomName() == null, "un cochon est refusé : " + refus);
            cochon.discard();
            assembly.discard();
        }).thenSucceed();
    }
}
