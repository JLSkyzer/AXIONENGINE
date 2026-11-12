package dev.axion.forge;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.commands.CommandSource;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * {@code /axion defs list | info} sur un vrai serveur (CAP-42, C-71) : la vraie commande, ses vraies réponses.
 * Contenu de développement, jamais empaqueté (R-1790).
 */
@GameTestHolder("axion")
@PrefixGameTestTemplate(false)
public final class DefinitionsCommandGameTests {

    private DefinitionsCommandGameTests() {}

    /**
     * La liste compte les definitions et montre celles du témoin ; le détail d'une definition chargée nomme son genre,
     * son modèle et le fichier qui l'a enregistrée ; une definition inconnue le dit.
     */
    @GameTest(template = "test/plancher")
    public static void listeEtDetail(GameTestHelper helper) {
        MinecraftServer serveur = helper.getLevel().getServer();

        List<String> liste = commande(serveur, "axion defs list");
        helper.assertTrue(!liste.isEmpty() && liste.get(0).startsWith("AXION ENGINE — definitions : "),
                "en-tête de la liste : " + liste);
        helper.assertTrue(liste.contains("  example_addon (2) : crate, heavy_crate"),
                "le témoin manque à la liste : " + liste);

        List<String> detail = commande(serveur, "axion defs info example_addon:crate");
        helper.assertTrue(detail.contains("AXION ENGINE — example_addon:crate")
                && detail.contains("  genre : RIGID_OBJECT")
                && detail.contains("  enregistrée par : example_addon — axion/definitions/crate.json"),
                "détail : " + detail);

        List<String> inconnue = commande(serveur, "axion defs info example_addon:fantome");
        helper.assertTrue(inconnue.size() == 1 && inconnue.get(0).contains("ni chargée ni refusée"),
                "definition inconnue : " + inconnue);
        helper.succeed();
    }

    /** {@return les réponses d'une commande tapée avec les droits du serveur} */
    private static List<String> commande(MinecraftServer serveur, String ligne) {
        List<String> sortie = new ArrayList<>();
        CommandSource recueil = new CommandSource() {
            @Override
            public void sendSystemMessage(Component message) {
                sortie.add(message.getString());
            }

            @Override
            public boolean acceptsSuccess() {
                return true;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }

            @Override
            public boolean shouldInformAdmins() {
                return false;
            }
        };
        serveur.getCommands().performPrefixedCommand(serveur.createCommandSourceStack().withSource(recueil), ligne);
        return sortie;
    }
}
