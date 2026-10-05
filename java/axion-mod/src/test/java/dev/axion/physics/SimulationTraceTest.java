package dev.axion.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.List;
import org.junit.jupiter.api.Test;

/** C-71 : la trace de la simulation dit, tick par tick, ce qu'a fait chaque corps. */
class SimulationTraceTest {

    @Test
    void chaqueCorpsEtChaqueEvenementDonnentUneLigneComplete() throws IOException {
        StringWriter fichier = new StringWriter();
        SimulationTrace trace = new SimulationTrace(new BufferedWriter(fichier));
        BodyState cube = new BodyState(
                7,
                1,
                new double[] {130.5, 62.25, -40.0},
                new float[] {0f, 0.3826834f, 0f, 0.9238795f},
                new float[] {0f, -21.5f, 0.5f},
                new float[] {3f, 0f, -1f},
                4);
        PhysicsEvent choc = new PhysicsEvent(
                PhysicsEventCodes.Kind.CONTACT_IMPULSE, 7, 1, 0, 0, 0, 0,
                new float[] {130.5f, 61.75f, -40f}, new float[] {0f, 1f, 0f},
                2150f, 0f, 21.5f, 1000f, 0, 0, 0);

        trace.record(42L, List.of(cube), List.of(choc));

        // Vidé à chaque tick : la trace se lit pendant la partie, et survit à un arrêt brutal.
        String[] lignes = fichier.toString().split("\n");
        assertEquals(3, lignes.length);
        assertEquals(SimulationTrace.HEADER, lignes[0]);

        String[] etat = lignes[1].split(";", -1);
        assertEquals(25, etat.length, lignes[1]);
        assertEquals(List.of("42", "etat", "7", "130.5000", "62.2500", "-40.0000"), List.of(etat).subList(0, 6));
        assertEquals("0.3827", etat[7], "rotation y");
        assertEquals("0.9239", etat[9], "rotation w");
        assertEquals("-21.5000", etat[11], "vitesse y");
        assertEquals("3.0000", etat[13], "vitesse angulaire x");
        assertEquals("-1.0000", etat[15], "vitesse angulaire z");
        assertEquals("4", etat[16], "drapeaux bruts : dans un fluide");
        assertEquals("", etat[17], "pas de genre d'événement sur un état");

        String[] evenement = lignes[2].split(";", -1);
        assertEquals(25, evenement.length, lignes[2]);
        assertEquals(List.of("42", "evenement", "7", "130.5000", "61.7500", "-40.0000"),
                List.of(evenement).subList(0, 6));
        assertEquals("", evenement[6], "pas de rotation sur un événement");
        assertEquals("", evenement[16], "pas de drapeaux sur un événement");
        assertEquals("2", evenement[17], "genre : impulsion de contact");
        assertEquals("0", evenement[18], "l'autre assembly");
        assertEquals("1.0000", evenement[20], "normale y");
        assertEquals("2150.0000", evenement[22], "impulsion");
        assertEquals("21.5000", evenement[23], "vitesse d'approche");
        assertEquals("0", evenement[24], "data");

        assertEquals(2, trace.lines());
    }

    @Test
    void fermerLaTraceFermeSonFichier() throws IOException {
        boolean[] ferme = {false};
        Writer fichier = new StringWriter() {
            @Override
            public void close() throws IOException {
                ferme[0] = true;
                super.close();
            }
        };
        new SimulationTrace(fichier).close();
        assertTrue(ferme[0]);
    }
}
