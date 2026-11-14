package dev.axion.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.axion.physics.SimCommandStream;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-168 D4 : {@code S2C_Correction}, son aller-retour et ses bornes (R-1650). */
class CorrectionMessageTest {

    private static byte[] bloc(int octets, int graine) {
        byte[] bloc = new byte[octets];
        for (int i = 0; i < octets; i++) {
            bloc[i] = (byte) (graine + 7 * i);
        }
        return bloc;
    }

    private static CorrectionMessage exemple() {
        double[] valeurs = {1.0, 0.0, 0.1, 42.5};
        return new CorrectionMessage(123_456L, 0xFFFF_FFF0L, 3, 300, 120_000L,
                CorrectionMessage.Variables.of(valeurs, 0b1101L),
                List.of(new CorrectionMessage.State(300, bloc(264, 3)), new CorrectionMessage.State(301, bloc(80, 9))));
    }

    @Test
    @DisplayName("D4 : la correction se relit à l'identique, variables en pleine précision, blocs intacts")
    void allerRetour() throws MalformedPacketException {
        CorrectionMessage message = exemple();
        byte[] octets = message.encode();
        assertEquals(MessageKinds.CORRECTION, octets[0]);
        CorrectionMessage relu = CorrectionMessage.decode(octets);
        assertEquals(message, relu);
        assertEquals(0.1, relu.variables().value(2), "un f64 : 0,1 n'est pas arrondi en f32");
        assertFalse(relu.variables().has(1), "absente du masque");
        assertEquals(0xFFFF_FFF0L, relu.appliedSequence(), "séquence non signée");
        assertEquals(3, relu.queued(), "ADR-168 D3 : la profondeur de la file, que le client règle");
        assertTrue(Arrays.equals(bloc(264, 3), relu.states().get(0).bytes()));
    }

    @Test
    @DisplayName("D4 : sans entrée appliquée, la séquence est absente du fil")
    void sansEntree() throws MalformedPacketException {
        CorrectionMessage message = new CorrectionMessage(5L, -1L, -1, 7, 0L, CorrectionMessage.Variables.NONE,
                List.of(new CorrectionMessage.State(7, bloc(16, 1))));
        byte[] octets = message.encode();
        assertEquals(1 + 1 + 1 + 1 + 1 + 1 + 1 + 1 + 1 + 16, octets.length,
                "genre, tick, drapeaux, entité, tick des paramètres, variables, états, entité, longueur, bloc");
        CorrectionMessage relu = CorrectionMessage.decode(octets);
        assertFalse(relu.applied());
        assertEquals(message, relu);
    }

    @Test
    @DisplayName("R-1650 : drapeau inconnu, bloc qui n'est pas un multiple de 8, compte d'états trop grand, octets en trop")
    void bornes() {
        byte[] sain = exemple().encode();

        byte[] drapeau = sain.clone();
        drapeau[1 + 3] = 3; // tick de trois octets, puis les drapeaux
        assertThrows(MalformedPacketException.class, () -> CorrectionMessage.decode(drapeau));

        byte[] impair = new WireWriter(32).u8(MessageKinds.CORRECTION).varULong(1).u8(0).varUInt(7).varULong(0)
                .varUInt(0).varUInt(1).varUInt(7).bytes(new byte[12]).toByteArray();
        assertThrows(MalformedPacketException.class, () -> CorrectionMessage.decode(impair));

        byte[] tropGrand = new WireWriter(32).u8(MessageKinds.CORRECTION).varULong(1).u8(0).varUInt(7).varULong(0)
                .varUInt(0).varUInt(SimCommandStream.MAX_ASSEMBLY_STATE_BYTES).toByteArray();
        assertThrows(MalformedPacketException.class, () -> CorrectionMessage.decode(tropGrand));

        byte[] bloque = new WireWriter(32).u8(MessageKinds.CORRECTION).varULong(1).u8(0).varUInt(7).varULong(0)
                .varUInt(0).varUInt(1).varUInt(7).varUInt(SimCommandStream.MAX_ASSEMBLY_STATE_BYTES + 8).toByteArray();
        assertThrows(MalformedPacketException.class, () -> CorrectionMessage.decode(bloque));

        byte[] enTrop = Arrays.copyOf(sain, sain.length + 1);
        assertThrows(MalformedPacketException.class, () -> CorrectionMessage.decode(enTrop));

        assertThrows(IllegalArgumentException.class, () -> new CorrectionMessage(5L, 4L, 256, 7, 0L,
                CorrectionMessage.Variables.NONE, List.of(new CorrectionMessage.State(7, bloc(16, 1)))),
                "une profondeur tient dans un octet");
        assertThrows(IllegalArgumentException.class, () -> new CorrectionMessage(5L, -1L, 0, 7, 0L,
                CorrectionMessage.Variables.NONE, List.of(new CorrectionMessage.State(7, bloc(16, 1)))),
                "pas de profondeur sans entrée appliquée");

        byte[] sansEtat = new WireWriter(32).u8(MessageKinds.CORRECTION).varULong(1).u8(0).varUInt(7).varULong(0)
                .varUInt(0).varUInt(0).toByteArray();
        assertThrows(MalformedPacketException.class, () -> CorrectionMessage.decode(sansEtat), "le véhicule au moins");
    }

    @Test
    @DisplayName("D4 : les variables gardent le masque, et refusent une valeur non finie")
    void variables() {
        CorrectionMessage.Variables variables = CorrectionMessage.Variables.of(new double[] {3.0, 4.0}, 0b10L);
        assertFalse(variables.has(0));
        assertEquals(0.0, variables.value(0), "absente : à zéro");
        assertEquals(4.0, variables.value(1));
        assertThrows(IllegalArgumentException.class,
                () -> new CorrectionMessage.Variables(1, 1L, new double[] {Double.NaN}));
        assertThrows(IllegalArgumentException.class,
                () -> new CorrectionMessage.Variables(1, 0b10L, new double[] {0.0}));
    }
}
