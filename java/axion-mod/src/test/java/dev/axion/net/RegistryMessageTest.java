package dev.axion.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-420 — les registres du serveur atteignent le client, empreintes vérifiées (21.3, 21.4, ADR-150 D5).
 *
 * <p>La compression est ici l'identité, bornée comme celle du natif : le zstd lui-même est éprouvé
 * contre la vraie bibliothèque par {@code NativeBridgeTest}.
 */
class RegistryMessageTest {

    /** L'identité, qui refuse au-delà de sa borne comme le zstd du natif. */
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

    private static byte[] json(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, byte[]> map(String... pairs) {
        Map<String, byte[]> map = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            map.put(pairs[index], json(pairs[index + 1]));
        }
        return map;
    }

    private static RegistryContent registre() {
        return RegistryContent.of(
                map("axion:axion/physics_materials/test/balle.json", "{\"restitution\":0.8}"),
                map("tiers:axion/collision_groups/tiers.json", "{\"groups\":[\"roue\"]}"),
                map("axion:axion/definitions/test/ballon.json", "{\"schema\":1}",
                        "axion:axion/definitions/test_cube.json", "{\"schema\":1,\"kind\":\"rigid_object\"}"));
    }

    private static void memeContenu(RegistryContent attendu, RegistryContent lu) {
        for (RegistryContent.Family family : RegistryContent.Family.values()) {
            assertEquals(attendu.files(family).keySet(), lu.files(family).keySet(), family.name());
            for (String path : attendu.files(family).keySet()) {
                assertArrayEquals(attendu.files(family).get(path), lu.files(family).get(path), path);
            }
        }
    }

    @Test
    @DisplayName("T-420 : le registre envoyé se relit à l'identique, empreintes comprises")
    void t420_leRegistreSeRelitAvecSesEmpreintes() throws MalformedPacketException {
        RegistryContent envoye = registre();
        RegistryContent recu = RegistryMessage.decode(RegistryMessage.encode(envoye, IDENTITE), IDENTITE);
        memeContenu(envoye, recu);
        assertEquals(envoye.definitionsHash(), recu.definitionsHash());
        assertEquals(envoye.materialsHash(), recu.materialsHash());
    }

    @Test
    @DisplayName("T-420 : l'empreinte ne dépend pas de l'ordre de lecture, mais d'un seul octet, d'un nom ou d'une famille")
    void t420_lEmpreinteDitLesTextesEtRienDAutre() {
        RegistryContent reference = registre();
        RegistryContent autreOrdre = RegistryContent.of(
                map("axion:axion/physics_materials/test/balle.json", "{\"restitution\":0.8}"),
                map("tiers:axion/collision_groups/tiers.json", "{\"groups\":[\"roue\"]}"),
                map("axion:axion/definitions/test_cube.json", "{\"schema\":1,\"kind\":\"rigid_object\"}",
                        "axion:axion/definitions/test/ballon.json", "{\"schema\":1}"));
        assertEquals(reference.definitionsHash(), autreOrdre.definitionsHash());

        RegistryContent unOctet = RegistryContent.of(
                map("axion:axion/physics_materials/test/balle.json", "{\"restitution\":0.9}"),
                map("tiers:axion/collision_groups/tiers.json", "{\"groups\":[\"roue\"]}"),
                map("axion:axion/definitions/test/ballon.json", "{\"schema\":1}",
                        "axion:axion/definitions/test_cube.json", "{\"schema\":1,\"kind\":\"rigid_object\"}"));
        assertNotEquals(reference.materialsHash(), unOctet.materialsHash());
        assertEquals(reference.definitionsHash(), unOctet.definitionsHash(), "les matériaux n'entrent pas ici");

        RegistryContent sansGroupe = RegistryContent.of(
                map("axion:axion/physics_materials/test/balle.json", "{\"restitution\":0.8}"),
                map(),
                map("axion:axion/definitions/test/ballon.json", "{\"schema\":1}",
                        "axion:axion/definitions/test_cube.json", "{\"schema\":1,\"kind\":\"rigid_object\"}"));
        assertNotEquals(reference.definitionsHash(), sansGroupe.definitionsHash(), "les groupes y entrent");

        RegistryContent autreNom = RegistryContent.of(
                map("axion:axion/physics_materials/test/balle.json", "{\"restitution\":0.8}"),
                map("tiers:axion/collision_groups/tiers.json", "{\"groups\":[\"roue\"]}"),
                map("axion:axion/definitions/test/ballon2.json", "{\"schema\":1}",
                        "axion:axion/definitions/test_cube.json", "{\"schema\":1,\"kind\":\"rigid_object\"}"));
        assertNotEquals(reference.definitionsHash(), autreNom.definitionsHash());

        RegistryContent vide = RegistryContent.of(map(), map(), map());
        assertEquals(RegistryContent.EMPTY_DIGEST, vide.definitionsHash());
        assertEquals(RegistryContent.EMPTY_DIGEST, vide.materialsHash());
    }

    @Test
    @DisplayName("T-420 : deux découpages du même flux d'octets ne donnent pas la même empreinte")
    void t420_deuxDecoupagesDuMemeFluxSeDistinguent() {
        // Un texte qui contient le nom et le séparateur de l'entrée qui, ailleurs, le suit : sans la
        // longueur de chaque texte, les deux registres produiraient le même flux.
        String suivante = "axion:axion/definitions/q.json";
        byte[] colle = ("X" + suivante + "\0Y").getBytes(StandardCharsets.UTF_8);
        Map<String, byte[]> une = new LinkedHashMap<>();
        une.put("axion:axion/definitions/p.json", colle);
        RegistryContent unSeul = RegistryContent.of(map(), map(), une);
        RegistryContent deux = RegistryContent.of(map(), map(),
                map("axion:axion/definitions/p.json", "X", suivante, "Y"));
        assertNotEquals(unSeul.definitionsHash(), deux.definitionsHash());
    }

    @Test
    @DisplayName("T-420 : un registre altéré en chemin, ou dont l'empreinte annoncée diffère, est refusé")
    void t420_unRegistreAltereEstRefuse() {
        byte[] message = RegistryMessage.encode(registre(), IDENTITE);
        // Le dernier octet du registre : celui du dernier texte, compté dans l'empreinte.
        byte[] altere = message.clone();
        altere[altere.length - 2] ^= 0x01;
        assertThrows(MalformedPacketException.class, () -> RegistryMessage.decode(altere, IDENTITE));

        // L'empreinte annoncée, juste après le genre.
        byte[] annonce = message.clone();
        annonce[1] ^= 0x01;
        assertThrows(MalformedPacketException.class, () -> RegistryMessage.decode(annonce, IDENTITE));
    }

    @Test
    @DisplayName("Un identifiant hors de sa famille, ou qui n'en est pas un, est une faute de l'émetteur")
    void unIdentifiantInvalideEstRefuseALEmission() {
        for (String path : new String[] {
            "axion:axion/definitions/test.json",
            "axion:axion/physics_materials/../definitions/x.json",
            "axion:axion/physics_materials/X.json",
            "axion:axion/physics_materials/x.txt",
            "axion/physics_materials/x.json",
            "C:/axion/physics_materials/x.json",
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> RegistryContent.of(map(path, "{}"), map(), map()), path);
        }
    }

    @Test
    @DisplayName("T-422 : un registre reçu mal formé — famille inconnue, identifiant invalide, doublon, borne — est refusé")
    void t422_unRegistreRecuMalFormeEstRefuse() {
        byte[] familleInconnue = new WireWriter().varUInt(1).u8(9)
                .utf8("axion:axion/definitions/x.json").bytes(json("{}")).toByteArray();
        byte[] horsFamille = new WireWriter().varUInt(1).u8(1)
                .utf8("axion:axion/definitions/x.json").bytes(json("{}")).toByteArray();
        byte[] doublon = new WireWriter().varUInt(2)
                .u8(3).utf8("axion:axion/definitions/x.json").bytes(json("{}"))
                .u8(3).utf8("axion:axion/definitions/x.json").bytes(json("{}")).toByteArray();
        // Des entrées réelles et minuscules : refusées pour leur nombre, pas pour une troncature.
        WireWriter nombreuses = new WireWriter().varUInt(RegistryContent.MAX_ENTRIES + 1);
        for (int index = 0; index <= RegistryContent.MAX_ENTRIES; index++) {
            nombreuses.u8(3).utf8("axion:axion/definitions/d" + index + ".json").bytes(json("{}"));
        }
        byte[] tropDEntrees = nombreuses.toByteArray();
        byte[] tropGros = new WireWriter().varUInt(1).u8(3).utf8("axion:axion/definitions/x.json")
                .bytes(new byte[RegistryContent.MAX_FILE_BYTES + 1]).toByteArray();
        for (byte[] plain : new byte[][] {familleInconnue, horsFamille, doublon, tropDEntrees, tropGros}) {
            assertThrows(MalformedPacketException.class, () -> RegistryContent.decode(plain));
        }

        // La décompression reçoit la borne du registre décompressé (R-1691) : c'est elle qui refuse
        // une trame qui annonce davantage, avant d'allouer.
        int[] borneRecue = {-1};
        NetCompression releve = new NetCompression() {
            @Override
            public byte[] compress(byte[] payload) {
                return payload.clone();
            }

            @Override
            public byte[] decompress(byte[] frame, int maxBytes) {
                borneRecue[0] = maxBytes;
                return frame.clone();
            }
        };
        try {
            RegistryMessage.decode(RegistryMessage.encode(registre(), releve), releve);
        } catch (MalformedPacketException error) {
            throw new AssertionError(error);
        }
        assertEquals(RegistryMessage.MAX_CONTENT_BYTES, borneRecue[0]);
        // Un autre genre de message.
        byte[] autreGenre = new ClientHandshake(NetProtocol.PROTOCOL_VERSION,
                new DetProfile(0L, 1, false)).encode();
        assertThrows(MalformedPacketException.class, () -> RegistryMessage.decode(autreGenre, IDENTITE));
    }

    @Test
    @DisplayName("R-1691 : un registre que le client refuserait pour sa taille n'est pas encodé")
    void unRegistreTropGrosNEstPasEncode() {
        // Décompressé au-delà de la borne du client : un fichier de 1 Mio de plus que ce qu'elle tient.
        Map<String, byte[]> gros = new LinkedHashMap<>();
        for (int index = 0; index <= RegistryMessage.MAX_CONTENT_BYTES / RegistryContent.MAX_FILE_BYTES; index++) {
            gros.put("axion:axion/definitions/g" + index + ".json", new byte[RegistryContent.MAX_FILE_BYTES]);
        }
        RegistryContent tropGros = RegistryContent.of(Map.of(), Map.of(), gros);
        IllegalArgumentException taille = assertThrows(IllegalArgumentException.class,
                () -> RegistryMessage.encode(tropGros, IDENTITE));
        assertTrue(taille.getMessage().contains("au-delà de " + RegistryMessage.MAX_CONTENT_BYTES), taille::getMessage);

        // Sous cette borne, mais plus long que ce que la fragmentation porte.
        Map<String, byte[]> moyen = new LinkedHashMap<>();
        for (int index = 0; (long) index * RegistryContent.MAX_FILE_BYTES <= Fragments.MAX_MESSAGE_BYTES; index++) {
            moyen.put("axion:axion/definitions/m" + index + ".json", new byte[RegistryContent.MAX_FILE_BYTES]);
        }
        RegistryContent tropLong = RegistryContent.of(Map.of(), Map.of(), moyen);
        IllegalArgumentException longueur = assertThrows(IllegalArgumentException.class,
                () -> RegistryMessage.encode(tropLong, IDENTITE));
        assertTrue(longueur.getMessage().contains("au-delà de " + Fragments.MAX_MESSAGE_BYTES), longueur::getMessage);
    }
}
