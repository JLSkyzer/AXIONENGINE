package dev.axion.forge.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * C-71 — ADR-121 : la commande client {@code /axion debug} ne masque pas les commandes
 * serveur.
 *
 * <p>Forge ({@code ClientCommandHandler.runCommand}, 47.4.23) transmet au serveur une commande
 * dont l'exécution côté client échoue en « commande inconnue » ou « argument inconnu ». Ce
 * test épingle donc le type d'échec que l'arbre client donne aux autres branches.
 */
class AxionClientCommandsTest {

    private static CommandDispatcher<CommandSourceStack> arbre() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        AxionClientCommands.register(dispatcher);
        return dispatcher;
    }

    @Test
    @DisplayName("ADR-121 : /axion debug colliders on|off est une commande complète du client")
    void debugColliders() {
        CommandDispatcher<CommandSourceStack> dispatcher = arbre();
        for (String etat : new String[] {"on", "off"}) {
            ParseResults<CommandSourceStack> parse =
                    dispatcher.parse("axion debug colliders " + etat, null);
            assertTrue(parse.getExceptions().isEmpty(), etat);
            assertFalse(parse.getReader().canRead(), etat);
            assertNotNull(parse.getContext().getCommand(), etat);
        }
    }

    @Test
    @DisplayName("ADR-121 : /axion spawn échoue côté client en argument inconnu, donc part au serveur")
    void spawnTransmisAuServeur() {
        CommandDispatcher<CommandSourceStack> dispatcher = arbre();
        CommandSyntaxException echec = assertThrows(
                CommandSyntaxException.class,
                () -> dispatcher.execute("axion spawn axion:test_cube ~ ~ ~", null));
        assertEquals(
                CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownArgument(),
                echec.getType());
    }

    @Test
    @DisplayName("ADR-121 : un overlay non implémenté n'est pas proposé, et part au serveur")
    void overlayNonImplemente() {
        CommandDispatcher<CommandSourceStack> dispatcher = arbre();
        CommandSyntaxException echec = assertThrows(
                CommandSyntaxException.class,
                () -> dispatcher.execute("axion debug aabb on", null));
        assertEquals(
                CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownArgument(),
                echec.getType());
    }

    @Test
    @DisplayName("ADR-121 : une autre racine que axion reste inconnue du client")
    void autreRacine() {
        CommandDispatcher<CommandSourceStack> dispatcher = arbre();
        CommandSyntaxException echec = assertThrows(
                CommandSyntaxException.class, () -> dispatcher.execute("gamemode creative", null));
        assertEquals(
                CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownCommand(),
                echec.getType());
    }
}
