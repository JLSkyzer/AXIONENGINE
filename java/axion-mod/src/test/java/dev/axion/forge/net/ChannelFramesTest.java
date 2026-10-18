package dev.axion.forge.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.axion.net.NetProtocol;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-422 — les décodeurs Forge du canal ne lèvent jamais (R-1650) : une exception y remonterait jusqu'au
 * pipeline Netty et fermerait la connexion. Ils copient la trame, bornée, et la session la juge.
 */
class ChannelFramesTest {

    private static FriendlyByteBuf buffer(byte[] content) {
        return new FriendlyByteBuf(Unpooled.wrappedBuffer(content));
    }

    @Test
    @DisplayName("Chaque message porte sa trame à l'identique")
    void chaqueMessagePorteSaTrame() {
        byte[] trame = {0, 1, 2, 3, (byte) 0xFF};

        FriendlyByteBuf versClient = new FriendlyByteBuf(Unpooled.buffer());
        new ChannelFrames.LoginToClient(trame).encode(versClient);
        assertArrayEquals(trame, ChannelFrames.LoginToClient.decode(versClient).frame());

        FriendlyByteBuf versServeur = new FriendlyByteBuf(Unpooled.buffer());
        new ChannelFrames.LoginToServer(trame).encode(versServeur);
        assertArrayEquals(trame, ChannelFrames.LoginToServer.decode(versServeur).frame());

        FriendlyByteBuf enJeu = new FriendlyByteBuf(Unpooled.buffer());
        new ChannelFrames.PlayToClient(trame).encode(enJeu);
        assertArrayEquals(trame, ChannelFrames.PlayToClient.decode(enJeu).frame());
    }

    @Test
    @DisplayName("T-422 : une trame trop longue est écartée sans lever, et son tampon entièrement lu")
    void uneTrameTropLongueEstEcarteeSansLever() {
        FriendlyByteBuf limite = buffer(new byte[NetProtocol.MAX_FRAME_BYTES]);
        assertEquals(NetProtocol.MAX_FRAME_BYTES, ChannelFrames.read(limite).length, "la borne est admise");

        for (int longueur : new int[] {NetProtocol.MAX_FRAME_BYTES + 1, 1 << 20}) {
            FriendlyByteBuf tropLong = buffer(new byte[longueur]);
            assertNull(ChannelFrames.LoginToClient.decode(tropLong).frame());
            assertEquals(0, tropLong.readableBytes(), "le reste du tampon ne doit pas être pris pour un autre message");
            assertNull(ChannelFrames.LoginToServer.decode(buffer(new byte[longueur])).frame());
            assertNull(ChannelFrames.PlayToClient.decode(buffer(new byte[longueur])).frame());
        }
    }

    @Test
    @DisplayName("Une réponse vide est un simple acquittement, pas une faute")
    void uneReponseVideEstUnAcquittement() {
        assertEquals(0, ChannelFrames.LoginToServer.decode(buffer(new byte[0])).frame().length);
    }

    @Test
    @DisplayName("Le numéro de transaction que Forge pose se relit")
    void leNumeroDeTransactionSeRelit() {
        ChannelFrames.LoginToClient versClient = new ChannelFrames.LoginToClient(new byte[0]);
        versClient.setLoginIndex(7);
        assertEquals(7, versClient.getAsInt());
        ChannelFrames.LoginToServer versServeur = new ChannelFrames.LoginToServer(new byte[0]);
        versServeur.setLoginIndex(9);
        assertEquals(9, versServeur.getAsInt());
    }
}
