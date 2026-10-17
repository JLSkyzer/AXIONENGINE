package dev.axion.asset;

import java.util.List;

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
     * @param options texte du {@code <modèle>.axion.json} voisin, tel quel, ou un
     *     tableau vide sans fichier (ADR-135)
     * @param siblings fichiers voisins que la source peut référencer — {@code .mtl},
     *     buffers externes —, lus à la demande du compilateur (ADR-139)
     * @return l'identifiant du travail, strictement positif, ou un code
     *     d'erreur negatif
     */
    int submit(long assetId, int format, byte[] source, byte[] options, SiblingFiles siblings);

    /**
     * Oublie une compilation que personne ne sondera plus.
     *
     * <p>Le registre l'appelle quand il cesse d'attendre un travail en vol : une source
     * modifiée ou retirée, une recompilation forcée, le délai de démarrage écoulé. Ce que
     * le compilateur gardait pour poursuivre le travail — la source et ses voisins, pour
     * un nouveau passage ; côté natif, le travail et son résultat (ADR-140) — serait sinon
     * retenu jusqu'à l'arrêt.
     *
     * @param jobId identifiant rendu par {@link #submit}
     */
    default void abandon(int jobId) {}

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
     * <p>L'asset compile voyage avec l'avancement plutot que de rester dans le
     * tampon partage : celui-ci sert a la compilation suivante (R-270), et ce
     * qu'on y laisserait appartiendrait alors a un autre asset.
     *
     * @param state etat courant
     * @param payload conteneur A3D compile, ou {@code null}
     * @param error code d'erreur de l'ANNEXE A.1, ou zero
     * @param lines lignes du travail fini (ADR-148) : ses avertissements s'il a abouti, le
     *     message de son echec sinon ; vide tant qu'il continue
     */
    record CompileStatus(AssetState state, byte[] payload, int error, List<String> lines) {

        /** Copie les lignes : l'avancement ne change plus une fois rendu. */
        public CompileStatus {
            lines = List.copyOf(lines);
        }

        /** {@return un avancement disant que le travail continue} */
        public static CompileStatus pending() {
            return new CompileStatus(AssetState.COMPILING, null, 0, List.of());
        }

        /**
         * {@return un avancement disant que le travail a abouti, sans avertissement}
         *
         * @param payload conteneur A3D compile
         */
        public static CompileStatus compiled(byte[] payload) {
            return compiled(payload, List.of());
        }

        /**
         * {@return un avancement disant que le travail a abouti}
         *
         * @param payload conteneur A3D compile
         * @param warnings avertissements de la compilation (ADR-148)
         */
        public static CompileStatus compiled(byte[] payload, List<String> warnings) {
            return new CompileStatus(AssetState.COMPILED, payload, 0, warnings);
        }

        /**
         * {@return un avancement disant que le travail a echoue, sans message}
         *
         * @param error code d'erreur
         */
        public static CompileStatus failed(int error) {
            return failed(error, List.of());
        }

        /**
         * {@return un avancement disant que le travail a echoue}
         *
         * @param error code d'erreur
         * @param message ce qui a empeche la compilation, en lignes (ADR-148)
         */
        public static CompileStatus failed(int error, List<String> message) {
            return new CompileStatus(AssetState.FAILED, null, error, message);
        }

        /** {@return la taille de l'asset compile, en octets} */
        public long size() {
            return payload == null ? 0 : payload.length;
        }
    }
}
