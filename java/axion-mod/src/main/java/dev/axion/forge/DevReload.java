package dev.axion.forge;

import com.mojang.logging.LogUtils;
import dev.axion.config.AxionConfig;
import dev.axion.lifecycle.AxionRuntime;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

/**
 * {@code /axion dev reload} : recharger en développement (ADR-162 D9, CAP-50).
 *
 * <p>La commande rappelle le fournisseur de chaque mod sur un registre neuf — un mod qui tourne depuis ses sources
 * relit ainsi son dossier depuis le disque —, publie le contenu résolu, puis le remet au runtime famille par famille,
 * les surcharges de l'administrateur relues, comme le démarrage du serveur. Le registre réseau rebâti repart vers les
 * joueurs distants s'il a changé. En solo, le client de la même JVM recompile ses modèles depuis ce contenu ; un
 * client distant garde les siens, qui viennent de ses propres mods. Les assemblies déjà posées gardent leur
 * definition compilée (CAP-33).
 *
 * <p>Réservée au développement : sans {@code content.dev_reload}, la commande refuse et dit comment l'activer. Elle
 * s'exécute sur le thread du serveur, qui lit les fichiers et attend la fin du rechargement.
 */
public final class DevReload {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** L'option qui l'autorise (ANNEXE A.3, amendement A13). */
    static final String OPTION = "content.dev_reload";

    /** Ce que le client de la même JVM fait après un rechargement ; posé par le client, absent d'un serveur dédié. */
    private static volatile Runnable clientRefresh;

    private final AxionRuntime runtime;
    private final Runnable reloadFamilies;

    /**
     * @param runtime le cycle de vie, qui porte la configuration
     * @param reloadFamilies remet toutes les familles du contenu publié au runtime, sur le thread appelant
     */
    DevReload(AxionRuntime runtime, Runnable reloadFamilies) {
        this.runtime = runtime;
        this.reloadFamilies = reloadFamilies;
    }

    /**
     * Pose ce que le client fait de ses modèles après un rechargement en solo : le code commun ne nomme aucune
     * classe du client.
     *
     * @param refresh recompile les modèles du client depuis le contenu publié ; appelé du thread du serveur intégré
     */
    public static void installClientRefresh(Runnable refresh) {
        clientRefresh = refresh;
    }

    /**
     * {@return pourquoi la commande refuse, ou {@code null} si elle est autorisée}
     *
     * @param config la configuration commune, {@code null} si elle n'est pas chargée
     */
    static String refusal(AxionConfig config) {
        if (config == null) {
            return "AXION : configuration non chargée";
        }
        if (!config.getBoolean(OPTION)) {
            return "AXION : /axion dev reload est désactivé ; il se réserve au développement : " + OPTION
                    + " = true dans axion-common.toml, ou -Daxion." + OPTION + "=true au lancement (ADR-162 D9)";
        }
        return null;
    }

    /**
     * Exécute la commande.
     *
     * @param source qui la demande
     * @return 1 si le contenu a été rechargé, 0 sinon
     */
    int run(CommandSourceStack source) {
        AuthoritativeThread.require();
        var outcome = runtime.outcome();
        String refusal = refusal(outcome == null ? null : outcome.config());
        if (refusal != null) {
            source.sendFailure(Component.literal(refusal));
            return 0;
        }
        // R-810 : commande mutante, journalisée avec son auteur.
        LOGGER.info("AXION : /axion dev reload demandé par {}", source.getTextName());
        long started = System.nanoTime();
        ContentRegistration.Outcome registered;
        try {
            registered = ContentRegistration.reregister(runtime.maxSiblingBytes());
        } catch (IllegalStateException refused) {
            source.sendFailure(Component.literal("AXION : " + refused.getMessage()));
            return 0;
        }
        reloadFamilies.run();
        Runnable refresh = clientRefresh;
        if (refresh != null) {
            refresh.run();
        }
        long millis = (System.nanoTime() - started) / 1_000_000L;
        LOGGER.info("AXION : /axion dev reload fait en {} ms — {} mod(s), {} ; {} erreur(s)", millis,
                registered.mods().size(), registered.describe(), registered.errors());
        String errors = registered.errors() == 0 ? ""
                : " ; " + registered.errors() + " erreur(s) d'enregistrement, voir le journal du serveur";
        source.sendSuccess(() -> Component.literal("AXION : contenu réenregistré par " + registered.mods().size()
                + " mod(s) — " + registered.describe() + errors
                + ". Les assemblies déjà posées gardent leur definition."), true);
        return 1;
    }
}
