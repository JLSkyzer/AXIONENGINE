package dev.axion.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.axion.seat.VehicleInput;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-168 D3, D5, R-1682 : la boucle du client qui prédit, contre un monde jouet déterministe. */
class PredictionTest {

    private static final long MS = 1_000_000L;

    /** Un mobile sur une droite : l'accélérateur pousse, le pas intègre. Son état tient en seize octets. */
    private static final class Mobile implements Prediction.World {
        double position;
        double speed;
        final long[] clock;
        final long stepCost;
        int steps;

        Mobile(long[] clock, long stepCost) {
            this.clock = clock;
            this.stepCost = stepCost;
        }

        @Override
        public byte[] step(VehicleInput input) {
            clock[0] += stepCost;
            steps++;
            speed += input.throttle() * 0.05;
            position += speed * 0.05;
            return state();
        }

        @Override
        public void restore(byte[] state) {
            ByteBuffer buffer = ByteBuffer.wrap(state);
            position = buffer.getDouble();
            speed = buffer.getDouble();
        }

        byte[] state() {
            return ByteBuffer.allocate(16).putDouble(position).putDouble(speed).array();
        }
    }

    private static VehicleInput gaz(float throttle) {
        return new VehicleInput(throttle, 0f, 0f, 0f, 0f, (byte) 0, 0);
    }

    /** Le client envoie et prédit les entrées données, une par tick. */
    private static void envoyer(Prediction prediction, Mobile client, long[] clock, long premiere, float... gaz) {
        for (int i = 0; i < gaz.length; i++) {
            prediction.sent(premiere + i, clock[0]);
            prediction.advance(client, premiere + i, gaz(gaz[i]));
            clock[0] += 50 * MS;
        }
    }

    @Test
    @DisplayName("D5 : une correction égale à la prédiction ne change rien ; la latence se mesure par l'acquittement")
    void predictionJuste() {
        long[] clock = {0};
        Prediction prediction = new Prediction(() -> clock[0], 10 * MS);
        Mobile client = new Mobile(clock, 0);
        Mobile serveur = new Mobile(new long[1], 0);
        envoyer(prediction, client, clock, 0, 1f, 1f, 0.5f, 0f);
        // Le serveur a appliqué les deux premières ; la correction arrive 150 ms après l'envoi de la seconde.
        serveur.step(gaz(1f));
        byte[] etat = serveur.step(gaz(1f));
        Prediction.Reconciliation bilan = prediction.reconcile(client, 7, 1, etat);
        assertEquals(Prediction.Outcome.MATCH, bilan.outcome());
        assertEquals(4, client.steps, "aucun pas rejoué");
        assertEquals(2, prediction.pending(), "les deux non acquittées restent");
        assertEquals(150.0, prediction.latencyMillis(), 1e-9, "de l'envoi de la séquence 1 à maintenant");
    }

    @Test
    @DisplayName("D5 : un écart reprend l'état du serveur et rejoue les entrées postérieures, au résultat exact")
    void rejeu() {
        long[] clock = {0};
        Prediction prediction = new Prediction(() -> clock[0], 10 * MS);
        Mobile client = new Mobile(clock, 0);
        envoyer(prediction, client, clock, 10, 1f, 1f, 1f, 0.5f, 0.25f);
        // Le serveur a rejoué la première deux fois (file vide), puis appliqué la deuxième : l'état diffère.
        Mobile serveur = new Mobile(new long[1], 0);
        serveur.step(gaz(1f));
        serveur.step(gaz(1f));
        byte[] etat = serveur.step(gaz(1f));
        Prediction.Reconciliation bilan = prediction.reconcile(client, 3, 11, etat);
        assertEquals(Prediction.Outcome.REPLAYED, bilan.outcome());
        assertEquals(3, bilan.replayed(), "les séquences 12, 13 et 14");
        // Ce que le client doit tenir : l'état du serveur, puis ses trois entrées non acquittées.
        Mobile attendu = new Mobile(new long[1], 0);
        attendu.restore(etat);
        attendu.step(gaz(1f));
        attendu.step(gaz(0.5f));
        assertArrayEquals(attendu.step(gaz(0.25f)), client.state());
        // Et une correction suivante qui suit ce rejeu tombe juste.
        Mobile suite = new Mobile(new long[1], 0);
        suite.restore(etat);
        byte[] apres12 = suite.step(gaz(1f));
        assertEquals(Prediction.Outcome.MATCH, prediction.reconcile(client, 4, 12, apres12).outcome());
    }

    @Test
    @DisplayName("R-1682 : un rejeu qui dépasserait son budget n'a pas lieu ; l'état du serveur est accepté tel quel")
    void budgetEstime() {
        long[] clock = {0};
        Prediction prediction = new Prediction(() -> clock[0], 3 * MS);
        Mobile client = new Mobile(clock, MS);
        envoyer(prediction, client, clock, 0, 1f, 1f, 1f, 1f, 1f);
        byte[] etat = new Mobile(new long[1], 0).state();
        int avant = client.steps;
        Prediction.Reconciliation bilan = prediction.reconcile(client, 1, 0, etat);
        assertEquals(Prediction.Outcome.ACCEPTED, bilan.outcome(), "quatre pas d'une milliseconde, budget de trois");
        assertEquals(avant, client.steps, "aucun pas rejoué");
        assertArrayEquals(etat, client.state(), "l'état du serveur, tel quel");
        assertEquals(1, prediction.accepted());
        // Les états gardés sont caducs : la correction suivante rejoue, trois pas tenant dans le budget.
        Prediction.Reconciliation suivante = prediction.reconcile(client, 2, 1, etat);
        assertEquals(Prediction.Outcome.REPLAYED, suivante.outcome());
        assertEquals(3, suivante.replayed());
    }

    @Test
    @DisplayName("R-1682 : un rejeu qui s'avère plus long que prévu s'interrompt, et l'état du serveur reste")
    void budgetDepasseEnCours() {
        long[] clock = {0};
        Prediction prediction = new Prediction(() -> clock[0], 5 * MS);
        Mobile client = new Mobile(clock, 0);
        envoyer(prediction, client, clock, 0, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f);
        // Les pas deviennent lents après coup : l'estimation dit oui, la mesure dit non.
        Prediction.World lent = new Prediction.World() {
            @Override
            public byte[] step(VehicleInput input) {
                clock[0] += 2 * MS;
                return client.step(input);
            }

            @Override
            public void restore(byte[] state) {
                client.restore(state);
            }
        };
        byte[] etat = new Mobile(new long[1], 0).state();
        Prediction.Reconciliation bilan = prediction.reconcile(lent, 1, 0, etat);
        assertEquals(Prediction.Outcome.ACCEPTED, bilan.outcome());
        assertArrayEquals(etat, client.state(), "le rejeu interrompu ne laisse rien");
    }

    @Test
    @DisplayName("R-1682 : l'historique garde 64 entrées ; une correction sans entrée appliquée rejoue tout")
    void historique() {
        long[] clock = {0};
        Prediction prediction = new Prediction(() -> clock[0], Long.MAX_VALUE);
        Mobile client = new Mobile(clock, 0);
        for (int i = 0; i < 100; i++) {
            envoyer(prediction, client, clock, i, 1f);
        }
        assertEquals(Prediction.HISTORY, prediction.pending());
        Prediction.Reconciliation bilan = prediction.reconcile(client, 1, -1, new Mobile(new long[1], 0).state());
        assertEquals(Prediction.Outcome.REPLAYED, bilan.outcome());
        assertEquals(Prediction.HISTORY, bilan.replayed());
        assertEquals(Prediction.Outcome.STALE, prediction.reconcile(client, 1, -1, client.state()).outcome(),
                "une correction d'un tick déjà traité");
        assertThrows(IllegalArgumentException.class, () -> new Prediction(() -> 0, 0));
    }
}
