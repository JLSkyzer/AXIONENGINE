package dev.axion.asset;

/**
 * Ce que l'orchestrateur attend du compilateur natif (IF-06).
 *
 * <p>Injectable pour la meme raison que {@code NativeApi} l'est : sans cela,
 * tester une compilation qui echoue supposerait de fabriquer un asset fautif et
 * de charger la bibliotheque native, ce que personne ne fait — et ce chemin,
 * qui doit laisser le jeu jouable, ne serait jamais verifie.
 */
public interface AssetCompiler {

    /**
     * Lance la compilation d'une source.
     *
     * @param assetId identifiant de l'asset
     * @param format code de format, voir {@link SourceFormats}
     * @param source contenu de la source
     * @return l'identifiant du travail, strictement positif, ou un code
     *     d'erreur negatif
     */
    int submit(long assetId, int format, byte[] source);

    /**
     * Sonde une compilation lancee.
     *
     * @param jobId identifiant rendu par {@link #submit}
     * @return l'avancement du travail
     */
    CompileStatus poll(int jobId);

    /**
     * Avancement d'une compilation.
     *
     * @param state etat courant
     * @param size taille de l'asset compile, en octets
     * @param error code d'erreur de l'ANNEXE A.1, ou zero
     */
    record CompileStatus(AssetState state, long size, int error) {

        /** {@return un avancement disant que le travail continue} */
        public static CompileStatus pending() {
            return new CompileStatus(AssetState.COMPILING, 0, 0);
        }

        /**
         * {@return un avancement disant que le travail a abouti}
         *
         * @param size taille de l'asset compile
         */
        public static CompileStatus compiled(long size) {
            return new CompileStatus(AssetState.COMPILED, size, 0);
        }

        /**
         * {@return un avancement disant que le travail a echoue}
         *
         * @param error code d'erreur
         */
        public static CompileStatus failed(int error) {
            return new CompileStatus(AssetState.FAILED, 0, error);
        }
    }
}
