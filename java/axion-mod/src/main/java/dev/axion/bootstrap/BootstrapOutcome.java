package dev.axion.bootstrap;

import dev.axion.config.AxionConfig;
import java.util.List;

/**
 * Issue de la séquence de démarrage (C-02).
 *
 * <p>Le démarrage ne lève jamais d'exception : « échec 3..9 : journaliser,
 * DISABLED, jamais d'exception non capturée ». Cette issue porte donc soit un
 * contexte natif utilisable, soit la raison précise pour laquelle AXION reste
 * inactif — celle que le journal et {@code /axion status} afficheront.
 *
 * <p>Un démarrage en {@link Phase#DISABLED} n'est pas une panne : le jeu reste
 * jouable, et les assemblies sont chargées inertes sans que leur NBT soit
 * modifié (R-410, INV-11). Elles retrouvent leur état exact au prochain
 * démarrage réussi.
 *
 * @param phase phase atteinte
 * @param context jeton de contexte natif, ou {@code 0} si aucun n'a été ouvert
 * @param config configuration résolue, toujours présente — au minimum les
 *     défauts compilés
 * @param reason cause de la désactivation, ou {@code null} si le démarrage a
 *     abouti
 * @param detail message expliquant la cause, vide si le démarrage a abouti
 * @param ffiRoundtripNanos coût médian d'un aller-retour FFI mesuré au
 *     démarrage, ou {@code -1} si la mesure n'a pas eu lieu (R-330)
 * @param diagnostics observations faites en chemin, dans l'ordre
 */
public record BootstrapOutcome(
        Phase phase,
        long context,
        AxionConfig config,
        Reason reason,
        String detail,
        long ffiRoundtripNanos,
        List<String> diagnostics) {

    /** Fige la liste de diagnostics reçue. */
    public BootstrapOutcome {
        diagnostics = List.copyOf(diagnostics);
    }

    /** Étapes de la séquence de démarrage. */
    public enum Phase {
        /** Rien n'a encore été tenté. */
        INIT,
        /** Lecture et validation de la configuration. */
        CONFIG,
        /** Extraction et chargement de la bibliothèque native. */
        LOAD_NATIVE,
        /** Comparaison des versions d'ABI. */
        HANDSHAKE,
        /** Ouverture du contexte, calibration, acquisition des tampons. */
        PROBE,
        /** Démarrage abouti : le runtime natif est utilisable. */
        READY,
        /** Runtime natif inutilisé ; le jeu reste jouable. */
        DISABLED
    }

    /** Cause d'un démarrage en {@link Phase#DISABLED} (25.7). */
    public enum Reason {
        /** {@code general.enabled} vaut faux : désactivation demandée. */
        CONFIGURATION,
        /** La bibliothèque native est absente, illisible ou non chargeable. */
        NATIVE_UNAVAILABLE,
        /**
         * La bibliothèque parle une autre version d'ABI ({@code E-1002}).
         *
         * <p>Signe d'une installation partielle : un JAR dont le binaire natif
         * ne correspond pas aux classes.
         */
        ABI_MISMATCH,
        /** Le natif a refusé la configuration ou l'initialisation. */
        INIT_REFUSED,
        /** Les tampons de transfert n'ont pas pu être acquis. */
        BUFFERS_UNAVAILABLE
    }

    /** {@return vrai si le runtime natif est utilisable} */
    public boolean isReady() {
        return phase == Phase.READY;
    }

    /**
     * {@return un résumé d'une ligne, tel qu'il apparaîtra au journal}
     */
    public String summary() {
        if (isReady()) {
            return "AXION prêt (aller-retour FFI mesuré à " + ffiRoundtripNanos + " ns)";
        }
        return "AXION inactif — " + reason + " : " + detail;
    }
}
