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
        assertTrue(NetProtocol.accepts("AXION-17"), "ADR-182 : les réserves d'énergie montent le protocole");
        assertFalse(NetProtocol.accepts("AXION-16"), "protocole du mouillé, antérieur aux réserves");
        assertFalse(NetProtocol.accepts("AXION-15"), "protocole de l'usure des pneus, antérieur au mouillé");
        assertFalse(NetProtocol.accepts("AXION-14"), "protocole de l'énergie du véhicule, antérieur à l'usure des pneus");
        assertFalse(NetProtocol.accepts("AXION-13"), "protocole des événements de contenu, antérieur à l'énergie");
        assertFalse(NetProtocol.accepts("AXION-12"), "protocole des réglages des attaches, antérieur aux événements");
        assertFalse(NetProtocol.accepts("AXION-11"), "protocole des attaches, antérieur à leurs réglages");
        assertFalse(NetProtocol.accepts("AXION-10"), "protocole de la cadence, antérieur aux attaches");
        assertFalse(NetProtocol.accepts("AXION-9"), "protocole des correspondances, antérieur à la cadence");
        assertFalse(NetProtocol.accepts("AXION-8"), "protocole des paramètres, antérieur aux correspondances");
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
