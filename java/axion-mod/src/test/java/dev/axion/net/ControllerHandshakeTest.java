package dev.axion.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ADR-167 Q7 : le handshake du serveur annonce les contrôleurs de véhicule que ses definitions nomment ; le client
 * répond ceux qu'il n'enregistre pas, et le serveur ne retient que ceux qu'il a annoncés.
 */
class ControllerHandshakeTest {

    private static final DetProfile VALIDEE = new DetProfile(0x1234_5678_9ABC_DEF0L, 1, true);

    private static ServerHandshake serveur(List<String> controleurs) {
        return new ServerHandshake(NetProtocol.PROTOCOL_VERSION, 0.05f, 4, -9.81f, 10, 100, true, true, 0, 11L,
                RegistryContent.EMPTY_DIGEST, 22L, VALIDEE, List.of(new RequiredMod("turbo", "1.0.0")), controleurs);
    }

    @Test
    @DisplayName("Q7 : les contrôleurs annoncés et les manquants font l'aller-retour")
    void allerRetour() throws MalformedPacketException {
        ServerHandshake serveur = serveur(List.of("abs:frein", "turbo:gestion"));
        assertEquals(serveur, ServerHandshake.decode(serveur.encode()));
        ClientHandshake client = new ClientHandshake(NetProtocol.PROTOCOL_VERSION, VALIDEE, List.of("1.0.0"),
                List.of("turbo:gestion"));
        assertEquals(client, ClientHandshake.decode(client.encode()));
        assertEquals(List.of(), ServerHandshake.decode(serveur(List.of()).encode()).controllers());
    }

    @Test
    @DisplayName("Q7 : une liste annoncée non triée, en double ou mal formée est refusée (R-1650)")
    void malFormes() {
        assertThrows(IllegalArgumentException.class, () -> serveur(List.of("turbo:gestion", "abs:frein")),
                "non triée");
        assertThrows(IllegalArgumentException.class, () -> serveur(List.of("abs:frein", "abs:frein")), "en double");
        assertThrows(IllegalArgumentException.class, () -> serveur(List.of("Turbo")), "mal formé");
        assertThrows(IllegalArgumentException.class, () -> new ClientHandshake(NetProtocol.PROTOCOL_VERSION, VALIDEE,
                List.of(), List.of("sans espace")), "un manquant mal formé");

        // Sur le fil : deux noms de même longueur échangés, la liste n'est plus triée.
        byte[] fil = serveur(List.of("aa:x", "bb:y")).encode();
        byte[] premier = "aa:x".getBytes(StandardCharsets.UTF_8);
        int position = indexOf(fil, premier);
        System.arraycopy("bb:y".getBytes(StandardCharsets.UTF_8), 0, fil, position, 4);
        System.arraycopy(premier, 0, fil, position + 5, 4);
        assertThrows(MalformedPacketException.class, () -> ServerHandshake.decode(fil));
    }

    private static int indexOf(byte[] octets, byte[] motif) {
        for (int i = 0; i + motif.length <= octets.length; i++) {
            boolean trouve = true;
            for (int j = 0; j < motif.length && trouve; j++) {
                trouve = octets[i + j] == motif[j];
            }
            if (trouve) {
                return i;
            }
        }
        throw new AssertionError("motif absent");
    }

    @Test
    @DisplayName("Q7 : le client répond les contrôleurs annoncés qu'aucun de ses mods n'enregistre, dans leur ordre")
    void leClientNommeCeQuiLuiManque() {
        ServerHandshake serveur = serveur(List.of("abs:frein", "radar:balise", "turbo:gestion"));
        ClientSession client = new ClientSession(null, VALIDEE, registre -> List.of(), etats -> {}, mod -> "1.0.0",
                "turbo:gestion"::equals);
        ServerPeer pair = new ServerPeer(VALIDEE);
        pair.onLoginReply(client.onLoginFrame(Fragments.split(serveur.encode(), 0).get(0)));
        assertEquals(List.of("abs:frein", "radar:balise"), pair.client().orElseThrow().missingControllers());
    }

    @Test
    @DisplayName("Q7 : le serveur ne retient que les manquants qu'il a annoncés, dans son ordre")
    void leServeurNeRetientQueCeQuIlAAnnonce() {
        assertEquals(List.of("abs:frein", "turbo:gestion"), RequiredMod.missingControllers(
                List.of("abs:frein", "radar:balise", "turbo:gestion"), List.of("turbo:gestion", "x:y", "abs:frein")));
        assertEquals(List.of(), RequiredMod.missingControllers(List.of("abs:frein"), List.of()));
    }
}
