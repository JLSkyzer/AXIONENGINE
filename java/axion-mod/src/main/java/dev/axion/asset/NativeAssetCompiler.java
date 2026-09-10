package dev.axion.asset;

import dev.axion.bridge.BufferKinds;
import dev.axion.bridge.NativeBridge;
import java.nio.ByteBuffer;

/**
 * Compilateur d'assets adossé au runtime natif (IF-06).
 *
 * <p>Le protocole est celui d'IF-02 : la source est écrite dans le tampon
 * {@code ASSET_IN}, puis {@code axion_asset_compile} est appelé avec sa
 * longueur. R-313 interdit à une fonction FFI d'allouer côté Java, et faire
 * traverser un pointeur de plus n'apporterait qu'un pointeur de plus à valider.
 *
 * <p>Le tampon est <strong>ré-acquis à chaque soumission</strong>, jamais
 * conservé : R-270 autorise sa réallocation entre deux ticks, et une vue
 * périmée écrirait dans de la mémoire qui ne lui appartient plus.
 */
public final class NativeAssetCompiler implements AssetCompiler {

    private final long context;
    private final long[] scratch = new long[NativeBridge.ASSET_POLL_SLOTS];

    /**
     * Crée un compilateur adossé à un contexte natif.
     *
     * @param context jeton de contexte
     */
    public NativeAssetCompiler(long context) {
        this.context = context;
    }

    @Override
    public int submit(long assetId, int format, byte[] source) {
        ByteBuffer buffer = NativeBridge.acquire(context, BufferKinds.ASSET_IN, source.length);
        if (buffer == null) {
            return NativeBridge.E_INVALID_BUFFER;
        }

        // La charge utile commence après l'en-tête : ce qui précède décrit le
        // tampon, et l'écraser rendrait la vue invalide (R-271).
        buffer.position(BufferKinds.HEADER_BYTES);
        if (buffer.remaining() < source.length) {
            return NativeBridge.E_INVALID_BUFFER;
        }
        buffer.put(source);

        return NativeBridge.compileAsset(context, assetId, format, source.length);
    }

    @Override
    public CompileStatus poll(int jobId) {
        int code = NativeBridge.pollAsset(context, jobId, scratch);
        if (code != NativeBridge.OK) {
            // Un jeton périmé ou un contexte fermé : le travail est perdu, et
            // le dire vaut mieux que de sonder indéfiniment.
            return CompileStatus.failed(code);
        }

        int state = (int) scratch[0];
        return switch (state) {
            case NativeBridge.ASSET_COMPILED -> CompileStatus.compiled(scratch[1]);
            case NativeBridge.ASSET_FAILED -> CompileStatus.failed((int) scratch[2]);
            default -> CompileStatus.pending();
        };
    }
}
