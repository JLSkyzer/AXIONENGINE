package dev.axion.bootstrap;

import dev.axion.bootstrap.BootstrapOutcome.Phase;
import dev.axion.bootstrap.BootstrapOutcome.Reason;
import dev.axion.bridge.BufferKinds;
import dev.axion.bridge.CborWriter;
import dev.axion.bridge.NativeBridge;
import dev.axion.config.AxionConfig;
import dev.axion.config.ConfigLoader;
import dev.axion.config.ConfigSchema.Scope;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;

/**
 * Séquence de démarrage d'AXION (C-02).
 *
 * <p>Elle suit l'ordre du cahier des charges :
 *
 * <pre>
 * INIT -&gt; CONFIG -&gt; LOAD_NATIVE -&gt; HANDSHAKE -&gt; PROBE -&gt; READY | DISABLED
 * </pre>
 *
 * <p><strong>Aucune étape ne lève d'exception.</strong> Un échec entre la
 * détection de plateforme et l'acquisition des tampons se journalise et conduit
 * à {@code DISABLED} : le mod se charge, le jeu reste jouable, et les
 * assemblies restent inertes sans que leur NBT soit touché (R-410, INV-11).
 * C'est la seule conduite acceptable pour un mod qui ajoute un moteur : son
 * indisponibilité ne doit jamais empêcher de jouer.
 *
 * <p>Deux étapes de la fiche C-02 ne sont pas encore ici, faute des composants
 * qu'elles interrogent : les capacités GPU côté client (M3) et le profil de
 * qualité initial du gouverneur (C-77, M5). Leur absence ne change pas la
 * séquence, qui passe directement en {@code READY}.
 */
public final class AxionBootstrap {

    /**
     * Nombre d'itérations de la calibration FFI (R-330).
     *
     * <p>La valeur est mesurée, jamais supposée : elle dimensionne ensuite la
     * taille des lots, et l'hypothèse H-07 sur le coût d'un appel JNI n'est
     * qu'une hypothèse.
     */
    private static final int CALIBRATION_ITERATIONS = 10_000;

    /** Taille demandée à l'acquisition de contrôle des tampons, en octets. */
    private static final long PROBE_CAPACITY = 4096;

    /** Position de la génération dans l'en-tête d'un tampon (IF-02). */
    private static final int GENERATION_OFFSET = 8;

    private AxionBootstrap() {
        throw new AssertionError("classe utilitaire, non instanciable");
    }

    /**
     * Démarre AXION.
     *
     * @param scope portée de configuration à charger, selon le côté
     * @param gameDir répertoire de jeu
     * @param configDir répertoire de configuration
     * @param systemProperties propriétés système, source des surcharges
     *     {@code -Daxion.*}
     * @return l'issue du démarrage, jamais {@code null}
     */
    public static BootstrapOutcome start(
            Scope scope, Path gameDir, Path configDir, Properties systemProperties) {
        return start(
                scope,
                gameDir,
                configDir,
                systemProperties,
                NativeLoader::load,
                NativeApi.real());
    }

    /**
     * Démarre AXION, chargeur natif et surface native fournis.
     *
     * <p>Les deux sont injectables pour que les chemins d'échec soient
     * testables : sans cela, ni le refus d'ABI ni un `axion_init` fautif ne
     * seraient jamais vérifiés, alors que ce sont eux qui garantissent que le
     * jeu reste jouable.
     *
     * @param scope portée de configuration
     * @param gameDir répertoire de jeu
     * @param configDir répertoire de configuration
     * @param systemProperties propriétés système
     * @param loader chargement de la bibliothèque native
     * @param native_ surface native
     * @return l'issue du démarrage, jamais {@code null}
     */
    public static BootstrapOutcome start(
            Scope scope,
            Path gameDir,
            Path configDir,
            Properties systemProperties,
            Function<Path, NativeLoadResult> loader,
            NativeApi native_) {

        List<String> diagnostics = new ArrayList<>();

        // --- CONFIG --------------------------------------------------------
        try {
            if (ConfigLoader.writeReferenceIfAbsent(Scope.COMMON, configDir)) {
                diagnostics.add("fichier de configuration de référence créé : "
                        + Scope.COMMON.fileName());
            }
            if (scope != Scope.COMMON && ConfigLoader.writeReferenceIfAbsent(scope, configDir)) {
                diagnostics.add("fichier de configuration de référence créé : "
                        + scope.fileName());
            }
        } catch (Exception failure) {
            // Un répertoire de configuration non inscriptible n'empêche pas de
            // démarrer : les défauts compilés font foi.
            diagnostics.add("écriture des fichiers de référence impossible : " + failure);
        }

        AxionConfig config = ConfigLoader.load(Scope.COMMON, configDir, systemProperties);
        config.issues().forEach(issue -> diagnostics.add(issue.path() + " — " + issue.message()));

        AxionConfig sideConfig =
                scope == Scope.COMMON ? null : ConfigLoader.load(scope, configDir, systemProperties);
        if (sideConfig != null) {
            sideConfig
                    .issues()
                    .forEach(issue -> diagnostics.add(issue.path() + " — " + issue.message()));
        }

        if (!config.getBoolean("general.enabled")) {
            return disabled(
                    config,
                    Reason.CONFIGURATION,
                    "general.enabled vaut faux : AXION reste inactif, à la demande",
                    diagnostics);
        }

        // --- LOAD_NATIVE ---------------------------------------------------
        NativeLoadResult loaded;
        try {
            loaded = loader.apply(gameDir);
        } catch (RuntimeException | UnsatisfiedLinkError failure) {
            // Le chargeur ne devrait rien lever, mais une bibliothèque native
            // qui refuse de se lier peut le faire depuis n'importe où.
            return disabled(
                    config,
                    Reason.NATIVE_UNAVAILABLE,
                    "chargement de la bibliothèque native impossible : " + failure,
                    diagnostics);
        }

        if (loaded instanceof NativeLoadResult.Failed failed) {
            return disabled(
                    config,
                    Reason.NATIVE_UNAVAILABLE,
                    failed.reason() + " — " + failed.detail(),
                    diagnostics);
        }
        NativeLoadResult.Loaded ok = (NativeLoadResult.Loaded) loaded;
        diagnostics.add("bibliothèque native chargée : " + ok.path());

        // --- HANDSHAKE -----------------------------------------------------
        // R-260 : la version d'ABI se compare avant tout autre appel.
        int abi;
        try {
            abi = native_.abiVersion();
        } catch (RuntimeException | UnsatisfiedLinkError failure) {
            return disabled(
                    config,
                    Reason.ABI_MISMATCH,
                    "la bibliothèque n'expose pas de version d'ABI : " + failure,
                    diagnostics);
        }
        if (abi != NativeBridge.EXPECTED_ABI_VERSION) {
            return disabled(
                    config,
                    Reason.ABI_MISMATCH,
                    "ABI " + abi + " côté natif, " + NativeBridge.EXPECTED_ABI_VERSION
                            + " attendue — réinstaller le JAR complet",
                    diagnostics);
        }

        // --- PROBE ---------------------------------------------------------
        long context;
        try {
            context = native_.initialize(encodeConfig(config, sideConfig));
        } catch (RuntimeException failure) {
            return disabled(
                    config, Reason.INIT_REFUSED, "axion_init a échoué : " + failure, diagnostics);
        }
        if (context <= 0) {
            return disabled(
                    config,
                    Reason.INIT_REFUSED,
                    "axion_init a refusé, code " + context,
                    diagnostics);
        }

        long roundtrip = calibrate(native_);
        diagnostics.add("aller-retour FFI mesuré : " + roundtrip + " ns (médiane sur "
                + CALIBRATION_ITERATIONS + " itérations)");

        ByteBuffer probe = native_.acquire(context, BufferKinds.SIM_OUT, PROBE_CAPACITY);
        if (probe == null) {
            String detail = "acquisition d'un tampon refusée : " + native_.lastErrorMessage(context);
            // Le contexte est refermé : rien ne doit rester ouvert derrière un
            // démarrage qui n'aboutit pas.
            native_.close(context);
            return disabled(config, Reason.BUFFERS_UNAVAILABLE, detail, diagnostics);
        }

        // Le tampon de contrôle est rendu aussitôt vérifié. Le garder ouvert
        // fausserait le bilan d'allocations de l'arrêt (R-322) — c'est
        // exactement ce que le bilan a signalé au premier démarrage réel — et
        // le protocole veut de toute façon qu'on acquière à chaque tick plutôt
        // que de conserver une vue (R-270).
        probe.order(ByteOrder.LITTLE_ENDIAN);
        int generation = probe.getInt(GENERATION_OFFSET);
        int released = native_.release(context, BufferKinds.SIM_OUT, generation);
        if (released != 0) {
            diagnostics.add("tampon de contrôle non relâché, code " + released);
        }

        return new BootstrapOutcome(
                Phase.READY, context, config, null, "", roundtrip, diagnostics);
    }

    /**
     * Mesure le coût médian d'un aller-retour FFI (R-330).
     *
     * <p>La médiane, et non la moyenne : une pause du ramasse-miettes ou une
     * préemption suffisent à décaler une moyenne, alors que la médiane décrit
     * le coût habituel, seul utile pour dimensionner des lots.
     */
    private static long calibrate(NativeApi native_) {
        long[] samples = new long[CALIBRATION_ITERATIONS];
        for (int index = 0; index < CALIBRATION_ITERATIONS; index++) {
            long start = System.nanoTime();
            native_.abiVersion();
            samples[index] = System.nanoTime() - start;
        }
        Arrays.sort(samples);
        return samples[samples.length / 2];
    }

    /**
     * Encode la configuration à transmettre au natif.
     *
     * <p>Les deux portées sont fusionnées en une seule map : leurs chemins ne
     * se recouvrent pas, chaque option n'appartenant qu'à un fichier.
     */
    private static byte[] encodeConfig(AxionConfig common, AxionConfig side) {
        Map<String, Object> merged = new LinkedHashMap<>(common.values());
        if (side != null) {
            merged.putAll(side.values());
        }
        return CborWriter.encodeMap(merged);
    }

    private static BootstrapOutcome disabled(
            AxionConfig config, Reason reason, String detail, List<String> diagnostics) {
        return new BootstrapOutcome(
                Phase.DISABLED, 0L, config, reason, detail, -1L, diagnostics);
    }
}
