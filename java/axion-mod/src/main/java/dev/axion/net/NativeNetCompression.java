package dev.axion.net;

import dev.axion.bridge.BufferKinds;
import dev.axion.bridge.NativeBridge;
import java.nio.ByteBuffer;

/**
 * Le zstd du natif pour les charges utiles réseau (C-51, ADR-150 D5), par le tampon
 * {@code AXION_BUF_NET_OUT} : l'entrée y est écrite après l'en-tête, la sortie l'y remplace.
 *
 * <p>Toute la séquence — acquérir, écrire, appeler, réacquérir, lire, relâcher — se fait sous un
 * verrou : deux appelants qui s'y croiseraient liraient l'un la sortie de l'autre. Relâcher un
 * tampon le libère : il ne se relâche qu'une fois la sortie lue.
 */
public final class NativeNetCompression implements NetCompression {

    /** Verrou du tampon {@code NET_OUT}, pour tous les contextes du processus. */
    private static final Object LOCK = new Object();

    /** Position de la génération dans l'en-tête d'un tampon (R-271). */
    private static final int GENERATION_OFFSET = 8;

    private final long context;

    /**
     * @param context jeton de contexte natif
     */
    public NativeNetCompression(long context) {
        this.context = context;
    }

    @Override
    public byte[] compress(byte[] payload) {
        synchronized (LOCK) {
            write(payload);
            long[] size = new long[1];
            int code = NativeBridge.compressNet(context, payload.length, size);
            if (code != NativeBridge.OK) {
                releaseCurrent();
                throw new IllegalStateException("compression réseau refusée par le natif, code " + code);
            }
            return readAndRelease(size[0]);
        }
    }

    @Override
    public byte[] decompress(byte[] frame, int maxBytes) throws MalformedPacketException {
        synchronized (LOCK) {
            write(frame);
            long[] size = new long[1];
            int code = NativeBridge.decompressNet(context, frame.length, maxBytes, size);
            if (code != NativeBridge.OK) {
                releaseCurrent();
                throw new MalformedPacketException("trame zstd refusée (code " + code + ") : invalide, "
                        + "ou plus grande que " + maxBytes + " octets");
            }
            return readAndRelease(size[0]);
        }
    }

    private void write(byte[] bytes) {
        ByteBuffer buffer = NativeBridge.acquire(context, BufferKinds.NET_OUT, bytes.length);
        if (buffer == null || buffer.capacity() - BufferKinds.HEADER_BYTES < bytes.length) {
            throw new IllegalStateException("tampon NET_OUT refusé pour " + bytes.length + " octets");
        }
        buffer.position(BufferKinds.HEADER_BYTES);
        buffer.put(bytes);
    }

    /** La sortie a pu agrandir le tampon (R-270) : elle se lit par une vue réacquise. */
    private byte[] readAndRelease(long size) {
        ByteBuffer buffer = NativeBridge.acquire(context, BufferKinds.NET_OUT, 0);
        if (buffer == null) {
            throw new IllegalStateException("tampon NET_OUT perdu après l'appel natif");
        }
        int generation = buffer.getInt(GENERATION_OFFSET);
        if (size < 0 || size > buffer.capacity() - BufferKinds.HEADER_BYTES) {
            NativeBridge.release(context, BufferKinds.NET_OUT, generation);
            throw new IllegalStateException("taille de sortie hors du tampon : " + size);
        }
        byte[] out = new byte[(int) size];
        buffer.position(BufferKinds.HEADER_BYTES);
        buffer.get(out);
        NativeBridge.release(context, BufferKinds.NET_OUT, generation);
        return out;
    }

    private void releaseCurrent() {
        ByteBuffer buffer = NativeBridge.acquire(context, BufferKinds.NET_OUT, 0);
        if (buffer != null) {
            NativeBridge.release(context, BufferKinds.NET_OUT, buffer.getInt(GENERATION_OFFSET));
        }
    }
}
