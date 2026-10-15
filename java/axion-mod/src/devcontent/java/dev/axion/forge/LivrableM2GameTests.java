package dev.axion.forge;

import dev.axion.api.ImpactFlag;
import dev.axion.api.ImpactSource;
import dev.axion.api.ImpactSpec;
import dev.axion.damage.BoundedImpactSpecs;
import java.util.List;
import java.util.Locale;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.joml.Quaternionf;

/**
 * Le livrable de M2 joué en jeu (PARTIE 37) : « /axion spawn crée une AxionEntity persistante ».
 *
 * <p>La commande elle-même, pas sa copie d'{@link AssembliesDeTest} : l'apparition, la sauvegarde
 * que Minecraft écrit dans {@code entities/*.mca}, le rechargement d'une entité neuve depuis cette
 * sauvegarde — liée, à la même position et la même orientation, son corps recréé —, et
 * {@code /axion remove} par un sélecteur.
 */
@GameTestHolder("axion")
@PrefixGameTestTemplate(false)
public final class LivrableM2GameTests {

    /** Hauteur du dessus de la dalle du gabarit {@code test/plancher}, en blocs. */
    private static final double DESSUS_DALLE = 2.0;

    private LivrableM2GameTests() {}

    /** {@return un compteur de l'entité (R-500), dans le registre que l'export publie} */
    private static long compteur(String nom) {
        return dev.axion.diag.JavaMetrics.GLOBAL.counter(dev.axion.diag.EntityMetrics.PREFIX + nom, "count").value();
    }

    @GameTest(template = "test/plancher", timeoutTicks = 200)
    public static void uneAssemblyApparaitSeSauvegardeSeRechargeEtSeRetireParSesCommandes(
            GameTestHelper helper) {
        // Un observateur près du corps : sans lui, R-612 l'endormirait et il ne tomberait pas.
        ObservateursDeTest.declarer(helper, new Vec3(4.0, DESSUS_DALLE, 4.0));
        ServerLevel level = helper.getLevel();
        Vec3 depart = helper.absoluteVec(new Vec3(4.5, DESSUS_DALLE + 4.0, 4.5));
        CommandSourceStack source = level.getServer().createCommandSourceStack()
                .withLevel(level)
                .withPosition(depart)
                .withPermission(2)
                .withSuppressedOutput();
        Commands commandes = level.getServer().getCommands();

        // R-500 : l'entrée et la sortie se comptent. D'autres GameTests font apparaître des
        // assemblies en même temps : le compteur global n'est comparé qu'en « au moins une ».
        long entrees = compteur("joined");
        long sorties = compteur("left");
        long refus = compteur("spawn_refused");

        int creees = commandes.performPrefixedCommand(source, String.format(
                Locale.ROOT, "axion spawn axion:test_cube %.4f %.4f %.4f", depart.x, depart.y, depart.z));
        helper.assertTrue(creees == 1, "/axion spawn a rendu " + creees);
        helper.assertTrue(compteur("joined") > entrees, "entrée de l'assembly non comptée");
        // Seul ce test demande une definition inconnue : le refus se compte exactement.
        int refusee = commandes.performPrefixedCommand(source, "axion spawn axion:definition_inconnue 0 0 0");
        helper.assertTrue(refusee == 0, "definition inconnue acceptée");
        helper.assertTrue(compteur("spawn_refused") == refus + 1, "refus d'apparition non compté");
        List<AxionEntity> trouvees =
                level.getEntitiesOfClass(AxionEntity.class, new AABB(depart, depart).inflate(1.0));
        helper.assertTrue(trouvees.size() == 1, trouvees.size() + " assembly(s) au point d'apparition");
        AxionEntity cube = trouvees.get(0);
        helper.assertTrue(!cube.isInert(), "assembly inerte dès l'apparition");
        helper.assertTrue("axion:test_cube".equals(cube.definitionId()), "definition " + cube.definitionId());

        // La sauvegarde, telle que Minecraft l'écrit pour un chunk qui se décharge.
        CompoundTag sauvegarde = new CompoundTag();
        helper.assertTrue(cube.save(sauvegarde), "sauvegarde refusée");
        Vec3 position = cube.position();
        Quaternionf orientation = cube.bodyRotation();

        // R-810 : la commande de retrait, par le sélecteur d'un joueur — pas la visée.
        int retirees = commandes.performPrefixedCommand(source, "axion remove @e[type=axion:assembly,distance=..2]");
        helper.assertTrue(retirees == 1, "/axion remove a rendu " + retirees);
        helper.assertTrue(cube.isRemoved(), "l'assembly est restée dans le monde");
        helper.assertTrue(compteur("left") > sorties, "sortie de l'assembly non comptée");

        // Le rechargement : une entité neuve, lue depuis la sauvegarde comme au chargement d'un chunk.
        Entity relue = EntityType.loadEntityRecursive(sauvegarde, level, entite -> entite);
        helper.assertTrue(relue instanceof AxionEntity, "la sauvegarde ne rend pas une assembly : " + relue);
        AxionEntity recharge = (AxionEntity) relue;
        helper.assertTrue(!recharge.isInert(), "assembly rechargée inerte");
        helper.assertTrue("axion:test_cube".equals(recharge.definitionId()),
                "definition rechargée " + recharge.definitionId());
        helper.assertTrue(recharge.position().distanceTo(position) < 1e-6,
                "position rechargée " + recharge.position() + " au lieu de " + position);
        helper.assertTrue(recharge.bodyRotation().equals(orientation, 1e-6f),
                "orientation rechargée " + recharge.bodyRotation() + " au lieu de " + orientation);
        helper.assertTrue(level.addFreshEntity(recharge), "l'assembly rechargée n'a pas pu entrer dans le monde");

        // Son corps est recréé : elle tombe vers la dalle.
        double hauteur = recharge.getY();
        helper.succeedWhen(() -> {
            helper.assertTrue(!recharge.isRemoved(), "l'assembly rechargée a quitté le monde");
            helper.assertTrue(recharge.getY() < hauteur - 0.5,
                    "l'assembly rechargée ne tombe pas : y = " + recharge.getY());
        });
    }

    @GameTest(template = "test/plancher")
    public static void leBuilderDImpactSeResoutSousLeChargeurDeForge(GameTestHelper helper) {
        // R-1762 : ImpactSpec.builder() trouve sa fabrique par ServiceLoader, ici sous les couches de
        // modules de ModLauncher et non plus sur le classpath plat d'un test JUnit.
        ImpactSpec impact = ImpactSpec.builder()
                .point(Vec3.ZERO)
                .normal(new Vec3(0, 1, 0))
                .energy(1e12f)
                .area(0.01f)
                .effectiveMass(1f)
                .flags(ImpactFlag.BLUNT)
                .source(new ImpactSource() {})
                .build();
        helper.assertTrue(impact instanceof BoundedImpactSpecs.Bounded, "fabrique inattendue : " + impact);
        float energie = ((BoundedImpactSpecs.Bounded) impact).energy();
        helper.assertTrue(energie <= 1e9f, "énergie non plafonnée : " + energie);
        helper.succeed();
    }
}
