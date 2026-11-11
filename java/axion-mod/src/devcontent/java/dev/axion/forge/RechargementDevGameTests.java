package dev.axion.forge;

import dev.axion.content.RegisteredContent;
import dev.axion.lifecycle.AxionRuntime;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * {@code /axion dev reload} de bout en bout (ADR-162 D9, CAP-50). Contenu de développement, jamais empaqueté
 * (R-1790).
 */
@GameTestHolder("axion")
@PrefixGameTestTemplate(false)
public final class RechargementDevGameTests {

    /** Le dossier du contenu de développement tel que le jeu le lit (build.gradle, {@code gameTestServer}). */
    private static final String DOSSIER = "axion.essai.devcontent";

    /** Le fichier que le test ajoute, puis retire. */
    private static final String FICHIER = "axion/definitions/test/ajoutee_au_rechargement_dev.json";

    private static final String AJOUTEE = "axion:test/ajoutee_au_rechargement_dev";

    /** Le cube de test, sous un autre nom. */
    private static final String DEFINITION =
            "{\"schema\": 1, \"asset\": \"axion:models/test/cube.gltf\", \"kind\": \"rigid_object\"}";

    private RechargementDevGameTests() {}

    /**
     * Le développeur ajoute une definition au dossier de son mod, qui tourne depuis ses sources, et tape la commande :
     * la definition entre au registre sans redémarrer. Il la retire, retape la commande : elle en sort. C'est le
     * fournisseur de contenu d'AXION, reçu au chargement des mods, qui est rappelé et relit son dossier depuis le
     * disque ; le reste du contenu ne bouge pas.
     */
    // Un lot à lui : le rechargement remet au runtime toutes les familles, modèles compris.
    @GameTest(template = "test/plancher", batch = "rechargement_dev", timeoutTicks = 600)
    public static void unFichierAjoutePuisRetireSuitLaCommande(GameTestHelper helper) {
        AxionRuntime runtime = RuntimeAccess.get();
        helper.assertTrue(runtime != null && runtime.outcome() != null, "AXION n'est pas chargé");
        String dossier = System.getProperty(DOSSIER);
        helper.assertTrue(dossier != null, "propriété " + DOSSIER + " absente : lancer par runGameTestServer");
        Path fichier = Path.of(dossier).resolve(FICHIER);
        MinecraftServer serveur = helper.getLevel().getServer();
        try {
            Files.deleteIfExists(fichier);
            int avant = runtime.definitions().size();
            long generation = RegisteredContent.generation();
            helper.assertTrue(runtime.definitions().get(AJOUTEE).isEmpty(), AJOUTEE + " présente avant le test");

            Files.createDirectories(fichier.getParent());
            Files.writeString(fichier, DEFINITION, StandardCharsets.UTF_8);
            List<String> ajout = new ArrayList<>();
            int rendu = commande(serveur, ajout);
            helper.assertTrue(rendu == 1, "/axion dev reload a rendu " + rendu + " : " + ajout);
            helper.assertTrue(RegisteredContent.generation() == generation + 1, "le contenu n'a pas été republié");
            helper.assertTrue(runtime.definitions().get(AJOUTEE).isPresent(),
                    AJOUTEE + " absente après le rechargement : le dossier n'a pas été relu depuis le disque");
            helper.assertTrue(runtime.definitions().size() == avant + 1,
                    "attendu " + (avant + 1) + " definitions, " + runtime.definitions().size());
            helper.assertTrue(ajout.stream().anyMatch(ligne -> ligne.contains("contenu réenregistré")),
                    "réponse de la commande : " + ajout);

            Files.delete(fichier);
            List<String> retrait = new ArrayList<>();
            rendu = commande(serveur, retrait);
            helper.assertTrue(rendu == 1, "/axion dev reload a rendu " + rendu + " : " + retrait);
            helper.assertTrue(runtime.definitions().get(AJOUTEE).isEmpty(), AJOUTEE + " présente après son retrait");
            helper.assertTrue(runtime.definitions().size() == avant,
                    "attendu " + avant + " definitions, " + runtime.definitions().size());
        } catch (IOException echec) {
            throw new UncheckedIOException(echec);
        } finally {
            try {
                Files.deleteIfExists(fichier);
            } catch (IOException ignore) {
                // Le passage suivant le retire avant de commencer.
            }
        }
        // Les modèles, redécouverts, se recompilent : les lots suivants les trouvent prêts.
        helper.succeedWhen(() -> helper.assertTrue(runtime.assets() != null && runtime.assets().isSettled(),
                "modèles en cours de compilation après le rechargement"));
    }

    /** Tape la commande avec les droits du serveur ; {@code sortie} reçoit ses réponses. */
    private static int commande(MinecraftServer serveur, List<String> sortie) {
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
        return serveur.getCommands().performPrefixedCommand(
                serveur.createCommandSourceStack().withSource(recueil), "axion dev reload");
    }
}
