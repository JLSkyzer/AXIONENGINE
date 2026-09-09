package dev.axion.forge;

import com.mojang.logging.LogUtils;
import dev.axion.AxionMod;
import dev.axion.lifecycle.AxionRuntime;
import dev.axion.lifecycle.HookGuard;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.GameShuttingDownEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;
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

        MinecraftForge.EVENT_BUS.register(this);

        runtime.onConstructed(platform);
        LOGGER.info("AXION ENGINE construit sur {}", platform.platformName());
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        // Le démarrage a lieu ici : la configuration est lisible, et Forge
        // attend de cette phase qu'elle prépare le mod.
        runtime.onSetup(System.getProperties());

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
        AxionCommands.register(event.getDispatcher(), runtime);
    }

    /** Prend note du serveur qui démarre. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onServerStarting(ServerStartingEvent event) {
        platform.setServer(event.getServer());
        runtime.onServerStarting();
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
        logTransitions(runtime::onServerStopping);
        platform.setServer(null);
        reportDisabledHooks();
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
        reportDisabledHooks();
    }

    /**
     * Fait entrer AXION en {@code RUNNING_CLIENT} au premier tick du client.
     *
     * <p>Seul le premier tick produit quelque chose ; les suivants ne coûtent
     * qu'une comparaison de phase.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            runtime.onClientTick();
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
