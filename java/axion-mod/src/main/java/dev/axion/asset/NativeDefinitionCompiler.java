package dev.axion.asset;

import dev.axion.bridge.BufferKinds;
import dev.axion.bridge.NativeBridge;
import java.nio.ByteBuffer;

/**
 * La compilation d'une definition par le natif (ADR-156 D3, D4).
 *
 * <p>Le protocole est celui d'IF-02, sous le verrou des tampons d'asset ({@link AssetBuffers}),
 * comme le chargement d'un asset : écrire le conteneur dans {@code ASSET_IN} et le charger avec ce
 * qu'une definition lit ({@link #SECTIONS}) ; écrire le {@code DEFN} dans {@code ASSET_IN} et
 * compiler ; lire la réponse dans {@code ASSET_OUT} ; rendre le handle. La definition compilée garde
 * ce qu'elle a lu de l'asset : le handle ne lui survit pas, et rien ne reste chargé à l'arrêt.
 */
public final class NativeDefinitionCompiler implements DefinitionCompiler {

    /** Ce qu'une definition lit de son asset : hiérarchie, colliders, os et clips (ADR-156 D3). */
    public static final int SECTIONS = NativeBridge.SECTION_NODE
            | NativeBridge.SECTION_PHYS
            | NativeBridge.SECTION_SKEL
            | NativeBridge.SECTION_ANIM;

    /** Position de la génération dans l'en-tête d'un tampon (IF-02). */
    private static final int GENERATION_OFFSET = 8;

    private final long context;
    private final long[] handleOut = new long[NativeBridge.ASSET_LOAD_SLOTS];
    private final long[] sizeOut = new long[1];

    /** Génération d'{@code ASSET_IN} entre son écriture et son relâchement. */
    private int inGeneration;

    /**
     * Crée un compilateur adossé à un contexte natif.
     *
     * @param context jeton de contexte
     */
    public NativeDefinitionCompiler(long context) {
        this.context = context;
    }

    @Override
    public Result compile(long definition, long assetId, byte[] a3d, byte[] defn) {
        synchronized (AssetBuffers.LOCK) {
            int code = write(a3d);
            if (code == NativeBridge.OK) {
                code = NativeBridge.loadAsset(context, assetId, SECTIONS, handleOut);
                releaseIn();
            }
            if (code != NativeBridge.OK) {
                return new Result(code, null, -1,
                        "asset non chargé : " + NativeBridge.lastErrorMessage(context));
            }
            int index = (int) handleOut[0];
            int generation = (int) handleOut[1];
            try {
                code = write(defn);
                if (code != NativeBridge.OK) {
                    return new Result(code, null, -1, "DEFN non écrit");
                }
                sizeOut[0] = -1;
                code = NativeBridge.compileDefinition(context, definition, index, generation, defn.length, sizeOut);
                releaseIn();
                byte[] payload = sizeOut[0] < 0 ? null : readAssetOut();
                if (code == NativeBridge.OK) {
                    Summary summary = payload == null ? null : Summary.read(payload);
                    return summary == null
                            ? new Result(NativeBridge.E_INVALID_BUFFER, null, -1, "résumé illisible")
                            : new Result(NativeBridge.OK, summary, -1, "");
                }
                Result refusal = new Result(code, null, -1, "");
                if (refusal.refused() && payload != null) {
                    return Result.readRefusal(code, payload);
                }
                return new Result(code, null, -1, NativeBridge.lastErrorMessage(context));
            } finally {
                NativeBridge.unloadAsset(context, index, generation);
            }
        }
    }

    @Override
    public boolean remove(long definition) {
        return NativeBridge.removeDefinition(context, definition) == NativeBridge.OK;
    }

    /** Écrit des octets dans la charge d'{@code ASSET_IN}, acquis jusqu'à {@link #releaseIn}. */
    private int write(byte[] bytes) {
        ByteBuffer in = NativeBridge.acquire(context, BufferKinds.ASSET_IN, bytes.length);
        if (in == null) {
            return NativeBridge.E_INVALID_BUFFER;
        }
        in.position(BufferKinds.HEADER_BYTES);
        if (in.remaining() < bytes.length) {
            NativeBridge.release(context, BufferKinds.ASSET_IN, in.getInt(GENERATION_OFFSET));
            return NativeBridge.E_INVALID_BUFFER;
        }
        in.put(bytes);
        inGeneration = in.getInt(GENERATION_OFFSET);
        return NativeBridge.OK;
    }

    /** Relâche {@code ASSET_IN} : le natif a copié ce qu'il y lisait. */
    private void releaseIn() {
        NativeBridge.release(context, BufferKinds.ASSET_IN, inGeneration);
    }

    /** Lit la réponse déposée dans {@code ASSET_OUT}, puis relâche le tampon ; {@code null} si elle n'y tient pas. */
    private byte[] readAssetOut() {
        ByteBuffer out = NativeBridge.acquire(context, BufferKinds.ASSET_OUT, 0);
        if (out == null) {
            return null;
        }
        int outGeneration = out.getInt(GENERATION_OFFSET);
        try {
            long size = sizeOut[0];
            if (size < 0 || size > out.capacity() - BufferKinds.HEADER_BYTES) {
                return null;
            }
            byte[] payload = new byte[(int) size];
            out.position(BufferKinds.HEADER_BYTES);
            out.get(payload);
            return payload;
        } finally {
            NativeBridge.release(context, BufferKinds.ASSET_OUT, outGeneration);
        }
    }
}
