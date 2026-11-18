package dev.axion.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-171 D7, Q8 : l'état d'un ensemble prédit, le véhicule puis ses membres. */
class EnsembleStateTest {

    private static EnsembleState.Member membre(int entite, int... octets) {
        byte[] etat = new byte[octets.length];
        for (int i = 0; i < octets.length; i++) {
            etat[i] = (byte) octets[i];
        }
        return new EnsembleState.Member(entite, etat);
    }

    @Test
    @DisplayName("un ensemble fait l'aller-retour, ordre et états compris")
    void allerRetour() throws MalformedPacketException {
        List<EnsembleState.Member> ensemble = List.of(membre(12, 1, 2, 3), membre(40), membre(7, 9));
        List<EnsembleState.Member> relu = EnsembleState.decode(EnsembleState.encode(ensemble));
        assertEquals(3, relu.size());
        for (int i = 0; i < 3; i++) {
            assertEquals(ensemble.get(i).entity(), relu.get(i).entity());
            assertArrayEquals(ensemble.get(i).state(), relu.get(i).state());
        }
    }

    @Test
    @DisplayName("tronqué, trop de membres ou un octet de trop : refusé")
    void malForme() {
        byte[] bon = EnsembleState.encode(List.of(membre(12, 1, 2, 3)));
        assertThrows(MalformedPacketException.class, () -> EnsembleState.decode(Arrays.copyOf(bon, bon.length - 1)));
        assertThrows(MalformedPacketException.class, () -> EnsembleState.decode(Arrays.copyOf(bon, bon.length + 1)));
        assertThrows(MalformedPacketException.class,
                () -> EnsembleState.decode(new WireWriter().varUInt(EnsembleState.MAX_MEMBERS + 1).toByteArray()));
        List<EnsembleState.Member> trop = new ArrayList<>();
        for (int i = 0; i <= EnsembleState.MAX_MEMBERS; i++) {
            trop.add(membre(i));
        }
        assertThrows(IllegalArgumentException.class, () -> EnsembleState.encode(trop));
    }

    @Test
    @DisplayName("proches : mêmes membres, même ordre, chaque état proche ; sinon la correction se reprend")
    void tolerance() {
        Prediction.Tolerance ensemble = EnsembleState.tolerance(Prediction.Tolerance.EXACT);
        byte[] predit = EnsembleState.encode(List.of(membre(12, 1, 2), membre(40, 5)));
        assertTrue(ensemble.close(predit, EnsembleState.encode(List.of(membre(12, 1, 2), membre(40, 5)))));
        assertFalse(ensemble.close(predit, EnsembleState.encode(List.of(membre(12, 1, 2), membre(40, 6)))),
                "la remorque s'écarte");
        assertFalse(ensemble.close(predit, EnsembleState.encode(List.of(membre(12, 1, 2)))), "la remorque détachée");
        assertFalse(ensemble.close(predit, EnsembleState.encode(List.of(membre(40, 5), membre(12, 1, 2)))),
                "un autre ordre");
        assertFalse(ensemble.close(predit, new byte[] {9}), "un état illisible");
        assertFalse(ensemble.close(null, predit), "rien de prédit encore pour cette séquence");
        assertFalse(ensemble.close(predit, null), "rien du serveur");
    }
}
