package dev.axion.bootstrap;

import dev.axion.bridge.NativeBridge;
import java.nio.ByteBuffer;

/**
 * Surface native dont le bootstrap a besoin (C-02).
 *
 * <p>Cette interface n'existe que pour rendre la séquence de démarrage
 * vérifiable. Sans elle, tester le refus d'une ABI incompatible, ou un
 * `axion_init` qui échoue, supposerait de construire une bibliothèque native
 * fautive pour chaque cas — ce que personne ne fait, et ces chemins ne seraient
 * donc jamais testés. Or ce sont précisément eux qui doivent laisser le jeu
 * jouable.
 *
 * <p>{@link #real()} rend l'implémentation qui délègue à {@link NativeBridge}.
 */
public interface NativeApi {

    /** {@return la version d'ABI de la bibliothèque chargée} */
    int abiVersion();

    /**
     * Ouvre le contexte natif.
     *
     * @param configCbor configuration encodée en CBOR
     * @return le jeton, strictement positif, ou un code d'erreur négatif
     */
    long initialize(byte[] configCbor);

    /**
     * Ferme le contexte natif.
     *
     * @param context jeton de contexte
     * @return {@code 0} en succès, sinon un code d'erreur
     */
    int close(long context);

    /**
     * Acquiert un tampon de transfert.
     *
     * @param context jeton de contexte
     * @param kind nature du tampon
     * @param minCapacity taille minimale de la charge utile, en octets
     * @return la vue, ou {@code null} en cas d'échec
     */
    ByteBuffer acquire(long context, int kind, long minCapacity);

    /**
     * Libère un tampon de transfert.
     *
     * @param context jeton de contexte
     * @param kind nature du tampon
     * @param generation génération courante, lue dans l'en-tête
     * @return {@code 0} en succès, sinon un code d'erreur
     */
    int release(long context, int kind, int generation);

    /**
     * {@return le dernier message d'erreur du contexte}
     *
     * @param context jeton de contexte
     */
    String lastErrorMessage(long context);

    /**
     * {@return l'implémentation qui appelle la bibliothèque native chargée}
     */
    static NativeApi real() {
        return new NativeApi() {
            @Override
            public int abiVersion() {
                return NativeBridge.nativeAbiVersion();
            }

            @Override
            public long initialize(byte[] configCbor) {
                return NativeBridge.initialize(configCbor);
            }

            @Override
            public int close(long context) {
                return NativeBridge.close(context);
            }

            @Override
            public ByteBuffer acquire(long context, int kind, long minCapacity) {
                return NativeBridge.acquire(context, kind, minCapacity);
            }

            @Override
            public int release(long context, int kind, int generation) {
                return NativeBridge.release(context, kind, generation);
            }

            @Override
            public String lastErrorMessage(long context) {
                return NativeBridge.lastErrorMessage(context);
            }
        };
    }
}
