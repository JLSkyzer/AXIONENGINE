package dev.axion.forge.net;

import dev.axion.net.NetProtocol;
import java.util.function.IntSupplier;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Les messages Forge du canal {@code axion:main} (ADR-150) : chacun porte une trame AXION, rien d'autre.
 *
 * <p>Le décodeur ne lève jamais (R-1650) : une exception dans un décodeur Forge remonte jusqu'au
 * pipeline Netty et ferme la connexion. Il copie la trame, bornée ; une trame plus longue que
 * {@link NetProtocol#MAX_FRAME_BYTES} est sautée et rendue {@code null}, que la session refuse et
 * journalise. Toute la validation se fait en Java pur, dans {@code dev.axion.net}.
 */
final class ChannelFrames {

    private ChannelFrames() {
        throw new AssertionError("non instanciable");
    }

    /**
     * @param buffer charge utile, après le discriminant de Forge
     * @return la trame, ou {@code null} si elle dépasse {@link NetProtocol#MAX_FRAME_BYTES}
     */
    static byte[] read(FriendlyByteBuf buffer) {
        int length = buffer.readableBytes();
        if (length > NetProtocol.MAX_FRAME_BYTES) {
            buffer.skipBytes(length);
            return null;
        }
        byte[] frame = new byte[length];
        buffer.readBytes(frame);
        return frame;
    }

    /** Un paquet de connexion, du serveur vers le client. */
    static final class LoginToClient implements IntSupplier {

        private final byte[] frame;
        private int loginIndex;

        LoginToClient(byte[] frame) {
            this.frame = frame;
        }

        static LoginToClient decode(FriendlyByteBuf buffer) {
            return new LoginToClient(read(buffer));
        }

        void encode(FriendlyByteBuf buffer) {
            buffer.writeBytes(frame);
        }

        /** {@return la trame, ou {@code null} si le décodeur l'a écartée pour sa taille} */
        byte[] frame() {
            return frame;
        }

        void setLoginIndex(int index) {
            loginIndex = index;
        }

        @Override
        public int getAsInt() {
            return loginIndex;
        }
    }

    /**
     * La réponse du client à un paquet de connexion : une trame, ou rien pour un simple acquittement.
     * Forge la rapporte au paquet auquel elle répond par son numéro de transaction.
     */
    static final class LoginToServer implements IntSupplier {

        private final byte[] frame;
        private int loginIndex;

        LoginToServer(byte[] frame) {
            this.frame = frame;
        }

        static LoginToServer decode(FriendlyByteBuf buffer) {
            return new LoginToServer(read(buffer));
        }

        void encode(FriendlyByteBuf buffer) {
            buffer.writeBytes(frame);
        }

        /** {@return la trame, vide pour un simple acquittement, ou {@code null} si écartée pour sa taille} */
        byte[] frame() {
            return frame;
        }

        void setLoginIndex(int index) {
            loginIndex = index;
        }

        @Override
        public int getAsInt() {
            return loginIndex;
        }
    }

    /** Un message en jeu, du serveur vers le client : en M4, le registre renvoyé. */
    static final class PlayToClient {

        private final byte[] frame;

        PlayToClient(byte[] frame) {
            this.frame = frame;
        }

        static PlayToClient decode(FriendlyByteBuf buffer) {
            return new PlayToClient(read(buffer));
        }

        void encode(FriendlyByteBuf buffer) {
            buffer.writeBytes(frame);
        }

        /** {@return la trame, ou {@code null} si le décodeur l'a écartée pour sa taille} */
        byte[] frame() {
            return frame;
        }
    }
}
