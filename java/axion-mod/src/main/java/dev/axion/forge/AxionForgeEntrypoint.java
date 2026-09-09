package dev.axion.forge;

import com.mojang.logging.LogUtils;
import dev.axion.AxionMod;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

/**
 * Point d'ancrage unique entre Forge et AXION (C-01).
 *
 * <p>Ce paquet est le seul autorisé à importer {@code net.minecraftforge.*}
 * (R-401, T-020) : tout le reste du moteur reste indépendant de la plateforme,
 * conformément au principe d'isolation P-12.
 *
 * <p>À ce stade, la classe n'assure que la construction du mod. La machine à
 * états {@code UNLOADED -> CONSTRUCTED -> SETUP -> LOAD_COMPLETE -> RUNNING},
 * l'abonnement aux événements Forge et la séquence de démarrage C-02
 * (configuration, chargement du natif, handshake ABI, mode DISABLED sûr) sont
 * l'objet du jalon M0.
 */
@Mod(AxionMod.MODID)
public final class AxionForgeEntrypoint {

    private static final Logger LOGGER = LogUtils.getLogger();

    public AxionForgeEntrypoint() {
        LOGGER.info("AXION ENGINE : point d'entrée Forge construit (modid={})", AxionMod.MODID);
    }
}
