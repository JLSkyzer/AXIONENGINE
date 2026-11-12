package dev.axion.forge;

import java.util.ArrayList;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import net.minecraftforge.fml.loading.FMLPaths;
import dev.axion.definition.ServerOverrides;
import com.mojang.logging.LogUtils;
import dev.axion.AxionMod;
import dev.axion.api.content.ContentKind;
import dev.axion.asset.AssetSource;
import dev.axion.api.ApiVersion;
import dev.axion.content.ApiRequirements;
import dev.axion.content.RegisteredContent;
import java.util.HashMap;
import java.util.Map;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoader;
import net.minecraftforge.fml.ModLoadingStage;
import net.minecraftforge.fml.ModLoadingWarning;
import net.minecraftforge.forgespi.language.IModInfo;
import dev.axion.forge.net.AxionChannel;
import dev.axion.forge.net.AxionNetwork;
import dev.axion.lifecycle.AxionRuntime;
import dev.axion.lifecycle.HookGuard;
import dev.axion.net.ServerSnapshot;
import dev.axion.physics.SimulationJournal;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.GameShuttingDownEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.minecraftforge.fml.event.lifecycle.InterModEnqueueEvent;
import net.minecraftforge.fml.event.lifecycle.InterModProcessEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/**
 * Point d'ancrage unique entre Forge et AXION (C-01).
 *
 * <p>Ce paquet est le seul autorisé à importer {@code net.minecraftforge.*}
 * (R-401, vérifié par T-020) : tout le reste du moteur reste indépendant de la
 * plateforme, ce qui le rend testable sans démarrer le jeu.
 *
 * <p>Cette classe ne décide de rien. Elle traduit des événements Forge en
 * transitions de cycle de vie, que {@link AxionRuntime} traite. Chacune passe
 * par un garde qui absorbe toute exception : AXION n'annule aucun événement et
 * ne modifie le résultat d'aucun événement d'un autre mod (R-400).
 *
 * <p>Les abonnements suivent la règle de C-01 : priorité {@code HIGHEST} sur les
 * phases d'ouverture, {@code LOWEST} sur les phases de fermeture. AXION observe
 * ainsi une fenêtre qui englobe celle des autres mods, plutôt que de s'insérer
 * au milieu de leur travail.
 */
@Mod(AxionMod.MODID)
public final class AxionForgeEntrypoint {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final AxionRuntime runtime = new AxionRuntime();
    private final ForgePlatformAdapter platform = new ForgePlatformAdapter();

    /** Pont des tuiles de collision du monde (C-38), vivant le temps d'un serveur. */
    private WorldTileBridge worldTileBridge;

    /** Runtime des assemblies (boucle C-40 ↔ C-50), vivant le temps d'un serveur. */
    private AssemblyRuntime assemblyRuntime;

    /**
     * Construit le mod et abonne AXION aux événements de la plateforme.
     *
     * <p>Le bus est récupéré depuis {@link FMLJavaModLoadingContext}, dont
     * l'accès est marqué déprécié. C'est pourtant la seule forme qui fonctionne
     * sur Forge 47 : l'injection du bus dans le constructeur n'y existe pas
     * encore, et un constructeur qui la réclame fait échouer le chargement du
     * mod avec {@code NoSuchMethodException}. Un avertissement de dépréciation
     * n'autorise pas à employer une API absente de la version visée.
     */
    public AxionForgeEntrypoint() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener(EventPriority.HIGHEST, this::onCommonSetup);
        modBus.addListener(EventPriority.LOWEST, this::onLoadComplete);
        // ADR-162 D2 : le contenu arrive par les messages entre mods, AXION compris.
        modBus.addListener((InterModEnqueueEvent event) -> ContentRegistration.enqueue(event));
        modBus.addListener((InterModProcessEvent event) ->
                ContentRegistration.process(event, runtime::maxSiblingBytes));
        AxionEntities.register(modBus, runtime);
        RuntimeAccess.install(runtime);
        // ADR-166 D1 : la version d'API que chaque addon exige, lue avant que son contenu ne soit enregistré.
        checkApiRequirements();
        // Le canal doit exister avant la première connexion : AXION est exigé des deux côtés
        // (R-1630, ADR-150).
        AxionChannel.register();

        MinecraftForge.EVENT_BUS.register(this);

        runtime.onConstructed(platform);
        LOGGER.info("AXION ENGINE construit sur {}", platform.platformName());
    }

    /**
     * ADR-166 D1 : lit {@code [modproperties.<modId>] axion_api} pour chaque mod chargé. Un addon que l'API présente ne
     * satisfait pas reçoit un avertissement de chargement, que Forge affiche à la fin du chargement, et son contenu
     * n'est pas enregistré ; le jeu démarre (Q1).
     */
    private static void checkApiRequirements() {
        Map<String, Object> declared = new HashMap<>();
        Map<String, IModInfo> infos = new HashMap<>();
        for (IModInfo info : ModList.get().getMods()) {
            Object value = info.getModProperties().get(ApiRequirements.PROPERTY);
            if (value != null) {
                declared.put(info.getModId(), value);
                infos.put(info.getModId(), info);
            }
        }
        List<ApiRequirements.Refusal> refusals = ApiRequirements.check(declared, ApiVersion.CURRENT);
        ApiRequirements.publish(refusals);
        for (ApiRequirements.Refusal refusal : refusals) {
            LOGGER.warn("AXION : {}", refusal.describe());
            ModLoader.get().addWarning(refusal.required()
                    .map(required -> new ModLoadingWarning(infos.get(refusal.modId()), ModLoadingStage.CONSTRUCT,
                            "axion.loading.api_too_old", refusal.modId(), required.toString(),
                            refusal.present().toString()))
                    .orElseGet(() -> new ModLoadingWarning(infos.get(refusal.modId()), ModLoadingStage.CONSTRUCT,
                            "axion.loading.api_malformed", refusal.modId(), refusal.declared())));
        }
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        // Le démarrage a lieu ici : la configuration est lisible, et Forge
        // attend de cette phase qu'elle prépare le mod.
        runtime.onSetup(System.getProperties());
        writeOverridesTemplate();

        var outcome = runtime.outcome();
        if (outcome == null) {
            LOGGER.error("AXION : la séquence de démarrage n'a pas abouti");
            return;
        }
        // Les diagnostics remontent ici, et nulle part ailleurs : le noyau ne
        // journalise pas lui-même, ce qui le laisse testable.
        outcome.diagnostics().forEach(line -> LOGGER.info("AXION : {}", line));
        if (outcome.isReady()) {
            LOGGER.info("AXION : {}", outcome.summary());
        } else {
            // Un démarrage désactivé n'est pas une panne : le jeu reste
            // jouable, et les assemblies seront chargées inertes (R-410).
            LOGGER.warn("AXION : {}", outcome.summary());
        }
    }

    private void onLoadComplete(FMLLoadCompleteEvent event) {
        runtime.onLoadComplete();
    }

    /**
     * Enregistre les commandes d'AXION.
     *
     * <p>Priorité normale : la commande n'entre en concurrence avec aucune
     * autre, son littéral racine lui étant propre.
     */
    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        AxionCommands.register(event.getDispatcher(), runtime, new DevReload(runtime, this::reloadFamilies));
    }

    /**
     * Remet au runtime, à chaque rechargement des données du serveur, le contenu que les mods ont enregistré
     * (ADR-162 D2, D10).
     *
     * <p>Plus rien ne se lit dans les datapacks : le contenu vient de l'enregistrement, fait une fois au chargement
     * des mods. Ces écouteurs ne servent qu'au cycle de vie du serveur, qui charge ses données à son démarrage et à
     * chaque {@code /reload} : ils y remettent le même contenu, dans l'ordre où ses familles se nomment. Les modèles
     * d'abord ; les matériaux physiques (DM-07, ADR-145) ; les groupes de collision (R-980, ADR-132 A1) ; les
     * mappages bloc → matériau, qui nomment des matériaux ; les pneus ; les definitions enfin, qui vérifient leur
     * modèle (C-27, étape 3), leurs groupes et leurs matériaux. Minecraft applique les écouteurs dans l'ordre
     * d'ajout.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onAddReloadListener(AddReloadListenerEvent event) {
        families().forEach(family -> event.addListener(contentListener(family)));
    }

    /**
     * Une famille du contenu enregistré, telle que le runtime la reçoit.
     *
     * @param kind la famille
     * @param what ce qu'est une entrée de la famille, pour le journal
     * @param reload chargement par le runtime
     */
    private record Family(ContentKind kind, String what, Consumer<AssetSource> reload) {}

    /** {@return les familles, dans l'ordre où elles se nomment : chacune ne nomme que celles qui la précèdent} */
    private List<Family> families() {
        return List.of(
                new Family(ContentKind.MODEL, "modèle", runtime::onAssetReload),
                new Family(ContentKind.PHYSICS_MATERIAL, "matériau physique", runtime::onPhysicsMaterialReload),
                new Family(ContentKind.COLLISION_GROUP, "groupe de collision", runtime::onCollisionGroupReload),
                new Family(ContentKind.BLOCK_MATERIAL, "mappage bloc → matériau", runtime::onBlockMaterialReload),
                new Family(ContentKind.TIRE, "pneu", runtime::onTireReload),
                // Les definitions rebâtissent le registre réseau ; s'il a changé, il repart vers les joueurs
                // distants (ADR-150 D5).
                new Family(ContentKind.DEFINITION, "definition", source -> {
                    ServerSnapshot before = runtime.netSnapshot();
                    runtime.onDefinitionReload(source);
                    AxionNetwork.onRegistryReloaded(before, runtime.netSnapshot());
                }));
    }

    /**
     * ADR-162 D9 : remet au runtime toutes les familles du contenu enregistré, dans leur ordre, sur le thread
     * appelant — celui du serveur, pour {@code /axion dev reload}. Les surcharges de l'administrateur sont relues,
     * comme à un {@code /reload}.
     */
    private void reloadFamilies() {
        ServerOverrides overrides = ServerOverrides.read(FMLPaths.CONFIGDIR.get().resolve(ServerOverrides.FILE_NAME));
        for (Family family : families()) {
            List<String> applied = new ArrayList<>();
            List<String> refused = new ArrayList<>();
            if (family.kind() == ContentKind.DEFINITION) {
                refused.addAll(overrides.refusals());
            }
            applyFamily(family, RegisteredContent.source(family.kind(), overrides, applied, refused), applied,
                    refused);
        }
    }

    /**
     * Remet une famille au runtime, et journalise ce que les surcharges en ont fait.
     *
     * @param family la famille
     * @param source son contenu, surcharges appliquées
     * @param applied les surcharges appliquées
     * @param refused les surcharges refusées
     */
    private void applyFamily(Family family, AssetSource source, List<String> applied, List<String> refused) {
        logTransitions(() -> {
            if (!RegisteredContent.published()) {
                LOGGER.warn("AXION : aucun contenu enregistré à remettre ({}) : l'enregistrement des mods n'a pas "
                        + "eu lieu", family.what());
            }
            applied.forEach(line -> LOGGER.info("AXION : surcharge appliquée — {}", line));
            refused.forEach(line -> LOGGER.warn("AXION : surcharge refusée — {}", line));
            ServerOverrides.record(family.kind(), applied.size(), refused.size());
            family.reload().accept(source);
        });
    }

    /**
     * Un joueur entre en jeu : son mode de réplication se journalise, et le registre lui est renvoyé
     * s'il ne tient pas le registre courant (ADR-150 D4, 5.12bis).
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            AxionNetwork.onPlayerLoggedIn(player);
            seatReturns.onLogin(player);
        }
    }

    /** Les joueurs assis à leur départ, à rasseoir (ADR-158). */
    private final SeatReturns seatReturns = new SeatReturns();

    /**
     * Un passager quitte une assembly : celle-ci peut refuser une descente au sneak quand aucune place
     * n'est libre (ADR-158 D5, R-711). Seul Forge permet de l'empêcher : Minecraft retire le véhicule du
     * joueur avant d'en demander la position de sortie.
     */
    @SubscribeEvent
    public void onEntityMount(net.minecraftforge.event.entity.EntityMountEvent event) {
        if (event.isDismounting() && event.getEntityBeingMounted() instanceof AxionEntity assembly
                && !assembly.allowsDismount(event.getEntityMounting())) {
            event.setCanceled(true);
        }
    }

    /** Un joueur quitte le serveur : sa connexion sort du statut. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            AxionNetwork.onPlayerLoggedOut(player);
        }
    }

    /**
     * Un joueur commence à suivre une entité : son client a reçu son apparition. Une assembly entre dans
     * ce que ses snapshots portent (ADR-150 D2).
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onStartTracking(PlayerEvent.StartTracking event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            AxionNetwork.onStartTracking(player, event.getTarget());
        }
    }

    /** Un joueur cesse de suivre une entité : elle sort de ses snapshots. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onStopTracking(PlayerEvent.StopTracking event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            AxionNetwork.onStopTracking(player, event.getTarget());
        }
    }

    /** ADR-165 D4 : le modèle commenté du fichier des surcharges, s'il manque ; il n'en porte aucune. */
    private static void writeOverridesTemplate() {
        Path file = FMLPaths.CONFIGDIR.get().resolve(ServerOverrides.FILE_NAME);
        if (Files.exists(file)) {
            return;
        }
        try {
            Files.writeString(file, ServerOverrides.template(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            LOGGER.warn("AXION : modèle de {} non écrit : {}", ServerOverrides.FILE_NAME, failure.toString());
        }
    }

    /**
     * {@return un écouteur qui remet au runtime le contenu enregistré d'une famille}
     *
     * @param family la famille
     */
    private SimplePreparableReloadListener<AssetSource> contentListener(Family family) {
        return new SimplePreparableReloadListener<>() {
            private final List<String> applied = new ArrayList<>();
            private final List<String> refused = new ArrayList<>();

            @Override
            protected AssetSource prepare(ResourceManager manager, ProfilerFiller profiler) {
                // ADR-165 D4 : les surcharges de l'administrateur, relues à chaque rechargement des données.
                applied.clear();
                refused.clear();
                ServerOverrides overrides =
                        ServerOverrides.read(FMLPaths.CONFIGDIR.get().resolve(ServerOverrides.FILE_NAME));
                if (family.kind() == ContentKind.DEFINITION) {
                    refused.addAll(overrides.refusals());
                }
                return RegisteredContent.source(family.kind(), overrides, applied, refused);
            }

            @Override
            protected void apply(AssetSource source, ResourceManager manager, ProfilerFiller profiler) {
                applyFamily(family, source, applied, refused);
            }
        };
    }

    /** Prend note du serveur qui démarre. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onServerStarting(ServerStartingEvent event) {
        platform.setServer(event.getServer());
        runtime.onServerStarting();

        // C-38/C-40 : brancher les fournisseurs de commandes et le puits d'états. Le runtime
        // natif porte la config ; sans lui (outcome absent), on ne branche rien.
        if (runtime.outcome() != null) {
            int radius = (int) runtime.outcome().config().getInt("world.tile_radius");
            int perTick = (int) runtime.outcome().config().getInt("world.tiles_per_tick");
            // C-40 ↔ C-50 : les entités liées deviennent des corps natifs et reçoivent leur état ;
            // les tuiles passent d'abord, ce que ces corps occupent en tête.
            assemblyRuntime = new AssemblyRuntime(
                    runtime::definitions,
                    runtime::compiledDefinition,
                    runtime.outcome().config().getBoolean("modules.joints"),
                    runtime.outcome().config().getInt("persistence.max_load_ns_per_tick"),
                    runtime::lastSubmitNanos,
                    new dev.axion.entity.ControllerRunner.Limits(
                            runtime.outcome().config().getInt("vehicle.controller_budget_ns"),
                            (int) runtime.outcome().config().getInt("vehicle.controller_strikes")));
            worldTileBridge =
                    new WorldTileBridge(
                            event.getServer(),
                            runtime::blockMaterials,
                            runtime::physicsMaterialIndex,
                            radius,
                            perTick,
                            assemblyRuntime,
                            runtime::traceTiles);
            runtime.addCommandProvider(worldTileBridge);
            runtime.addCommandProvider(assemblyRuntime);
            runtime.setStateSink(assemblyRuntime);
            // ADR-123 : chaque tick, les joueurs (rayon de simulation, plafond, R-610) et les
            // entités vanilla qu'une assembly peut heurter (R-614).
            runtime.addCommandProvider(new EntityPresenceBridge(event.getServer(), assemblyRuntime));
            // R-1010, R-614 : les contacts avec ces entités les poussent et les blessent, selon
            // les réglages de l'annexe A.3 (amendement A1) ; le journal des faits de simulation
            // nomme une assembly par sa definition et sa position.
            runtime.setEventSink(new EntityImpactEffects(
                    assemblyRuntime,
                    runtime.outcome().config().getBoolean("physics.entity_push"),
                    runtime.outcome().config().getBoolean("physics.entity_damage")));
            runtime.setAssemblyDescriber(assemblyRuntime::describe);
            AxionEntities.controllerCosts(assemblyRuntime::controllerCosts);
            // 22.5.2 : le journal latéral du monde, ni lu ni écrit s'il est coupé ou en mode DISABLED (cas 6).
            if (runtime.outcome().config().getBoolean("persistence.journal_enabled")) {
                JournalBridge.start(
                        event.getServer(),
                        platform.gameDir(),
                        runtime.outcome().config().getInt("persistence.journal_max_bytes"),
                        runtime.outcome().config().getInt("persistence.journal_ns_per_tick"));
            }
        }

        // R-521 : barrière de démarrage. Sans elle, le monde se chargerait
        // avant ses assets, et les premières entités apparaîtraient inertes
        // sans que rien n'explique pourquoi.
        logTransitions(() -> {
            long timeout = runtime.assetStartupTimeoutNanos();
            if (timeout > 0 && !runtime.awaitAssets(timeout)) {
                LOGGER.warn("AXION : des assets n'ont pas compilé dans le délai de démarrage "
                        + "(E-3001) ; les definitions concernées sont désactivées");
            }
        });
        logAssetJournal();
    }

    /**
     * Prend note de l'arrêt d'un serveur.
     *
     * <p>Sur un serveur dédié, c'est l'arrêt du jeu. Sur un client, ce n'est
     * que la fin d'un monde solo : le cycle de vie décide, pas ce point
     * d'ancrage.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onServerStopping(ServerStoppingEvent event) {
        // C-38/C-40 : débrancher les fournisseurs et le puits avant de couper le serveur.
        runtime.clearCommandProviders();
        AxionNetwork.onServerStopping();
        runtime.setStateSink(null);
        runtime.setEventSink(null);
        runtime.setAssemblyDescriber(null);
        AxionEntities.controllerCosts(null);
        if (worldTileBridge != null) {
            worldTileBridge.close();
            worldTileBridge = null;
        }
        if (assemblyRuntime != null) {
            assemblyRuntime.close();
            assemblyRuntime = null;
        }
        // C-15 : les ticks lents encore comptés sont dits avant l'arrêt — il peut fermer le
        // contexte et repartir d'un journal neuf, qui les aurait tus. Une trace ouverte se ferme
        // aussi, pour que sa ligne parte avec eux.
        runtime.stopTrace();
        runtime.flushSimulationJournal();
        logSimulationJournal();
        logTransitions(runtime::onServerStopping);
        platform.setServer(null);
        reportHookFailures();
        reportDisabledHooks();
    }

    /**
     * Arrête le journal latéral une fois le serveur arrêté : Forge émet {@link ServerStoppingEvent} avant la
     * dernière sauvegarde du monde, dont les assemblies doivent encore partir au journal (22.5.2).
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onServerStopped(ServerStoppedEvent event) {
        JournalBridge.stop();
    }

    /**
     * Relâche les ressources natives quand le jeu s'arrête.
     *
     * <p>Forge émet cet événement depuis {@code Minecraft} comme depuis
     * {@code DedicatedServer} : c'est le seul signal qui distingue « ce monde
     * se ferme » de « le processus se termine ».
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onGameShuttingDown(GameShuttingDownEvent event) {
        logTransitions(runtime::onGameShuttingDown);
        reportHookFailures();
        reportDisabledHooks();
    }

    /**
     * Fait entrer AXION en {@code RUNNING_CLIENT} au premier tick du client, puis fait avancer la
     * compilation des modèles du client (ADR-150 D3) et journalise ses diagnostics (ADR-148).
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            runtime.onClientTick();
            for (String line : runtime.drainClientAssetJournal()) {
                LOGGER.warn("AXION : client : {}", line);
            }
            reportHookFailures();
        }
    }

    /**
     * Exécute une transition et journalise ce qu'elle a produit.
     *
     * <p>R-322 : le bilan des allocations est journalisé à l'arrêt. Sans cette
     * trace, un déséquilibre resterait invisible — et c'est justement à l'arrêt
     * qu'il se constate.
     */
    private void logTransitions(Runnable transition) {
        int before = runtime.transitions().size();
        transition.run();
        runtime.transitions().stream()
                .skip(before)
                .forEach(line -> LOGGER.info("AXION : {}", line));
    }

    /**
     * Fait avancer AXION d'un tick.
     *
     * <p>Seule la fin du tick est traitée : le moteur observe l'état une fois
     * que le jeu a fini de le modifier, plutôt que de s'intercaler au milieu.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            runtime.onTick();
            seatReturns.tick(event.getServer());
            // Les états du natif viennent d'être appliqués aux entités : ils partent dans les snapshots.
            AxionNetwork.sendSnapshots(event.getServer());
            JournalBridge journal = JournalBridge.active();
            if (journal != null) {
                journal.tick();
            }
            logSimulationJournal();
            logAssetJournal();
            reportHookFailures();
        }
    }

    /**
     * Journalise les diagnostics d'assets consignés depuis le dernier passage (ADR-148) : refus
     * (C-20, étape 5), avertissements de compilation, soucis du cache — chacun une fois, ceux d'un
     * rechargement compris.
     */
    private void logAssetJournal() {
        for (String line : runtime.drainAssetJournal()) {
            LOGGER.warn("AXION : {}", line);
        }
    }

    /**
     * Journalise les faits de simulation consignés depuis le dernier passage — E-2030, bornes,
     * paliers de dégradation, ticks lents, collect refusé : le runtime les consigne, ce point
     * d'ancrage les journalise.
     */
    private void logSimulationJournal() {
        for (SimulationJournal.Entry entry : runtime.drainSimulationJournal()) {
            if (entry.warning()) {
                LOGGER.warn("AXION : {}", entry.text());
            } else {
                LOGGER.info("AXION : {}", entry.text());
            }
        }
    }

    /**
     * Écrit les échecs de hook signalés depuis le dernier passage (FM-02), exception comprise : un échec
     * absorbé saute tout ce que son hook devait faire, et doit se voir sans attendre la désactivation.
     * Chaque garde borne ce qu'il signale ; la désactivation (E-1010) s'écrit quand elle survient.
     */
    private void reportHookFailures() {
        for (HookGuard guard : List.copyOf(runtime.guards().values())) {
            for (HookGuard.Report report : guard.drainReports()) {
                if (report.disabling()) {
                    LOGGER.error("AXION : hook {} {}", report.hook(), report.message(), report.failure());
                } else if (report.failure() != null) {
                    LOGGER.warn("AXION : hook {} {}", report.hook(), report.message(), report.failure());
                } else {
                    LOGGER.warn("AXION : hook {} : {}", report.hook(), report.message());
                }
            }
        }
    }

    /**
     * Signale les hooks désactivés après des échecs répétés.
     *
     * <p>Un hook devenu silencieux doit rester visible quelque part, sans quoi
     * une fonctionnalité disparaîtrait sans explication.
     */
    private void reportDisabledHooks() {
        for (HookGuard guard : runtime.guards().values()) {
            if (guard.isDisabled()) {
                LOGGER.error("AXION : hook {} désactivé (E-1010)", guard.name());
                guard.failures().forEach(failure -> LOGGER.error("AXION :   {}", failure));
            }
        }
    }
}
