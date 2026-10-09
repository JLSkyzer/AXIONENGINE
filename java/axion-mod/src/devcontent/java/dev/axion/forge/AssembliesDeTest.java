package dev.axion.forge;

import dev.axion.entity.AssemblySpawns;
import dev.axion.lifecycle.AxionRuntime;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * Création d'assemblies pour les GameTests et le banc de rendu, comme {@code /axion spawn} les crée : definition
 * vérifiée, entité liée, puis ajoutée au monde ; son corps naît au cycle de simulation suivant.
 *
 * <p>Contenu de développement, jamais empaqueté (R-1790) ; dans le paquet du pont Forge pour lier
 * l'entité à sa definition.
 */
final class AssembliesDeTest {

    private AssembliesDeTest() {}

    /**
     * @param helper le test
     * @param definitionId definition de l'assembly
     * @param position position dans les coordonnées du test, en blocs
     * @param rotation rotation initiale du corps {@code [x, y, z, w]}
     * @return l'entité ajoutée
     */
    static AxionEntity poser(GameTestHelper helper, String definitionId, Vec3 position, float[] rotation) {
        try {
            return poser(helper.getLevel(), definitionId, helper.absoluteVec(position), rotation);
        } catch (IllegalStateException refus) {
            throw new GameTestAssertException(refus.getMessage());
        }
    }

    /**
     * La même création hors d'un GameTest — le banc de rendu (ADR-126). Sur le thread du serveur.
     *
     * @param level monde d'accueil
     * @param definitionId definition de l'assembly
     * @param absolue position dans le monde, en blocs
     * @param rotation rotation initiale du corps {@code [x, y, z, w]}
     * @return l'entité ajoutée
     * @throws IllegalStateException si AXION n'est pas chargé, la definition refusée, ou l'entité
     *     refusée par le monde
     */
    static AxionEntity poser(ServerLevel level, String definitionId, Vec3 absolue, float[] rotation) {
        AxionRuntime runtime = RuntimeAccess.get();
        if (runtime == null || runtime.outcome() == null) {
            throw new IllegalStateException("AXION n'est pas chargé : bibliothèque native absente ?");
        }
        AssemblySpawns.Decision decision = AssemblySpawns.check(
                runtime.definitions(),
                definitionId,
                1,
                runtime.outcome().config().getInt("limits.max_spawn_per_command"));
        if (!decision.accepted()) {
            throw new IllegalStateException("definition " + definitionId + " refusée : " + decision.refusal());
        }
        AxionEntity assembly = AxionEntities.ASSEMBLY.get().create(level);
        if (assembly == null) {
            throw new IllegalStateException("l'entité d'assembly n'a pas pu être construite");
        }
        assembly.moveTo(absolue.x, absolue.y, absolue.z, 0f, 0f);
        assembly.setBodyRotation(rotation[0], rotation[1], rotation[2], rotation[3]);
        assembly.bind(decision.definition());
        if (!level.addFreshEntity(assembly)) {
            throw new IllegalStateException("l'assembly n'a pas pu entrer dans le monde");
        }
        return assembly;
    }
}
