package dev.axion.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-174 D2 : {@code S2C_Event}, les événements de contenu d'un tick vers un joueur (protocole 13). */
class EventMessageTest {

    @Test
    @DisplayName("ADR-174 D2 : un message d'événements se relit à l'identique, dans l'ordre")
    void unMessageSeRelit() throws MalformedPacketException {
        EventMessage message = new EventMessage(List.of(new EventMessage.Event(42, 0, 1.0),
                new EventMessage.Event(300, 3, -2.5), new EventMessage.Event(42, 1, 0.0)));
        byte[] octets = message.encode();
        assertEquals(MessageKinds.EVENT, octets[0]);
        assertEquals(message, EventMessage.decode(octets));
    }

    @Test
    @DisplayName("R-1650 : un genre étranger, aucun événement, trop d'événements, une valeur non finie, une queue : refusés")
    void unMessageMalFormeEstRefuse() {
        byte[] bon = new EventMessage(List.of(new EventMessage.Event(1, 0, 1.0))).encode();
        byte[] autreGenre = bon.clone();
        autreGenre[0] = (byte) MessageKinds.SNAPSHOT;
        assertThrows(MalformedPacketException.class, () -> EventMessage.decode(autreGenre));
        assertThrows(MalformedPacketException.class,
                () -> EventMessage.decode(new WireWriter(4).u8(MessageKinds.EVENT).varUInt(0).toByteArray()));
        assertThrows(MalformedPacketException.class, () -> EventMessage.decode(new WireWriter(4).u8(MessageKinds.EVENT)
                .varUInt(EventMessage.MAX_EVENTS + 1).toByteArray()));
        assertThrows(MalformedPacketException.class, () -> EventMessage.decode(new WireWriter(16)
                .u8(MessageKinds.EVENT).varUInt(1).varUInt(1).varUInt(0).f64(Double.NaN).toByteArray()));
        byte[] queue = java.util.Arrays.copyOf(bon, bon.length + 1);
        assertThrows(MalformedPacketException.class, () -> EventMessage.decode(queue));
        assertThrows(IllegalArgumentException.class, () -> new EventMessage(List.of()));
    }

    @Test
    @DisplayName("ADR-174 D2 : un joueur reçoit au plus la borne par tick ; le reste se compte, et la file se vide")
    void laBorneParJoueurEtParTick() throws MalformedPacketException {
        PlayerSync joueur = new PlayerSync(new SnapshotSender(10, 32_768), 1000);
        assertTrue(joueur.eventFrames(1).isEmpty(), "rien à envoyer");
        for (int i = 0; i < 3; i++) {
            assertTrue(joueur.event(new EventMessage.Event(7, i, i), 3));
        }
        assertFalse(joueur.event(new EventMessage.Event(7, 3, 3), 3), "au-delà de la borne");
        assertEquals(1, joueur.eventsDropped());
        List<byte[]> trames = joueur.eventFrames(1);
        assertEquals(1, trames.size());
        assertEquals(3, joueur.eventsSent());
        assertTrue(joueur.eventFrames(2).isEmpty(), "la file est vidée par l'envoi");
    }
}
