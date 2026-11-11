package dev.axion.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-165 : les addons que le serveur exige, dans le handshake et à la réponse du client. */
class RequiredModTest {

    private static final DetProfile VALIDEE = new DetProfile(0x0123_4567_89AB_CDEFL, 1, true);

    private static final List<RequiredMod> EXIGES = List.of(
            new RequiredMod("axion", "1.0.0"), new RequiredMod("policemod", "2.1.0"),
            new RequiredMod("tracteurs", "1.4.0"));

    @Test
    @DisplayName("ADR-165 D1 : un addon exigé a l'identifiant d'un mod et une version non vide de 128 octets au plus")
    void unAddonExigeEstValide() {
        assertThrows(IllegalArgumentException.class, () -> new RequiredMod("Police", "1"), "majuscule");
        assertThrows(IllegalArgumentException.class, () -> new RequiredMod("p", "1"), "un seul caractère");
        assertThrows(IllegalArgumentException.class, () -> new RequiredMod("1mod", "1"), "commence par un chiffre");
        assertThrows(IllegalArgumentException.class, () -> new RequiredMod("police", ""), "version vide");
        assertThrows(IllegalArgumentException.class, () -> new RequiredMod("police", "é".repeat(65)), "130 octets");
        assertEquals("é".repeat(64), new RequiredMod("police", "é".repeat(64)).version(), "128 octets");
    }

    @Test
    @DisplayName("ADR-165 D1 : la liste est triée par identifiant, et bornée")
    void laListeEstTrieeEtBornee() {
        Map<String, String> versions = new HashMap<>(Map.of("tracteurs", "1.4.0", "axion", "1.0.0",
                "policemod", "2.1.0"));
        assertEquals(EXIGES, RequiredMod.of(versions));
        SortedMap<String, String> trop = new TreeMap<>();
        for (int i = 0; i <= RequiredMod.MAX_MODS; i++) {
            trop.put(String.format("mod%04d", i), "1");
        }
        assertThrows(IllegalArgumentException.class, () -> RequiredMod.of(trop));
    }

    @Test
    @DisplayName("ADR-165 D3 : un addon absent ou d'une autre version est en cause ; un addon en plus chez le client ne l'est pas")
    void lesDifferencesSontCellesDuServeur() {
        assertEquals(List.of(), RequiredMod.mismatches(EXIGES, List.of("1.0.0", "2.1.0", "1.4.0")));
        List<RequiredMod.Mismatch> causes = RequiredMod.mismatches(EXIGES, List.of("1.0.0", "", "1.3.2"));
        assertEquals(List.of(new RequiredMod.Mismatch("policemod", "2.1.0", ""),
                new RequiredMod.Mismatch("tracteurs", "1.4.0", "1.3.2")), causes);
        assertTrue(causes.get(0).absent());
        assertEquals(List.of(new RequiredMod.Mismatch("tracteurs", "1.4.0", "")),
                RequiredMod.mismatches(EXIGES, List.of("1.0.0", "2.1.0")), "une réponse courte : le reste est absent");
        assertEquals(List.of(new RequiredMod.Mismatch("policemod", "2.1.0", "2.1")),
                RequiredMod.mismatches(EXIGES, List.of("1.0.0", "2.1", "1.4.0")), "l'égalité est exacte (Q1)");
    }

    @Test
    @DisplayName("ADR-165 D2 : les addons exigés et les versions du client se relisent à l'identique")
    void lesMessagesFontLAllerRetour() throws MalformedPacketException {
        ServerHandshake serveur = new ServerHandshake(NetProtocol.PROTOCOL_VERSION, 0.05f, 4, -9.81f, 10, 100,
                true, true, 0, 1L, 2L, 3L, VALIDEE, EXIGES);
        assertEquals(serveur, ServerHandshake.decode(serveur.encode()));
        ClientHandshake client = new ClientHandshake(NetProtocol.PROTOCOL_VERSION, VALIDEE,
                List.of("1.0.0", "", "1.3.2"));
        assertEquals(client, ClientHandshake.decode(client.encode()));
    }

    @Test
    @DisplayName("R-1650 : un addon exigé mal formé dans le handshake est refusé, sans lever ailleurs")
    void unAddonMalFormeEstRefuse() {
        byte[] sain = new ServerHandshake(NetProtocol.PROTOCOL_VERSION, 0.05f, 4, -9.81f, 10, 100,
                true, true, 0, 1L, 2L, 3L, VALIDEE, List.of(new RequiredMod("policemod", "2.1.0"))).encode();
        // « policemod » est la dernière chaîne avant la version : sa première lettre devient une majuscule.
        byte[] fautif = sain.clone();
        int lettre = indexOf(fautif, "policemod".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        fautif[lettre] = 'P';
        assertThrows(MalformedPacketException.class, () -> ServerHandshake.decode(fautif));
    }

    @Test
    @DisplayName("ADR-165 D2 : la réponse du client donne la version qu'il a de chaque addon exigé, dans l'ordre du serveur")
    void leClientRepondSesVersions() throws MalformedPacketException {
        ServerHandshake serveur = new ServerHandshake(NetProtocol.PROTOCOL_VERSION, 0.05f, 4, -9.81f, 10, 100,
                true, true, 0, 1L, 2L, 3L, VALIDEE, EXIGES);
        Map<String, String> installes = Map.of("axion", "1.0.0", "tracteurs", "1.3.2", "autre", "9");
        ClientSession session = new ClientSession(null, VALIDEE, registre -> List.of(), etats -> { },
                installes::get);
        byte[] reponse = session.onLoginFrame(Fragments.split(serveur.encode(), 0).get(0));
        ServerPeer pair = new ServerPeer(VALIDEE);
        pair.onLoginReply(reponse);
        ClientHandshake lu = pair.client().orElseThrow();
        assertEquals(List.of("1.0.0", "", "1.3.2"), lu.versions());
        assertEquals(List.of(new RequiredMod.Mismatch("policemod", "2.1.0", ""),
                new RequiredMod.Mismatch("tracteurs", "1.4.0", "1.3.2")),
                RequiredMod.mismatches(serveur.requiredMods(), lu.versions()));
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = haystack.length - needle.length; i >= 0; i--) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        throw new AssertionError("motif absent");
    }
}
