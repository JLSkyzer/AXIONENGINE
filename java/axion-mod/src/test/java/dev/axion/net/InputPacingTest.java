package dev.axion.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.axion.seat.InputQueue;
import java.util.ArrayDeque;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-168 D3, révisé le 2026-11-14 : le client qui prédit tient lui-même la profondeur de la file de son siège. */
class InputPacingTest {

    @Test
    @DisplayName("une file trouvée vide : deux entrées au tick suivant, puis plus rien avant les entrées d'après")
    void fileVide() {
        InputPacing pacing = new InputPacing();
        pacing.onCorrection(10, 1);
        assertEquals(1, pacing.inputsThisTick(20, true));
        pacing.onCorrection(11, 0);
        assertEquals(2, pacing.inputsThisTick(20, false), "un pas de plus, même sur un tick qui bouge");
        assertEquals(1, pacing.inputsThisTick(22, true));
        pacing.onCorrection(12, 0);
        assertEquals(1, pacing.inputsThisTick(22, true), "une correction d'avant l'ajustement ne compte pas");
        pacing.onCorrection(22, 0);
        assertEquals(2, pacing.inputsThisTick(23, true), "celle d'après, si");
        assertEquals(2, pacing.added());
    }

    @Test
    @DisplayName("l'excédent d'une fenêtre se retient en entier, un envoi par tick tranquille")
    void retenue() {
        InputPacing pacing = new InputPacing();
        for (int i = 0; i < InputPacing.BASE_WINDOW - 1; i++) {
            pacing.onCorrection(i, 4);
        }
        assertEquals(1, pacing.inputsThisTick(200, true), "la fenêtre n'est pas pleine");
        pacing.onCorrection(InputPacing.BASE_WINDOW, 3);
        assertEquals(1, pacing.inputsThisTick(200, false), "une commande tapée ne se retient pas");
        assertEquals(0, pacing.inputsThisTick(200, true));
        assertEquals(0, pacing.inputsThisTick(200, true), "la plus basse était de trois : deux de trop");
        assertEquals(1, pacing.inputsThisTick(200, true));
        assertEquals(2, pacing.withheld());
    }

    @Test
    @DisplayName("un gros excédent se retient sans attendre la fenêtre")
    void grosExcedent() {
        InputPacing pacing = new InputPacing();
        for (int i = 0; i < InputPacing.FAST_WINDOW - 1; i++) {
            pacing.onCorrection(i, 10 - i);
            assertEquals(1, pacing.inputsThisTick(100, true), "pas encore assez de corrections");
        }
        pacing.onCorrection(InputPacing.FAST_WINDOW, 6);
        int retenues = 0;
        while (pacing.inputsThisTick(100, true) == 0) {
            retenues++;
        }
        assertEquals(4, retenues, "la plus basse était de six : ramenée à deux, la fenêtre fera le reste");
    }

    @Test
    @DisplayName("une fenêtre où la file n'a tenu que l'entrée du tick ne retient rien")
    void margeJuste() {
        InputPacing pacing = new InputPacing();
        for (int i = 0; i < 30 * InputPacing.BASE_WINDOW; i++) {
            pacing.onCorrection(i, i % 30 == 0 ? 1 : 3);
            assertEquals(1, pacing.inputsThisTick(100_000 + i, true));
        }
    }

    @Test
    @DisplayName("une file vide après une retenue double la fenêtre, bornée")
    void fenetreDoublee() {
        InputPacing pacing = new InputPacing();
        long sequence = 0;
        for (int cycle = 0; cycle < 8; cycle++) {
            int fenetre = pacing.window();
            for (int i = 0; i < fenetre; i++) {
                pacing.onCorrection(sequence++, 2);
            }
            assertEquals(0, pacing.inputsThisTick(sequence, true));
            // La première correction qui voit la retenue : celle de l'entrée d'après.
            pacing.onCorrection(sequence + 1, 0);
            assertEquals(2, pacing.inputsThisTick(sequence + 2, true));
            sequence += 4;
            assertEquals(Math.min(InputPacing.MAX_WINDOW, fenetre * 2), pacing.window());
        }
    }

    @Test
    @DisplayName("sur un lien qui tremble, la file réglée ne saute plus et se vide bien moins qu'une libre")
    void lienQuiTremble() {
        Bilan regle = simuler(true, 4000, 0, 0);
        Bilan libre = simuler(false, 4000, 0, 0);
        assertEquals(0, regle.sautees, "aucune entrée sautée");
        assertTrue(regle.vides < libre.vides / 4, "files vides : " + regle.vides + " réglée, " + libre.vides + " libre");
    }

    @Test
    @DisplayName("une file remplie au départ, sans correction pour la voir, revient au plus juste en quelques secondes")
    void fileRempliePendantLeSommeil() {
        Bilan regle = simuler(true, 2000, 7, 0);
        assertTrue(regle.profondeurFinale <= 3.0, "profondeur moyenne de la fin : " + regle.profondeurFinale);
        assertEquals(0, regle.sautees);
    }

    @Test
    @DisplayName("des à-coups du client — quelques ticks sans envoi, puis tous d'un coup — ne creusent pas la file")
    void acoupsDuClient() {
        Bilan regle = simuler(true, 6000, 0, 150);
        assertTrue(regle.profondeurFinale <= 3.5, "profondeur moyenne de la fin : " + regle.profondeurFinale);
        assertEquals(0, regle.sautees, "aucune rafale ne dépasse la borne d'une file réglée");
    }

    private record Bilan(long vides, long sautees, double profondeurFinale) {}

    /**
     * Le client et le serveur, tick à tick, sur un lien qui retarde chaque entrée de 2 à 5 ticks, au hasard, l'ordre
     * gardé ; les corrections reviennent en 3 ticks, seulement quand le véhicule roule.
     *
     * @param regle vrai si le client règle la cadence
     * @param ticks la durée
     * @param pleineAuDepart les entrées déjà en file quand le véhicule se met à rouler
     * @param acoups un à-coup du client tous les tant de ticks, en moyenne (0 : jamais) : 2 à 6 ticks sans envoi, puis
     *     tous d'un coup, comme Minecraft rattrape ses ticks
     */
    private static Bilan simuler(boolean regle, int ticks, int pleineAuDepart, int acoups) {
        Random hasard = new Random(168);
        InputPacing pacing = new InputPacing();
        InputQueue<Long> file = new InputQueue<>();
        ArrayDeque<long[]> enRoute = new ArrayDeque<>();
        ArrayDeque<long[]> corrections = new ArrayDeque<>();
        long sequence = 0;
        for (; sequence < pleineAuDepart; sequence++) {
            file.offer(sequence, sequence, regle);
        }
        long arrivee = 0;
        long vides = 0;
        int enRetard = 0;
        int tardifs = 0;
        int gel = 0;
        double profondeurs = 0;
        int mesures = 0;
        for (int tick = 0; tick < ticks; tick++) {
            while (!corrections.isEmpty() && corrections.peekFirst()[0] <= tick) {
                long[] c = corrections.removeFirst();
                pacing.onCorrection(c[1], (int) c[2]);
            }
            tardifs = 0;
            if (gel == 0 && acoups > 0 && hasard.nextInt(acoups) == 0) {
                gel = 2 + hasard.nextInt(5);
            }
            int envois;
            if (gel > 0) {
                gel--;
                enRetard++;
                envois = 0;
            } else {
                envois = 0;
                for (int rattrape = 0; rattrape <= enRetard; rattrape++) {
                    envois += regle ? pacing.inputsThisTick(sequence + envois, true) : 1;
                }
                tardifs = enRetard;
                enRetard = 0;
            }
            boolean rattrapage = envois > 0 && gel == 0 && tardifs > 0;
            for (int i = 0; i < envois; i++) {
                if (rattrapage && regle) {
                    pacing.sentLate(sequence);
                }
                arrivee = Math.max(arrivee, tick + 2 + hasard.nextInt(4));
                enRoute.addLast(new long[] {arrivee, sequence++});
            }
            while (!enRoute.isEmpty() && enRoute.peekFirst()[0] <= tick) {
                long[] e = enRoute.removeFirst();
                file.offer(e[1], e[1], regle);
            }
            InputQueue.Step<Long> pas = file.next();
            if (pas == null && tick > 200) {
                vides++;
            }
            if (tick >= ticks - 500) {
                profondeurs += file.lastDepth();
                mesures++;
            }
            if (file.lastApplied() >= 0) {
                corrections.addLast(new long[] {tick + 3, file.lastApplied(), file.lastDepth()});
            }
        }
        return new Bilan(vides, file.skippedTotal(), profondeurs / mesures);
    }
}
