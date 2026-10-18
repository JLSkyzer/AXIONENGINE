package dev.axion.forge;

import dev.axion.asset.AssetSource;
import dev.axion.definition.DefinitionRegistry;
import dev.axion.lifecycle.AxionRuntime;
import dev.axion.net.ClientSession;
import dev.axion.net.RegistryAck;
import dev.axion.net.RemoteRegistry;
import dev.axion.net.ServerPeer;
import dev.axion.net.ServerSnapshot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * La moitié serveur du registre réseau (ADR-150 D5, T-420), sur les packs du contenu de développement et
 * le zstd du natif : ce qu'un client distant recevrait, relu comme il le relirait, donne exactement les
 * definitions du serveur. Contenu de développement, jamais empaqueté (R-1790).
 *
 * <p>La plomberie de Forge — paquets de connexion, réponses — est éprouvée par le banc de rendu, dont le
 * client se connecte en local, et par l'essai à deux instances d'ADR-150 D8.
 */
@GameTestHolder("axion")
@PrefixGameTestTemplate(false)
public final class RegistreReseauGameTests {

    private static final String AJOUTEE = "axion:test/ajoutee_au_registre_reseau";

    private static final String CHEMIN = "axion:axion/definitions/test/ajoutee_au_registre_reseau.json";

    private static final byte[] DEFINITION =
            "{\"schema\": 1, \"asset\": \"axion:models/test/cube.gltf\", \"kind\": \"rigid_object\"}"
                    .getBytes(StandardCharsets.UTF_8);

    private RegistreReseauGameTests() {}

    private static AxionRuntime runtime(GameTestHelper helper) {
        AxionRuntime runtime = RuntimeAccess.get();
        helper.assertTrue(runtime != null && runtime.outcome() != null, "AXION n'est pas chargé");
        return runtime;
    }

    /** Rejoue la connexion d'un client distant : les definitions qu'il tiendrait, et ce que le serveur en saurait. */
    private static DefinitionRegistry connecter(
            GameTestHelper helper, AxionRuntime runtime, ServerSnapshot instantane, ServerPeer pair) {
        List<DefinitionRegistry> tenues = new ArrayList<>();
        ClientSession client = new ClientSession(runtime.netCompression(), runtime.detProfile(), registre -> {
            RemoteRegistry.Loaded relu = RemoteRegistry.load(registre);
            tenues.add(relu.definitions());
            return relu.journal();
        });
        for (byte[] trame : instantane.loginFrames(false)) {
            pair.onLoginReply(client.onLoginFrame(trame));
        }
        List<String> fautes = client.drainJournal().stream().filter(ligne -> ligne.startsWith("E-")).toList();
        helper.assertTrue(fautes.isEmpty(), "le client a refusé : " + fautes);
        helper.assertTrue(tenues.size() == 1, "le client devrait avoir appliqué un registre, il en a appliqué "
                + tenues.size());
        return tenues.get(0);
    }

    /**
     * T-420 : le registre que le serveur envoie, décompressé par le zstd du natif et relu par le lecteur
     * du client, donne les definitions du serveur, ni plus ni moins ; le client l'acquitte, et le serveur
     * n'a rien à lui renvoyer en jeu. Une connexion locale ne reçoit que le handshake.
     */
    @GameTest(template = "test/plancher")
    public static void unClientDistantTiendraitLesDefinitionsDuServeur(GameTestHelper helper) {
        AxionRuntime runtime = runtime(helper);
        ServerSnapshot instantane = runtime.netSnapshot();
        helper.assertTrue(instantane != null && instantane.hasRegistry(),
                "aucun registre réseau après le rechargement des definitions : " + runtime.transitions());
        helper.assertTrue(instantane.handshake().definitionsHash() == instantane.registry().definitionsHash(),
                "le handshake n'annonce pas l'empreinte du registre envoyé");
        helper.assertTrue(instantane.loginFrames(true).size() == 1, "une connexion locale ne reçoit que le handshake");

        ServerPeer pair = new ServerPeer(instantane.handshake().det());
        DefinitionRegistry tenues = connecter(helper, runtime, instantane, pair);
        helper.assertTrue(tenues.ids().equals(runtime.definitions().ids()),
                "le client tiendrait " + tenues.ids() + ", le serveur tient " + runtime.definitions().ids());
        helper.assertTrue(!runtime.definitions().ids().isEmpty(), "aucune definition dans les packs de test");
        helper.assertTrue(pair.registry().map(ack -> ack.matches(instantane.registry())).orElse(false),
                "le client n'a pas acquitté le registre reçu");
        helper.assertTrue(!pair.needsRegistry(instantane, false), "rien ne devrait repartir en jeu");
        helper.assertTrue(pair.mode().isPresent(), "aucun mode de réplication négocié");

        // Une déconnexion côté client n'oublie que les definitions reçues d'un serveur distant : celles
        // du serveur intégré restent.
        int avant = runtime.definitions().size();
        runtime.onRemoteDisconnect();
        helper.assertTrue(runtime.definitions().size() == avant, "la déconnexion a vidé les definitions du serveur");
        helper.succeed();
    }

    /**
     * Un rechargement des definitions rebâtit l'instantané : le registre change d'empreinte, et la
     * definition ajoutée part vers les clients distants.
     */
    @GameTest(template = "test/plancher")
    public static void unRechargementRebatitLeRegistreReseau(GameTestHelper helper) {
        AxionRuntime runtime = runtime(helper);
        ResourceAssetSource courantes = new ResourceAssetSource(
                helper.getLevel().getServer().getResourceManager(),
                ResourceAssetSource.DEFINITIONS,
                path -> path.endsWith(".json"));
        AssetSource avecUneDePlus = new AssetSource() {
            @Override
            public List<String> list() {
                List<String> paths = new ArrayList<>(courantes.list());
                paths.add(CHEMIN);
                return paths;
            }

            @Override
            public byte[] read(String path) throws IOException {
                return CHEMIN.equals(path) ? DEFINITION.clone() : courantes.read(path);
            }
        };
        ServerSnapshot avant = runtime.netSnapshot();
        try {
            runtime.onDefinitionReload(avecUneDePlus);
            ServerSnapshot apres = runtime.netSnapshot();
            helper.assertTrue(apres != null && apres != avant, "l'instantané n'a pas été rebâti");
            helper.assertTrue(!RegistryAck.of(avant.registry()).matches(apres.registry()),
                    "l'empreinte du registre n'a pas changé");
            DefinitionRegistry tenues = connecter(helper, runtime, apres, new ServerPeer(apres.handshake().det()));
            helper.assertTrue(tenues.get(AJOUTEE).isPresent(), AJOUTEE + " n'atteint pas le client");
        } finally {
            // Les autres tests retrouvent le registre des packs.
            runtime.onDefinitionReload(courantes);
        }
        helper.succeed();
    }
}
