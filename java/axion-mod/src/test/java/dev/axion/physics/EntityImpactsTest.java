package dev.axion.physics;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** ADR-123 §7, part Java : ce qu'une assembly fait aux entités vanilla qu'elle heurte (R-614). */
class EntityImpactsTest {

    private static final float[] VERS_X = {1.0f, 0.0f, 0.0f};

    /** Impulsion d'un contact entre l'assembly {@code (assembly, 1)} et l'entité {@code entite}. */
    private static PhysicsEvent impulsion(
            int assembly, int entite, float impulse, float[] normale, float approche, float masseEffective) {
        return new PhysicsEvent(
                PhysicsEventCodes.Kind.CONTACT_IMPULSE,
                assembly,
                1,
                entite,
                0,
                0,
                0,
                new float[] {0.0f, 0.0f, 0.0f},
                normale,
                impulse,
                0.0f,
                approche,
                masseEffective,
                0,
                0,
                PhysicsEventCodes.Data.CONTACT_OTHER_ENTITY);
    }

    @Test
    void seulesLesImpulsionsVersUneEntiteComptent() {
        PhysicsEvent versEntite = impulsion(1, 77, 10.0f, VERS_X, 5.0f, 100.0f);
        assertTrue(EntityImpacts.concernsEntity(versEntite));

        // Même contact, sans le bit : l'autre corps est le monde ou une assembly.
        PhysicsEvent sansBit = new PhysicsEvent(
                PhysicsEventCodes.Kind.CONTACT_IMPULSE, 1, 1, 77, 0, 0, 0,
                new float[3], VERS_X, 10.0f, 0.0f, 5.0f, 100.0f, 0, 0, 0);
        assertFalse(EntityImpacts.concernsEntity(sansBit));

        // Le début d'un contact porte les mêmes données ; seule l'impulsion pousse et blesse,
        // sans quoi le premier sous-pas compterait deux fois.
        PhysicsEvent debut = new PhysicsEvent(
                PhysicsEventCodes.Kind.CONTACT_START, 1, 1, 77, 0, 0, 0,
                new float[3], VERS_X, 10.0f, 0.0f, 5.0f, 100.0f, 0, 0,
                PhysicsEventCodes.Data.CONTACT_OTHER_ENTITY);
        assertFalse(EntityImpacts.concernsEntity(debut));

        // Un autre corps doté d'une génération est une assembly, pas une entité.
        PhysicsEvent assemblyB = new PhysicsEvent(
                PhysicsEventCodes.Kind.CONTACT_IMPULSE, 1, 1, 77, 1, 0, 0,
                new float[3], VERS_X, 10.0f, 0.0f, 5.0f, 100.0f, 0, 0,
                PhysicsEventCodes.Data.CONTACT_OTHER_ENTITY);
        assertFalse(EntityImpacts.concernsEntity(assemblyB));
    }

    @Test
    void laMasseEstLeVolumeALaDensiteDeLEauBorne() {
        // Une vache : 0,9 × 1,4 × 0,9 m.
        assertEquals(1134.0, EntityImpacts.massOf(0.9 * 1.4 * 0.9), 1e-9);
        assertEquals(EntityImpacts.MIN_MASS, EntityImpacts.massOf(1.0e-6), "borne basse");
        assertEquals(EntityImpacts.MAX_MASS, EntityImpacts.massOf(20.0), "borne haute");
        assertEquals(EntityImpacts.MIN_MASS, EntityImpacts.massOf(0.0));
        assertEquals(EntityImpacts.MIN_MASS, EntityImpacts.massOf(-3.0));
        assertEquals(EntityImpacts.MIN_MASS, EntityImpacts.massOf(Double.NaN));
    }

    @Test
    void lesDegatsSuiventLaCourbeDeChuteVanilla() {
        // 10 m/s : une chute de 5,097 blocs, dont 3 sans dégâts.
        assertEquals(100.0 / (2.0 * 9.81) - 3.0, EntityImpacts.fallDamage(10.0), 1e-12);
        // La vitesse d'une chute de 3 blocs ne blesse pas ; un peu plus, si.
        double seuil = Math.sqrt(2.0 * 9.81 * 3.0);
        assertEquals(0.0, EntityImpacts.fallDamage(seuil), 1e-12);
        assertTrue(EntityImpacts.fallDamage(seuil + 0.5) > 0.0);
        assertEquals(0.0, EntityImpacts.fallDamage(5.0), "sous le seuil");
        assertEquals(0.0, EntityImpacts.fallDamage(-12.0), "séparation");
        assertEquals(0.0, EntityImpacts.fallDamage(Double.NaN));
        assertEquals(0.0, EntityImpacts.fallDamage(Double.POSITIVE_INFINITY));
    }

    @Test
    void laPousseePartageLImpulsionEntreLesDeuxMasses() {
        // Une voiture d'une tonne à 10 m/s contre un proxy de masse infinie : J = 10 000 N·s.
        PhysicsEvent choc = impulsion(1, 77, 10_000.0f, VERS_X, 10.0f, 1000.0f);
        EntityImpacts.Impact poule = EntityImpacts.gather(List.of(choc)).get(0);
        double massePoule = EntityImpacts.massOf(0.4 * 0.7 * 0.4); // 112 kg

        // J / (m + m_eff) : la poule repart à peu près à la vitesse de la voiture…
        double[] dv = poule.velocityChange(massePoule);
        assertEquals(10_000.0 / (112.0 + 1000.0), dv[0], 1e-9);
        assertEquals(0.0, dv[1]);
        assertEquals(0.0, dv[2]);
        // … et non à J / m, près de 90 m/s.
        assertTrue(dv[0] < 10.0, "vitesse rendue " + dv[0]);

        // Sans masse effective (assembly sans inertie au point), la formule rejoint J / m.
        EntityImpacts.Impact sansInertie =
                EntityImpacts.gather(List.of(impulsion(1, 78, 50.0f, VERS_X, 1.0f, 0.0f))).get(0);
        assertEquals(50.0 / 100.0, sansInertie.velocityChange(100.0)[0], 1e-12);
    }

    @Test
    void lesContactsDUnTickSAdditionnentEtLApprocheLaPlusForteCompte() {
        float[] versY = {0.0f, 1.0f, 0.0f};
        List<EntityImpacts.Impact> impacts = EntityImpacts.gather(List.of(
                impulsion(1, 77, 100.0f, VERS_X, 9.0f, 100.0f),
                impulsion(2, 88, 30.0f, versY, 2.0f, 50.0f),
                // Deuxième sous-pas du même contact : l'assembly est déjà arrêtée.
                impulsion(1, 77, 60.0f, VERS_X, 0.5f, 100.0f),
                impulsion(1, 77, 20.0f, versY, -1.0f, 100.0f)));

        // Une entrée par entité, dans l'ordre de son premier contact (R-1020).
        assertEquals(List.of(77, 88), impacts.stream().map(EntityImpacts.Impact::entity).toList());
        EntityImpacts.Impact premier = impacts.get(0);
        assertEquals(1, premier.assemblyIndex());
        assertEquals(1, premier.assemblyGeneration());
        assertEquals(9.0, premier.approachSpeed(), 1e-6);
        assertArrayEquals(
                new double[] {160.0 / 200.0, 20.0 / 200.0, 0.0}, premier.velocityChange(100.0), 1e-9);
        assertEquals(2.0, impacts.get(1).approachSpeed(), 1e-6);
    }

    @Test
    void uneDonneeAberranteNePousseNiNeBlesse() {
        List<EntityImpacts.Impact> impacts = EntityImpacts.gather(List.of(
                impulsion(1, 77, Float.NaN, VERS_X, 9.0f, 100.0f),
                impulsion(1, 78, 10.0f, new float[] {Float.POSITIVE_INFINITY, 0.0f, 0.0f}, 9.0f, 100.0f),
                impulsion(1, 79, 10.0f, VERS_X, Float.NaN, 100.0f),
                impulsion(1, 80, 10.0f, VERS_X, 9.0f, Float.NaN)));
        assertTrue(impacts.isEmpty(), "événements non finis ignorés");

        // Une impulsion négative, qu'un solveur ne rend pas, ne tire pas l'entité vers l'assembly.
        EntityImpacts.Impact negative =
                EntityImpacts.gather(List.of(impulsion(1, 81, -10.0f, VERS_X, 1.0f, 10.0f))).get(0);
        assertArrayEquals(new double[] {0.0, 0.0, 0.0}, negative.velocityChange(10.0));
        // Sans contact qui approche, aucune vitesse d'approche.
        EntityImpacts.Impact separation =
                EntityImpacts.gather(List.of(impulsion(1, 82, 10.0f, VERS_X, -4.0f, 10.0f))).get(0);
        assertEquals(0.0, separation.approachSpeed());
    }

    @Test
    void uneMasseNonPositiveEstRameneeALaBorneBasse() {
        EntityImpacts.Impact impact =
                EntityImpacts.gather(List.of(impulsion(1, 77, 10.0f, VERS_X, 1.0f, 0.0f))).get(0);
        assertEquals(10.0, impact.velocityChange(0.0)[0], 1e-12);
        assertEquals(10.0, impact.velocityChange(-5.0)[0], 1e-12);
    }
}
