package dev.axion.forge.net;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.axion.net.NetProtocol;
import net.minecraftforge.network.NetworkRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-419 — R-1630 : un client sans AXION est refusé, avec un message de Forge.
 *
 * <p>Forge présente au prédicat du canal la version annoncée par l'autre extrémité, ou ses propres
 * marqueurs quand le canal manque ({@link NetworkRegistry#ABSENT}) ou que le client est vanilla
 * ({@link NetworkRegistry#ACCEPTVANILLA}). Ces marqueurs sont lus ici chez Forge, pas recopiés : s'ils
 * changeaient, le test suivrait.
 */
class AxionChannelTest {

    @Test
    @DisplayName("T-419 : seul un pair AXION-4 est accepté ; canal absent et client vanilla sont refusés")
    void t419_unClientSansAxionEstRefuse() {
        assertTrue(NetProtocol.accepts(NetProtocol.VERSION));
        assertTrue(NetProtocol.accepts("AXION-8"), "ADR-167 D8 : les modificateurs des paramètres montent le protocole");
        assertFalse(NetProtocol.accepts("AXION-7"), "protocole des addons exigés, antérieur aux paramètres");
        assertFalse(NetProtocol.accepts("AXION-6"), "protocole du véhicule, antérieur aux addons exigés");
        assertFalse(NetProtocol.accepts("AXION-5"), "protocole des roues, antérieur au véhicule");
        assertFalse(NetProtocol.accepts(NetworkRegistry.ABSENT.version()), "canal absent");
        assertFalse(NetProtocol.accepts(NetworkRegistry.ACCEPTVANILLA), "client vanilla");
        assertFalse(NetProtocol.accepts("AXION-1"), "version de protocole antérieure");
        assertFalse(NetProtocol.accepts("AXION-2"), "autre version de protocole");
        assertFalse(NetProtocol.accepts("axion-2"), "casse différente");
        assertFalse(NetProtocol.accepts(""));
        assertFalse(NetProtocol.accepts(null));
    }
}
