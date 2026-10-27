package dev.axion.forge;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Le jeton du monde pour son journal latéral ({@code data/axion_journal.dat}, ADR-153 D6) : une UUID tirée
 * au premier démarrage avec AXION. Vanilla garde les fichiers de {@code data/} qu'il ne connaît pas : le
 * jeton survit à un monde joué sans AXION.
 */
final class JournalWorldToken extends SavedData {

    /** Nom du fichier dans {@code data/}. */
    static final String NAME = "axion_journal";

    private static final String KEY = "token";

    private final String token;

    private JournalWorldToken(String token) {
        this.token = token;
    }

    /** {@return le jeton du monde, tiré et marqué à sauvegarder au premier appel} */
    static String of(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                tag -> new JournalWorldToken(tag.getString(KEY)), JournalWorldToken::fresh, NAME).token;
    }

    /** {@return un jeton neuf, marqué à sauvegarder avec le monde} */
    private static JournalWorldToken fresh() {
        AuthoritativeThread.require();
        JournalWorldToken fresh = new JournalWorldToken(UUID.randomUUID().toString());
        fresh.setDirty();
        return fresh;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putString(KEY, token);
        return tag;
    }
}
