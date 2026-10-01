package dev.axion.asset;

/**
 * Verrou des tampons d'asset partagés (ADR-119 §8).
 *
 * <p>{@code ASSET_IN} et {@code ASSET_OUT} sont <strong>uniques par session native</strong>.
 * En solo, le serveur intégré y compile (thread serveur) pendant que le client y charge des
 * maillages (thread de fond). Sans sérialisation, une acquisition concurrente peut
 * <strong>réallouer</strong> un tampon (R-270) sous la vue que l'autre thread est en train
 * d'écrire ou de lire : il travaillerait alors dans de la mémoire native libérée — une
 * corruption du tas, non déterministe, sans message.
 *
 * <p>Toute séquence <i>acquérir → écrire → appeler → lire → relâcher</i> sur ces tampons se
 * fait donc sous ce verrou. Les appels natifs qui écrivent eux-mêmes {@code ASSET_OUT}
 * (sondage d'une compilation, dépôt d'une géométrie) en font partie.
 */
final class AssetBuffers {

    /** Le verrou des tampons d'asset. */
    static final Object LOCK = new Object();

    private AssetBuffers() {}
}
