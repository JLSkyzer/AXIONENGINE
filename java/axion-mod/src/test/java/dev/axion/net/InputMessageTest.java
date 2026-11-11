package dev.axion.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.axion.definition.Seating;
import dev.axion.seat.VehicleInput;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-158 D4, T-442 : le message des entrées, son bornage, et ce que le serveur en écarte. */
class InputMessageTest {

    private static final VehicleInput ENTREE = new VehicleInput(0.5f, 0.25f, -0.75f, 0.125f, 1f, (byte) -1, 3);

    private static Seating.Seat siege(Seating.Control... controles) {
        int bits = 0;
        for (Seating.Control controle : controles) {
            bits |= controle.bit();
        }
        return new Seating.Seat("conducteur", 1, true, List.of(), bits);
    }

    private static byte[] trame(InputMessage message) {
        return Fragments.split(message.encode(), 0).get(0);
    }

    @Test
    @DisplayName("ADR-158 D4 : le message se relit à l'identique, et sa taille est celle annoncée")
    void allerRetour() throws MalformedPacketException {
        InputMessage message = new InputMessage(0xFFFF_FFFE, 300, 15, ENTREE);
        byte[] octets = message.encode();
        assertEquals(1 + 4 + 2 + 1 + 5 * 4 + 1 + 1, octets.length, "genre, séquence, entité, siège, 22 octets d'entrées");
        assertEquals(MessageKinds.INPUT, octets[0]);
        assertEquals(message, InputMessage.decode(octets));
    }

    @Test
    @DisplayName("T-442 : un message tronqué, prolongé ou d'un autre genre est mal formé")
    void malForme() {
        byte[] octets = new InputMessage(1, 2, 0, ENTREE).encode();
        assertThrows(MalformedPacketException.class, () -> InputMessage.decode(Arrays.copyOf(octets, octets.length - 1)));
        assertThrows(MalformedPacketException.class, () -> InputMessage.decode(Arrays.copyOf(octets, octets.length + 1)));
        byte[] genre = octets.clone();
        genre[0] = (byte) MessageKinds.ACK;
        assertThrows(MalformedPacketException.class, () -> InputMessage.decode(genre));
    }

    @Test
    @DisplayName("T-442, R-1110 : une valeur non finie vaut zéro, les autres sont bornées")
    void bornage() {
        Seating.Seat tout = siege(Seating.Control.values());
        VehicleInput fou = new VehicleInput(Float.NaN, 7f, -9f, Float.POSITIVE_INFINITY, -2f, (byte) 4, 0xFF);
        assertFalse(fou.finite());
        assertEquals(new VehicleInput(0f, 1f, -1f, 0f, 0f, (byte) 4, 0), fou.sanitized(tout), "et aucun drapeau n'est défini");
        assertTrue(ENTREE.finite());
        assertEquals(new VehicleInput(0.5f, 0.25f, -0.75f, 0.125f, 1f, (byte) -1, 0), ENTREE.sanitized(tout));
        assertEquals(new VehicleInput(0f, 0f, 1f, 0f, 0f, (byte) 0, 0),
                new VehicleInput(-1f, -1f, 3f, -1f, -1f, (byte) 0, 0).sanitized(tout));
    }

    @Test
    @DisplayName("T-442 : un contrôle que le siège ne déclare pas vaut zéro")
    void controleNonDeclare() {
        assertEquals(new VehicleInput(0.5f, 0f, -0.75f, 0f, 0f, (byte) 0, 0),
                ENTREE.sanitized(siege(Seating.Control.THROTTLE, Seating.Control.STEER)));
        assertEquals(VehicleInput.NONE, ENTREE.sanitized(siege()));
    }

    @Test
    @DisplayName("ADR-158 D4 : le clavier accélère, freine, tourne à droite en positif, et serre le frein à main")
    void clavier() {
        assertEquals(new VehicleInput(1f, 0f, -1f, 0f, 0f, (byte) 0, 0), VehicleInput.ofKeys(1f, 1f, false));
        assertEquals(new VehicleInput(0f, 1f, 1f, 0f, 1f, (byte) 0, 0), VehicleInput.ofKeys(-1f, -1f, true));
        assertEquals(new VehicleInput(1f, 0f, 0f, 0f, 0f, (byte) -1, 0), VehicleInput.ofKeys(1f, 0f, false, (byte) -1),
                "ADR-159 D6 : le rapport des touches de boîte, l'embrayage automatique");
    }

    @Test
    @DisplayName("T-442 : une séquence qui ne croît pas s'écarte, comparée sans signe, et ne se journalise qu'une fois")
    void sequence() {
        PlayerSync serveur = new PlayerSync(new SnapshotSender(10, 32_768), 1000);
        serveur.onClientFrame(trame(new InputMessage(5, 2, 0, ENTREE)));
        serveur.onClientFrame(trame(new InputMessage(5, 2, 0, ENTREE)));
        serveur.onClientFrame(trame(new InputMessage(4, 2, 0, ENTREE)));
        serveur.onClientFrame(trame(new InputMessage(0xFFFF_FFFF, 2, 0, ENTREE)));
        serveur.onClientFrame(trame(new InputMessage(6, 2, 0, ENTREE)));

        List<InputMessage> retenues = serveur.drainInputs();
        assertEquals(List.of(5, 0xFFFF_FFFF), retenues.stream().map(InputMessage::sequence).toList(),
                "0xFFFFFFFF est la plus grande : 6 ne croît plus");
        assertTrue(serveur.drainInputs().isEmpty(), "rendues une fois");
        List<String> journal = serveur.drainJournal();
        assertEquals(1, journal.size(), journal.toString());
        assertTrue(journal.get(0).contains("séquence"), journal.toString());
    }

    @Test
    @DisplayName("T-442 : un message mal formé ne lève rien, et les fautes se journalisent une fois par cause")
    void fautes() {
        PlayerSync serveur = new PlayerSync(new SnapshotSender(10, 32_768), 1000);
        byte[] coupe = Arrays.copyOf(new InputMessage(1, 2, 0, ENTREE).encode(), 6);
        serveur.onClientFrame(Fragments.split(coupe, 0).get(0));
        serveur.onClientFrame(Fragments.split(coupe, 0).get(0));
        assertTrue(serveur.drainInputs().isEmpty());
        assertEquals(1, serveur.drainJournal().size());
        serveur.reportInput("siège");
        serveur.reportInput("siège");
        serveur.reportInput("autre");
        assertEquals(2, serveur.drainJournal().size());
    }

    @Test
    @DisplayName("R-1690 : les entrées comptent au plafond de paquets du joueur, avec les acquittements")
    void plafond() {
        PlayerSync serveur = new PlayerSync(new SnapshotSender(10, 32_768), 3);
        for (int sequence = 1; sequence <= 5; sequence++) {
            serveur.onClientFrame(trame(new InputMessage(sequence, 2, 0, ENTREE)));
        }
        assertEquals(List.of(1, 2, 3), serveur.drainInputs().stream().map(InputMessage::sequence).toList());
    }

    @Test
    @DisplayName("ADR-158 D4 : la session du client numérote ses entrées en croissant")
    void sessionNumerote() throws MalformedPacketException {
        assertEquals(0, numero(0));
        assertEquals(1, numero(1));
    }

    private static int numero(int rang) throws MalformedPacketException {
        ClientSession session = new ClientSession(null, null, null, null, null);
        byte[] frame = null;
        for (int i = 0; i <= rang; i++) {
            frame = session.inputFrame(2, 0, VehicleInput.NONE);
        }
        Fragments.Reassembler assembleur = new Fragments.Reassembler();
        return InputMessage.decode(assembleur.accept(frame)).sequence();
    }
}
