package dev.axion.forge;

import com.mojang.brigadier.CommandDispatcher;
import dev.axion.diag.StatusReport;
import dev.axion.lifecycle.AxionRuntime;
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
                                    for (String line : StatusReport.of(runtime)) {
                                        context.getSource()
                                                .sendSuccess(() -> Component.literal(line), false);
                                    }
                                    return 1;
                                })));
    }
}
