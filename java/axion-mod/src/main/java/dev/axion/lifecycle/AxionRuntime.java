package dev.axion.lifecycle;

import dev.axion.bootstrap.AxionBootstrap;
import dev.axion.bootstrap.BootstrapOutcome;
import dev.axion.bootstrap.NativeApi;
import dev.axion.config.ConfigSchema.Scope;
import dev.axion.platform.PlatformAdapter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.BiFunction;

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

    /** Signale qu'un client est prêt. */
    public void onClientStarted() {
        guard("clientStarted").run(() -> transitionTo(LifecyclePhase.RUNNING_CLIENT));
    }

    /**
     * Signale un tick du thread autoritatif.
     *
     * <p>Ne fait rien tant qu'aucune assembly n'existe : le budget
     * {@code budgets.idle_hook_ns} plafonne précisément ce que coûte un tick à
     * vide, et il n'y a rien à faire avant que le moteur n'ait du contenu à
     * simuler.
     */
    public void onTick() {
        guard("tick").run(() -> {
            if (!isOperational()) {
                return;
            }
            // La boucle de simulation arrive avec C-40 ; d'ici là, un tick ne
            // coûte que ce passage.
        });
    }

    /** Signale l'arrêt : les ressources natives sont relâchées. */
    public void onStopping() {
        guard("stopping").run(() -> {
            if (!transitionTo(LifecyclePhase.STOPPING)) {
                return;
            }
            if (outcome != null && outcome.isReady()) {
                try {
                    // R-322 : l'arrêt journalise le bilan des allocations ; il
                    // doit donc avoir lieu, même si le jeu se ferme
                    // brutalement après.
                    int code = nativeApi.close(outcome.context());
                    transitions.add("contexte natif fermé, code " + code);
                } catch (Throwable failure) {
                    // Une fermeture qui échoue ne doit pas laisser le cycle
                    // bloqué en STOPPING : l'arrêt doit toujours aboutir, quitte
                    // à abandonner des ressources que le processus va de toute
                    // façon rendre en se terminant.
                    transitions.add("fermeture du contexte natif impossible : " + failure);
                }
            }
            outcome = null;
            transitionTo(LifecyclePhase.UNLOADED);
        });
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
