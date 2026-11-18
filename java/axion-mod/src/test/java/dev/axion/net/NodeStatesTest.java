package dev.axion.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-174 D3 : l'état des nodes que les actions ont changé, et son encodage (descripteur 9). */
class NodeStatesTest {

    private static final NodeStates ETATS = new NodeStates(List.of(
            new NodeStates.State(300, false, 2.5f, new float[] {1f, 0.25f, 0.25f}),
            new NodeStates.State(4, true, Float.NaN, null, 9)));

    @Test
    @DisplayName("ADR-174 D3 : rangés par node ; un état neutre retire son node ; un node se remplace")
    void lesEtatsSeRemplacent() {
        assertEquals(List.of(4, 300), ETATS.states().stream().map(NodeStates.State::node).toList());
        NodeStates montre = ETATS.with(new NodeStates.State(4, false, Float.NaN, null, -1));
        assertNull(montre.of(4), "montré, il sort de l'état");
        assertEquals(1, montre.states().size());
        assertEquals(3.0f, ETATS.with(new NodeStates.State(300, false, 3f, null)).of(300).emissive());
        assertThrows(IllegalArgumentException.class, () -> new NodeStates(List.of(
                new NodeStates.State(1, true, Float.NaN, null), new NodeStates.State(1, false, 1f, null))), "doublon");
        assertThrows(IllegalArgumentException.class, () -> new NodeStates(List.of(
                new NodeStates.State(1, false, Float.NaN, null))), "neutre");
        assertThrows(IllegalArgumentException.class, () -> new NodeStates.State(0, false, Float.POSITIVE_INFINITY,
                null));
        assertThrows(IllegalArgumentException.class, () -> new NodeStates.State(3, true, Float.NaN, null, 3),
                "un node ne se remplace pas par lui-même");
        assertEquals(9, ETATS.of(4).swapped(), "le remplaçant se porte");
    }

    @Test
    @DisplayName("ADR-174 D3 : l'encodage se relit à l'identique, de la taille annoncée")
    void lEncodageSeRelit() throws MalformedPacketException {
        WireWriter writer = new WireWriter(64);
        ETATS.write(writer);
        byte[] octets = writer.toByteArray();
        assertEquals(ETATS.encodedSize(), octets.length);
        WireReader reader = new WireReader(octets);
        assertEquals(ETATS, NodeStates.read(reader, 7));
        reader.expectEnd();
        assertEquals(NodeStates.NONE, NodeStates.read(new WireReader(new WireWriter(1).varUInt(0).toByteArray()), 7));
    }

    @Test
    @DisplayName("R-1650 : trop de nodes, des drapeaux inconnus, une émission non finie ou un doublon : refusés")
    void unEtatMalFormeEstRefuse() {
        assertThrows(MalformedPacketException.class, () -> NodeStates.read(
                new WireReader(new WireWriter(4).varUInt(NodeStates.MAX + 1).toByteArray()), 7));
        assertThrows(MalformedPacketException.class, () -> NodeStates.read(
                new WireReader(new WireWriter(4).varUInt(1).varUInt(2).u8(8).toByteArray()), 7));
        assertThrows(MalformedPacketException.class, () -> NodeStates.read(
                new WireReader(new WireWriter(8).varUInt(1).varUInt(2).u8(2).f32(Float.NaN).toByteArray()), 7));
        assertThrows(MalformedPacketException.class, () -> NodeStates.read(new WireReader(new WireWriter(8)
                .varUInt(2).varUInt(2).u8(1).varUInt(2).u8(1).toByteArray()), 7));
    }
}
