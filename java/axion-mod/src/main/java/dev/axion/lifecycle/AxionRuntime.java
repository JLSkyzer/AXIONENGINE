package dev.axion.lifecycle;

import dev.axion.asset.AssetCache;
import dev.axion.asset.AssetRegistry;
import dev.axion.asset.AssetSource;
import dev.axion.asset.NativeAssetCompiler;
import dev.axion.bootstrap.AxionBootstrap;
import dev.axion.bootstrap.BootstrapOutcome;
import dev.axion.bootstrap.NativeApi;
import dev.axion.config.ConfigSchema;
import dev.axion.config.ConfigSchema.Scope;
import dev.axion.definition.BlockMaterialMappings;
import dev.axion.definition.CollisionGroupRegistry;
import dev.axion.definition.DefinitionRegistry;
import dev.axion.definition.DefinitionRules;
import dev.axion.definition.PhysicsMaterialTable;
import dev.axion.definition.PhysicsMaterials;
import dev.axion.physics.CollectResult;
import dev.axion.physics.DegradationGauges;
import dev.axion.physics.NativeSimulation;
import dev.axion.physics.SimCommandProvider;
import dev.axion.physics.SimCommandStream;
import dev.axion.physics.SimEventSink;
import dev.axion.physics.SimStateSink;
import dev.axion.physics.SimulationJournal;
import dev.axion.physics.SimulationTrace;
import dev.axion.physics.StepBreakdown;
import dev.axion.debug.DevMode;
import dev.axion.diag.AssetCacheMetrics;
import dev.axion.diag.DefinitionMetrics;
import dev.axion.diag.JavaMetrics;
import dev.axion.net.DetProfile;
import dev.axion.net.NativeNetCompression;
import dev.axion.net.NetCompression;
import dev.axion.net.RegistryContent;
import dev.axion.net.RemoteRegistry;
import dev.axion.net.ServerPeer;
import dev.axion.net.ServerRegistry;
import dev.axion.net.ServerSnapshot;
import dev.axion.platform.PlatformAdapter;
import dev.axion.render.RenderCapabilities;
import dev.axion.world.BlockMaterials;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;
import java.util.function.IntFunction;

/**
 * Cycle de vie d'AXION, indépendant de la plateforme (C-01).
 *
 * <p>Cette classe reçoit les transitions que l'intégration Forge lui transmet et
 * décide quoi faire. Elle ne connaît pas Forge : c'est ce qui la rend testable,
 * et ce qui permet à R-401 de tenir — seul {@code dev.axion.forge} importe
 * {@code net.minecraftforge}.
 *
 * <p>Chaque transition passe par un {@link HookGuard} : une exception levée ici
 * ne doit jamais remonter jusqu'à la distribution d'un événement, sous peine
 * d'interrompre le travail des autres mods (R-400).
 */
public final class AxionRuntime {

    /** Taille maximale d'une source, qui borne aussi les fichiers voisins gardés (ADR-139). */
    private static final String MAX_SOURCE_BYTES = "assets.max_source_bytes";

    private static final String MAX_CUSTOM_BYTES = "persistence.max_custom_bytes";

    private final Map<String, HookGuard> guards = new LinkedHashMap<>();
    private final BiFunction<PlatformAdapter, Properties, BootstrapOutcome> bootstrap;
    private final NativeApi nativeApi;
    private final List<String> transitions = new ArrayList<>();

    private LifecyclePhase phase = LifecyclePhase.UNLOADED;
    private PlatformAdapter platform;
    private BootstrapOutcome outcome;
    private boolean shuttingDown;
    /**
     * Registres remplacés d'un bloc à chaque rechargement, et lus depuis le thread de rendu
     * client (ADR-119 §8) : {@code volatile}, pour que ce thread voie le registre publié et non
     * une référence périmée ou à moitié construite.
     */
    private volatile AssetRegistry assets;
    /**
     * Registre d'assets de ce client (ADR-150 D3) : ses resource packs, puis ses mods, compilés chez
     * lui, en solo comme en multijoueur. {@code null} sur un serveur dédié, ou avant le premier
     * rechargement des ressources du client. Son cache est le sien, {@value #CLIENT_CACHE_DIR} : un
     * cache s'ouvre seul sur son répertoire, et le serveur intégré tient le sien.
     */
    private volatile AssetRegistry clientAssets;
    private volatile DefinitionRegistry definitions = DefinitionRegistry.empty();
    private volatile PhysicsMaterials physicsMaterials = PhysicsMaterials.empty();
    /** Table des matériaux de la session (ADR-149) : index stables, renvoyée au natif quand elle change. */
    private final PhysicsMaterialTable materialTable = new PhysicsMaterialTable();
    private volatile BlockMaterials blockMaterials = BlockMaterials.empty();
    private volatile CollisionGroupRegistry collisionGroups = CollisionGroupRegistry.empty();
    /** Un registre des groupes a déjà été chargé : le suivant qui diffère se journalise. */
    private boolean collisionGroupsLoaded;

    /** Textes des matériaux physiques du dernier rechargement, pour le registre réseau (ADR-150 D5). */
    private SortedMap<String, byte[]> materialTexts = new TreeMap<>();
    /** Textes des groupes de collision du dernier rechargement, pour le registre réseau (ADR-150 D5). */
    private SortedMap<String, byte[]> groupTexts = new TreeMap<>();
    /**
     * Ce que le serveur envoie à chaque connexion (ADR-150 D4, D5), rebâti après chaque rechargement
     * des definitions ; {@code null} sans runtime natif ou hors d'un serveur. {@code volatile} : Forge
     * le lit sur un thread de Netty, à l'arrivée d'un client.
     */
    private volatile ServerSnapshot netSnapshot;
    /** Les definitions tenues viennent d'un serveur distant (ADR-150 D5) : la déconnexion les oublie. */
    private volatile boolean remoteDefinitions;
    /** Les connexions en cours, vues du serveur, par joueur : leur mode de réplication (5.12bis). */
    private final Map<String, ServerPeer> peers = new ConcurrentHashMap<>();

    /**
     * Capacités du backend de rendu du client (R-1493), posées par la passe de rendu à chaque
     * choix de backend ; {@code null} sur un serveur dédié, ou tant qu'aucun monde client n'a
     * été chargé. {@code volatile} : posées sur le thread de rendu, lues par
     * {@code /axion status} sur le thread du serveur intégré.
     */
    private volatile RenderCapabilities renderCapabilities;

    /**
     * Ce qui détient des handles natifs hors du runtime (cache de maillages client, R-321) et
     * doit les rendre avant la fermeture du contexte (R-322). Appelés dans {@link #shutdown()},
     * sur le thread qui arrête — d'où une liste sûre entre threads.
     */
    private final List<Runnable> nativeReleasers = new CopyOnWriteArrayList<>();

    /** Pilote du cycle de simulation (IF-03), créé au premier tick opérationnel. */
    private NativeSimulation simulation;
    /**
     * Budget de la simulation ({@code budgets.sim_ns_per_tick}), en ns : lu avec le pilote, comme
     * le natif le lit à l'ouverture du contexte, dont il ne change plus.
     */
    private long simBudgetNs;

    /** {@code budgets.idle_hook_ns} : ce que coûte le hook de tick quand rien n'est simulé. */
    private long idleHookBudgetNs;

    /** Le dernier tick n'a simulé ni corps ni événement. */
    private boolean lastTickIdle;

    /** INV-19 : le hook de tick à vide, mesuré côté Java (ADR-133 D1). */
    private static final JavaMetrics.Budget IDLE_HOOK =
            JavaMetrics.GLOBAL.budget("budgets.idle_hook_ns", "ns");
    /** R-500 : l'activité du cache d'assets (C-25), à travers les rechargements. */
    private static final AssetCacheMetrics ASSET_CACHE_METRICS = new AssetCacheMetrics(JavaMetrics.GLOBAL);
    /** Répertoire du cache d'assets du serveur, sous {@code <gameDir>/axion/}. */
    static final String SERVER_CACHE_DIR = "cache";
    /** Répertoire du cache d'assets du client, sous {@code <gameDir>/axion/} (ADR-150 D3). */
    static final String CLIENT_CACHE_DIR = "client-cache";
    /** R-500 : les rechargements des definitions (C-27). */
    private static final DefinitionMetrics DEFINITION_METRICS = new DefinitionMetrics(JavaMetrics.GLOBAL);
    /** Trace de la simulation en cours d'écriture ({@code /axion debug trace}), ou {@code null}. */
    private SimulationTrace trace;
    /** Fichier de la trace en cours, ou {@code null}. */
    private Path tracePath;
    /**
     * Sources des commandes de tick (tuiles du monde C-38, assemblies C-40), posées par la
     * couche Forge quand un serveur démarre. Vide → cycle à vide (aucune commande). Les flux
     * sont fusionnés dans l'ordre d'ajout.
     */
    private final List<SimCommandProvider> commandProviders = new ArrayList<>();
    /**
     * Puits des états collectés (boucle C-40 ↔ C-50), posé par la couche Forge. Absent → les
     * états ne sont pas réappliqués (aucune entité à piloter).
     */
    private SimStateSink stateSink;
    /**
     * Puits des événements collectés (R-1010), posé par la couche Forge : effets sur les entités
     * vanilla (R-614, ADR-123 §7). Absent → les événements ne touchent aucune entité.
     */
    private SimEventSink eventSink;
    /**
     * Faits de simulation du tick (FM-20, R-180, FM-21, FM-22, R-1880, R-281), que la couche
     * Forge journalise après chaque tick ({@link #drainSimulationJournal()}). Il survit aux
     * serveurs successifs d'un client, comme le contexte natif dont il suit le palier, et
     * repart à neuf avec un nouveau contexte.
     */
    private SimulationJournal simulationJournal = new SimulationJournal();
    /** L'export des métriques s'est-il montré illisible pendant un relevé de palier ? Dit une fois. */
    private boolean gaugesUnreadableNoted;

    /**
     * Le cycle de simulation était-il sain au tick précédent ? Sert à ne
     * journaliser qu'aux transitions (sain ↔ refusé), jamais à chaque tick.
     */
    private boolean simulationHealthy = true;

    /** Crée un runtime qui démarre AXION par la séquence normale. */
    public AxionRuntime() {
        this(AxionRuntime::defaultBootstrap, NativeApi.real());
    }

    /**
     * Crée un runtime dont la séquence de démarrage est fournie.
     *
     * <p>La surface native l'est aussi : l'arrêt doit pouvoir fermer le
     * contexte, et un test qui ne charge aucune bibliothèque ne peut pas passer
     * par la vraie.
     *
     * @param bootstrap séquence à exécuter au passage en {@code SETUP}
     */
    public AxionRuntime(BiFunction<PlatformAdapter, Properties, BootstrapOutcome> bootstrap) {
        this(bootstrap, NativeApi.real());
    }

    /**
     * Crée un runtime dont la séquence de démarrage et la surface native sont
     * fournies.
     *
     * @param bootstrap séquence à exécuter au passage en {@code SETUP}
     * @param nativeApi surface native, employée pour fermer le contexte
     */
    public AxionRuntime(
            BiFunction<PlatformAdapter, Properties, BootstrapOutcome> bootstrap,
            NativeApi nativeApi) {
        this.bootstrap = bootstrap;
        this.nativeApi = nativeApi;
    }

    private static BootstrapOutcome defaultBootstrap(
            PlatformAdapter platform, Properties properties) {
        return AxionBootstrap.start(
                platform.isClient() ? Scope.CLIENT : Scope.SERVER,
                platform.gameDir(),
                platform.configDir(),
                properties,
                dev.axion.bootstrap.NativeLoader::load,
                NativeApi.real());
    }

    /**
     * Signale que le mod vient d'être construit.
     *
     * @param platform vue sur la plateforme hôte
     */
    public void onConstructed(PlatformAdapter platform) {
        guard("construct").run(() -> {
            if (!transitionTo(LifecyclePhase.CONSTRUCTED)) {
                return;
            }
            this.platform = platform;
            transitions.add("plateforme : " + platform.platformName()
                    + " / Minecraft " + platform.minecraftVersion());
        });
    }

    /**
     * Signale la phase de préparation commune : c'est là qu'AXION démarre.
     *
     * @param systemProperties propriétés système, source des surcharges
     *     {@code -Daxion.*}
     */
    public void onSetup(Properties systemProperties) {
        guard("setup").run(() -> {
            if (!transitionTo(LifecyclePhase.SETUP)) {
                return;
            }
            outcome = bootstrap.apply(platform, systemProperties);
            // R-1762, R-673 : les impacts que décrivent les mods tiers se bornent au
            // plafond de cette configuration, pas à celui du registre.
            dev.axion.damage.BoundedImpactSpecs.bind(outcome.config());
            transitions.add(outcome.summary());
        });
    }

    /** Signale que tous les mods sont chargés. */
    public void onLoadComplete() {
        guard("loadComplete").run(() -> transitionTo(LifecyclePhase.LOAD_COMPLETE));
    }

    /** Signale qu'un serveur démarre. */
    public void onServerStarting() {
        guard("serverStarting").run(() -> transitionTo(LifecyclePhase.RUNNING_SERVER));
    }

    /**
     * Signale un tick du client.
     *
     * <p>C'est le premier tick qui fait entrer en {@code RUNNING_CLIENT} : un
     * client tourne dès que sa boucle de jeu tourne, et aucun événement de
     * Forge ne dit cela plus tôt sans mentir — {@code FMLClientSetupEvent}
     * précède {@code LOAD_COMPLETE} et décrirait un client qui charge encore.
     *
     * <p>Les ticks suivants ne changent pas de phase et ne consignent rien : une transition
     * refusée par tick remplirait le journal des transitions sans fin. Ils font avancer la
     * compilation des modèles du client (ADR-150 D3), sous le budget
     * {@code budgets.asset_ns_per_tick}, comme le tick serveur celle du serveur (R-521).
     */
    public void onClientTick() {
        guard("clientTick").run(() -> {
            if (phase == LifecyclePhase.LOAD_COMPLETE) {
                transitionTo(LifecyclePhase.RUNNING_CLIENT);
            }
            AssetRegistry registry = clientAssets;
            if (registry != null && hasNativeRuntime() && !registry.isSettled()) {
                registry.pump(outcome.config().getInt("budgets.asset_ns_per_tick"));
            }
        });
    }

    /**
     * Signale un rechargement de ressources (C-20 étape 1).
     *
     * <p>Le registre et le cache sont <strong>recréés</strong> sur la nouvelle
     * source. R-520 — seuls les assets dont la clé a changé sont recompilés —
     * tient par le cache sur disque : à l'ouverture, il s'accorde aux fichiers
     * rangés par les rechargements précédents (R-561), et chaque asset inchangé
     * y est repris sans passer par le compilateur.
     *
     * @param source sources énumérées par le gestionnaire de ressources
     * @return le nombre d'assets à recompiler
     */
    public int onAssetReload(AssetSource source) {
        // Le runtime natif suffit ; la phase, non. Forge émet le rechargement
        // des ressources **avant** le démarrage du serveur, donc avant
        // `RUNNING_SERVER` : exiger une phase en cours ferait passer la
        // découverte à côté à chaque démarrage, sans qu'aucune erreur ne le
        // dise. C'est le premier lancement réel qui l'a montré.
        if (!hasNativeRuntime()) {
            return 0;
        }
        int changed = 0;
        HookGuard guard = guard("assetReload");
        guard.run(() -> {
            AssetRegistry registry = newAssetRegistry(source, SERVER_CACHE_DIR, ASSET_CACHE_METRICS);
            ASSET_CACHE_METRICS.enabled(registry.cache() != null);

            // Publié une fois découvert : un lecteur d'un autre thread ne voit jamais un registre
            // vide entre deux rechargements.
            int aCompiler = registry.discover();
            assets = registry;
            transitions.add(aCompiler + " asset(s) à compiler, " + reused(registry) + " repris du cache");
        });
        if (assets != null) {
            changed = (int) assets.entries().stream()
                    .filter(entry -> entry.state().isPending())
                    .count();
        }
        return changed;
    }

    /**
     * Signale un rechargement des ressources du client (ADR-150 D3).
     *
     * <p>Les assets ne passent jamais par le réseau (R-1640) : le client compile ses modèles chez
     * lui, depuis ses resource packs puis depuis ses mods, en solo comme en multijoueur. Le registre
     * est recréé sur la nouvelle source, comme celui du serveur ; sa compilation avance aux ticks du
     * client ({@link #onClientTick()}).
     *
     * @param source modèles énumérés côté client
     * @return le nombre d'assets à compiler
     */
    public int onClientAssetReload(AssetSource source) {
        if (!hasNativeRuntime()) {
            return 0;
        }
        int[] pending = {0};
        guard("clientAssetReload").run(() -> {
            AssetRegistry registry =
                    newAssetRegistry(source, CLIENT_CACHE_DIR, AssetCache.Observer.NONE);
            // Publié une fois découvert : le rendu, qui lit depuis son thread, ne voit jamais un
            // registre vide entre deux rechargements — ce qui ferait clignoter tous les maillages
            // vers leur boîte de repli.
            pending[0] = registry.discover();
            clientAssets = registry;
            transitions.add("client : " + pending[0] + " asset(s) à compiler, " + reused(registry)
                    + " repris du cache");
        });
        return pending[0];
    }

    /**
     * Crée un registre d'assets sur une source, adossé à un cache sous {@code <gameDir>/axion/}.
     *
     * @param source sources énumérées
     * @param cacheDir répertoire du cache, sous {@code <gameDir>/axion/}
     * @param observer destinataire de l'activité du cache
     */
    private AssetRegistry newAssetRegistry(AssetSource source, String cacheDir, AssetCache.Observer observer) {
        // R-563, INV-10 : le cache vit sous `<gameDir>/axion/`, jamais dans une sauvegarde. Un cache
        // rangé dans un monde le ferait grossir de données reconstructibles et le rendrait non
        // transportable.
        // §31.3 : en mode développeur seulement, le cache peut être désactivé — chaque asset se
        // recompile alors, ce qui vérifie le compilateur sans soupçonner le cache.
        boolean cacheDisabled = DevMode.assetCacheDisabled();
        AssetCache cache = cacheDisabled
                ? null
                : new AssetCache(
                        platform.gameDir().resolve("axion").resolve(cacheDir),
                        outcome.config().getInt("assets.cache_max_bytes"),
                        observer,
                        System::nanoTime);
        if (cacheDisabled) {
            transitions.add("cache d'assets désactivé (mode développeur, "
                    + DevMode.ASSET_CACHE_PROPERTY + "=false)");
        }
        return new AssetRegistry(
                source,
                new NativeAssetCompiler(outcome.context()),
                dev.axion.asset.CompilerVersion.CURRENT,
                dev.axion.bridge.NativeBridge.EXPECTED_ABI_VERSION,
                System::nanoTime,
                cache);
    }

    /** {@return le nombre d'assets d'un registre repris de son cache} */
    private static long reused(AssetRegistry registry) {
        return registry.entries().stream()
                .filter(entry -> entry.state() == dev.axion.asset.AssetState.CACHED)
                .count();
    }

    /**
     * Attend la compilation des assets, sans dépasser un délai (R-521).
     *
     * <p>La barrière du démarrage d'un serveur dédié. Au-delà du délai, les
     * assets encore en attente sont désactivés : un serveur qui ne démarre
     * jamais est pire qu'un serveur auquel il manque une pièce.
     *
     * @param timeoutNanos délai maximal, en nanosecondes
     * @return vrai si tout a abouti dans le délai
     */
    public boolean awaitAssets(long timeoutNanos) {
        if (assets == null) {
            return true;
        }
        boolean[] settled = {true};
        guard("assetBarrier").run(() -> {
            settled[0] = assets.awaitSettled(timeoutNanos);
            long prets = assets.entries().stream()
                    .filter(entry -> entry.state().isUsable())
                    .count();
            // Le dire même quand tout va bien : un silence ne distingue pas un
            // travail réussi d'un travail qui n'a pas eu lieu, et c'est
            // précisément ce qui a caché une découverte muette au premier
            // démarrage réel.
            transitions.add(prets + " asset(s) prêt(s) sur " + assets.entries().size());
            // Les refus et avertissements suivent par le journal des assets (ADR-148), que la
            // couche Forge vide après la barrière comme après chaque tick.
        });
        return settled[0];
    }

    /**
     * Signale un tick du thread autoritatif.
     *
     * <p>Le tick fait avancer les compilations d'assets sous le budget
     * {@code budgets.asset_ns_per_tick} (R-521). Tant qu'aucune assembly
     * n'existe, il ne coûte que ce passage : {@code budgets.idle_hook_ns}
     * plafonne précisément cela.
     */
    public void onTick() {
        guard("tick").run(() -> {
            if (!isOperational()) {
                return;
            }
            long started = System.nanoTime();
            if (assets != null && !assets.isSettled()) {
                assets.pump(outcome.config().getInt("budgets.asset_ns_per_tick"));
            }
            driveSimulation();
            // INV-19 : un tick sans corps ni événement ne coûte que ce passage, que
            // `budgets.idle_hook_ns` plafonne.
            if (lastTickIdle) {
                IDLE_HOOK.report(System.nanoTime() - started, idleHookBudgetNs);
            }
        });
    }

    /**
     * Déroule un tick du cycle de simulation (IF-03) sur le thread autoritatif.
     *
     * <p>Tant qu'aucune assembly n'existe ({@code CREATE_ASSEMBLY} attend C-32),
     * le cycle avance à vide : aucune commande soumise, zéro état, zéro événement
     * — mais toute la frontière {@code submit}→{@code collect}→lecture est
     * traversée à chaque tick, et le bilan d'allocations reste équilibré (R-322).
     * L'application des états aux entités et l'envoi de commandes de dimension
     * arrivent avec la création de corps.
     *
     * <p>Un {@code collect} refusé n'est jamais un abandon silencieux (R-281) ; il
     * est journalisé, mais seulement à la transition sain → refusé, pour ne pas
     * inonder les diagnostics à chaque tick.
     *
     * <p>Les événements du tick vont, après les états, au journal des faits de simulation
     * (ADR-123 §8, §11) puis au puits Forge (R-1010). Le drapeau {@code AXION_SIM_DEGRADED} dit
     * seulement « pas {@code NORMAL} » : tant qu'il est levé, et au tick qui le baisse, le palier
     * et sa cause se relèvent dans les jauges natives (R-1880). Aux ticks nominaux, rien de tout
     * cela ne coûte.
     *
     * <p>Le cycle natif est chronométré : au-delà du budget de la simulation, le journal dit le
     * tick lent avec le pas qui l'explique (C-15), lu dans l'export pour les seuls ticks lents.
     */
    private void driveSimulation() {
        if (simulation == null) {
            simulation = new NativeSimulation(outcome.context());
            // Une session native neuve n'a pas reçu la table des matériaux (ADR-149).
            materialTable.markChanged();
            simBudgetNs = outcome.config().getInt("budgets.sim_ns_per_tick");
            idleHookBudgetNs = outcome.config().getInt("budgets.idle_hook_ns");
        }
        long tick = platform.currentTick();
        // Étape 1 (C-40) : les commandes de tick viennent des fournisseurs Forge branchés
        // (tuiles du monde C-38, assemblies C-40), fusionnés dans l'ordre. Sans fournisseur,
        // le cycle avance à vide. Le délai reste nul.
        SimCommandStream commands = new SimCommandStream();
        // ADR-149 D1 : la table des matériaux quand elle a changé — démarrage, rechargement. Le natif
        // résout de nouveau ce qui est posé, et ce que les fournisseurs créent dans le même flux.
        PhysicsMaterialTable.Encoded table = materialTable.takeIfChanged();
        if (table != null) {
            commands.setPhysicsMaterials(table.entries(), table.count());
        }
        for (SimCommandProvider provider : commandProviders) {
            commands.merge(provider.commandsForTick(tick));
        }
        long started = System.nanoTime();
        CollectResult result = simulation.tick(tick, commands, 0L);
        long cycleNs = System.nanoTime() - started;
        lastTickIdle = result.bodies().isEmpty() && result.events().isEmpty();
        // Étape 15 (C-40) : les états collectés sont réappliqués aux entités liées via le
        // puits Forge (boucle C-40 ↔ C-50). Absent → rien à piloter.
        if (stateSink != null) {
            stateSink.applyStates(tick, result.bodies(), result.bounds(), result.joints());
        }
        // R-1010 : les événements, sur ce thread, après les états — une entité liée est déjà à sa
        // place quand un effet la touche. Les faits qu'ils portent vont au journal d'abord : un
        // puits qui lèverait ne les ferait pas taire.
        if (!result.events().isEmpty()) {
            simulationJournal.recordEvents(tick, result.events());
            if (eventSink != null) {
                eventSink.applyEvents(tick, result.events());
            }
        }
        if (result.ok()) {
            simulationJournal.recordCycle(
                    tick, cycleNs, simBudgetNs, () -> StepBreakdown.parse(nativeMetrics()));
        }
        if (trace != null && result.ok()) {
            try {
                trace.record(tick, result.bodies(), result.events());
            } catch (IOException failure) {
                simulationJournal.note(true, "trace de la simulation interrompue au tick " + tick
                        + " : " + failure.getMessage());
                stopTrace();
            }
        }
        if (result.ok() && (result.degraded() || simulationJournal.degradationLevel() != 0)) {
            readDegradation(tick);
        }
        if (result.ok() != simulationHealthy) {
            simulationHealthy = result.ok();
            simulationJournal.note(!result.ok(), result.ok()
                    ? "cycle de simulation rétabli au tick " + tick
                    : "collect refusé au tick " + tick + " (code " + result.code() + ")");
        }
    }

    /**
     * Relève le palier de dégradation et le p95 dans les jauges natives (SM-02, R-1880) ; le
     * journal dit chaque changement de palier avec sa cause.
     *
     * @param tick numéro du tick
     */
    private void readDegradation(long tick) {
        DegradationGauges gauges = DegradationGauges.parse(nativeMetrics());
        if (gauges == null) {
            if (!gaugesUnreadableNoted) {
                gaugesUnreadableNoted = true;
                simulationJournal.note(true, "palier de dégradation illisible dans l'export des"
                        + " métriques au tick " + tick + " : ses transitions ne peuvent être"
                        + " journalisées tant qu'il le reste (R-1880)");
            }
            return;
        }
        simulationJournal.recordDegradation(tick, gauges.level(), gauges.p95Ns(), simBudgetNs);
    }

    /** {@return l'export des métriques natives, ou {@code null} s'il n'a pu être lu} */
    private String nativeMetrics() {
        try {
            return nativeApi.metricsJson(outcome.context());
        } catch (RuntimeException | UnsatisfiedLinkError failure) {
            return null;
        }
    }

    /**
     * Ajoute une source de commandes de tick, branchée par la couche Forge au démarrage d'un
     * serveur (tuiles du monde C-38, assemblies C-40). Les flux sont fusionnés dans l'ordre
     * d'ajout.
     *
     * @param provider fournisseur de commandes par tick
     */
    public void addCommandProvider(SimCommandProvider provider) {
        this.commandProviders.add(provider);
    }

    /**
     * Ajoute un libérateur de handles natifs, appelé à l'arrêt avant la fermeture du contexte
     * (R-321, R-322).
     *
     * <p>Le contexte natif d'un client vit jusqu'à la fin du processus, et l'arrêt peut avoir
     * lieu sur le thread du serveur intégré : celui qui détient des handles ne peut donc pas
     * compter sur ses propres événements pour les rendre à temps.
     *
     * @param releaser rend tous les handles détenus ; appelé à l'arrêt, depuis le thread qui
     *     arrête
     */
    public void addNativeReleaser(Runnable releaser) {
        nativeReleasers.add(releaser);
    }

    /** Retire toutes les sources de commandes (arrêt du serveur). */
    public void clearCommandProviders() {
        this.commandProviders.clear();
    }

    /**
     * Pose (ou retire, avec {@code null}) le puits des états collectés (boucle C-40 ↔ C-50).
     *
     * @param sink puits d'états, ou {@code null} pour le retirer
     */
    public void setStateSink(SimStateSink sink) {
        this.stateSink = sink;
    }

    /**
     * Pose (ou retire, avec {@code null}) le puits des événements collectés (R-1010).
     *
     * @param sink puits d'événements, ou {@code null} pour le retirer
     */
    public void setEventSink(SimEventSink sink) {
        this.eventSink = sink;
    }

    /**
     * Pose (ou retire, avec {@code null}) ce qui nomme une assembly dans le journal des faits de
     * simulation : un groupe nominal, « l'assembly … », que la couche Forge tire de l'entité
     * (definition, position). Sans lui, l'index du handle seul.
     *
     * @param describer index de handle → description, ou {@code null}
     */
    public void setAssemblyDescriber(IntFunction<String> describer) {
        simulationJournal.setDescriber(describer);
    }

    /**
     * {@return les faits de simulation consignés depuis l'appel précédent, puis les oublie}
     *
     * <p>Appelé par la couche Forge après chaque tick, qui les journalise : le runtime ne
     * journalise pas lui-même. Liste vide, sans allocation, quand rien n'est arrivé.
     */
    public List<SimulationJournal.Entry> drainSimulationJournal() {
        return simulationJournal.drain();
    }

    /**
     * {@return les diagnostics d'assets consignés depuis l'appel précédent, puis les oublie}
     *
     * <p>ADR-148 : refus, avertissements de compilation et soucis du cache, chacun une fois. La
     * couche Forge les journalise après la barrière de démarrage et après chaque tick serveur —
     * ceux d'un rechargement compris. Liste vide sans registre d'assets.
     */
    public List<String> drainAssetJournal() {
        AssetRegistry registry = assets;
        return registry == null ? List.of() : registry.drainJournal();
    }

    /**
     * {@return les diagnostics des assets du client consignés depuis l'appel précédent, puis les
     * oublie}
     *
     * <p>Même journal qu'{@link #drainAssetJournal()} (ADR-148), pour le registre du client (ADR-150
     * D3) ; la couche Forge le vide à chaque tick du client. Liste vide sans registre client.
     */
    public List<String> drainClientAssetJournal() {
        AssetRegistry registry = clientAssets;
        return registry == null ? List.of() : registry.drainJournal();
    }

    /**
     * Fait dire au journal de simulation les ticks lents qu'il compte encore (C-15). Appelé par
     * la couche Forge à l'arrêt d'un serveur, avant de vider le journal : la ligne qui les
     * rapporte attendait la minute suivante, et l'arrêt les aurait tus.
     */
    public void flushSimulationJournal() {
        simulationJournal.flushPendingSlowTicks();
    }

    /**
     * Commence à tracer la simulation dans {@code file} (C-71, {@code /axion debug trace on}) :
     * une ligne par corps et par tick, une par événement ({@link SimulationTrace}). Remplace une
     * trace en cours, qui est fermée d'abord.
     *
     * @param file fichier CSV à créer ; ses répertoires le sont au besoin
     * @throws IOException si le fichier ne peut être créé
     */
    public void startTrace(Path file) throws IOException {
        stopTrace();
        // Absolu d'abord : un nom de fichier nu n'a pas de parent, et c'est le dossier courant.
        Files.createDirectories(file.toAbsolutePath().getParent());
        trace = new SimulationTrace(Files.newBufferedWriter(file, StandardCharsets.UTF_8));
        tracePath = file;
        simulationJournal.note(false, "trace de la simulation ouverte : " + file);
    }

    /**
     * Arrête la trace en cours et ferme son fichier ; le journal dit où elle est.
     *
     * @return le nombre de lignes écrites, ou -1 s'il n'y avait pas de trace
     */
    public long stopTrace() {
        if (trace == null) {
            return -1L;
        }
        long lines = trace.lines();
        try {
            trace.close();
        } catch (IOException failure) {
            simulationJournal.note(true, "trace de la simulation mal fermée : " + failure.getMessage());
        }
        simulationJournal.note(false, "trace de la simulation fermée : " + lines + " ligne(s) dans "
                + tracePath);
        trace = null;
        tracePath = null;
        return lines;
    }

    /**
     * Écrit dans la trace en cours, s'il y en a une, les sections de collision du monde posées et
     * retirées à ce tick (C-38) : elles précèdent les états du tick qu'elles servent.
     *
     * @param tick numéro du tick
     * @param tiles sections posées et retirées, dans l'ordre de leurs commandes
     * @param pending sections encore en file après ce tick
     */
    public void traceTiles(long tick, List<SimulationTrace.Tile> tiles, int pending) {
        if (trace == null) {
            return;
        }
        try {
            trace.recordTiles(tick, tiles, pending);
        } catch (IOException failure) {
            simulationJournal.note(true, "trace de la simulation interrompue au tick " + tick
                    + " : " + failure.getMessage());
            stopTrace();
        }
    }

    /** {@return le rang du palier de dégradation de la simulation, 0 pour {@code NORMAL}} */
    public int degradationLevel() {
        return simulationJournal.degradationLevel();
    }

    /** {@return le p95 du tick de simulation au dernier relevé, en ns, ou -1 sans relevé} */
    public long degradationP95Ns() {
        return simulationJournal.lastP95Ns();
    }

    /**
     * {@return le délai de la barrière de démarrage, en nanosecondes}
     *
     * <p>Zéro lorsqu'il n'y a rien à attendre : sans runtime natif, aucun asset
     * n'a été découvert, et attendre reviendrait à retarder le démarrage pour
     * rien.
     */
    public long assetStartupTimeoutNanos() {
        if (assets == null || !hasNativeRuntime()) {
            return 0;
        }
        long seconds = outcome.config().getInt("assets.startup_timeout_s");
        return seconds * 1_000_000_000L;
    }

    /**
     * {@return la taille au-delà de laquelle un fichier voisin n'est pas gardé au
     * rechargement, en octets : {@code assets.max_source_bytes} (ADR-139)}
     *
     * <p>Sans runtime natif, rien ne sera compilé ; la valeur par défaut du registre
     * borne quand même ce que le rechargement retient.
     */
    public long maxSiblingBytes() {
        return commonLimit(MAX_SOURCE_BYTES);
    }

    /**
     * {@return le plafond des données personnalisées d'une assembly, en octets :
     * {@code persistence.max_custom_bytes} (ADR-153 D7)} Sans runtime natif, la valeur
     * par défaut du registre.
     */
    public long maxCustomBytes() {
        return commonLimit(MAX_CUSTOM_BYTES);
    }

    /** {@return une option entière commune : celle de la configuration chargée, sinon son défaut} */
    private long commonLimit(String path) {
        if (hasNativeRuntime()) {
            return outcome.config().getInt(path);
        }
        return ConfigSchema.COMMON_OPTIONS.stream()
                .filter(option -> option.path().equals(path))
                .map(option -> (Long) option.defaultValue())
                .findFirst()
                .orElseThrow();
    }

    /**
     * Charge les matériaux physiques d'un rechargement de données (DM-07, R-171, ADR-145).
     *
     * <p>Avant les mappages bloc → matériau et les definitions, qui les nomment. Les refus sont
     * journalisés un par un (R-580), et ce que la résolution a supposé une fois par matériau
     * (R-1038).
     *
     * @param source ressources sous {@code axion/physics_materials}
     * @return le nombre de matériaux acceptés
     */
    public int onPhysicsMaterialReload(AssetSource source) {
        // Même condition que les definitions : sans runtime natif, rien ne les consomme.
        if (!hasNativeRuntime()) {
            return 0;
        }
        guard("physicsMaterialReload").run(() -> {
            PhysicsMaterials loaded = PhysicsMaterials.load(source);
            physicsMaterials = loaded;
            materialTexts = ServerRegistry.read(source);
            // ADR-149 D1 : la table suit le registre, et le tick suivant la renvoie au natif.
            materialTable.update(loaded);
            transitions.add(loaded.ids().size() + " matériau(x) physique(s) chargé(s), "
                    + loaded.refusals().size() + " refusé(s)");
            loaded.refusals().forEach(transitions::add);
            loaded.notes().forEach(transitions::add);
        });
        return physicsMaterials.ids().size();
    }

    /** {@return le registre des matériaux physiques, vide avant tout rechargement} */
    public PhysicsMaterials physicsMaterials() {
        return physicsMaterials;
    }

    /**
     * {@return l'index d'un matériau physique dans la table de la session (ADR-149)} Un matériau non
     * déclaré vaut l'acier générique (R-1032).
     *
     * @param id identifiant {@code <ns>:<chemin>}
     */
    public int physicsMaterialIndex(String id) {
        return materialTable.indexOf(id);
    }

    /**
     * Charge les mappages bloc → matériau d'un rechargement de données (§11.4, R-643, ADR-145),
     * contre les matériaux physiques chargés juste avant. Les tuiles construites ensuite en
     * prennent le contact ; une tuile déjà construite garde le sien jusqu'à sa reconstruction.
     *
     * @param source ressources sous {@code axion/block_materials}
     */
    public void onBlockMaterialReload(AssetSource source) {
        if (!hasNativeRuntime()) {
            return;
        }
        guard("blockMaterialReload").run(() -> {
            BlockMaterialMappings.Result loaded = BlockMaterialMappings.load(source, physicsMaterials);
            blockMaterials = loaded.mapping();
            transitions.add("mappage bloc → matériau chargé, " + loaded.refusals().size()
                    + " entrée(s) refusée(s), " + loaded.notes().size() + " remplacée(s)");
            loaded.refusals().forEach(transitions::add);
            loaded.notes().forEach(transitions::add);
        });
    }

    /** {@return le mappage bloc → matériau, celui du solveur avant tout rechargement} */
    public BlockMaterials blockMaterials() {
        return blockMaterials;
    }

    /**
     * Charge les groupes de collision tiers d'un rechargement de données (R-980, ADR-132 A1).
     *
     * <p>Avant les definitions, qui les nomment. Le registre vaut pour les corps créés après lui :
     * un corps vivant garde le filtre posé à sa création, comme il garde la definition qui l'a
     * créé. Un rechargement qui change le registre le dit, pour qu'un écart entre deux corps de
     * même definition se comprenne. Les refus sont journalisés un par un (R-580).
     *
     * @param source ressources sous {@code axion/collision_groups}
     * @return le nombre de groupes tiers acceptés
     */
    public int onCollisionGroupReload(AssetSource source) {
        // Même condition que les definitions : sans runtime natif, aucun corps à filtrer.
        if (!hasNativeRuntime()) {
            return 0;
        }
        guard("collisionGroupReload").run(() -> {
            CollisionGroupRegistry loaded = CollisionGroupRegistry.load(source);
            if (collisionGroupsLoaded && !loaded.fingerprint().equals(collisionGroups.fingerprint())) {
                transitions.add("registre des groupes de collision changé : les corps vivants gardent"
                        + " le filtre de leur création, les corps créés ensuite prennent le nouveau");
            }
            collisionGroups = loaded;
            collisionGroupsLoaded = true;
            groupTexts = ServerRegistry.read(source);
            transitions.add(loaded.ids().size() + " groupe(s) de collision tiers chargé(s), "
                    + loaded.refusals().size() + " refusé(s)");
            loaded.refusals().forEach(transitions::add);
        });
        return collisionGroups.ids().size();
    }

    /** {@return le registre des groupes de collision tiers, les réservés seuls avant tout rechargement} */
    public CollisionGroupRegistry collisionGroups() {
        return collisionGroups;
    }

    /**
     * Charge les definitions d'un rechargement de données (C-27).
     *
     * <p>La registry est reconstruite d'un bloc puis remplacée : une entité en
     * cours d'apparition ne voit jamais un mélange de l'ancienne et de la
     * nouvelle. Les refus sont journalisés un par un (R-580).
     *
     * @param source ressources sous {@code axion/definitions}
     * @return le nombre de definitions acceptées
     */
    public int onDefinitionReload(AssetSource source) {
        // Même condition que les assets, pour la même raison : le rechargement
        // précède le démarrage du serveur, et une definition ne sert à rien
        // sans runtime natif pour l'instancier.
        if (!hasNativeRuntime()) {
            return 0;
        }
        guard("definitionReload").run(() -> {
            AssetRegistry known = assets;
            DefinitionRules rules = new DefinitionRules(
                    outcome.config().getBoolean("modules.vehicles"),
                    path -> known != null && known.entry(path) != null,
                    outcome.config().getBoolean("content.examples"),
                    collisionGroups,
                    physicsMaterials);
            long start = System.nanoTime();
            DefinitionRegistry loaded = DefinitionRegistry.load(source, rules);
            long nanos = System.nanoTime() - start;
            definitions = loaded;
            DEFINITION_METRICS.reloaded(
                    loaded.size(), loaded.refusals().size(), nanos, sourceBytes(source));
            transitions.add(loaded.size() + " definition(s) chargée(s), "
                    + loaded.refusals().size() + " refusée(s)");
            loaded.refusals().forEach(transitions::add);
            loaded.warnings().forEach(transitions::add);
            rebuildNetSnapshot(loaded, ServerRegistry.read(source));
        });
        return definitions.size();
    }

    /**
     * Rebâtit ce que le serveur envoie à chaque connexion (ADR-150 D4, D5) : son handshake, et le
     * registre des données qu'il vient de recharger — dans l'ordre de Minecraft, les definitions
     * viennent après les matériaux et les groupes.
     *
     * <p>Un échec n'interrompt pas le rechargement : les definitions sont chargées, seuls les clients
     * distants s'en trouvent privés, et le journal le dit.
     */
    private void rebuildNetSnapshot(DefinitionRegistry loaded, SortedMap<String, byte[]> definitionTexts) {
        try {
            RegistryContent.Builder registry =
                    ServerRegistry.collect(materialTexts, groupTexts, loaded, definitionTexts);
            ServerSnapshot snapshot = ServerSnapshot.build(outcome.config(), registry.build(), registry.excluded(),
                    DetProfile.ofThisBinary(), new NativeNetCompression(outcome.context()));
            netSnapshot = snapshot;
            transitions.add(snapshot.describe());
            snapshot.notes().forEach(transitions::add);
        } catch (RuntimeException | LinkageError failure) {
            netSnapshot = null;
            transitions.add("instantané réseau non bâti : " + failure
                    + " ; aucun client ne pourra se connecter avec AXION");
        }
    }

    /**
     * {@return ce que le serveur envoie à chaque connexion (ADR-150 D4, D5)} {@code null} sans runtime
     * natif, avant le premier rechargement des definitions, ou hors d'un serveur.
     */
    public ServerSnapshot netSnapshot() {
        return netSnapshot;
    }

    /**
     * Applique chez ce client le registre reçu d'un serveur distant (ADR-150 D5) : ses definitions
     * deviennent celles que lit le rendu. Seule une connexion distante en reçoit un ; une connexion
     * locale partage les definitions du serveur intégré (ADR-118).
     *
     * <p>Appelé depuis le thread de Netty de la connexion : rien n'est ajouté aux transitions, et la
     * registry est publiée d'un bloc, par un champ {@code volatile}.
     *
     * @param registry registre reçu, empreintes vérifiées
     * @return les lignes à journaliser
     */
    public List<String> applyRemoteRegistry(RegistryContent registry) {
        RemoteRegistry.Loaded loaded = RemoteRegistry.load(registry);
        definitions = loaded.definitions();
        remoteDefinitions = true;
        return loaded.journal();
    }

    /**
     * Oublie, à la déconnexion, les definitions reçues d'un serveur distant : celles d'un serveur ne
     * valent pas pour le suivant, qui peut ne rien envoyer. Celles d'un monde solo restent, elles sont
     * au serveur intégré.
     */
    public void onRemoteDisconnect() {
        if (remoteDefinitions) {
            remoteDefinitions = false;
            definitions = DefinitionRegistry.empty();
        }
    }

    /**
     * {@return la compression réseau du natif, ou {@code null} si le runtime natif est inactif} Le
     * registre reçu ne peut alors pas se lire.
     */
    public NetCompression netCompression() {
        return hasNativeRuntime() ? new NativeNetCompression(outcome.context()) : null;
    }

    /**
     * {@return l'empreinte déterministe de ce binaire (5.12bis), ou {@link DetProfile#UNKNOWN} si le
     * runtime natif est inactif ou la refuse}
     */
    public DetProfile detProfile() {
        if (!hasNativeRuntime()) {
            return DetProfile.UNKNOWN;
        }
        try {
            return DetProfile.ofThisBinary();
        } catch (RuntimeException | LinkageError failure) {
            return DetProfile.UNKNOWN;
        }
    }

    /**
     * Retient une connexion entrée en jeu, pour le statut (5.12bis).
     *
     * @param player identifiant du joueur, jamais affiché (R-442)
     * @param peer ce que le serveur sait de sa connexion
     */
    public void onPeerJoined(String player, ServerPeer peer) {
        peers.put(player, peer);
    }

    /**
     * Oublie une connexion fermée.
     *
     * @param player identifiant du joueur
     */
    public void onPeerLeft(String player) {
        peers.remove(player);
    }

    /** {@return les connexions en cours, vues du serveur} */
    public List<ServerPeer> peers() {
        return List.copyOf(peers.values());
    }

    /**
     * {@return les octets des sources que le rechargement retient en mémoire} Une ressource
     * illisible ne compte pas : elle n'est pas retenue.
     */
    private static long sourceBytes(AssetSource source) {
        long total = 0;
        for (String path : source.list()) {
            try {
                total += source.read(path).length;
            } catch (IOException unreadable) {
                // Déjà signalée par le rechargement ; rien n'est retenu pour elle.
            }
        }
        return total;
    }

    /** {@return la registry des definitions, vide avant tout rechargement} */
    public DefinitionRegistry definitions() {
        return definitions;
    }

    /** {@return le registre d'assets du serveur, ou {@code null} avant tout rechargement} */
    public AssetRegistry assets() {
        return assets;
    }

    /**
     * {@return le registre d'assets de ce client (ADR-150 D3), ou {@code null} sur un serveur dédié
     * et avant le premier rechargement des ressources du client}
     *
     * <p>C'est lui que lit le rendu : un client dessine ce qu'il a compilé, jamais ce qu'a compilé
     * le serveur, en solo comme en multijoueur.
     */
    public AssetRegistry clientAssets() {
        return clientAssets;
    }

    /**
     * Retient les capacités du backend de rendu que le client vient de choisir (R-1493).
     *
     * @param capabilities capacités du backend retenu
     */
    public void setRenderCapabilities(RenderCapabilities capabilities) {
        renderCapabilities = capabilities;
    }

    /**
     * {@return les capacités du backend de rendu du client, ou {@code null} sur un serveur
     * dédié et tant qu'aucun monde client n'a été chargé}
     */
    public RenderCapabilities renderCapabilities() {
        return renderCapabilities;
    }

    /**
     * Signale l'arrêt d'un serveur.
     *
     * <p>Sur un serveur dédié, la fin de la session est la fin du processus :
     * le runtime natif se ferme ici. <strong>Sur un client, non</strong> —
     * revenir au menu principal arrête le serveur intégré sans quitter le jeu,
     * et le monde suivant a besoin du même contexte natif. Fermer ici laissait
     * AXION mort pour tout le reste de la session, sans qu'aucune erreur ne le
     * dise ; c'est le premier lancement réel du client qui l'a montré.
     */
    public void onServerStopping() {
        guard("serverStopping").run(() -> {
            // L'instantané et les connexions étaient ceux de ce serveur ; le suivant rebâtit les siens.
            netSnapshot = null;
            peers.clear();
            if (processIsEnding()) {
                shutdown();
                return;
            }
            transitionTo(LifecyclePhase.RUNNING_CLIENT);
        });
    }

    /**
     * Signale que le jeu s'arrête, client comme serveur dédié.
     *
     * <p>Il n'y a rien à faire ici quand un serveur tourne encore : la fin de
     * session doit avoir lieu pendant que le contexte natif existe, faute de
     * quoi rien ne pourrait plus être persisté à l'arrêt. Rien non plus quand
     * le cycle est déjà refermé — c'est le cas courant du serveur dédié, dont
     * Forge émet les deux événements dans cet ordre. Ce n'est donc pas une
     * transition inattendue, et la journaliser comme telle inquiéterait pour
     * rien.
     */
    public void onGameShuttingDown() {
        guard("gameShuttingDown").run(() -> {
            shuttingDown = true;
            if (phase == LifecyclePhase.RUNNING_SERVER
                    || !phase.canTransitionTo(LifecyclePhase.STOPPING)) {
                return;
            }
            shutdown();
        });
    }

    /**
     * Indique si l'arrêt du serveur en cours est aussi celui du processus.
     *
     * <p>Le drapeau seul suffirait si {@code GameShuttingDownEvent} arrivait
     * toujours ; l'absence de client est la seconde raison, et elle est
     * indépendante : un serveur dédié qui s'arrête ne rouvrira pas de monde.
     */
    private boolean processIsEnding() {
        return shuttingDown || platform == null || !platform.isClient();
    }

    /** Relâche le runtime natif et ramène le cycle en {@code UNLOADED}. */
    private void shutdown() {
        // L'index du cache est écrit avant tout le reste : il évite de
        // parcourir l'arborescence au prochain démarrage, et le perdre ne coûte
        // qu'un cache qui se remplit de nouveau.
        if (assets != null && assets.cache() != null) {
            assets.cache().writeIndex();
        }
        if (clientAssets != null && clientAssets.cache() != null) {
            clientAssets.cache().writeIndex();
        }
        // Une trace encore ouverte se ferme, complète : le journal de simulation va être
        // remplacé, c'est le journal des transitions qui le dit.
        long traced = stopTrace();
        if (traced >= 0) {
            transitions.add("trace de la simulation fermée à l'arrêt : " + traced + " ligne(s)");
        }
        if (!transitionTo(LifecyclePhase.STOPPING)) {
            return;
        }
        if (outcome != null && outcome.isReady()) {
            // R-321 : les handles détenus hors du runtime sont rendus avant la fermeture, sans
            // quoi le bilan qui suit les compterait comme oubliés. Un libérateur qui échoue
            // n'empêche ni les autres ni la fermeture.
            for (Runnable releaser : nativeReleasers) {
                try {
                    releaser.run();
                } catch (RuntimeException failure) {
                    transitions.add("libération native impossible : " + failure);
                }
            }
            try {
                // R-322 : l'arrêt journalise le bilan des allocations ; il
                // doit donc avoir lieu, même si le jeu se ferme brutalement
                // après.
                int code = nativeApi.close(outcome.context());
                transitions.add("contexte natif fermé, code " + code);
            } catch (Throwable failure) {
                // Une fermeture qui échoue ne doit pas laisser le cycle bloqué
                // en STOPPING : l'arrêt doit toujours aboutir, quitte à
                // abandonner des ressources que le processus va de toute façon
                // rendre en se terminant.
                transitions.add("fermeture du contexte natif impossible : " + failure);
            }
        }
        outcome = null;
        // Le pilote de simulation référençait le contexte qu'on vient de fermer :
        // un redémarrage en recréera un sur le nouveau contexte.
        simulation = null;
        simulationHealthy = true;
        // Un nouveau contexte a un gouverneur neuf, au palier NORMAL : le journal qui suivait
        // l'ancien n'a plus rien à comparer.
        simulationJournal = new SimulationJournal();
        gaugesUnreadableNoted = false;
        transitionTo(LifecyclePhase.UNLOADED);
    }

    /**
     * {@return la durée de la dernière soumission native des commandes du tick, en nanosecondes, ou 0
     * sans simulation} Le budget de chargement en tire le coût natif d'une création (R-1730, ADR-153 D4).
     */
    public long lastSubmitNanos() {
        NativeSimulation current = simulation;
        return current == null ? 0L : current.lastSubmitNanos();
    }

    /**
     * {@return l'export JSON des métriques natives et de celles que Java mesure (ADR-133 D1),
     * ou {@code null} si le runtime n'est pas opérationnel}
     *
     * <p>R-502. Un runtime inactif n'a pas de métriques à donner : rendre un
     * document vide laisserait croire qu'il n'a rien mesuré, alors qu'il n'a
     * rien pu mesurer.
     */
    public String metricsJson() {
        if (!isOperational()) {
            return null;
        }
        try {
            // ADR-133 D1 : les métriques que Java mesure entrent dans le même export.
            return JavaMetrics.GLOBAL.merge(nativeApi.metricsJson(outcome.context()));
        } catch (RuntimeException | UnsatisfiedLinkError failure) {
            // Un export qui échoue ne doit pas remonter jusqu'à la commande :
            // R-400 veut qu'aucun hook ne lève, et une commande de diagnostic
            // encore moins.
            transitions.add("export des métriques impossible : " + failure);
            return null;
        }
    }

    /** {@return le répertoire de jeu, ou {@code null} avant la construction} */
    public java.nio.file.Path gameDir() {
        return platform == null ? null : platform.gameDir();
    }

    /**
     * {@return vrai si le runtime natif est démarré}
     *
     * <p>Indépendamment de la phase : certains travaux — la découverte
     * d'assets, notamment — ont lieu avant qu'un serveur ne tourne.
     */
    private boolean hasNativeRuntime() {
        return outcome != null && outcome.isReady();
    }

    /**
     * {@return vrai si le runtime natif est utilisable en ce moment}
     */
    public boolean isOperational() {
        return outcome != null && outcome.isReady() && phase.isRunning();
    }

    /** {@return la phase courante} */
    public LifecyclePhase phase() {
        return phase;
    }

    /** {@return l'issue du démarrage, ou {@code null} s'il n'a pas eu lieu} */
    public BootstrapOutcome outcome() {
        return outcome;
    }

    /** {@return les gardes de hook, pour le diagnostic} */
    public Map<String, HookGuard> guards() {
        return Map.copyOf(guards);
    }

    /**
     * {@return le journal des transitions et des faits notables}
     */
    public List<String> transitions() {
        return List.copyOf(transitions);
    }

    private HookGuard guard(String name) {
        return guards.computeIfAbsent(name, HookGuard::new);
    }

    private boolean transitionTo(LifecyclePhase next) {
        if (!phase.canTransitionTo(next)) {
            // Une transition inattendue n'interrompt rien : elle est constatée
            // et le cycle continue depuis où il en est. FM-03 traite le cas
            // d'un PRE sans POST de la même façon.
            transitions.add("transition ignorée : " + phase + " -> " + next);
            return false;
        }
        transitions.add(phase + " -> " + next);
        phase = next;
        return true;
    }
}
