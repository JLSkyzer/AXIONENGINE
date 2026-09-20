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
    public static final int EXPECTED_ABI_VERSION = 2;

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
     * Taille du premier tampon d'export des métriques.
     *
     * Assez grande pour l'export courant, sans qu'aucune valeur ne soit
     * supposée : si elle ne suffit pas, le natif rend la taille exacte et
     * l'appel est refait avec elle.
     */
    private static final int EXPORT_INITIAL_BYTES = 16384;

    /** Nombre maximal de tentatives d'export. */
    private static final int EXPORT_MAX_ATTEMPTS = 3;

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

    static native long init(byte[] configCbor, int side);

    static native int shutdown(long ctx);

    static native int lastError(long ctx, byte[] out);

    static native ByteBuffer bufferAcquire(long ctx, int kind, long minCapacity);

    static native int bufferRelease(long ctx, int kind, int generation);

    static native int metricsExport(long ctx, byte[] out);

    static native int assetCompile(long ctx, long assetId, int format, long sourceLen);

    static native int assetPoll(long ctx, int jobId, long[] out);

    static native int simSubmit(long ctx, long tick, int commandCount, int impactCount);

    static native int simCollect(long ctx, long deadlineNs, long[] out);

    static native int simCancel(long ctx);

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

    /** Côté client, tel qu'{@code axion_init} l'attend. */
    public static final int SIDE_CLIENT = 0;

    /** Côté serveur dédié, tel qu'{@code axion_init} l'attend. */
    public static final int SIDE_SERVER = 1;

    /**
     * Initialise le contexte natif.
     *
     * <p>Le côté est un paramètre et non une option de configuration : ce n'est
     * pas un réglage, c'est une donnée de démarrage. R-471 en fait dépendre le
     * plafond de workers du pool de jobs — quatre sur un client, huit sur un
     * serveur dédié.
     *
     * @param configCbor configuration encodée en CBOR, ou {@code null} pour
     *     s'en tenir aux défauts compilés
     * @param side {@link #SIDE_CLIENT} ou {@link #SIDE_SERVER}
     * @return le jeton de contexte, strictement positif, ou un code d'erreur
     *     négatif de l'ANNEXE A.1
     */
    public static long initialize(byte[] configCbor, int side) {
        return init(configCbor, side);
    }

    /**
     * {@return l'export JSON des métriques natives (R-502)}
     *
     * <p>Le tampon est agrandi et l'appel refait tant que la capacité ne suffit
     * pas : le natif rend toujours la longueur complète et n'écrit rien de
     * partiel, un JSON tronqué n'étant pas un JSON.
     *
     * @param ctx jeton de contexte
     * @throws IllegalStateException si le natif refuse l'export
     */
    public static String metricsJson(long ctx) {
        byte[] buffer = new byte[EXPORT_INITIAL_BYTES];
        for (int attempt = 0; attempt < EXPORT_MAX_ATTEMPTS; attempt++) {
            int needed = metricsExport(ctx, buffer);
            if (needed < 0) {
                throw new IllegalStateException("export des métriques refusé, code " + needed);
            }
            if (needed <= buffer.length) {
                return new String(buffer, 0, needed, StandardCharsets.UTF_8);
            }
            buffer = new byte[needed];
        }
        // Le natif rend la taille exacte : une seconde tentative suffit
        // toujours. Y arriver signalerait un export qui grossit entre deux
        // appels, ce qu'aucun chemin ne produit.
        throw new IllegalStateException("export des métriques instable");
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

    /** Compilation en cours : ni aboutie, ni échouée (SM-01). */
    public static final int ASSET_PENDING = 0;

    /** Compilation aboutie ; l'asset attend dans le tampon de sortie. */
    public static final int ASSET_COMPILED = 1;

    /** Compilation échouée. */
    public static final int ASSET_FAILED = 2;

    /** Nombre de valeurs que {@link #pollAsset} écrit. */
    public static final int ASSET_POLL_SLOTS = 3;

    /**
     * Lance la compilation d'un asset (IF-06).
     *
     * <p>La source doit avoir été écrite dans le tampon
     * {@code AXION_BUF_ASSET_IN} avant l'appel : R-313 interdit à une fonction
     * FFI d'allouer côté Java, et faire traverser un pointeur de plus
     * n'apporterait qu'un pointeur de plus à valider.
     *
     * <p>La compilation est <strong>asynchrone</strong> (R-521). Elle ne bloque
     * jamais le thread appelant ; c'est {@link #pollAsset} qui rapporte son
     * avancement, aucun rappel ne venant du natif (INV-07).
     *
     * @param ctx jeton de contexte
     * @param assetId identifiant de l'asset produit
     * @param format code de format, voir {@code SourceFormats}
     * @param sourceLen longueur de la source écrite dans le tampon d'entrée
     * @return l'identifiant du travail, strictement positif, ou un code d'erreur
     */
    public static int compileAsset(long ctx, long assetId, int format, long sourceLen) {
        return assetCompile(ctx, assetId, format, sourceLen);
    }

    /**
     * Sonde une compilation lancée (IF-06).
     *
     * <p>Le tableau reçoit {@code [état, taille, code d'erreur]}. Une
     * compilation aboutie dépose son A3D dans le tampon
     * {@code AXION_BUF_ASSET_OUT}, et son résultat n'est rendu
     * <strong>qu'une fois</strong> : sonder de nouveau rend
     * {@link #E_INVALID_HANDLE}, ce qui vaut mieux qu'une seconde lecture d'un
     * tampon qui a pu changer.
     *
     * @param ctx jeton de contexte
     * @param jobId identifiant rendu par {@link #compileAsset}
     * @param out tableau d'au moins {@link #ASSET_POLL_SLOTS} éléments
     * @return {@link #OK}, ou un code d'erreur négatif
     */
    public static int pollAsset(long ctx, int jobId, long[] out) {
        if (out == null || out.length < ASSET_POLL_SLOTS) {
            throw new IllegalArgumentException(
                    "le tableau de sondage compte au moins " + ASSET_POLL_SLOTS + " éléments");
        }
        return assetPoll(ctx, jobId, out);
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

    // --- Cycle de simulation (IF-03) --------------------------------------

    /** Nombre de valeurs qu'{@link #collect} écrit, une par champ du résultat. */
    public static final int SIM_COLLECT_SLOTS = 7;

    /** Index de {@code state_count} (BodyState déposés) dans le tableau de collect. */
    public static final int SIM_STATE_COUNT = 0;

    /** Index de {@code event_count} (PhysicsEvent déposés). */
    public static final int SIM_EVENT_COUNT = 1;

    /** Index de {@code deform_page_count} (pages de déformation, M6). */
    public static final int SIM_DEFORM_PAGE_COUNT = 2;

    /** Index de {@code refit_count} (colliders refités, M6). */
    public static final int SIM_REFIT_COUNT = 3;

    /** Index de {@code detach_count} (détachements, M7). */
    public static final int SIM_DETACH_COUNT = 4;

    /** Index de {@code net_bytes} (octets réseau prêts, M4). */
    public static final int SIM_NET_BYTES = 5;

    /** Index de {@code flags} ({@link #SIM_INCOMPLETE}, {@link #SIM_DEGRADED}). */
    public static final int SIM_FLAGS = 6;

    /** Drapeau collect : tout n'a pas été produit dans le délai (R-281). */
    public static final int SIM_INCOMPLETE = 1;

    /** Drapeau collect : la simulation tourne en qualité dégradée. */
    public static final int SIM_DEGRADED = 1 << 1;

    /**
     * Soumet les entrées d'un tick de simulation (IF-03).
     *
     * <p>Les commandes ont été écrites dans le tampon {@code SIM_IN} avant
     * l'appel ; leur nombre voyage en paramètre, pas dans l'en-tête (convention
     * IF-02, comme {@link #compileAsset}). R-280 : l'appel ne bloque pas au-delà
     * de {@code budgets.submit_ns}. Un cycle laissé ouvert par un
     * {@code submit} sans {@link #collect} est refermé au prochain {@code submit}
     * (R-282).
     *
     * @param ctx jeton de contexte
     * @param tick numéro de tick du serveur autoritatif
     * @param commandCount nombre de commandes écrites dans {@code SIM_IN}
     * @param impactCount nombre d'impacts écrits dans {@code IMPACT_IN} (0 avant M6)
     * @return {@link #OK}, ou un code d'erreur négatif
     */
    public static int submit(long ctx, long tick, int commandCount, int impactCount) {
        return simSubmit(ctx, tick, commandCount, impactCount);
    }

    /**
     * Récolte le résultat d'un tick (IF-03).
     *
     * <p>Avance la simulation, dépose les {@code BodyState} dans {@code SIM_OUT}
     * et les {@code PhysicsEvent} dans {@code EVENTS}, et écrit les sept
     * compteurs du résultat dans {@code out} — non signés, aux index
     * {@link #SIM_STATE_COUNT}..{@link #SIM_FLAGS}. R-281 : un résultat
     * {@link #SIM_INCOMPLETE} n'est jamais un abandon ; l'appelant conserve
     * l'état précédent et le travail se poursuit au tick suivant.
     *
     * @param ctx jeton de contexte
     * @param deadlineNs délai en nanosecondes, {@code 0} pour « sans limite »
     * @param out tableau d'au moins {@link #SIM_COLLECT_SLOTS} éléments
     * @return {@link #OK}, ou un code d'erreur négatif
     */
    public static int collect(long ctx, long deadlineNs, long[] out) {
        if (out == null || out.length < SIM_COLLECT_SLOTS) {
            throw new IllegalArgumentException(
                    "le tableau de collecte compte au moins " + SIM_COLLECT_SLOTS + " éléments");
        }
        return simCollect(ctx, deadlineNs, out);
    }

    /**
     * Annule le cycle de simulation courant (IF-03).
     *
     * <p>Les entrées soumises sont abandonnées et rien n'est avancé. Annuler un
     * cycle déjà clos est inoffensif.
     *
     * @param ctx jeton de contexte
     * @return {@link #OK}, ou un code d'erreur négatif
     */
    public static int cancel(long ctx) {
        return simCancel(ctx);
    }
}
