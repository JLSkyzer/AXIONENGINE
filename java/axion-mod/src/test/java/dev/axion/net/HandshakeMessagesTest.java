package dev.axion.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.axion.config.ConfigSchema;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Le handshake de la connexion (21.3, ADR-150 D4) : ce qui s'écrit se relit, ce qui sort du domaine
 * est refusé, et le mode de réplication suit les deux empreintes (T-820c).
 */
class HandshakeMessagesTest {

    private static final DetProfile VALIDEE = new DetProfile(0x1234_5678_9ABC_DEF0L, 1, true);

    private static ServerHandshake serveur(float dt, int sousPas, float gravite, int frequence, int retard) {
        return new ServerHandshake(NetProtocol.PROTOCOL_VERSION, dt, sousPas, gravite, frequence, retard,
                true, true, 0, 11L, RegistryContent.EMPTY_DIGEST, 22L, VALIDEE);
    }

    @Test
    @DisplayName("Le handshake du serveur et la réponse du client se relisent à l'identique")
    void lesHandshakesFontLAllerRetour() throws MalformedPacketException {
        ServerHandshake serveur = serveur(0.0166667f, 4, -9.81f, 10, 100);
        assertEquals(serveur, ServerHandshake.decode(serveur.encode()));

        ClientHandshake client = new ClientHandshake(NetProtocol.PROTOCOL_VERSION,
                new DetProfile(-1L, 1, false));
        assertEquals(client, ClientHandshake.decode(client.encode()));
    }

    @Test
    @DisplayName("Toute valeur que la configuration admet passe le handshake : un serveur bien configuré n'est jamais refusé")
    void leDomaineDeLaConfigurationPasseLeHandshake() throws MalformedPacketException {
        List<ConfigSchema.Option> options = Arrays.stream(ConfigSchema.Scope.values())
                .flatMap(scope -> scope.options().stream())
                .toList();
        for (String pas : option(options, "sim.fixed_dt").domain().allowed()) {
            relu(serveur(Float.parseFloat(pas), 4, -9.81f, 10, 100));
        }
        for (double sousPas : bornes(option(options, "sim.max_substeps"))) {
            relu(serveur(0.05f, (int) sousPas, -9.81f, 10, 100));
        }
        for (double gravite : bornes(option(options, "physics.gravity"))) {
            relu(serveur(0.05f, 4, (float) gravite, 10, 100));
        }
        for (double frequence : bornes(option(options, "net.snapshot_rate_hz"))) {
            relu(serveur(0.05f, 4, -9.81f, (int) frequence, 100));
        }
        for (double retard : bornes(option(options, "net.interpolation_delay_ms"))) {
            relu(serveur(0.05f, 4, -9.81f, 10, (int) retard));
        }
    }

    private static ConfigSchema.Option option(List<ConfigSchema.Option> options, String path) {
        return options.stream().filter(option -> option.path().equals(path)).findFirst().orElseThrow();
    }

    private static double[] bornes(ConfigSchema.Option option) {
        return new double[] {option.domain().min(), option.domain().max()};
    }

    private static void relu(ServerHandshake serveur) throws MalformedPacketException {
        assertEquals(serveur, ServerHandshake.decode(serveur.encode()));
    }

    @Test
    @DisplayName("T-422 : une valeur hors de son domaine est refusée, pas appliquée")
    void t422_uneValeurHorsDomaineEstRefusee() {
        for (ServerHandshake fautif : new ServerHandshake[] {
            serveur(0f, 4, -9.81f, 10, 100),
            serveur(Float.NaN, 4, -9.81f, 10, 100),
            serveur(2f, 4, -9.81f, 10, 100),
            serveur(0.05f, 0, -9.81f, 10, 100),
            serveur(0.05f, 9, -9.81f, 10, 100),
            serveur(0.05f, 4, 1f, 10, 100),
            serveur(0.05f, 4, Float.NaN, 10, 100),
            serveur(0.05f, 4, -51f, 10, 100),
            serveur(0.05f, 4, -9.81f, 1, 100),
            serveur(0.05f, 4, -9.81f, 21, 100),
        }) {
            assertThrows(MalformedPacketException.class, () -> ServerHandshake.decode(fautif.encode()),
                    fautif::toString);
        }
    }

    @Test
    @DisplayName("T-422 : un autre genre, une autre version de protocole, un message tronqué ou trop long sont refusés")
    void t422_unMessageMalFormeEstRefuse() {
        byte[] serveur = serveur(0.05f, 4, -9.81f, 10, 100).encode();
        byte[] client = new ClientHandshake(NetProtocol.PROTOCOL_VERSION, VALIDEE).encode();

        assertThrows(MalformedPacketException.class, () -> ServerHandshake.decode(client));
        assertThrows(MalformedPacketException.class, () -> ClientHandshake.decode(serveur));
        assertThrows(MalformedPacketException.class,
                () -> ServerHandshake.decode(Arrays.copyOf(serveur, serveur.length - 1)));
        assertThrows(MalformedPacketException.class,
                () -> ServerHandshake.decode(Arrays.copyOf(serveur, serveur.length + 1)));
        assertThrows(MalformedPacketException.class,
                () -> ClientHandshake.decode(Arrays.copyOf(client, client.length - 1)));

        ServerHandshake autreVersion = new ServerHandshake(NetProtocol.PROTOCOL_VERSION + 1, 0.05f, 4,
                -9.81f, 10, 100, true, true, 0, 0L, 0L, 0L, VALIDEE);
        assertThrows(MalformedPacketException.class, () -> ServerHandshake.decode(autreVersion.encode()));
        ClientHandshake clientAutreVersion = new ClientHandshake(NetProtocol.PROTOCOL_VERSION + 1, VALIDEE);
        assertThrows(MalformedPacketException.class, () -> ClientHandshake.decode(clientAutreVersion.encode()));
    }

    @Test
    @DisplayName("T-820c : des empreintes différentes, ou une extrémité hors de la matrice, donnent SNAPSHOT dès le handshake")
    void t820c_leModeSuitLesDeuxEmpreintes() {
        assertEquals(ReplicationMode.RECONSTRUCT, ReplicationMode.negotiate(VALIDEE, VALIDEE));
        assertEquals(ReplicationMode.SNAPSHOT,
                ReplicationMode.negotiate(VALIDEE, new DetProfile(VALIDEE.profile() + 1, 1, true)));
        assertEquals(ReplicationMode.SNAPSHOT,
                ReplicationMode.negotiate(VALIDEE, new DetProfile(VALIDEE.profile(), 1, false)));
        assertEquals(ReplicationMode.SNAPSHOT,
                ReplicationMode.negotiate(new DetProfile(VALIDEE.profile(), 1, false), VALIDEE));
    }
}
