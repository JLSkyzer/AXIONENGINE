package dev.axion.bridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Unique déclaration des méthodes natives d'AXION (C-14, R-492).
 *
 * <p>Aucune autre classe ne déclare de méthode {@code native} : toute la
 * frontière passe par ici, ce qui rend son coût mesurable et son inventaire
 * vérifiable.
 *
 * <p>Les implémentations ne sont pas résolues par convention de nommage mais
 * enregistrées par {@code JNI_OnLoad} au chargement de la bibliothèque. C'est
 * ce qui permet à celle-ci de n'exporter que des symboles {@code axion_*}
 * (R-2020) : sans cela, chaque méthode ajouterait un symbole exporté, avec le
 * risque de collision qui va avec.
 *
 * <p><strong>Conventions de retour.</strong> Aucune méthode ne lève
 * d'exception : le natif ne peut pas en construire sans appeler la JVM, ce que
 * R-313 lui interdit. Les codes sont donc négatifs et proviennent de l'ANNEXE
 * A.1 ; {@link #initialize} renvoie un jeton toujours positif, et
 * {@link #acquire} renvoie {@code null} en cas d'échec.
 */
public final class NativeBridge {

    /**
     * Version de l'ABI attendue par ce mod.
     *
     * <p>R-260 : elle est comparée à {@link #nativeAbiVersion()} avant tout
     * autre appel, et un écart fait basculer en {@code DISABLED} avec
     * {@code E-1002}.
     */
    public static final int EXPECTED_ABI_VERSION = 1;

    /** Succès. */
    public static final int OK = 0;

    /** ABI incompatible ({@code E-1002}). */
    public static final int E_ABI_MISMATCH = -1002;

    /** Contexte déjà initialisé ({@code E-1004}). */
    public static final int E_ALREADY_INITIALIZED = -1004;

    /** Contexte ou handle invalide ({@code E-2001}). */
    public static final int E_INVALID_HANDLE = -2001;

    /** Tampon ou pointeur invalide ({@code E-2002}). */
    public static final int E_INVALID_BUFFER = -2002;

    /** Capacité du tampon de lecture des messages d'erreur, en octets. */
    private static final int ERROR_BUFFER_BYTES = 4096;

    /**
     * Tampon de lecture des messages d'erreur, alloué une fois.
     *
     * <p>Le natif y écrit sans jamais allouer côté JVM. Il n'est pas partagé
     * entre threads : la lecture d'erreur se fait depuis le thread qui a
     * constaté l'échec.
     */
    private static final ThreadLocal<byte[]> ERROR_SCRATCH =
            ThreadLocal.withInitial(() -> new byte[ERROR_BUFFER_BYTES]);

    private NativeBridge() {
        throw new AssertionError("classe utilitaire, non instanciable");
    }

    // --- Méthodes natives -------------------------------------------------
    //
    // Enregistrées par JNI_OnLoad. Elles restent en visibilité de paquet :
    // l'extérieur passe par les méthodes publiques ci-dessous, qui appliquent
    // les conventions — ordre des octets, capacité du tampon d'erreur.

    static native int abiVersion();

    static native long init(byte[] configCbor);

    static native int shutdown(long ctx);

    static native int lastError(long ctx, byte[] out);

    static native ByteBuffer bufferAcquire(long ctx, int kind, long minCapacity);

    static native int bufferRelease(long ctx, int kind, int generation);

    // --- API ---------------------------------------------------------------

    /**
     * {@return la version d'ABI de la bibliothèque chargée}
     *
     * <p>À appeler avant toute autre chose (R-260). Ne dépend d'aucun contexte.
     */
    public static int nativeAbiVersion() {
        return abiVersion();
    }

    /**
     * {@return vrai si la bibliothèque chargée parle la même ABI que ce mod}
     */
    public static boolean isAbiCompatible() {
        return nativeAbiVersion() == EXPECTED_ABI_VERSION;
    }

    /**
     * Initialise le contexte natif.
     *
     * @param configCbor configuration encodée en CBOR, ou {@code null} pour
     *     s'en tenir aux défauts compilés
     * @return le jeton de contexte, strictement positif, ou un code d'erreur
     *     négatif de l'ANNEXE A.1
     */
    public static long initialize(byte[] configCbor) {
        return init(configCbor);
    }

    /**
     * Ferme le contexte natif.
     *
     * <p>Reste possible sur un contexte empoisonné (R-311).
     *
     * @param ctx jeton de contexte
     * @return {@link #OK}, ou un code d'erreur négatif
     */
    public static int close(long ctx) {
        return shutdown(ctx);
    }

    /**
     * {@return le dernier message d'erreur du contexte, éventuellement vide}
     *
     * <p>Le message est de l'UTF-8 sans zéro terminal (R-263). S'il dépassait la
     * capacité du tampon de lecture, il est tronqué sur une frontière de
     * caractère par le natif : la chaîne rendue est toujours valide.
     *
     * @param ctx jeton de contexte
     */
    public static String lastErrorMessage(long ctx) {
        byte[] scratch = ERROR_SCRATCH.get();
        int written = lastError(ctx, scratch);
        if (written <= 0) {
            return "";
        }
        return new String(scratch, 0, written, StandardCharsets.UTF_8);
    }

    /**
     * Acquiert un tampon de transfert et renvoie une vue dessus.
     *
     * <p>La vue est <strong>toujours</strong> en little-endian : R-271 l'impose,
     * et un {@link ByteBuffer} est big-endian par défaut. Oublier ce réglage
     * ferait lire chaque entier à l'envers sans qu'aucune erreur ne le signale.
     *
     * <p>La vue reste valide tant que la génération du tampon ne change pas ;
     * elle se lit dans l'en-tête, à l'offset 8. Le protocole est d'acquérir à
     * chaque tick plutôt que de conserver une vue (R-270).
     *
     * @param ctx jeton de contexte
     * @param kind nature du tampon, valeur de {@code BufferKinds}
     * @param minCapacity taille minimale de la charge utile, en octets, hors
     *     en-tête
     * @return la vue, ou {@code null} si l'acquisition a échoué
     */
    public static ByteBuffer acquire(long ctx, int kind, long minCapacity) {
        ByteBuffer buffer = bufferAcquire(ctx, kind, minCapacity);
        return buffer == null ? null : buffer.order(ByteOrder.LITTLE_ENDIAN);
    }

    /**
     * Libère un tampon de transfert.
     *
     * @param ctx jeton de contexte
     * @param kind nature du tampon
     * @param generation génération courante, telle que lue dans l'en-tête
     * @return {@link #OK}, ou un code d'erreur négatif
     */
    public static int release(long ctx, int kind, int generation) {
        return bufferRelease(ctx, kind, generation);
    }
}
