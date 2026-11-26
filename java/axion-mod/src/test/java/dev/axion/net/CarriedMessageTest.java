package dev.axion.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-185 : la place que le joueur porté envoie, son codage et sa réception au serveur. */
class CarriedMessageTest {

    private static byte[] trame(byte[] message) {
        return Fragments.split(message, 0).get(0);
    }

    private static byte[] place(CarriedMessage message) {
        return trame(message.encode());
    }

    @Test
    @DisplayName("ADR-185 D1 : la place fait l'aller-retour au 1/256 de bloc, en 10 octets près de l'origine du corps")
    void adr185AllerRetour() throws MalformedPacketException {
        CarriedMessage proche = CarriedMessage.of(1200, 0, new double[] {0.5, 1.0, -2.25});
        byte[] octets = proche.encode();
        assertEquals(MessageKinds.CARRIED, octets[0]);
        assertEquals(10, octets.length, "genre, identifiant (2), corps, trois places (2 chacune)");
        assertEquals(proche, CarriedMessage.decode(octets));
        CarriedMessage loin = CarriedMessage.of(7, AssemblyState.MAX_BODIES, new double[] {-200.126, 0.001, 300.5});
        assertArrayEquals(new double[] {-200.126, 0.001, 300.5}, CarriedMessage.decode(loin.encode()).place().local(),
                0.5 / 256);
    }

    @Test
    @DisplayName("ADR-185 : un message mal formé est refusé (R-1650)")
    void adr185MalForme() {
        byte[] sain = CarriedMessage.of(3, 0, new double[3]).encode();
        byte[] corps = sain.clone();
        corps[2] = (byte) (AssemblyState.MAX_BODIES + 1);
        assertThrows(MalformedPacketException.class, () -> CarriedMessage.decode(corps), "corps hors borne");
        assertThrows(MalformedPacketException.class, () -> CarriedMessage.decode(Arrays.copyOf(sain, sain.length - 1)));
        assertThrows(MalformedPacketException.class, () -> CarriedMessage.decode(Arrays.copyOf(sain, sain.length + 1)));
        assertThrows(MalformedPacketException.class, () -> CarriedMessage.decode(new AckMessage(0, List.of()).encode()),
                "autre genre");
    }

    @Test
    @DisplayName("ADR-185 D2 : la dernière place reçue se garde, et date en ticks du serveur")
    void adr185GardeeEtDatee() {
        PlayerSync joueur = new PlayerSync(new SnapshotSender(10, 32_768), 40);
        assertNull(joueur.carried(5), "rien reçu");
        CarriedMessage avant = CarriedMessage.of(1, 0, new double[] {1, 0, 0});
        CarriedMessage apres = CarriedMessage.of(1, 0, new double[] {2, 0, 0});
        joueur.onClientFrame(place(avant));
        joueur.onClientFrame(place(apres));
        for (int tick = 0; tick < 4; tick++) {
            assertEquals(apres.place(), joueur.carried(5), "reçue il y a " + tick + " tick(s)");
            joueur.tick(tick, handle -> null, tick);
        }
        assertEquals(apres.place(), joueur.carried(5));
        joueur.tick(4, handle -> null, 4);
        assertNull(joueur.carried(5), "vieille de cinq ticks : plus");
        assertTrue(joueur.drainJournal().isEmpty());
    }

    @Test
    @DisplayName("R-1690, ADR-185 : les places portées se comptent à part, sous le même plafond")
    void adr185ComptesAPart() {
        PlayerSync joueur = new PlayerSync(new SnapshotSender(10, 32_768), 3);
        for (int i = 0; i < 3; i++) {
            joueur.onClientFrame(place(CarriedMessage.of(1, 0, new double[] {i, 0, 0})));
            joueur.onClientFrame(trame(new AckMessage(0, List.of()).encode()));
        }
        joueur.tick(0, handle -> null, 0);
        assertEquals(0, joueur.lastTickLimited(), "trois places et trois accusés : ni les uns ni les autres au-delà");
        assertArrayEquals(new double[] {2, 0, 0}, joueur.carried(5).local(), 1e-9);
        joueur.onClientFrame(place(CarriedMessage.of(1, 0, new double[] {9, 0, 0})));
        joueur.tick(1, handle -> null, 1);
        assertEquals(1, joueur.lastTickLimited(), "la quatrième place dépasse son plafond");
        assertArrayEquals(new double[] {2, 0, 0}, joueur.carried(5).local(), 1e-9, "ignorée");
    }
}
