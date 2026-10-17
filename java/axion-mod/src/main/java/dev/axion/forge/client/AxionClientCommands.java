package dev.axion.forge.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.axion.AxionMod;
import dev.axion.debug.DebugOverlays;
import dev.axion.debug.DevMode;
import dev.axion.diag.MetricsExport;
import dev.axion.forge.RuntimeAccess;
import dev.axion.lifecycle.AxionRuntime;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Commandes client d'AXION (C-71) : {@code /axion debug <overlay> on|off} (C-67, ADR-121) et
 * {@code /axion metrics export client} (ADR-133 D1, ADR-144).
 *
 * <p>L'état d'un overlay est propre au client (R-800) : la commande ne quitte pas le client.
 * Sa racine {@code axion} ne porte que ces deux branches complètes ; Forge transmet au serveur
 * toute commande client inconnue, incomplète ou à argument inconnu, si bien que
 * {@code /axion spawn}, {@code /axion metrics} et {@code /axion metrics export} y arrivent comme
 * avant. Ne sont proposés que les overlays implémentés.
 *
 * <p>Les overlays exigent le mode développeur (§31.3, décision 2 d'ADR-121) : sans
 * {@code -Daxion.dev=true}, la commande refuse et n'allume rien (R-2270).
 */
@Mod.EventBusSubscriber(modid = AxionMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class AxionClientCommands {

    private static final Logger LOGGER = LoggerFactory.getLogger("axion");

    private AxionClientCommands() {}

    /**
     * Enregistre les commandes client.
     *
     * @param event enregistrement des commandes client
     */
    @SubscribeEvent
    public static void onRegister(RegisterClientCommandsEvent event) {
        register(event.getDispatcher());
    }

    /**
     * Ajoute {@code /axion debug <overlay> on|off} et {@code /axion metrics export client} au
     * répartiteur des commandes client.
     *
     * @param dispatcher répartiteur des commandes client
     */
    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> debug = Commands.literal("debug");
        for (DebugOverlays.Overlay overlay : DebugOverlays.Overlay.values()) {
            debug.then(Commands.literal(overlay.commandName())
                    .then(Commands.literal("on")
                            .executes(context -> toggle(context.getSource(), overlay, true)))
                    .then(Commands.literal("off")
                            .executes(context -> toggle(context.getSource(), overlay, false))));
        }
        // Seule la feuille `client` s'exécute ici : `metrics` et `metrics export` restent des
        // chemins incomplets côté client, que Forge transmet au serveur.
        LiteralArgumentBuilder<CommandSourceStack> metrics = Commands.literal("metrics")
                .then(Commands.literal("export")
                        .then(Commands.literal("client")
                                .executes(context -> exportClient(context.getSource()))));
        dispatcher.register(Commands.literal("axion").then(debug).then(metrics));
    }

    /**
     * Écrit l'export des métriques de ce client (ADR-133 D1, ADR-144). Sur un client relié à un
     * serveur distant, {@code /axion metrics export} s'exécute sur le serveur et ne voit pas le
     * rendu du client ; celle-ci écrit ce que mesure le runtime du client, par le même chemin
     * ({@link MetricsExport}). En solo, ce runtime est aussi celui du serveur intégré.
     */
    private static int exportClient(CommandSourceStack source) {
        AxionRuntime runtime = RuntimeAccess.get();
        String json = runtime == null ? null : runtime.metricsJson();
        if (json == null) {
            source.sendFailure(Component.literal("AXION : runtime natif inactif, aucune métrique à exporter"));
            return 0;
        }
        Path gameDir = runtime.gameDir();
        if (gameDir == null) {
            source.sendFailure(Component.literal("AXION : répertoire de jeu inconnu"));
            return 0;
        }
        Path target;
        try {
            target = MetricsExport.write(json, gameDir, MetricsExport.CLIENT_PREFIX, LocalDateTime.now());
        } catch (IOException failure) {
            // Un export qui n'aboutit pas se dit : le taire laisserait croire qu'un fichier existe.
            source.sendFailure(Component.literal("AXION : export impossible — " + failure.getMessage()));
            return 0;
        }
        LOGGER.info("AXION : métriques du client exportées dans {} par {}", target, source.getTextName());
        source.sendSuccess(() -> Component.literal("AXION : métriques du client exportées dans " + target), false);
        return 1;
    }

    /** Allume ou éteint un overlay, en mode développeur seulement. */
    private static int toggle(CommandSourceStack source, DebugOverlays.Overlay overlay, boolean on) {
        if (!DevMode.enabled()) {
            source.sendFailure(Component.literal("Les overlays de debug exigent le mode développeur (-D"
                    + DevMode.PROPERTY + "=true)."));
            return 0;
        }
        AxionRenderPass.overlays().set(overlay, on);
        // Commande qui change un état : journalisée avec son auteur (R-810).
        LOGGER.info(
                "AXION : overlay de debug {} {} par {}",
                overlay.commandName(),
                on ? "allumé" : "éteint",
                source.getTextName());
        if (!on) {
            source.sendSuccess(() -> Component.literal("Overlay " + overlay.commandName() + " éteint."), false);
            return 1;
        }
        source.sendSuccess(
                () -> Component.literal("Overlay " + overlay.commandName() + " allumé — " + legendOf(overlay) + "."),
                false);
        if (Minecraft.getInstance().getSingleplayerServer() == null) {
            // Les corps natifs vivent avec le serveur : un client distant n'en a aucun (C-51).
            source.sendSuccess(
                    () -> Component.literal("Monde distant : aucun corps natif de ce côté, l'overlay restera vide."),
                    false);
        }
        return 1;
    }

    /** {@return la légende des couleurs d'un overlay} */
    private static String legendOf(DebugOverlays.Overlay overlay) {
        return DebugOverlayRenderer.legendOf(overlay);
    }
}
