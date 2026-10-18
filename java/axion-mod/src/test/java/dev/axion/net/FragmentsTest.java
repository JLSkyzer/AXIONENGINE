package dev.axion.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fragmentation ordonnée (R-1651, ADR-150) : aucune trame ne dépasse sa borne, tout message se
 * reconstitue, et une trame fautive est refusée sans contaminer la suite (R-1650, T-422).
 */
class FragmentsTest {

    private static byte[] message(int size, long seed) {
        byte[] message = new byte[size];
        new Random(seed).nextBytes(message);
        return message;
    }

    /** Passe les trames dans l'ordre, et exige que seule la dernière rende le message. */
    private static byte[] reassemble(Fragments.Reassembler reassembler, List<byte[]> frames)
            throws MalformedPacketException {
        byte[] result = null;
        for (int index = 0; index < frames.size(); index++) {
            byte[] out = reassembler.accept(frames.get(index));
            if (index < frames.size() - 1) {
                assertNull(out, "message rendu avant son dernier fragment");
            } else {
                result = out;
            }
        }
        return result;
    }

    private static byte[] fragment(int sequence, int index, int count, byte[] piece) {
        return new WireWriter().u8(Fragments.FLAG_FRAGMENT).varUInt(sequence).varUInt(index).varUInt(count)
                .raw(piece).toByteArray();
    }

    @Test
    @DisplayName("Un message tient en une trame jusqu'à la borne, et se relit tel quel")
    void unMessageCourtTientEnUneTrame() throws MalformedPacketException {
        for (int size : new int[] {1, 2, 1000, NetProtocol.MAX_FRAME_BYTES - 1}) {
            byte[] message = message(size, size);
            List<byte[]> frames = Fragments.split(message, 0);
            assertEquals(1, frames.size(), "taille " + size);
            assertTrue(frames.get(0).length <= NetProtocol.MAX_FRAME_BYTES);
            assertArrayEquals(message, new Fragments.Reassembler().accept(frames.get(0)));
        }
    }

    @Test
    @DisplayName("Au-delà, il se fragmente sans qu'aucune trame dépasse la borne, jusqu'au plus grand message")
    void unMessageLongSeFragmenteSousLaBorne() throws MalformedPacketException {
        int[] sizes = {
            NetProtocol.MAX_FRAME_BYTES,
            3 * Fragments.FRAGMENT_PAYLOAD_BYTES + 7,
            Fragments.MAX_MESSAGE_BYTES
        };
        for (int size : sizes) {
            byte[] message = message(size, size);
            List<byte[]> frames = Fragments.split(message, 41);
            assertEquals((size + Fragments.FRAGMENT_PAYLOAD_BYTES - 1) / Fragments.FRAGMENT_PAYLOAD_BYTES,
                    frames.size(), "taille " + size);
            assertTrue(frames.size() >= 2 && frames.size() <= Fragments.MAX_FRAGMENTS);
            for (byte[] frame : frames) {
                assertTrue(frame.length <= NetProtocol.MAX_FRAME_BYTES, "trame de " + frame.length);
            }
            assertArrayEquals(message, reassemble(new Fragments.Reassembler(), frames), "taille " + size);
        }
    }

    @Test
    @DisplayName("Un message vide ou trop grand est une faute de l'émetteur")
    void unMessageHorsBornesEstRefuseALEmission() {
        assertThrows(IllegalArgumentException.class, () -> Fragments.split(new byte[0], 0));
        assertThrows(IllegalArgumentException.class,
                () -> Fragments.split(new byte[Fragments.MAX_MESSAGE_BYTES + 1], 0));
        assertThrows(IllegalArgumentException.class, () -> Fragments.split(new byte[1], -1));
    }

    @Test
    @DisplayName("Des messages entiers et fragmentés se suivent sur une même connexion")
    void desMessagesSeSuivent() throws MalformedPacketException {
        Fragments.Reassembler reassembler = new Fragments.Reassembler();
        byte[] first = message(2 * NetProtocol.MAX_FRAME_BYTES, 1);
        byte[] second = message(17, 2);
        byte[] third = message(5 * Fragments.FRAGMENT_PAYLOAD_BYTES, 3);
        assertArrayEquals(first, reassemble(reassembler, Fragments.split(first, 0)));
        assertArrayEquals(second, reassemble(reassembler, Fragments.split(second, 1)));
        assertArrayEquals(third, reassemble(reassembler, Fragments.split(third, 2)));
        assertFalse(reassembler.inProgress());
    }

    @Test
    @DisplayName("T-422 : une trame au-delà de la borne, vide, ou aux drapeaux inconnus est refusée")
    void t422_uneTrameMalFormeeEstRefusee() {
        Fragments.Reassembler reassembler = new Fragments.Reassembler();
        assertThrows(MalformedPacketException.class,
                () -> reassembler.accept(new byte[NetProtocol.MAX_FRAME_BYTES + 1]));
        assertThrows(MalformedPacketException.class, () -> reassembler.accept(new byte[0]));
        assertThrows(MalformedPacketException.class, () -> reassembler.accept(new byte[] {0}));
        assertThrows(MalformedPacketException.class, () -> reassembler.accept(new byte[] {2, 1}));
        assertThrows(MalformedPacketException.class, () -> reassembler.accept(new byte[] {(byte) 0xFF, 1}));
    }

    @Test
    @DisplayName("T-422 : un nombre de fragments hors de [2, 256], ou un index hors du message, est refusé")
    void t422_unNombreDeFragmentsHorsBornesEstRefuse() {
        byte[] piece = {1};
        for (int count : new int[] {0, 1, Fragments.MAX_FRAGMENTS + 1}) {
            assertThrows(MalformedPacketException.class,
                    () -> new Fragments.Reassembler().accept(fragment(0, 0, count, piece)), "count " + count);
        }
        assertThrows(MalformedPacketException.class,
                () -> new Fragments.Reassembler().accept(fragment(0, 3, 3, piece)));
        assertThrows(MalformedPacketException.class,
                () -> new Fragments.Reassembler().accept(fragment(0, 0, 2, new byte[0])));
    }

    @Test
    @DisplayName("T-422 : un fragment hors d'ordre, sans début, ou d'un autre message est refusé, et le message abandonné")
    void t422_unFragmentHorsDeSaPlaceEstRefuse() throws MalformedPacketException {
        byte[] piece = {9, 9};

        Fragments.Reassembler outOfOrder = new Fragments.Reassembler();
        assertNull(outOfOrder.accept(fragment(4, 0, 3, piece)));
        assertThrows(MalformedPacketException.class, () -> outOfOrder.accept(fragment(4, 2, 3, piece)));
        assertFalse(outOfOrder.inProgress(), "le message fautif reste en cours");

        assertThrows(MalformedPacketException.class,
                () -> new Fragments.Reassembler().accept(fragment(4, 1, 3, piece)));

        Fragments.Reassembler otherSequence = new Fragments.Reassembler();
        otherSequence.accept(fragment(4, 0, 3, piece));
        assertThrows(MalformedPacketException.class, () -> otherSequence.accept(fragment(5, 1, 3, piece)));

        Fragments.Reassembler otherCount = new Fragments.Reassembler();
        otherCount.accept(fragment(4, 0, 3, piece));
        assertThrows(MalformedPacketException.class, () -> otherCount.accept(fragment(4, 1, 4, piece)));

        Fragments.Reassembler restarted = new Fragments.Reassembler();
        restarted.accept(fragment(4, 0, 3, piece));
        assertThrows(MalformedPacketException.class, () -> restarted.accept(fragment(5, 0, 3, piece)));

        Fragments.Reassembler interleaved = new Fragments.Reassembler();
        interleaved.accept(fragment(4, 0, 3, piece));
        assertThrows(MalformedPacketException.class, () -> interleaved.accept(new byte[] {0, 42}));
    }

    @Test
    @DisplayName("T-422 : des fragments aux en-têtes courts et pleins ne font pas dépasser la borne d'un message")
    void t422_unMessageAuDelaDeSaBorneEstRefuse() {
        // Un émetteur hostile raccourcit l'en-tête (séquence 0, petits index) pour loger plus que
        // FRAGMENT_PAYLOAD_BYTES par trame : 256 trames pleines dépassent MAX_MESSAGE_BYTES (R-1691).
        Fragments.Reassembler reassembler = new Fragments.Reassembler();
        int count = Fragments.MAX_FRAGMENTS;
        MalformedPacketException refused = null;
        for (int index = 0; index < count && refused == null; index++) {
            int header = new WireWriter().u8(Fragments.FLAG_FRAGMENT).varUInt(0).varUInt(index).varUInt(count)
                    .size();
            byte[] frame = fragment(0, index, count, new byte[NetProtocol.MAX_FRAME_BYTES - header]);
            assertEquals(NetProtocol.MAX_FRAME_BYTES, frame.length);
            try {
                assertNull(reassembler.accept(frame), "message rendu au-delà de sa borne");
            } catch (MalformedPacketException error) {
                refused = error;
            }
        }
        assertTrue(refused != null, "256 trames pleines acceptées au-delà de " + Fragments.MAX_MESSAGE_BYTES);
        assertFalse(reassembler.inProgress());
    }

    @Test
    @DisplayName("T-422 : après une trame fautive, le message suivant se reconstitue")
    void t422_uneFauteNeContaminePasLaSuite() throws MalformedPacketException {
        Fragments.Reassembler reassembler = new Fragments.Reassembler();
        byte[] message = message(3 * NetProtocol.MAX_FRAME_BYTES, 7);
        List<byte[]> frames = Fragments.split(message, 9);
        reassembler.accept(frames.get(0));
        assertThrows(MalformedPacketException.class, () -> reassembler.accept(frames.get(2)));
        assertArrayEquals(message, reassemble(reassembler, frames));
    }

    @Test
    @DisplayName("T-422 : des trames quelconques ne lèvent que des fautes de paquet, jamais autre chose")
    void t422_desTramesQuelconquesNeLeventQueDesFautesDePaquet() {
        Random random = new Random(0xF4A9_0422L);
        Fragments.Reassembler reassembler = new Fragments.Reassembler();
        int faults = 0;
        for (int round = 0; round < 20_000; round++) {
            byte[] frame;
            if (random.nextBoolean()) {
                frame = new byte[random.nextInt(40)];
                random.nextBytes(frame);
            } else {
                // Des en-têtes plausibles, aux valeurs tirées : ce que le hasard pur atteint rarement.
                List<Integer> header = new ArrayList<>(List.of(random.nextInt(3), random.nextInt(4),
                        random.nextInt(6), random.nextInt(260)));
                WireWriter writer = new WireWriter().u8(header.get(0)).varUInt(header.get(1))
                        .varUInt(header.get(2)).varUInt(header.get(3));
                frame = writer.raw(message(random.nextInt(8), round)).toByteArray();
            }
            try {
                reassembler.accept(frame);
            } catch (MalformedPacketException expected) {
                faults++;
            } catch (RuntimeException | Error other) {
                fail("trame " + java.util.Arrays.toString(frame) + " : " + other);
            }
        }
        assertTrue(faults > 0, "aucune faute vue : le test ne sollicite pas la validation");
    }
}
