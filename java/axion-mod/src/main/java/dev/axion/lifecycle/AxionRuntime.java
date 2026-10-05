package dev.axion.lifecycle;

import dev.axion.asset.AssetCache;
import dev.axion.asset.AssetRegistry;
import dev.axion.asset.AssetSource;
import dev.axion.asset.NativeAssetCompiler;
import dev.axion.bootstrap.AxionBootstrap;
import dev.axion.bootstrap.BootstrapOutcome;
import dev.axion.bootstrap.NativeApi;
import dev.axion.config.ConfigSchema.Scope;
import dev.axion.definition.DefinitionRegistry;
import dev.axion.definition.DefinitionRules;
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
import dev.axion.platform.PlatformAdapter;
import dev.axion.render.RenderCapabilities;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
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
    private volatile DefinitionRegistry definitions = DefinitionRegistry.empty();

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
     * <p>Les ticks suivants ne font rien et ne consignent rien : une transition
     * refusée par tick remplirait le journal des transitions sans fin.
     */
    public void onClientTick() {
        guard("clientTick").run(() -> {
            if (phase == LifecyclePhase.LOAD_COMPLETE) {
                transitionTo(LifecyclePhase.RUNNING_CLIENT);
            }
        });
    }

    /**
     * Signale un rechargement de ressources (C-20 étape 1).
     *
     * <p>Le registre est reconstruit sur la nouvelle source, mais **conserve
     * son état** : R-520 veut que seuls les assets dont la clé a changé soient
     * recompilés, et repartir de zéro à chaque rechargement rendrait la
     * commande inutilisable sur un pack un peu fourni.
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
            // R-563, INV-10 : le cache vit sous `<gameDir>/axion/`, jamais
            // dans une sauvegarde. Un cache range dans un monde le ferait
            // grossir de donnees reconstructibles et le rendrait non
            // transportable.
            AssetCache cache = new AssetCache(
                    platform.gameDir().resolve("axion").resolve("cache"),
                    outcome.config().getInt("assets.cache_max_bytes"));

            AssetRegistry registry = new AssetRegistry(
                    source,
                    new NativeAssetCompiler(outcome.context()),
                    dev.axion.asset.CompilerVersion.CURRENT,
                    dev.axion.bridge.NativeBridge.EXPECTED_ABI_VERSION,
                    System::nanoTime,
                    cache);

            // Publié une fois découvert : le rendu client, qui lit depuis son thread, ne voit
            // jamais un registre vide entre deux rechargements — ce qui ferait clignoter tous
            // les maillages vers leur boîte de repli.
            int aCompiler = registry.discover();
            assets = registry;
            long reprises = registry.entries().stream()
                    .filter(entry -> entry.state() == dev.axion.asset.AssetState.CACHED)
                    .count();
            transitions.add(aCompiler + " asset(s) à compiler, " + reprises + " repris du cache");
        });
        if (assets != null) {
            changed = (int) assets.entries().stream()
                    .filter(entry -> entry.state().isPending())
                    .count();
        }
        return changed;
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
            assets.diagnostics().forEach(transitions::add);
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
            if (assets != null && !assets.isSettled()) {
                assets.pump(outcome.config().getInt("budgets.asset_ns_per_tick"));
            }
            driveSimulation();
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
            simBudgetNs = outcome.config().getInt("budgets.sim_ns_per_tick");
        }
        long tick = platform.currentTick();
        // Étape 1 (C-40) : les commandes de tick viennent des fournisseurs Forge branchés
        // (tuiles du monde C-38, assemblies C-40), fusionnés dans l'ordre. Sans fournisseur,
        // le cycle avance à vide. Le délai reste nul.
        SimCommandStream commands = new SimCommandStream();
        for (SimCommandProvider provider : commandProviders) {
            commands.merge(provider.commandsForTick(tick));
        }
        long started = System.nanoTime();
        CollectResult result = simulation.tick(tick, commands, 0L);
        long cycleNs = System.nanoTime() - started;
        // Étape 15 (C-40) : les états collectés sont réappliqués aux entités liées via le
        // puits Forge (boucle C-40 ↔ C-50). Absent → rien à piloter.
        if (stateSink != null) {
            stateSink.applyStates(tick, result.bodies(), result.bounds());
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
        Files.createDirectories(file.getParent());
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
                    path -> known != null && known.entry(path) != null);
            DefinitionRegistry loaded = DefinitionRegistry.load(source, rules);
            definitions = loaded;
            transitions.add(loaded.size() + " definition(s) chargée(s), "
                    + loaded.refusals().size() + " refusée(s)");
            loaded.refusals().forEach(transitions::add);
        });
        return definitions.size();
    }

    /** {@return la registry des definitions, vide avant tout rechargement} */
    public DefinitionRegistry definitions() {
        return definitions;
    }

    /** {@return le registre d'assets, ou {@code null} avant tout rechargement} */
    public AssetRegistry assets() {
        return assets;
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
     * {@return l'export JSON des métriques natives, ou {@code null} si le
     * runtime n'est pas opérationnel}
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
            return nativeApi.metricsJson(outcome.context());
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
