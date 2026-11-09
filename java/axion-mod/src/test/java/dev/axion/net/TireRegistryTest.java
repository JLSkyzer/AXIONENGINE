package dev.axion.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-159 Q1 : les pneus partent avec le registre, et le client en tire ce que le serveur en a tiré. */
class TireRegistryTest {

    private static final byte[] PNEU = """
            {"schema": 1, "mu_long": 0.2, "mu_lat": 0.15,
             "long": {"peak_slip": 0.1, "peak_value": 1.0, "tail_slip": 0.4, "tail_value": 0.5},
             "lat": {"peak_slip": 0.2, "peak_value": 0.9, "tail_slip": 0.6, "tail_value": 0.0}}
            """.getBytes(StandardCharsets.UTF_8);

    private static RegistryContent avec(byte[] pneu) {
        return RegistryContent.builder().add(RegistryContent.Family.TIRES, "m:axion/tires/glace.json", pneu).build();
    }

    @Test
    @DisplayName("ADR-159 Q1 : le pneu se relit à l'identique après le réseau, et le client le connaît")
    void allerRetour() throws MalformedPacketException {
        RegistryContent recu = RegistryContent.decode(avec(PNEU).encode());
        assertEquals(1, recu.files(RegistryContent.Family.TIRES).size());
        assertTrue(RemoteRegistry.load(recu).definitions().tires().knows("m:tire/glace"));
    }

    @Test
    @DisplayName("21.3 : changer un pneu change l'empreinte des definitions, qu'il paramètre")
    void empreinte() {
        assertEquals(avec(PNEU).definitionsHash(), avec(PNEU.clone()).definitionsHash());
        assertNotEquals(avec(PNEU).definitionsHash(), avec(new byte[] {'{', '}'}).definitionsHash());
        assertNotEquals(avec(PNEU).definitionsHash(), RegistryContent.builder().build().definitionsHash());
    }

    @Test
    @DisplayName("ADR-159 Q1 : un registre construit sans pneu se code et se lit quand même")
    void sansPneu() throws MalformedPacketException {
        RegistryContent vide = RegistryContent.of(java.util.Map.of(), java.util.Map.of(), java.util.Map.of());
        assertEquals(0, RegistryContent.decode(vide.encode()).size());
    }
}
