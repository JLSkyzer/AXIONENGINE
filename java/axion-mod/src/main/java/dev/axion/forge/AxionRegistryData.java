package dev.axion.forge;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * L'unique {@code SavedData} d'AXION, {@code axion_registry} (R-1711). Il porte le jeton du journal latéral
 * du monde (ADR-153 D6) : une UUID tirée au premier démarrage avec AXION ; les correspondances d'attaches
 * inter-assemblies que R-1711 y place viendront avec C-48. Vanilla garde les fichiers de
 * {@code data/} qu'il ne connaît pas : le jeton survit à un monde joué sans AXION. Sans ce fichier, un
 * jeton neuf est tiré, et le journal existant est mis de côté, intact.
 */
final class AxionRegistryData extends SavedData {

    /** L'unique {@code SavedData} d'AXION (R-1711), {@code data/axion_registry.dat}. */
    static final String NAME = "axion_registry";

    private static final String KEY = "journal_token";

    private final String token;

    private AxionRegistryData(String token) {
        this.token = token;
    }

    /** {@return le jeton du journal latéral du monde, tiré et marqué à sauvegarder au premier appel} */
    static String journalToken(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                tag -> new AxionRegistryData(tag.getString(KEY)), AxionRegistryData::fresh, NAME).token;
    }

    /** {@return un jeton neuf, marqué à sauvegarder avec le monde} */
    private static AxionRegistryData fresh() {
        AuthoritativeThread.require();
        AxionRegistryData fresh = new AxionRegistryData(UUID.randomUUID().toString());
        fresh.setDirty();
        return fresh;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putString(KEY, token);
        return tag;
    }
}
