package dev.axion.asset;

import dev.axion.bridge.BufferKinds;
import dev.axion.bridge.NativeBridge;
import java.nio.ByteBuffer;

/**
 * Chargement d'un asset compilé dans le natif et récupération de sa géométrie (IF-06,
 * ADR-119).
 *
 * <p>Le protocole est celui d'IF-02, entièrement sous le verrou des tampons d'asset
 * ({@link AssetBuffers}) : écrire le conteneur dans {@code ASSET_IN}, charger, relâcher ;
 * demander la géométrie, la lire dans {@code ASSET_OUT}, relâcher. Les tampons sont
 * ré-acquis à chaque fois, jamais conservés (R-270), et toujours relâchés : les garder
 * fausserait le bilan de l'arrêt (R-322).
 *
 * <p>Le handle rendu appartient à l'appelant, qui doit le rendre par {@link #unload}
 * (R-321) ; un handle oublié est signalé à l'arrêt du natif.
 */
public final class NativeAssetLoader implements AssetLoader {

    /** Sections chargées pour le rendu : les nodes et la géométrie (ADR-119). */
    public static final int RENDER_SECTIONS = NativeBridge.SECTION_NODE | NativeBridge.SECTION_GEOM;

    /** Position de la génération dans l'en-tête d'un tampon (IF-02). */
    private static final int GENERATION_OFFSET = 8;

    private final long context;
    private final long[] handleOut = new long[NativeBridge.ASSET_LOAD_SLOTS];
    private final long[] sizeOut = new long[1];

    /**
     * Crée un chargeur adossé à un contexte natif.
     *
     * @param context jeton de contexte
     */
    public NativeAssetLoader(long context) {
        this.context = context;
    }

    /**
     * Charge un asset compilé et rend sa géométrie.
     *
     * <p>Peut s'appeler depuis n'importe quel thread : la séquence est sérialisée avec la
     * compilation, qui emploie les mêmes tampons. Si la géométrie ne peut être lue, le
     * handle est rendu aussitôt : un échec ne laisse rien de chargé.
     *
     * @param assetId identifiant de l'asset, celui que porte son en-tête
     * @param a3d conteneur A3D compilé
     * @return le handle et la géométrie, ou le code de l'échec
     */
    @Override
    public Loaded load(long assetId, byte[] a3d) {
        synchronized (AssetBuffers.LOCK) {
            ByteBuffer in = NativeBridge.acquire(context, BufferKinds.ASSET_IN, a3d.length);
            if (in == null) {
                return Loaded.failed(NativeBridge.E_INVALID_BUFFER);
            }
            int inGeneration = in.getInt(GENERATION_OFFSET);
            in.position(BufferKinds.HEADER_BYTES);
            if (in.remaining() < a3d.length) {
                NativeBridge.release(context, BufferKinds.ASSET_IN, inGeneration);
                return Loaded.failed(NativeBridge.E_INVALID_BUFFER);
            }
            in.put(a3d);

            int code = NativeBridge.loadAsset(context, assetId, RENDER_SECTIONS, handleOut);
            // Le natif a copié le conteneur : le tampon d'entrée n'a plus de raison d'être.
            NativeBridge.release(context, BufferKinds.ASSET_IN, inGeneration);
            if (code != NativeBridge.OK) {
                return Loaded.failed(code);
            }
            int index = (int) handleOut[0];
            int generation = (int) handleOut[1];

            byte[] transfer = readGeometry(index, generation);
            if (transfer == null) {
                NativeBridge.unloadAsset(context, index, generation);
                return Loaded.failed(NativeBridge.E_INVALID_BUFFER);
            }
            return new Loaded(NativeBridge.OK, index, generation, transfer);
        }
    }

    /**
     * Rend un asset chargé ; son handle devient périmé (R-110).
     *
     * @param index index du handle
     * @param generation génération du handle
     * @return {@link NativeBridge#OK}, ou {@link NativeBridge#E_INVALID_HANDLE} si le handle
     *     est déjà périmé
     */
    @Override
    public int unload(int index, int generation) {
        return NativeBridge.unloadAsset(context, index, generation);
    }

    /** Demande la géométrie et la lit dans {@code ASSET_OUT} ; {@code null} en cas d'échec. */
    private byte[] readGeometry(int index, int generation) {
        if (NativeBridge.geometryOf(context, index, generation, sizeOut) != NativeBridge.OK) {
            return null;
        }
        ByteBuffer out = NativeBridge.acquire(context, BufferKinds.ASSET_OUT, 0);
        if (out == null) {
            return null;
        }
        int outGeneration = out.getInt(GENERATION_OFFSET);
        long size = sizeOut[0];
        if (size < 0 || size > out.capacity() - BufferKinds.HEADER_BYTES) {
            NativeBridge.release(context, BufferKinds.ASSET_OUT, outGeneration);
            return null;
        }
        byte[] transfer = new byte[(int) size];
        out.position(BufferKinds.HEADER_BYTES);
        out.get(transfer);
        NativeBridge.release(context, BufferKinds.ASSET_OUT, outGeneration);
        return transfer;
    }
}
