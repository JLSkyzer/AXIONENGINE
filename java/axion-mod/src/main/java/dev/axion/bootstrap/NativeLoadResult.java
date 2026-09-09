package dev.axion.bootstrap;

import java.nio.file.Path;

/**
 * Issue d'une tentative de chargement de la bibliothèque native (C-03).
 *
 * <p>Aucun échec n'est une exception : le bootstrap doit pouvoir basculer en
 * {@code DISABLED} sans que rien ne remonte jusqu'au chargement du jeu. Un
 * résultat porte donc soit la bibliothèque chargée, soit la raison précise de
 * l'échec — celle que {@code /axion status} affichera.
 */
public sealed interface NativeLoadResult {

    /**
     * La bibliothèque a été chargée.
     *
     * @param path chemin absolu du fichier effectivement chargé
     * @param sha256 empreinte vérifiée de ce fichier
     */
    record Loaded(Path path, String sha256) implements NativeLoadResult {}

    /**
     * La bibliothèque n'a pas pu être chargée.
     *
     * @param reason cause de l'échec
     * @param detail message destiné au journal, en français
     */
    record Failed(Reason reason, String detail) implements NativeLoadResult {

        /**
         * {@return le code d'erreur de l'ANNEXE A.1, ou 0 si la cause n'en a pas}
         */
        public int code() {
            return reason.code();
        }
    }

    /** Cause d'un échec de chargement. */
    enum Reason {
        /**
         * Le couple système/architecture ne figure pas dans la table des
         * plateformes supportées (34.2). Aucun binaire n'existe pour lui.
         */
        PLATFORM_UNSUPPORTED(0),
        /**
         * La bibliothèque ou son empreinte est absente du JAR.
         *
         * <p>Cas normal d'un JAR marqué {@code partial}, que {@code validateJar}
         * interdit de publier mais qui existe pendant le développement.
         */
        RESOURCE_MISSING(0),
        /**
         * L'empreinte SHA-256 ne correspond pas.
         *
         * <p>Code {@code E-1003}. Le JAR est corrompu, ou le fichier extrait a
         * été altéré depuis : dans les deux cas la bibliothèque n'est pas
         * chargée. R-420 fait de cette vérification une obligation.
         */
        CHECKSUM_MISMATCH(-1003),
        /**
         * Aucun emplacement d'extraction n'est utilisable.
         *
         * <p>Système de fichiers en lecture seule, plein, ou monté
         * {@code noexec} — le repli par {@code java.io.tmpdir} ayant lui aussi
         * échoué (R-421).
         */
        NO_USABLE_LOCATION(0);

        private final int code;

        Reason(int code) {
            this.code = code;
        }

        /**
         * {@return le code d'erreur de l'ANNEXE A.1, ou 0 si la cause n'en a pas}
         *
         * <p>Toutes les causes n'ont pas de code : le cahier des charges n'en
         * attribue qu'à l'empreinte invalide. En inventer pour les autres
         * reviendrait à étendre l'annexe sans y toucher.
         */
        public int code() {
            return code;
        }
    }
}
