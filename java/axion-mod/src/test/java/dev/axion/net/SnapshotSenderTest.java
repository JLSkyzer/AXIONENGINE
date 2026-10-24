package dev.axion.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * L'émetteur et le récepteur des snapshots, reliés comme le serait une connexion (21.5, ADR-150 D6) :
 * deltas sur la base acquittée (R-1661), fréquences (R-1662, R-1663), plafond de débit sans omission
 * (R-1664, T-418), et reconstruction au bit près.
 */
class SnapshotSenderTest {

    private static final int RATE_HZ = 10;
    private static final long CAP = 32_768;

    private static AssemblyState etat(long x, long y, boolean dort) {
        return new AssemblyState(x, y, 0, 0x4000_0000, new AssemblyState.Velocity((short) 1, (short) 2, (short) 3,
                (short) 4, (short) 5, (short) 6), new AssemblyState.Bounds((short) -128, (short) -128, (short) -128,
                (short) 128, (short) 128, (short) 128), dort);
    }

    /** Jusqu'à 63 corps secondaires, à des nodes distincts parfois au-delà d'un varint d'un octet. */
    private static List<AssemblyState.Body> corps(Random random) {
        List<AssemblyState.Body> corps = new ArrayList<>();
        int nombre = random.nextInt(4) == 0 ? AssemblyState.MAX_BODIES : random.nextInt(4);
        for (int index = 0; index < nombre; index++) {
            corps.add(new AssemblyState.Body(index * 200, random.nextInt(), random.nextInt(), random.nextInt(),
                    random.nextInt()));
        }
        return corps;
    }

    /** Des joints, de zéro à quelques centaines, aux drapeaux tirés au hasard. */
    private static AssemblyState.JointBits joints(Random random) {
        int[] drapeaux = new int[random.nextInt(4) == 0 ? 300 : random.nextInt(9)];
        for (int index = 0; index < drapeaux.length; index++) {
            drapeaux[index] = random.nextInt(4);
        }
        return AssemblyState.JointBits.of(drapeaux);
    }

    private static SnapshotSender.Candidate candidat(int handle, AssemblyState etat) {
        return new SnapshotSender.Candidate(handle, etat, SnapshotSender.Frequency.of(etat), handle);
    }

    private static SnapshotMessage lu(byte[] message) {
        try {
            return SnapshotMessage.decode(message);
        } catch (MalformedPacketException error) {
            throw new AssertionError(error.getMessage(), error);
        }
    }

    /** Une connexion : l'émetteur, le récepteur, et ce que le client tient de chaque assembly. */
    private static final class Liaison {
        final SnapshotSender emetteur;
        final SnapshotReceiver recepteur = new SnapshotReceiver();
        final Map<Integer, AssemblyState> tenus = new HashMap<>();
        final List<SnapshotMessage> envoyes = new ArrayList<>();

        Liaison(int rateHz, long cap) {
            emetteur = new SnapshotSender(rateHz, cap);
        }

        /** Un tick : le snapshot part, est lu, et l'acquittement revient si {@code acquitter}. */
        SnapshotMessage tick(long tick, List<SnapshotSender.Candidate> candidats, boolean acquitter) {
            byte[] message = emetteur.tick(tick, candidats);
            if (message == null) {
                return null;
            }
            envoyes.add(lu(message));
            for (SnapshotReceiver.Applied applique : recepteur.onSnapshot(message)) {
                tenus.put(applique.handle(), applique.state());
                assertEquals(tick, applique.serverTick());
            }
            if (acquitter) {
                emetteur.onAck(recepteur.ack());
            }
            return envoyes.get(envoyes.size() - 1);
        }
    }

    @Test
    @DisplayName("R-1661 : une entrée complète d'abord, puis des deltas sur la base acquittée, reconstitués au bit près")
    void desDeltasSurLaBaseAcquittee() {
        Liaison liaison = new Liaison(20, CAP);
        Random random = new Random(1661);
        AssemblyState etat = etat(1_000_000, 64 * 1024, false);
        for (long tick = 1; tick <= 200; tick++) {
            etat = new AssemblyState(etat.x() + random.nextInt(2000) - 1000, etat.y() - random.nextInt(50), 0,
                    random.nextInt(), etat.velocity(), etat.bounds(), false);
            SnapshotMessage snapshot = liaison.tick(tick, List.of(candidat(7, etat)), true);
            SnapshotMessage.Entry entree = snapshot.entries().get(0);
            assertEquals(tick == 1, entree.full(), "tick " + tick);
            if (tick > 1) {
                assertEquals(snapshot.sequence() - 1, entree.base(), "base : le snapshot précédent, acquitté");
            }
            assertEquals(etat, liaison.tenus.get(7), "tick " + tick);
        }
        assertTrue(liaison.recepteur.drainJournal().isEmpty());
    }

    @Test
    @DisplayName("R-1661 : sans acquittement, ou acquitté au-delà de la fenêtre de 32, l'entrée part complète")
    void auDelaDeLaFenetreLEntreeEstComplete() {
        Liaison sansAck = new Liaison(20, CAP);
        for (long tick = 1; tick <= 50; tick++) {
            assertTrue(sansAck.tick(tick, List.of(candidat(1, etat(tick * 100, 0, false))), false).entries().get(0).full());
        }

        Liaison enRetard = new Liaison(20, CAP);
        enRetard.tick(1, List.of(candidat(1, etat(100, 0, false))), true);
        for (long tick = 2; tick <= 40; tick++) {
            SnapshotMessage snapshot = enRetard.tick(tick, List.of(candidat(1, etat(tick * 100, 0, false))), false);
            boolean dansLaFenetre = snapshot.sequence() - 1 <= SnapshotSender.WINDOW;
            assertEquals(!dansLaFenetre, snapshot.entries().get(0).full(), "séquence " + snapshot.sequence());
            assertEquals(etat(tick * 100, 0, false), enRetard.tenus.get(1));
        }
    }

    @Test
    @DisplayName("R-1661 : la fenêtre se compte en snapshots, pas en entrées d'une assembly — une assembly lente le montre")
    void laFenetreSeCompteEnSnapshots() {
        // A part à 2 Hz (endormie qui bouge), B à 10 Hz : cinq snapshots séparent deux entrées de A. Sa base,
        // le snapshot 1, sort de la fenêtre au snapshot 34, alors que A n'a encore envoyé que sept entrées.
        Liaison liaison = new Liaison(RATE_HZ, CAP);
        boolean vuHorsFenetre = false;
        for (long tick = 1; tick <= 100; tick++) {
            AssemblyState a = etat(tick * 10, 0, true);
            AssemblyState b = etat(tick * 10, 1, false);
            SnapshotMessage snapshot = liaison.tick(tick, List.of(candidat(1, a), candidat(2, b)), tick == 1);
            if (snapshot == null) {
                continue;
            }
            for (SnapshotMessage.Entry entree : snapshot.entries()) {
                if (entree.handle() == 1 && snapshot.sequence() > 1) {
                    boolean dansLaFenetre = snapshot.sequence() - 1 <= SnapshotSender.WINDOW;
                    assertEquals(!dansLaFenetre, entree.full(), "A au snapshot " + snapshot.sequence());
                    vuHorsFenetre |= !dansLaFenetre;
                }
            }
        }
        assertTrue(vuHorsFenetre, "le scénario doit faire sortir A de la fenêtre");
    }

    @Test
    @DisplayName("R-1662 : NORMAL à net.snapshot_rate_hz, même quand elle ne divise pas 20 ; LOW à 2 Hz")
    void lesFrequencesSontTenues() {
        for (int rate : new int[] {2, 5, 10, 15, 20}) {
            Liaison liaison = new Liaison(rate, CAP);
            List<Long> ticks = new ArrayList<>();
            for (long tick = 1; tick <= 200; tick++) {
                if (liaison.tick(tick, List.of(candidat(1, etat(tick, 0, false))), true) != null) {
                    ticks.add(tick);
                }
            }
            // Sur 10 s, à un envoi près selon où tombent les bords ; et sur toute fenêtre d'une seconde,
            // exactement la fréquence demandée.
            assertTrue(Math.abs(ticks.size() - rate * 10) <= 1, "à " + rate + " Hz sur 10 s : " + ticks.size());
            for (int index = 0; index + rate < ticks.size(); index++) {
                assertEquals(20L, ticks.get(index + rate) - ticks.get(index), "à " + rate + " Hz : " + ticks);
            }
        }
        // Endormie mais déplacée — un réveil pas encore vu, une correction : LOW, 2 Hz.
        Liaison endormie = new Liaison(RATE_HZ, CAP);
        for (long tick = 1; tick <= 200; tick++) {
            endormie.tick(tick, List.of(candidat(1, etat(tick, 0, true))), true);
        }
        assertEquals(20, endormie.envoyes.size());
    }

    @Test
    @DisplayName("R-1663 : une assembly endormie et inchangée n'envoie rien ; réveillée, elle repart aussitôt")
    void uneEndormieInchangeeNEnvoieRien() {
        Liaison liaison = new Liaison(RATE_HZ, CAP);
        AssemblyState repos = etat(500, 500, true);
        for (long tick = 1; tick <= 100; tick++) {
            liaison.tick(tick, List.of(candidat(1, repos)), true);
        }
        assertEquals(1, liaison.envoyes.size(), "la première, puis rien");
        AssemblyState reveil = etat(600, 500, false);
        assertNotNull(liaison.tick(101, List.of(candidat(1, reveil)), true), "son échéance est passée depuis longtemps");
        assertEquals(reveil, liaison.tenus.get(1));
    }

    @Test
    @DisplayName("T-418 : au-delà du plafond, les entrées sont retardées et comptées, chacune finit par partir, et le débit tient")
    void t418_lePlafondRetardeSansOmettre() {
        long cap = 4096;
        Liaison liaison = new Liaison(RATE_HZ, cap);
        int corps = 300;
        Map<Integer, Long> dernierEnvoi = new HashMap<>();
        long pireAttente = 0;
        for (long tick = 1; tick <= 400; tick++) {
            List<SnapshotSender.Candidate> candidats = new ArrayList<>();
            for (int handle = 1; handle <= corps; handle++) {
                candidats.add(candidat(handle, etat(tick * 7 + handle, handle, false)));
            }
            SnapshotMessage snapshot = liaison.tick(tick, candidats, true);
            if (snapshot != null) {
                for (SnapshotMessage.Entry entree : snapshot.entries()) {
                    long avant = dernierEnvoi.getOrDefault(entree.handle(), 0L);
                    pireAttente = Math.max(pireAttente, tick - avant);
                    dernierEnvoi.put(entree.handle(), tick);
                }
            }
        }
        assertEquals(corps, dernierEnvoi.size(), "aucune assembly n'est privée d'envoi");
        assertTrue(liaison.emetteur.delayedEntries() > 0, "le plafond doit retarder des entrées");
        // Une seconde de rafale, puis le débit : la moyenne sur 20 s tient sous le plafond, à une entrée près.
        double moyenne = liaison.emetteur.sentBytes() / 20.0;
        assertTrue(moyenne <= cap + cap / 20.0 + 64, "débit moyen " + moyenne + " o/s pour un plafond de " + cap);
        System.out.printf("T-418 : %d octets/s en moyenne sous un plafond de %d, %d entrée(s) retardée(s),"
                + " attente maximale %d tick(s)%n", Math.round(moyenne), cap, liaison.emetteur.delayedEntries(), pireAttente);
        for (int handle = 1; handle <= corps; handle++) {
            assertEquals(etat(dernierEnvoi.get(handle) * 7 + handle, handle, false), liaison.tenus.get(handle),
                    "entité " + handle + " : le client tient son dernier état envoyé");
        }
        List<String> journal = liaison.emetteur.drainJournal();
        assertTrue(journal.stream().anyMatch(ligne -> ligne.startsWith("débit plafonné : ")), journal::toString);

        // La charge retombe : la sortie de la dégradation se dit aussi.
        for (long tick = 401; tick <= 600; tick++) {
            liaison.tick(tick, List.of(candidat(1, etat(tick, 1, false))), true);
        }
        journal = liaison.emetteur.drainJournal();
        assertTrue(journal.stream().anyMatch(ligne -> ligne.startsWith("débit revenu sous le plafond")), journal::toString);
    }

    @Test
    @DisplayName("R-1664 : une entrée plus grosse que tout le seau part quand même, et la moyenne tient")
    void uneEntreeTropGrossePourLeSeauPartQuandMeme() {
        // 30 o/s : le seau tient 30 octets, une entrée complète en fait une soixantaine.
        long cap = 30;
        Liaison liaison = new Liaison(RATE_HZ, cap);
        for (long tick = 1; tick <= 400; tick++) {
            liaison.tick(tick, List.of(candidat(1, etat(tick, 0, false))), true);
        }
        assertTrue(liaison.envoyes.size() >= 2, "l'assembly ne doit pas rester bloquée : " + liaison.envoyes.size());
        double moyenne = liaison.emetteur.sentBytes() / 20.0;
        assertTrue(moyenne <= cap + (cap + 64) / 20.0, "débit moyen " + moyenne + " o/s pour un plafond de " + cap);
    }

    @Test
    @DisplayName("R-1664 : après un long silence, la rafale reste bornée à une seconde de débit")
    void laRafaleEstBornee() {
        long cap = 4096;
        Liaison liaison = new Liaison(RATE_HZ, cap);
        for (long tick = 1; tick <= 2000; tick++) {
            assertNull(liaison.tick(tick, List.of(), true));
        }
        List<SnapshotSender.Candidate> candidats = new ArrayList<>();
        for (int handle = 1; handle <= 300; handle++) {
            candidats.add(candidat(handle, etat(handle, handle, false)));
        }
        liaison.tick(2001, candidats, true);
        assertTrue(liaison.emetteur.sentBytes() <= cap + 64, "rafale de " + liaison.emetteur.sentBytes() + " octets");
    }

    @Test
    @DisplayName("Un delta dont le client ne tient pas la base est redemandé en entier, et la connexion s'en remet")
    void uneBaseAbsenteEstRedemandee() {
        Liaison liaison = new Liaison(20, CAP);
        liaison.tick(1, List.of(candidat(3, etat(10, 0, false))), true);
        liaison.recepteur.forget(3);
        liaison.tenus.remove(3);
        SnapshotMessage delta = liaison.tick(2, List.of(candidat(3, etat(20, 0, false))), true);
        assertFalse(delta.entries().get(0).full());
        assertNull(liaison.tenus.get(3), "un delta sans sa base n'est pas appliqué");
        assertTrue(liaison.recepteur.drainJournal().get(0).contains("entrée complète redemandée"));
        SnapshotMessage complet = liaison.tick(3, List.of(candidat(3, etat(30, 0, false))), true);
        assertTrue(complet.entries().get(0).full(), "la demande est servie");
        assertEquals(etat(30, 0, false), liaison.tenus.get(3));
        assertTrue(liaison.recepteur.ack().needFull().isEmpty(), "et retirée");

        // L'émetteur qui oublie une assembly — le joueur ne la suit plus — repart d'une entrée complète.
        liaison.emetteur.forget(3);
        assertTrue(liaison.tick(4, List.of(candidat(3, etat(40, 0, false))), true).entries().get(0).full());
    }

    @Test
    @DisplayName("T-422 : un acquittement du futur est ignoré et dit ; un snapshot répété ou mal formé est ignoré et dit")
    void lesMessagesFautifsSontIgnores() {
        Liaison liaison = new Liaison(20, CAP);
        byte[] premier = liaison.emetteur.tick(1, List.of(candidat(1, etat(1, 1, false))));
        liaison.emetteur.onAck(new AckMessage(99, List.of()));
        assertEquals(0, liaison.emetteur.acked());
        assertTrue(liaison.emetteur.drainJournal().get(0).startsWith("E-5001 paquet ignoré (acquittement)"));

        assertEquals(1, liaison.recepteur.onSnapshot(premier).size());
        assertTrue(liaison.recepteur.onSnapshot(premier).isEmpty(), "le même snapshot une seconde fois");
        assertTrue(liaison.recepteur.onSnapshot(new byte[] {(byte) MessageKinds.SNAPSHOT}).isEmpty());
        List<String> journal = liaison.recepteur.drainJournal();
        assertEquals(2, journal.size(), journal::toString);
        journal.forEach(ligne -> assertTrue(ligne.startsWith("E-5001 paquet ignoré (snapshot)"), ligne));
    }

    @Test
    @DisplayName("R-1691 : chaque côté ne garde, par assembly, que la fenêtre des bases possibles")
    void lesHistoriquesSontBornes() {
        Liaison liaison = new Liaison(20, CAP);
        for (long tick = 1; tick <= 100; tick++) {
            liaison.tick(tick, List.of(candidat(1, etat(tick, 0, false))), tick % 2 == 0);
        }
        assertEquals(SnapshotSender.WINDOW, liaison.emetteur.historySize(1));
        assertEquals(SnapshotSender.WINDOW, liaison.recepteur.historySize(1));
        liaison.recepteur.forget(1);
        assertEquals(0, liaison.recepteur.historySize(1));
    }

    @Test
    @DisplayName("La taille d'une entrée et d'un en-tête est celle que l'encodage leur donne")
    void laTailleCalculeeEstCelleDuFil() {
        Random random = new Random(1664);
        for (int essai = 0; essai < 500; essai++) {
            List<SnapshotMessage.Entry> entrees = new ArrayList<>();
            Set<Integer> handles = new HashSet<>();
            int taille = 0;
            int nombre = random.nextInt(20);
            for (int index = 0; index < nombre; index++) {
                int handle = random.nextInt(1 << (1 + random.nextInt(30)));
                if (!handles.add(handle)) {
                    continue;
                }
                AssemblyState base = etat(random.nextInt(), random.nextInt(), random.nextBoolean())
                        .with(corps(random), joints(random));
                AssemblyState cible = random.nextBoolean() ? base : etat(random.nextLong() >> 20, random.nextInt(), false)
                        .with(random.nextBoolean() ? base.bodies() : corps(random),
                                random.nextBoolean() ? base.joints() : joints(random));
                SnapshotMessage.Entry entree = random.nextBoolean()
                        ? SnapshotMessage.Entry.full(handle, cible)
                        : SnapshotMessage.Entry.delta(handle, 1 + random.nextInt(1 << 20), base, cible);
                entrees.add(entree);
                taille += SnapshotMessage.encodedSize(entree);
            }
            int sequence = (1 << 21) + random.nextInt(1 << 30);
            long tick = (random.nextLong() >>> 1) >>> random.nextInt(62);
            byte[] message = new SnapshotMessage(sequence, tick, SnapshotMessage.DESCRIPTOR_VERSION, entrees).encode();
            assertEquals(message.length, SnapshotMessage.headerSize(sequence, tick, entrees.size()) + taille);
        }
    }
}
