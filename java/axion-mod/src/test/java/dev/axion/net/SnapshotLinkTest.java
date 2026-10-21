package dev.axion.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Le chemin des snapshots en jeu, sans Forge (ADR-150 D2, D6) : la synchronisation du joueur côté serveur,
 * la session côté client, les trames entre elles, et l'acquittement qui revient.
 */
class SnapshotLinkTest {

    private static final DetProfile VALIDEE = new DetProfile(0x0123_4567_89AB_CDEFL, 1, true);

    private static AssemblyState etat(long x, boolean dort) {
        return new AssemblyState(x, 64 * 1024, 0, 0x4000_0000, AssemblyState.Velocity.ZERO, null, dort);
    }

    /** Un serveur et son client, reliés trame par trame. */
    private static final class Liaison {
        final PlayerSync serveur = new PlayerSync(new SnapshotSender(20, 32_768));
        final List<SnapshotReceiver.Applied> recus = new ArrayList<>();
        final ClientSession client = new ClientSession(null, VALIDEE, registre -> List.of(), recus::addAll);
        final Map<Integer, AssemblyState> monde = new HashMap<>();
        int sequence = 2;

        /** Un tick du serveur : les trames partent, le client répond, le serveur lit l'acquittement. */
        int tick(long tick) {
            List<byte[]> trames = serveur.tick(tick, handle -> {
                AssemblyState etat = monde.get(handle);
                return etat == null ? null : new SnapshotSender.Candidate(handle, etat, SnapshotSender.Frequency.of(etat), 1);
            }, sequence++);
            for (byte[] trame : trames) {
                byte[] ack = client.onPlayFrame(trame);
                if (ack != null) {
                    serveur.onAckFrame(ack);
                }
            }
            return trames.size();
        }

        AssemblyState dernier(int handle) {
            for (int index = recus.size() - 1; index >= 0; index--) {
                if (recus.get(index).handle() == handle) {
                    return recus.get(index).state();
                }
            }
            return null;
        }
    }

    @Test
    @DisplayName("T-410 : un joueur reçoit l'état des assemblies qu'il suit, et seulement d'elles, et l'acquitte")
    void unJoueurRecoitCeQuIlSuit() {
        Liaison liaison = new Liaison();
        liaison.serveur.track(3);
        liaison.serveur.track(5);
        for (long tick = 1; tick <= 40; tick++) {
            liaison.monde.put(3, etat(tick * 100, false));
            liaison.monde.put(5, etat(-tick * 100, false));
            liaison.monde.put(9, etat(tick, false));
            liaison.tick(tick);
            assertEquals(liaison.monde.get(3), liaison.dernier(3), "tick " + tick);
            assertEquals(liaison.monde.get(5), liaison.dernier(5), "tick " + tick);
        }
        assertNull(liaison.dernier(9), "une assembly que le joueur ne suit pas ne lui parvient pas");
        assertEquals(liaison.serveur.sender().sequence(), liaison.serveur.sender().acked(),
                "chaque snapshot est acquitté");
        assertTrue(liaison.client.drainJournal().isEmpty());
        assertTrue(liaison.serveur.drainJournal().isEmpty());
    }

    @Test
    @DisplayName("Une assembly sans état simulé, ou hors du rayon, n'est pas envoyée ; ne plus la suivre l'oublie")
    void sansEtatOuNonSuivieRienNePart() {
        Liaison liaison = new Liaison();
        liaison.serveur.track(3);
        assertEquals(0, liaison.tick(1), "aucun état : rien");
        liaison.monde.put(3, etat(10, false));
        assertEquals(1, liaison.tick(2));
        // Fin du suivi : le client voit l'entité quitter son monde et l'oublie, comme en jeu.
        liaison.serveur.untrack(3);
        liaison.client.forgetEntity(3);
        assertFalse(liaison.serveur.tracked().contains(3));
        assertEquals(0, liaison.tick(3));
        liaison.serveur.track(3);
        liaison.monde.put(3, etat(20, false));
        liaison.tick(4);
        assertEquals(etat(20, false), liaison.dernier(3),
                "suivie de nouveau, elle repart d'une entrée complète, appliquée sans aller-retour");
        assertTrue(liaison.client.drainJournal().isEmpty(), "aucune entrée complète n'a dû être redemandée");
    }

    @Test
    @DisplayName("Une entité oubliée par le client est redemandée en entier, et servie au snapshot suivant")
    void uneEntiteOublieeEstRedemandee() {
        Liaison liaison = new Liaison();
        liaison.serveur.track(3);
        liaison.monde.put(3, etat(10, false));
        liaison.tick(1);
        liaison.client.forgetEntity(3);
        liaison.monde.put(3, etat(20, false));
        liaison.tick(2);
        assertEquals(etat(10, false), liaison.dernier(3), "le delta sans base n'est pas appliqué");
        assertTrue(String.join("\n", liaison.client.drainJournal()).contains("entrée complète redemandée"));
        liaison.monde.put(3, etat(30, false));
        liaison.tick(3);
        assertEquals(etat(30, false), liaison.dernier(3));
    }

    @Test
    @DisplayName("25.2 : la consommation du débit d'un joueur est la somme de ses vingt derniers ticks, retards comptés")
    void leDebitDUnJoueurSeCompteSurUneSeconde() {
        PlayerSync serveur = new PlayerSync(new SnapshotSender(20, 4_000));
        serveur.track(3);
        long[] parTick = new long[30];
        for (int tick = 1; tick <= 30; tick++) {
            long x = tick * 1000L;
            serveur.tick(tick, handle -> new SnapshotSender.Candidate(handle, etat(x, false),
                    SnapshotSender.Frequency.NORMAL, 1), tick);
            parTick[tick - 1] = serveur.lastTickBytes();
            assertTrue(serveur.lastTickBytes() > 0, "tick " + tick + " : une assembly en mouvement part à chaque tick");
        }
        long attendu = 0;
        for (int index = 10; index < 30; index++) {
            attendu += parTick[index];
        }
        assertEquals(attendu, serveur.bytesLastSecond(), "les ticks 11 à 30, ni plus ni moins");
        assertEquals(0, serveur.lastTickDelayed());

        // Un plafond minuscule et trois assemblies : une seule part par tick, les autres attendent.
        PlayerSync serre = new PlayerSync(new SnapshotSender(20, 20));
        serre.track(1);
        serre.track(2);
        serre.track(3);
        serre.tick(1, handle -> new SnapshotSender.Candidate(handle, etat(handle * 10L, false),
                SnapshotSender.Frequency.NORMAL, handle), 0);
        assertEquals(2, serre.lastTickDelayed(), "deux entrées retardées à ce tick");
    }

    @Test
    @DisplayName("R-1690 : au-delà de net.max_packets_per_second, les trames sont ignorées et dites une fois par"
            + " seconde ; cinq secondes d'abus de suite, et le client est à déconnecter")
    void unClientQuiInondeEstLimitePuisAbusif() {
        PlayerSync serveur = new PlayerSync(new SnapshotSender(20, 32_768), 40);
        byte[] ack = Fragments.split(new AckMessage(0, List.of()).encode(), 0).get(0);
        // Une seconde à cinquante trames : dix ignorées, une ligne au journal à la fin de la seconde.
        for (int index = 0; index < 50; index++) {
            serveur.onAckFrame(ack);
        }
        serveur.tick(1, handle -> null, 0);
        assertEquals(10, serveur.lastTickLimited(), "les trames ignorées depuis le tick précédent");
        for (int tick = 2; tick <= 20; tick++) {
            serveur.tick(tick, handle -> null, 0);
        }
        List<String> journal = serveur.drainJournal();
        assertEquals(1, journal.size(), journal::toString);
        assertTrue(journal.get(0).startsWith("E-5001 paquet ignoré (limitation) : 10 trame(s) au-delà de 40"),
                journal.get(0));
        assertFalse(serveur.abusive(), "une seconde d'excès ne fait pas un abus");
        // Une seconde calme remet le compte à zéro.
        for (int tick = 21; tick <= 40; tick++) {
            serveur.tick(tick, handle -> null, 0);
        }
        // Quatre secondes d'abus de suite : pas encore ; la cinquième : à déconnecter.
        long tick = 40;
        for (int seconde = 1; seconde <= 5; seconde++) {
            for (int index = 0; index < 41; index++) {
                serveur.onAckFrame(ack);
            }
            for (int pas = 0; pas < 20; pas++) {
                serveur.tick(++tick, handle -> null, 0);
            }
            assertEquals(seconde == PlayerSync.ABUSE_SECONDS, serveur.abusive(), "après " + seconde + " seconde(s) d'abus");
        }
        List<String> abus = serveur.drainJournal();
        assertEquals(5, abus.size(), "une ligne par seconde d'abus, pas une par trame");
        assertTrue(abus.stream().allMatch(ligne -> ligne.contains(" : 1 trame(s) au-delà de 40")),
                "chaque seconde repart de zéro : 41 trames n'en ignorent qu'une — " + abus);
    }

    @Test
    @DisplayName("T-422 : un acquittement fautif est ignoré et dit, sans lever")
    void unAcquittementFautifEstIgnore() {
        Liaison liaison = new Liaison();
        liaison.serveur.onAckFrame(null);
        liaison.serveur.onAckFrame(Fragments.split(new byte[] {(byte) MessageKinds.SNAPSHOT}, 0).get(0));
        liaison.serveur.onAckFrame(Fragments.split(new AckMessage(50, List.of()).encode(), 0).get(0));
        List<String> journal = liaison.serveur.drainJournal();
        assertEquals(3, journal.size(), journal::toString);
        journal.forEach(ligne -> assertTrue(ligne.startsWith("E-5001 paquet ignoré (acquittement)"), ligne));
        assertEquals(0, liaison.serveur.sender().acked());
    }

    @Test
    @DisplayName("Un puits client qui échoue ne prive pas le serveur de l'acquittement")
    void unPuitsQuiEchoueLaisseAcquitter() {
        ClientSession client = new ClientSession(null, VALIDEE, registre -> List.of(), etats -> {
            throw new IllegalStateException("échec simulé");
        });
        PlayerSync serveur = new PlayerSync(new SnapshotSender(20, 32_768));
        serveur.track(1);
        List<byte[]> trames = serveur.tick(1, handle -> new SnapshotSender.Candidate(handle, etat(1, false),
                SnapshotSender.Frequency.NORMAL, 0), 0);
        byte[] ack = client.onPlayFrame(trames.get(0));
        assertNotNull(ack);
        serveur.onAckFrame(ack);
        assertEquals(1, serveur.sender().acked());
        assertTrue(String.join("\n", client.drainJournal()).contains("états du snapshot non appliqués"));
    }
}
