package dev.axion.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.axion.asset.AssetSource;
import dev.axion.config.AxionConfig;
import dev.axion.config.ConfigSchema;
import dev.axion.definition.CollisionGroupRegistry;
import dev.axion.definition.DefinitionRegistry;
import dev.axion.definition.DefinitionRules;
import dev.axion.definition.PhysicsMaterials;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * La phase de connexion de bout en bout, sans Forge (ADR-150 D4, D5) : le serveur bâtit son instantané
 * comme après un rechargement, chaque trame passe par la session du client, et chaque réponse revient au
 * serveur — une trame par paquet de connexion, une réponse par trame, comme Forge les échange.
 *
 * <p>La compression est l'identité, bornée comme celle du natif : le zstd est éprouvé contre la vraie
 * bibliothèque par {@code NativeBridgeTest}.
 */
class LoginFlowTest {

    private static final NetCompression IDENTITE = new NetCompression() {
        @Override
        public byte[] compress(byte[] payload) {
            return payload.clone();
        }

        @Override
        public byte[] decompress(byte[] frame, int maxBytes) throws MalformedPacketException {
            if (frame.length > maxBytes) {
                throw new MalformedPacketException("au-delà de la borne");
            }
            return frame.clone();
        }
    };

    private static final DetProfile VALIDEE = new DetProfile(0x0123_4567_89AB_CDEFL, 1, true);

    private static final AxionConfig CONFIG = defauts();

    /** Le nombre de definitions d'un lot : assez pour que le registre se fragmente. */
    private static final int LOT = 400;

    private static AxionConfig defauts() {
        Map<String, Object> values = new HashMap<>();
        ConfigSchema.COMMON_OPTIONS.forEach(option -> values.put(option.path(), option.defaultValue()));
        return new AxionConfig(ConfigSchema.Scope.COMMON, values, List.of());
    }

    private static byte[] json(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static AssetSource source(SortedMap<String, byte[]> files) {
        return new AssetSource() {
            @Override
            public List<String> list() {
                return List.copyOf(files.keySet());
            }

            @Override
            public byte[] read(String path) throws IOException {
                return files.get(path).clone();
            }
        };
    }

    /** Les données d'un serveur : textes lus et registres chargés, comme ses rechargements les tiennent. */
    private record Serveur(
            SortedMap<String, byte[]> materiaux,
            SortedMap<String, byte[]> groupes,
            SortedMap<String, byte[]> definitions,
            DefinitionRegistry acceptees) {

        /**
         * @param ouvert le serveur a le module des véhicules et charge les démonstrations ; sinon il
         *     refuse l'un et écarte les autres
         */
        static Serveur charge(SortedMap<String, byte[]> materiaux, SortedMap<String, byte[]> groupes,
                SortedMap<String, byte[]> definitions, boolean ouvert) {
            PhysicsMaterials lus = PhysicsMaterials.load(source(materiaux));
            CollisionGroupRegistry groupesLus = CollisionGroupRegistry.load(source(groupes));
            DefinitionRules regles = new DefinitionRules(ouvert, modele -> true, ouvert, groupesLus, lus);
            return new Serveur(materiaux, groupes, definitions, DefinitionRegistry.load(source(definitions), regles));
        }

        ServerSnapshot instantane(DetProfile det) {
            RegistryContent.Builder registre = ServerRegistry.collect(materiaux, groupes, acceptees, definitions);
            return ServerSnapshot.build(CONFIG, registre.build(), registre.excluded(), det, IDENTITE);
        }
    }

    private static Serveur serveur(String... definitionsEnPlus) {
        return serveur(false, "{\"density\": 7000, \"friction\": 0.6}", definitionsEnPlus);
    }

    private static Serveur serveur(boolean ouvert, String metal, String... definitionsEnPlus) {
        SortedMap<String, byte[]> materiaux = new TreeMap<>();
        materiaux.put("mymod:axion/physics_materials/metal.json", json(metal));
        materiaux.put("mymod:axion/physics_materials/casse.json", json("{\"density\": \"lourd\"}"));
        SortedMap<String, byte[]> groupes = new TreeMap<>();
        groupes.put("mymod:axion/collision_groups/roue.json", json("{}"));
        SortedMap<String, byte[]> definitions = new TreeMap<>();
        definitions.put("mymod:axion/definitions/caisse.json",
                json("{\"schema\": 1, \"asset\": \"mymod:models/caisse.glb\", \"kind\": \"visual\"}"));
        definitions.put("mymod:axion/definitions/pickup.json",
                json("{\"schema\": 1, \"asset\": \"mymod:models/pickup.glb\", \"kind\": \"vehicle\"}"));
        definitions.put("axion:axion/definitions/example/crate.json",
                json("{\"schema\": 1, \"asset\": \"axion:models/example/crate.glb\", \"kind\": \"visual\"}"));
        // Nomme un groupe tiers : le client doit avoir lu les groupes avant les definitions.
        definitions.put("mymod:axion/definitions/roulante.json", json("{\"schema\": 1, \"asset\":"
                + " \"mymod:models/caisse.glb\", \"kind\": \"static\","
                + " \"physics\": {\"collision_group\": \"mymod:roue\"}}"));
        for (int index = 0; index < LOT; index++) {
            definitions.put(String.format("mymod:axion/definitions/lot/d%03d.json", index),
                    json("{\"schema\": 1, \"asset\": \"mymod:models/caisse.glb\", \"kind\": \"static\"}"));
        }
        for (int index = 0; index < definitionsEnPlus.length; index += 2) {
            definitions.put(definitionsEnPlus[index], json(definitionsEnPlus[index + 1]));
        }
        return Serveur.charge(materiaux, groupes, definitions, ouvert);
    }

    /** Un client, et ce qu'il a appliqué. */
    private static final class Client {
        final List<DefinitionRegistry> appliques = new ArrayList<>();
        final ClientSession session;

        Client(DetProfile det) {
            session = new ClientSession(IDENTITE, det, registre -> {
                RemoteRegistry.Loaded relu = RemoteRegistry.load(registre);
                appliques.add(relu.definitions());
                return relu.journal();
            });
        }
    }

    /** Rejoue la phase de connexion : chaque trame reçoit sa réponse, que le serveur lit. */
    private static int connecter(List<byte[]> trames, ClientSession client, ServerPeer pair) {
        int reponses = 0;
        for (byte[] trame : trames) {
            byte[] reponse = client.onLoginFrame(trame);
            assertNotNull(reponse, "chaque paquet de connexion reçoit une réponse");
            reponses++;
            pair.onLoginReply(reponse);
        }
        return reponses;
    }

    private static String texte(List<String> journal) {
        return String.join("\n", journal);
    }

    @Test
    @DisplayName("T-420 : une connexion distante reçoit le registre du serveur, fragmenté, et tient exactement ses definitions")
    void t420_uneConnexionDistanteTientLeRegistreDuServeur() {
        Serveur serveur = serveur();
        ServerSnapshot instantane = serveur.instantane(VALIDEE);
        assertTrue(instantane.registryFrames(0).size() >= 2, "le registre doit ici se fragmenter");
        assertFalse(serveur.acceptees().get("mymod:pickup").isPresent(), "le serveur refuse le véhicule");
        assertFalse(serveur.acceptees().get("axion:example/crate").isPresent(), "et écarte la démonstration");
        assertTrue(serveur.acceptees().get("mymod:roulante").isPresent(), serveur.acceptees().refusals()::toString);

        Client client = new Client(VALIDEE);
        ServerPeer pair = new ServerPeer(instantane.handshake().det());
        List<byte[]> trames = instantane.loginFrames(false);
        assertEquals(trames.size(), connecter(trames, client.session, pair));

        assertEquals(1, client.appliques.size());
        assertEquals(serveur.acceptees().ids(), client.appliques.get(0).ids(),
                "le client tient les definitions du serveur, ni plus — le véhicule refusé — ni moins");
        assertEquals(LOT + 2, client.appliques.get(0).size());
        assertEquals(instantane.handshake(), client.session.server().orElseThrow());
        assertTrue(pair.registry().orElseThrow().matches(instantane.registry()));
        assertFalse(pair.needsRegistry(instantane, false), "rien à renvoyer en jeu");

        String journal = texte(client.session.drainJournal());
        assertTrue(journal.contains("registre du serveur appliqué : " + (LOT + 2) + " definition(s), 1 matériau(x)"
                + " physique(s), 1 groupe(s) de collision"), journal);
        assertTrue(journal.contains("refusé chez le client : E-7001 mymod:axion/physics_materials/casse.json"),
                "le matériau que le serveur refuse, le client le refuse aussi : " + journal);
        assertTrue(pair.drainJournal().isEmpty());
    }

    @Test
    @DisplayName("Le client ne rejuge pas la politique du serveur : un véhicule, une démonstration qu'il a acceptés, le client les tient")
    void leClientNeRejugePasLaPolitiqueDuServeur() {
        Serveur serveur = serveur(true, "{\"density\": 7000}");
        assertTrue(serveur.acceptees().get("mymod:pickup").isPresent());
        assertTrue(serveur.acceptees().get("axion:example/crate").isPresent());
        ServerSnapshot instantane = serveur.instantane(VALIDEE);

        Client client = new Client(VALIDEE);
        connecter(instantane.loginFrames(false), client.session, new ServerPeer(VALIDEE));
        assertEquals(serveur.acceptees().ids(), client.appliques.get(0).ids());
    }

    @Test
    @DisplayName("Une connexion locale ne reçoit que le handshake : elle partage le registre du serveur intégré")
    void uneConnexionLocaleNeRecoitQueLeHandshake() {
        ServerSnapshot instantane = serveur().instantane(VALIDEE);
        List<byte[]> trames = instantane.loginFrames(true);
        assertEquals(1, trames.size());

        Client client = new Client(VALIDEE);
        ServerPeer pair = new ServerPeer(VALIDEE);
        connecter(trames, client.session, pair);

        assertTrue(client.appliques.isEmpty());
        assertEquals(ReplicationMode.RECONSTRUCT, pair.mode().orElseThrow());
        assertTrue(pair.registry().isEmpty());
        assertFalse(pair.needsRegistry(instantane, true));
        assertTrue(pair.needsRegistry(instantane, false), "distante, la même connexion attendrait le registre");
    }

    @Test
    @DisplayName("T-820c : le mode de réplication se décide des deux côtés dès le handshake")
    void t820c_leModeSeDecideDesDeuxCotes() {
        ServerSnapshot instantane = serveur().instantane(VALIDEE);
        DetProfile autreEmpreinte = new DetProfile(VALIDEE.profile() + 1, 1, true);
        DetProfile horsMatrice = new DetProfile(VALIDEE.profile(), 1, false);
        Map<DetProfile, String> causes = Map.of(
                autreEmpreinte, "SNAPSHOT (empreintes déterministes différentes)",
                horsMatrice, "SNAPSHOT (client hors de la matrice validée)");
        for (Map.Entry<DetProfile, String> cas : causes.entrySet()) {
            Client client = new Client(cas.getKey());
            ServerPeer pair = new ServerPeer(VALIDEE);
            connecter(instantane.loginFrames(true), client.session, pair);
            assertEquals(ReplicationMode.SNAPSHOT, pair.mode().orElseThrow(), cas::toString);
            assertEquals(ReplicationMode.SNAPSHOT, client.session.mode().orElseThrow(), cas::toString);
            assertEquals(cas.getKey(), pair.client().orElseThrow().det());
            assertEquals(cas.getValue(), pair.describe());
            assertTrue(texte(client.session.drainJournal()).contains("mode de réplication " + cas.getValue()));
        }
        // Un serveur hors matrice face à un client qui y est.
        ServerPeer pairHorsMatrice = new ServerPeer(horsMatrice);
        connecter(instantane.loginFrames(true), new Client(VALIDEE).session, pairHorsMatrice);
        assertEquals("SNAPSHOT (serveur hors de la matrice validée)", pairHorsMatrice.describe());

        Client client = new Client(VALIDEE);
        ServerPeer pair = new ServerPeer(VALIDEE);
        connecter(instantane.loginFrames(true), client.session, pair);
        assertEquals(ReplicationMode.RECONSTRUCT, client.session.mode().orElseThrow());
        assertEquals("RECONSTRUCT (empreintes égales, dans la matrice validée)", pair.describe());
    }

    @Test
    @DisplayName("T-422 : un registre altéré en chemin est ignoré, la connexion aboutit, et le registre repart en jeu")
    void t422_unRegistreAltereEstIgnorePuisRenvoye() {
        Serveur serveur = serveur();
        ServerSnapshot instantane = serveur.instantane(VALIDEE);
        List<byte[]> trames = instantane.loginFrames(false);
        byte[] derniere = trames.get(trames.size() - 1);
        // Un octet du dernier texte JSON : la trame reste bien formée, l'empreinte ne l'est plus.
        derniere[derniere.length - 3] ^= 0x01;

        Client client = new Client(VALIDEE);
        ServerPeer pair = new ServerPeer(VALIDEE);
        assertEquals(trames.size(), connecter(trames, client.session, pair));
        assertTrue(client.appliques.isEmpty());
        assertTrue(texte(client.session.drainJournal()).contains("E-5001 paquet ignoré (registre de la connexion)"));
        assertEquals(RegistryAck.NONE, pair.registry().orElseThrow(), "le client dit ne rien tenir");
        assertEquals(ReplicationMode.RECONSTRUCT, pair.mode().orElseThrow(), "le handshake, lui, est passé");
        assertTrue(pair.needsRegistry(instantane, false));

        for (byte[] trame : instantane.registryFrames(7)) {
            client.session.onPlayFrame(trame);
        }
        assertEquals(1, client.appliques.size());
        assertEquals(serveur.acceptees().ids(), client.appliques.get(0).ids());
    }

    @Test
    @DisplayName("T-422 : une trame écartée pour sa taille, un genre inconnu, un message hors de sa phase sont ignorés sans lever")
    void t422_lesTramesFautivesSontIgnorees() {
        Client client = new Client(VALIDEE);
        ServerPeer pair = new ServerPeer(VALIDEE);

        assertEquals(0, client.session.onLoginFrame(null).length, "une trame écartée reçoit un simple acquittement");
        assertEquals(0, client.session.onLoginFrame(Fragments.split(new byte[] {9}, 0).get(0)).length);
        client.session.onPlayFrame(Fragments.split(
                new ClientHandshake(NetProtocol.PROTOCOL_VERSION, VALIDEE).encode(), 0).get(0));
        client.session.onPlayFrame(null);
        List<String> journal = client.session.drainJournal();
        assertEquals(4, journal.size(), journal::toString);
        journal.forEach(ligne -> assertTrue(ligne.startsWith("E-5001 paquet ignoré"), ligne));

        pair.onLoginReply(null);
        pair.onLoginReply(Fragments.split(new byte[] {MessageKinds.SERVER_HANDSHAKE}, 0).get(0));
        pair.onLoginReply(new byte[0]);
        assertEquals(2, pair.drainJournal().size(), "un simple acquittement n'est pas une faute");
        assertTrue(pair.mode().isEmpty());
        assertEquals("aucune réponse au handshake", pair.describe());
    }

    @Test
    @DisplayName("Un handshake du serveur refusé laisse quand même la connexion aboutir : le client répond par le sien")
    void unHandshakeRefuseObtientQuandMemeUneReponse() {
        ServerHandshake fautif = new ServerHandshake(NetProtocol.PROTOCOL_VERSION, 0f, 4, -9.81f, 10, 100,
                true, true, 0, 0L, 0L, 0L, VALIDEE);
        Client client = new Client(VALIDEE);
        ServerPeer pair = new ServerPeer(VALIDEE);
        pair.onLoginReply(client.session.onLoginFrame(Fragments.split(fautif.encode(), 0).get(0)));

        assertTrue(client.session.server().isEmpty());
        assertTrue(client.session.mode().isEmpty());
        assertEquals(ReplicationMode.RECONSTRUCT, pair.mode().orElseThrow(), "le serveur connaît le client");
        assertTrue(texte(client.session.drainJournal()).contains("E-5001 paquet ignoré (handshake du serveur)"));
    }

    @Test
    @DisplayName("Un rechargement entre la connexion et l'entrée en jeu fait renvoyer le registre courant")
    void unRechargementPendantLaConnexionFaitRenvoyerLeRegistre() {
        ServerSnapshot avant = serveur().instantane(VALIDEE);
        ServerSnapshot apres = serveur("mymod:axion/definitions/nouvelle.json",
                "{\"schema\": 1, \"asset\": \"mymod:models/caisse.glb\", \"kind\": \"visual\"}").instantane(VALIDEE);
        ServerSnapshot autresMateriaux = serveur(false, "{\"density\": 2700}").instantane(VALIDEE);

        Client client = new Client(VALIDEE);
        ServerPeer pair = new ServerPeer(VALIDEE);
        connecter(avant.loginFrames(false), client.session, pair);

        assertFalse(pair.needsRegistry(avant, false));
        assertTrue(pair.needsRegistry(apres, false), "une definition de plus");
        assertTrue(pair.needsRegistry(autresMateriaux, false), "un matériau changé, les definitions intactes");
    }

    @Test
    @DisplayName("Un client sans runtime natif, ou dont l'application échoue, dit ne rien tenir")
    void unClientQuiNePeutAppliquerDitNeRienTenir() {
        ServerSnapshot instantane = serveur().instantane(VALIDEE);

        ClientSession sansNatif = new ClientSession(null, VALIDEE, registre -> List.of());
        ServerPeer pair = new ServerPeer(VALIDEE);
        connecter(instantane.loginFrames(false), sansNatif, pair);
        assertEquals(RegistryAck.NONE, pair.registry().orElseThrow());
        assertTrue(texte(sansNatif.drainJournal()).contains("runtime natif inactif"));

        ClientSession enEchec = new ClientSession(IDENTITE, VALIDEE, registre -> {
            throw new IllegalStateException("échec simulé");
        });
        ServerPeer autre = new ServerPeer(VALIDEE);
        connecter(instantane.loginFrames(false), enEchec, autre);
        assertEquals(RegistryAck.NONE, autre.registry().orElseThrow());
        assertTrue(texte(enEchec.drainJournal()).contains("non appliqué : java.lang.IllegalStateException: échec simulé"));
    }

    @Test
    @DisplayName("R-1691 : une entrée hors des bornes du protocole ne part pas, et le serveur le dit ; les autres partent")
    void uneEntreeHorsBornesNePartPas() {
        RegistryContent.Builder registre = RegistryContent.builder()
                .add(RegistryContent.Family.DEFINITIONS, "mymod:axion/definitions/ok.json", json("{}"))
                .add(RegistryContent.Family.DEFINITIONS, "mymod:axion/definitions/gros.json",
                        new byte[RegistryContent.MAX_FILE_BYTES + 1])
                .add(RegistryContent.Family.DEFINITIONS,
                        "mymod:axion/definitions/" + "a".repeat(RegistryContent.MAX_PATH_BYTES) + ".json", json("{}"))
                .add(RegistryContent.Family.PHYSICS_MATERIALS, "mymod:axion/definitions/egare.json", json("{}"));

        assertEquals(List.of("mymod:axion/definitions/ok.json"),
                List.copyOf(registre.build().files(RegistryContent.Family.DEFINITIONS).keySet()));
        List<String> ecartees = registre.excluded();
        assertEquals(3, ecartees.size(), ecartees::toString);
        assertTrue(ecartees.get(0).startsWith("mymod:axion/definitions/gros.json non transmis aux clients : "
                + (RegistryContent.MAX_FILE_BYTES + 1) + " octets"), ecartees::toString);

        ServerSnapshot instantane = ServerSnapshot.build(CONFIG, registre.build(), ecartees, VALIDEE, IDENTITE);
        assertTrue(instantane.hasRegistry());
        assertEquals(ecartees, instantane.notes());
    }

    @Test
    @DisplayName("R-1691 : un registre au-delà des bornes du protocole n'est pas envoyé, et le serveur le dit")
    void unRegistreHorsBornesNEstPasEnvoye() {
        RegistryContent.Builder registre = RegistryContent.builder();
        for (int index = 0; index <= RegistryContent.MAX_ENTRIES; index++) {
            registre.add(RegistryContent.Family.DEFINITIONS, "axion:axion/definitions/d" + index + ".json", json("{}"));
        }
        ServerSnapshot instantane = ServerSnapshot.build(CONFIG, registre.build(), List.of(), VALIDEE, IDENTITE);

        assertFalse(instantane.hasRegistry());
        assertEquals(1, instantane.loginFrames(false).size(), "le handshake part quand même");
        assertTrue(instantane.registryFrames(3).isEmpty());
        assertFalse(new ServerPeer(VALIDEE).needsRegistry(instantane, false), "rien à renvoyer en jeu non plus");
        assertTrue(texte(instantane.notes()).startsWith("registre réseau non construit : registre de "
                + (RegistryContent.MAX_ENTRIES + 1) + " entrées"), () -> texte(instantane.notes()));
        assertTrue(instantane.describe().endsWith("; non envoyé"), instantane.describe());
    }

    @Test
    @DisplayName("Le handshake annonce la configuration du serveur et les empreintes du registre qu'il envoie")
    void leHandshakeAnnonceLaConfigurationEtLeRegistre() throws MalformedPacketException {
        // Chaque option a une valeur que nulle autre ne partage : une option lue à la place d'une autre
        // se verrait.
        Map<String, Object> values = new HashMap<>(CONFIG.values());
        values.put("sim.fixed_dt", 0.0333333);
        values.put("sim.max_substeps", 3L);
        values.put("physics.gravity", -4.5);
        values.put("net.snapshot_rate_hz", 15L);
        values.put("net.interpolation_delay_ms", 250L);
        values.put("net.prediction", false);
        values.put("deformation.enabled", true);
        AxionConfig config = new AxionConfig(ConfigSchema.Scope.COMMON, values, List.of());
        RegistryContent registre = serveur().instantane(VALIDEE).registry();
        ServerSnapshot instantane = ServerSnapshot.build(config, registre, List.of(), VALIDEE, IDENTITE);

        assertEquals(new ServerHandshake(NetProtocol.PROTOCOL_VERSION, 0.0333333f, 3, -4.5f, 15, 250, false, true,
                ServerHandshake.DEFORM_QUANT_POLICY, registre.definitionsHash(), RegistryContent.EMPTY_DIGEST,
                registre.materialsHash(), VALIDEE), instantane.handshake());
        assertTrue(registre.definitionsHash() != registre.materialsHash());

        ServerHandshake parDefaut = serveur().instantane(VALIDEE).handshake();
        assertEquals(parDefaut, ServerHandshake.decode(parDefaut.encode()), "les défauts passent leur lecture");
    }
}
