package dev.axion.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-169 : le rattrapage de la course de connexion de Forge 47 n'agit que sur sa signature. */
class ReadRescueTest {

    private static final long S = 1_000_000_000L;

    @Test
    @DisplayName("la signature tenue une seconde redemande la lecture, puis chaque seconde tant qu'elle dure")
    void signature() {
        ReadRescue r = new ReadRescue();
        assertFalse(r.observe(0, true, true, ReadRescue.Read.MISSING), "un instant entre deux tâches : rien");
        assertFalse(r.observe(S / 2, true, true, ReadRescue.Read.MISSING));
        assertTrue(r.observe(S, true, true, ReadRescue.Read.MISSING), "une seconde : la lecture se redemande");
        assertFalse(r.observe(S + S / 2, true, true, ReadRescue.Read.MISSING));
        assertTrue(r.observe(2 * S, true, true, ReadRescue.Read.MISSING), "toujours bloqué : encore");
        assertEquals(2, r.rescues());
    }

    @Test
    @DisplayName("lecture inscrite — normal, ou un autre mod l'a rattrapée — canal inactif ou autoRead coupé : rien")
    void horsSignature() {
        ReadRescue r = new ReadRescue();
        for (long t = 0; t < 10 * S; t += S / 4) {
            assertFalse(r.observe(t, true, true, ReadRescue.Read.REGISTERED));
            assertFalse(r.observe(t, false, true, ReadRescue.Read.MISSING), "inactif");
            assertFalse(r.observe(t, true, false, ReadRescue.Read.MISSING), "autoRead coupé à dessein");
        }
        assertEquals(0, r.rescues());
        // Une lecture rattrapée ailleurs remet le compte : la signature doit de nouveau tenir une seconde.
        assertFalse(r.observe(10 * S, true, true, ReadRescue.Read.MISSING));
        assertTrue(r.observe(11 * S, true, true, ReadRescue.Read.MISSING));
    }

    @Test
    @DisplayName("transport sans clé lisible : la lecture se redemande à partir de trois secondes d'écran, chaque seconde")
    void transportOpaque() {
        ReadRescue r = new ReadRescue();
        assertFalse(r.observe(0, true, true, ReadRescue.Read.UNKNOWN));
        assertFalse(r.observe(2 * S, true, true, ReadRescue.Read.UNKNOWN));
        assertTrue(r.observe(3 * S, true, true, ReadRescue.Read.UNKNOWN));
        assertFalse(r.observe(3 * S + S / 2, true, true, ReadRescue.Read.UNKNOWN));
        assertTrue(r.observe(4 * S, true, true, ReadRescue.Read.UNKNOWN));
        r.reset();
        assertEquals(0, r.rescues());
        assertFalse(r.observe(5 * S, true, true, ReadRescue.Read.UNKNOWN), "la connexion suivante repart de zéro");
    }
}
