package dev.axion.forge;

import com.mojang.brigadier.CommandDispatcher;
import dev.axion.diag.MetricsReport;
import dev.axion.diag.StatusReport;
import dev.axion.lifecycle.AxionRuntime;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * Commandes d'AXION (C-71).
 *
 * <p>Seule {@code /axion status} existe à ce stade : c'est le livrable du
 * premier jalon, et la seule commande qui ait du sens tant que le moteur n'a
 * ni assets ni assemblies à manipuler. Les autres branches arrivent avec les
 * composants qu'elles pilotent.
 *
 * <p>La commande exige le niveau de permission des opérateurs. Elle ne divulgue
 * aucune donnée de joueur, mais décrit l'installation — chemins, versions,
 * causes de désactivation — et cela n'a pas à être visible de tous. C'est
 * l'option conservatrice, que rien n'oblige à conserver si l'usage montre le
 * contraire.
 */
final class AxionCommands {

    /** Niveau de permission des opérateurs. */
    private static final int OPERATOR = 2;

    private AxionCommands() {
        throw new AssertionError("classe utilitaire, non instanciable");
    }

    /**
     * Enregistre les commandes d'AXION.
     *
     * @param dispatcher répartiteur de commandes du serveur
     * @param runtime cycle de vie à interroger
     */
    static void register(CommandDispatcher<CommandSourceStack> dispatcher, AxionRuntime runtime) {
        dispatcher.register(
                Commands.literal("axion")
                        .requires(source -> source.hasPermission(OPERATOR))
                        .then(Commands.literal("status")
                                .executes(context -> {
                                    // Le rapport est composé hors de toute API
                                    // de plateforme ; ici on ne fait que
                                    // l'afficher.
                                    return send(context.getSource(), StatusReport.of(runtime));
                                }))
                        .then(Commands.literal("metrics")
                                .executes(context -> metrics(context.getSource(), runtime))
                                .then(Commands.literal("export")
                                        .executes(context ->
                                                export(context.getSource(), runtime)))));
    }

    /** Affiche le résumé des métriques natives (R-502). */
    private static int metrics(CommandSourceStack source, AxionRuntime runtime) {
        String json = runtime.metricsJson();
        if (json == null) {
            source.sendFailure(Component.literal(
                    "AXION : runtime natif inactif, aucune métrique à lire"));
            return 0;
        }
        return send(source, MetricsReport.summary(json));
    }

    /**
     * Écrit l'export complet dans un fichier et en donne le chemin (R-502).
     *
     * <p>Le document part dans un fichier plutôt que dans le chat : il compte
     * plusieurs dizaines de lignes, et R-442 le destine aussi au dump de
     * diagnostic. Le nom porte l'horodatage, si bien que deux exports
     * successifs ne s'écrasent pas — comparer deux instants est précisément ce
     * qu'on veut faire de métriques.
     */
    private static int export(CommandSourceStack source, AxionRuntime runtime) {
        String json = runtime.metricsJson();
        if (json == null) {
            source.sendFailure(Component.literal(
                    "AXION : runtime natif inactif, aucune métrique à exporter"));
            return 0;
        }

        Path gameDir = runtime.gameDir();
        if (gameDir == null) {
            source.sendFailure(Component.literal("AXION : répertoire de jeu inconnu"));
            return 0;
        }

        String stamp = LocalDateTime.now().format(FILE_STAMP);
        Path target = gameDir.resolve("axion").resolve("metrics-" + stamp + ".json");
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, json, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            // Un export qui n'aboutit pas se dit : le taire laisserait croire
            // qu'un fichier existe quelque part.
            source.sendFailure(Component.literal(
                    "AXION : export impossible — " + failure.getMessage()));
            return 0;
        }

        source.sendSuccess(
                () -> Component.literal("AXION : métriques exportées dans " + target), true);
        return 1;
    }

    private static int send(CommandSourceStack source, List<String> lines) {
        for (String line : lines) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    /** Horodatage du nom de fichier : trié par ordre alphabétique comme par date. */
    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
}
