package dev.axion.bridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.LongAdder;

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
     * {@code E-1002}. 10 depuis l'animation (ADR-155) : {@link #animateRender}, le tick de rendu en tête de
     * {@code RENDER_IN} et les matrices des retenus animés dans {@code RENDER_OUT} ; 9 depuis le cycle de
     * rendu IF-05, son contexte hors du verrou de session et
     * le tampon {@code RENDER_IN} (ADR-154 D2, D3), après l'état restauré d'une assembly à sa
     * création et le sommeil des assemblies chargées (8, ADR-153 D3, D4), les assemblies à
     * plusieurs corps et leurs joints
     * (7, ADR-151 D7), l'empreinte déterministe et la compression des charges utiles réseau (6,
     * ADR-150 D4, D5).
     */
    public static final int EXPECTED_ABI_VERSION = 10;

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

    /**
     * Traversées de la frontière depuis le chargement de la classe : une par appel natif. INV-04
     * en borne le nombre par tick en régime établi ; T-012 le mesure sur la vraie bibliothèque.
     */
    private static final LongAdder CROSSINGS = new LongAdder();

    /** {@return le nombre d'appels natifs faits depuis le chargement de la classe, tous threads} */
    public static long crossings() {
        return CROSSINGS.sum();
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

    static native int assetCompile(
            long ctx, long assetId, int format, long sourceLen, long filesLen, byte[] optionsCbor);

    static native int assetPoll(long ctx, int jobId, long[] out);

    static native int assetCancel(long ctx, int jobId);

    static native int assetDiagnostics(long ctx, int jobId, long[] out);

    static native int detProfile(long[] out);

    static native int netCompress(long ctx, long inputLength, long[] out);

    static native int netDecompress(long ctx, long inputLength, long maxOutput, long[] out);

    static native int simSubmit(long ctx, long tick, int commandCount, int impactCount);

    static native int simCollect(long ctx, long deadlineNs, long[] out);

    static native int simCancel(long ctx);

    static native int assetLoad(long ctx, long assetId, int sectionsMask, long[] out);

    static native int assetGeometry(long ctx, int index, int generation, long[] out);

    static native int assetMaterials(long ctx, int index, int generation, long[] out);

    static native int assetTexture(long ctx, int index, int generation, int texture, long[] out);

    static native int assetUnload(long ctx, int index, int generation);

    static native int renderPrepare(long ctx, byte[] camera, float partialTick, long budgetNs, long[] out);

    static native int renderRelease(long ctx, int frameId);

    static native int renderAnimate(long ctx, byte[] commands);

    static native int debugFill(
            long ctx,
            long overlayMask,
            long dimension,
            double cameraX,
            double cameraY,
            double cameraZ,
            int maxSegments,
            long[] out);

    // --- API ---------------------------------------------------------------

    /**
     * {@return la version d'ABI de la bibliothèque chargée}
     *
     * <p>À appeler avant toute autre chose (R-260). Ne dépend d'aucun contexte.
     */
    public static int nativeAbiVersion() {
        CROSSINGS.increment();
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
        CROSSINGS.increment();
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
            CROSSINGS.increment();
            int needed = metricsExport(ctx, buffer);
            if (needed < 0) {
                throw new IllegalStateException("export des métriques refusé, code " + needed);
            }
            if (needed <= buffer.length) {
                return new String(buffer, 0, needed, StandardCharsets.UTF_8);
            }
            buffer = new byte[needed];
        }
        // Le natif rend la taille exacte du document qu'il garde pour l'appel
        // suivant : une seconde tentative suffit toujours, même si la mémoire
        // vivante que l'export décrit a changé entre-temps (ADR-133 D2).
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
        CROSSINGS.increment();
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
        CROSSINGS.increment();
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

    /**
     * La source référence des fichiers voisins que le natif n'a pas reçus (ADR-139) : leurs noms
     * attendent dans le tampon de sortie, et la compilation est à relancer avec eux. Le travail
     * est terminé ; sonder de nouveau rend {@link #E_INVALID_HANDLE}.
     */
    public static final int ASSET_NEEDS_FILES = 3;

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
     * <p>Les fichiers voisins (ADR-139) suivent la source dans le même tampon, en table : la
     * première soumission n'en porte aucune, et le sondage rend {@link #ASSET_NEEDS_FILES} si la
     * source en référence.
     *
     * @param ctx jeton de contexte
     * @param assetId identifiant de l'asset produit
     * @param format code de format, voir {@code SourceFormats}
     * @param sourceLen longueur de la source écrite dans le tampon d'entrée
     * @param filesLen longueur de la table des fichiers voisins qui suit la source, zéro sans
     *     table
     * @param optionsCbor texte du {@code <modèle>.axion.json} voisin en chaîne
     *     CBOR, ou {@code null} sans fichier (ADR-135) ; une option refusée fait
     *     échouer le travail, et son code se lit au sondage
     * @return l'identifiant du travail, strictement positif, ou un code d'erreur
     */
    public static int compileAsset(
            long ctx, long assetId, int format, long sourceLen, long filesLen, byte[] optionsCbor) {
        CROSSINGS.increment();
        return assetCompile(ctx, assetId, format, sourceLen, filesLen, optionsCbor);
    }

    /**
     * Sonde une compilation lancée (IF-06).
     *
     * <p>Le tableau reçoit {@code [état, taille, code d'erreur]}. Une
     * compilation aboutie dépose son A3D dans le tampon
     * {@code AXION_BUF_ASSET_OUT}, une compilation qui demande des fichiers
     * voisins y dépose leurs noms ({@link #ASSET_NEEDS_FILES}), et ce résultat n'est rendu
     * <strong>qu'une fois</strong> : sonder de nouveau rend
     * {@link #E_INVALID_HANDLE}, ce qui vaut mieux qu'une seconde lecture d'un
     * tampon qui a pu changer. Compilé ou échoué, le travail garde ses lignes pour
     * {@link #readAssetDiagnostics} (ADR-148).
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
        CROSSINGS.increment();
        return assetPoll(ctx, jobId, out);
    }

    /**
     * Abandonne une compilation que l'appelant n'attend plus (IF-06, ADR-140).
     *
     * <p>Le travail quitte la table du contexte, quel que soit son état : en file, il ne calcule
     * rien ; en cours, il va au bout et son résultat est libéré à la fin du calcul ; fini, tout de
     * suite. L'appel ne bloque jamais (R-280). Sonder ensuite le même identifiant rend
     * {@link #E_INVALID_HANDLE}.
     *
     * @param ctx jeton de contexte
     * @param jobId identifiant rendu par {@link #compileAsset}
     * @return {@link #OK}, ou {@link #E_INVALID_HANDLE} si l'identifiant ne désigne aucun travail
     *     retenu — jamais émis, repris par un sondage dont les lignes ont été lues (ADR-148), ou
     *     déjà abandonné
     */
    public static int cancelAsset(long ctx, int jobId) {
        CROSSINGS.increment();
        return assetCancel(ctx, jobId);
    }

    /**
     * Rend les lignes d'une compilation que le sondage a terminée (IF-06, ADR-148) : ses
     * avertissements si elle a abouti, le message de son échec sinon.
     *
     * <p>Les lignes sont déposées dans le tampon {@code AXION_BUF_ASSET_OUT}, au format de la
     * liste de {@link #ASSET_NEEDS_FILES}, et le premier élément du tableau reçoit leur taille ;
     * elles ne se lisent qu'une fois. Sans ligne, la taille vaut zéro et le tampon n'est pas
     * touché : l'A3D du sondage se lit donc <strong>avant</strong> cet appel.
     *
     * @param ctx jeton de contexte
     * @param jobId identifiant natif du travail sondé
     * @param out tableau d'au moins un élément
     * @return {@link #OK} ; {@link #E_INVALID_HANDLE} si le travail ne retient pas de lignes —
     *     jamais sondé, en cours, déjà lu ou abandonné ; {@link #E_INVALID_BUFFER} si le tampon
     *     ne peut pas les contenir
     */
    public static int readAssetDiagnostics(long ctx, int jobId, long[] out) {
        if (out == null || out.length < 1) {
            throw new IllegalArgumentException("le tableau des lignes compte au moins un élément");
        }
        CROSSINGS.increment();
        return assetDiagnostics(ctx, jobId, out);
    }

    /**
     * Rend l'empreinte de configuration déterministe de la bibliothèque (5.12bis), échangée au
     * handshake (ADR-150 D4). Sans contexte, comme {@link #nativeAbiVersion()} : c'est une
     * propriété du binaire.
     *
     * @param out tableau d'au moins trois éléments, qui reçoit l'empreinte, la version du noyau
     *     déterministe, et 1 si la configuration figure, validée, dans la matrice (R-516)
     * @return {@link #OK}, ou un code d'erreur
     */
    public static int readDetProfile(long[] out) {
        if (out == null || out.length < 3) {
            throw new IllegalArgumentException("le tableau de l'empreinte compte au moins trois éléments");
        }
        CROSSINGS.increment();
        return detProfile(out);
    }

    /**
     * Compresse en zstd la charge utile réseau que {@code AXION_BUF_NET_OUT} porte après son
     * en-tête (C-51, ADR-150 D5). La trame la remplace, et le premier élément du tableau reçoit sa
     * taille ; le tampon a pu être agrandi (R-270) : la vue se réacquiert avant de lire.
     *
     * @param ctx jeton de contexte
     * @param inputLength octets de la charge, après l'en-tête
     * @param out tableau d'au moins un élément
     * @return {@link #OK} ; {@link #E_INVALID_BUFFER} si la charge sort du tampon ou dépasse le
     *     plafond natif
     */
    public static int compressNet(long ctx, long inputLength, long[] out) {
        if (out == null || out.length < 1) {
            throw new IllegalArgumentException("le tableau de la taille compte au moins un élément");
        }
        CROSSINGS.increment();
        return netCompress(ctx, inputLength, out);
    }

    /**
     * Décompresse la trame zstd que {@code AXION_BUF_NET_OUT} porte après son en-tête, sans
     * dépasser {@code maxOutput} octets (C-51, ADR-150 D5, R-1691). Le contenu la remplace, et le
     * premier élément du tableau reçoit sa taille ; la vue se réacquiert avant de lire.
     *
     * @param ctx jeton de contexte
     * @param inputLength octets de la trame, après l'en-tête
     * @param maxOutput taille maximale admise du contenu
     * @param out tableau d'au moins un élément
     * @return {@link #OK} ; {@link #E_INVALID_BUFFER} si ce n'est pas une trame zstd, si elle
     *     déclare plus que la borne ou ment sur sa taille, ou si elle sort du tampon
     */
    public static int decompressNet(long ctx, long inputLength, long maxOutput, long[] out) {
        if (out == null || out.length < 1) {
            throw new IllegalArgumentException("le tableau de la taille compte au moins un élément");
        }
        CROSSINGS.increment();
        return netDecompress(ctx, inputLength, maxOutput, out);
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
        CROSSINGS.increment();
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
        CROSSINGS.increment();
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
        CROSSINGS.increment();
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
        CROSSINGS.increment();
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
        CROSSINGS.increment();
        return simCancel(ctx);
    }

    // --- Assets chargés (IF-06, ADR-119, ADR-122) --------------------------

    /**
     * Bit de la section {@code NODE} dans un masque de sections : le rang du tag
     * dans la table normative de la PARTIE 7 (ADR-119).
     */
    public static final int SECTION_NODE = 1;

    /** Bit de la section {@code GEOM} dans un masque de sections (ADR-119). */
    public static final int SECTION_GEOM = 1 << 1;

    /** Bit de la section {@code MATL} dans un masque de sections (ADR-122). */
    public static final int SECTION_MATL = 1 << 2;

    /** Bit de la section {@code TEXR} dans un masque de sections (ADR-122). */
    public static final int SECTION_TEXR = 1 << 3;

    /** Nombre de valeurs que {@link #loadAsset} écrit : index et génération du handle. */
    public static final int ASSET_LOAD_SLOTS = 2;

    /**
     * Charge un asset compilé dans le natif et rend son handle (IF-06, ADR-119).
     *
     * <p>Le conteneur A3D doit avoir été écrit dans la charge utile du tampon
     * {@code AXION_BUF_ASSET_IN} avant l'appel ; il se délimite lui-même. Le
     * tableau reçoit {@code [index, génération]}. Le handle appartient à
     * l'appelant, qui doit le rendre par {@link #unloadAsset} (R-321) : un handle
     * oublié est signalé à l'arrêt.
     *
     * @param ctx jeton de contexte
     * @param assetId identifiant de l'asset, celui que porte son en-tête
     * @param sectionsMask sections à charger parmi {@link #SECTION_NODE},
     *     {@link #SECTION_GEOM}, {@link #SECTION_MATL} et {@link #SECTION_TEXR} — ces deux
     *     dernières ensemble, ou aucune
     * @param out tableau d'au moins {@link #ASSET_LOAD_SLOTS} éléments
     * @return {@link #OK}, ou un code d'erreur négatif
     */
    public static int loadAsset(long ctx, long assetId, int sectionsMask, long[] out) {
        if (out == null || out.length < ASSET_LOAD_SLOTS) {
            throw new IllegalArgumentException(
                    "le tableau de chargement compte au moins " + ASSET_LOAD_SLOTS + " éléments");
        }
        CROSSINGS.increment();
        return assetLoad(ctx, assetId, sectionsMask, out);
    }

    /**
     * Dépose dans {@code AXION_BUF_ASSET_OUT} la géométrie d'un asset chargé et
     * sa liste de dessin au repos (ADR-119) ; {@code out[0]} reçoit la taille de
     * la charge utile.
     *
     * @param ctx jeton de contexte
     * @param index index du handle
     * @param generation génération du handle
     * @param out tableau d'au moins un élément
     * @return {@link #OK}, {@link #E_INVALID_HANDLE} si le handle est périmé,
     *     {@link #E_INVALID_BUFFER} si l'asset n'a pas été chargé avec
     *     {@code NODE | GEOM}
     */
    public static int geometryOf(long ctx, int index, int generation, long[] out) {
        if (out == null || out.length < 1) {
            throw new IllegalArgumentException("le tableau de géométrie compte au moins un élément");
        }
        CROSSINGS.increment();
        return assetGeometry(ctx, index, generation, out);
    }

    /**
     * Dépose dans {@code AXION_BUF_ASSET_OUT} la table des matériaux et des textures d'un
     * asset chargé (ADR-122 §6) ; {@code out[0]} reçoit la taille de la charge utile.
     *
     * @param ctx jeton de contexte
     * @param index index du handle
     * @param generation génération du handle
     * @param out tableau d'au moins un élément
     * @return {@link #OK}, {@link #E_INVALID_HANDLE} si le handle est périmé,
     *     {@link #E_INVALID_BUFFER} si l'asset n'a pas été chargé avec
     *     {@code MATL | TEXR}
     */
    public static int materialsOf(long ctx, int index, int generation, long[] out) {
        if (out == null || out.length < 1) {
            throw new IllegalArgumentException("le tableau des matériaux compte au moins un élément");
        }
        CROSSINGS.increment();
        return assetMaterials(ctx, index, generation, out);
    }

    /**
     * Dépose dans {@code AXION_BUF_ASSET_OUT} les octets PNG de la texture embarquée de
     * rang {@code texture} d'un asset chargé (ADR-122 §6), une texture par appel ;
     * {@code out[0]} reçoit leur nombre.
     *
     * @param ctx jeton de contexte
     * @param index index du handle
     * @param generation génération du handle
     * @param texture rang de la texture dans la table des matériaux
     * @param out tableau d'au moins un élément
     * @return {@link #OK}, {@link #E_INVALID_HANDLE} si le handle est périmé,
     *     {@link #E_INVALID_BUFFER} si la texture est hors de la table ou n'est pas
     *     embarquée
     */
    public static int textureOf(long ctx, int index, int generation, int texture, long[] out) {
        if (out == null || out.length < 1) {
            throw new IllegalArgumentException("le tableau de texture compte au moins un élément");
        }
        CROSSINGS.increment();
        return assetTexture(ctx, index, generation, texture, out);
    }

    /**
     * Rend un asset chargé (IF-06) : son handle devient périmé (R-110).
     *
     * @param ctx jeton de contexte
     * @param index index du handle
     * @param generation génération du handle
     * @return {@link #OK}, ou {@link #E_INVALID_HANDLE} si le handle est déjà
     *     périmé
     */
    public static int unloadAsset(long ctx, int index, int generation) {
        CROSSINGS.increment();
        return assetUnload(ctx, index, generation);
    }

    // --- Cycle de rendu (IF-05, ADR-154) ------------------------------------

    /** Taille de {@code AxionCameraDesc}, en octets (ADR-154 D3). */
    public static final int CAMERA_DESC_BYTES = 112;

    /** Taille d'un candidat de {@code RENDER_IN}, en octets (ADR-154 D3). */
    public static final int RENDER_CANDIDATE_BYTES = 48;

    /** Nombre de valeurs qu'écrit {@link #prepareRender}, une par champ d'{@code AxionRenderResult}. */
    public static final int RENDER_PREPARE_SLOTS = 8;

    /** Index des candidats retenus dans le résultat de {@link #prepareRender}. */
    public static final int RENDER_INSTANCE_COUNT = 0;

    /** Index de l'estimation des triangles. */
    public static final int RENDER_TRIANGLE_ESTIMATE = 5;

    /** Index de l'identifiant de frame, à rendre par {@link #releaseRender}. */
    public static final int RENDER_FRAME_ID = 6;

    /** Index des drapeaux. */
    public static final int RENDER_FLAGS = 7;

    /** Drapeau du résultat : partiel, l'échéance passée (R-300). */
    public static final int RENDER_DEGRADED = 1;

    /**
     * Drapeau du résultat : la frame dépassait {@code budgets.max_visible_instances} ; elle est descendue
     * d'un niveau de détail et ses retenus les plus lointains ont été écartés (C-64, étape 6).
     */
    public static final int RENDER_CAPPED = 1 << 1;

    /**
     * Prépare une frame de rendu (IF-05, §4.7) : le natif lit les candidats déjà écrits dans
     * {@code RENDER_IN}, les culle, et dépose les retenus dans {@code RENDER_OUT}, valides jusqu'à
     * {@link #releaseRender} (R-301). L'appel ne prend pas le verrou que tient le pas physique
     * (ADR-154 D2).
     *
     * @param ctx jeton de contexte
     * @param camera {@code AxionCameraDesc}, {@link #CAMERA_DESC_BYTES} octets little-endian
     * @param partialTick fraction de tick de la frame
     * @param budgetNs budget de la préparation, {@code budgets.render_prep_ns}
     * @param out tableau d'au moins {@link #RENDER_PREPARE_SLOTS} éléments
     * @return {@link #OK}, ou un code d'erreur négatif
     */
    public static int prepareRender(long ctx, byte[] camera, float partialTick, long budgetNs, long[] out) {
        if (camera == null || camera.length != CAMERA_DESC_BYTES) {
            throw new IllegalArgumentException("la caméra fait " + CAMERA_DESC_BYTES + " octets");
        }
        if (out == null || out.length < RENDER_PREPARE_SLOTS) {
            throw new IllegalArgumentException(
                    "le tableau de préparation compte au moins " + RENDER_PREPARE_SLOTS + " éléments");
        }
        CROSSINGS.increment();
        return renderPrepare(ctx, camera, partialTick, budgetNs, out);
    }

    /**
     * Pousse des commandes d'animation au contexte de rendu (ADR-155, ABI 10) : chacune un en-tête de
     * 16 octets (id du candidat, réservé, handle d'asset), une commande de 40 octets et ses nodes de
     * masque. L'appel ne prend pas le verrou que tient le pas physique (ADR-154 D2).
     *
     * @param ctx jeton de contexte
     * @param commands les commandes, little-endian
     * @return le nombre de commandes appliquées, ou un code d'erreur négatif ({@link #E_INVALID_BUFFER} si
     *     le tampon est tronqué : rien n'est alors appliqué)
     */
    public static int animateRender(long ctx, byte[] commands) {
        if (commands == null) {
            throw new IllegalArgumentException("commandes attendues");
        }
        CROSSINGS.increment();
        return renderAnimate(ctx, commands);
    }

    /**
     * Libère une frame de rendu (R-301).
     *
     * @param ctx jeton de contexte
     * @param frameId identifiant rendu par {@link #prepareRender}
     * @return {@link #OK}, ou {@link #E_INVALID_HANDLE} si la frame n'est pas celle en vol
     */
    public static int releaseRender(long ctx, int frameId) {
        CROSSINGS.increment();
        return renderRelease(ctx, frameId);
    }

    // --- Géométrie de debug (C-67, ADR-121) --------------------------------

    /**
     * Overlay {@code colliders} : bit 0 du masque, rang de l'overlay dans la liste du §31.4
     * (ADR-121).
     */
    public static final long OVERLAY_COLLIDERS = 1L;

    /** Overlay {@code aabb} : bit 1 (amendement A1 d'ADR-121). */
    public static final long OVERLAY_AABB = 1L << 1;

    /** Overlay {@code bodies} : bit 2 (A1). */
    public static final long OVERLAY_BODIES = 1L << 2;

    /** Overlay {@code contacts} : bit 3 (A1). */
    public static final long OVERLAY_CONTACTS = 1L << 3;

    /** Overlay {@code normals} : bit 4 (A1). */
    public static final long OVERLAY_NORMALS = 1L << 4;

    /** Overlay {@code joints} : bit 5 (ADR-151 D7). */
    public static final long OVERLAY_JOINTS = 1L << 5;

    /** Overlay {@code limits} : bit 6 (ADR-151 D7). */
    public static final long OVERLAY_LIMITS = 1L << 6;

    /** Overlay {@code world_tiles} : bit 30 (A1). */
    public static final long OVERLAY_WORLD_TILES = 1L << 30;

    /** Overlay {@code center_of_mass} : bit 31 (A1). */
    public static final long OVERLAY_CENTER_OF_MASS = 1L << 31;

    /**
     * Dépose dans {@code AXION_BUF_DEBUG} la géométrie des overlays demandés (ADR-121 et son
     * amendement A1) : corps d'assembly et tuiles de la dimension, les plus proches de la caméra
     * d'abord, au plus {@code maxSegments} segments, chaque plage dans son repère (schéma 2) ;
     * {@code out[0]} reçoit la taille de la charge utile.
     *
     * <p>À n'appeler que lorsqu'un overlay est allumé (R-800). Lecture seule côté natif.
     *
     * @param ctx jeton de contexte
     * @param overlayMask overlays demandés, parmi les neuf {@code OVERLAY_*}
     * @param dimension identifiant de dimension, celui des commandes de simulation
     * @param cameraX position monde de la caméra
     * @param cameraY position monde de la caméra
     * @param cameraZ position monde de la caméra
     * @param maxSegments budget de segments, positif ou nul
     * @param out tableau d'au moins un élément
     * @return {@link #OK}, ou {@link #E_INVALID_BUFFER} pour un masque, une caméra ou un
     *     budget refusé
     */
    public static int fillDebug(
            long ctx,
            long overlayMask,
            long dimension,
            double cameraX,
            double cameraY,
            double cameraZ,
            int maxSegments,
            long[] out) {
        if (out == null || out.length < 1) {
            throw new IllegalArgumentException("le tableau de debug compte au moins un élément");
        }
        CROSSINGS.increment();
        return debugFill(ctx, overlayMask, dimension, cameraX, cameraY, cameraZ, maxSegments, out);
    }
}
