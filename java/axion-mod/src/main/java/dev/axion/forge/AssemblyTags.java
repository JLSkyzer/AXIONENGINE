package dev.axion.forge;

import dev.axion.entity.AssemblyBinding;
import java.util.HexFormat;
import java.util.OptionalInt;
import java.util.OptionalLong;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;

/**
 * NBT et données d'apparition d'une AxionEntity (PARTIE 22.2, R-703).
 *
 * <p>Toutes les clés d'AXION commencent par {@code axion:}. L'entité en garde
 * une copie et la réécrit <strong>telle quelle</strong> : en M2, seules
 * {@code axion:v} et {@code axion:def} ont un composant qui les écrit, mais une
 * clé posée par une version ultérieure ne doit pas disparaître parce que cette
 * version-ci ne la comprend pas (R-1710).
 */
final class AssemblyTags {

    /** Préfixe de toute clé NBT d'AXION. */
    static final String PREFIX = "axion:";

    /** Version du schéma NBT. */
    static final String VERSION = "axion:v";

    /** Identifiant 64 bits de la definition. */
    static final String DEFINITION = "axion:def";

    /** Longueur maximale d'un identifiant transmis, celle des chaînes réseau de Minecraft. */
    static final int MAX_ID_CHARS = 32767;

    /**
     * Ce que le client reçoit à l'apparition.
     *
     * @param definitionId identifiant de la definition, ou description d'une
     *     definition inconnue
     * @param inert l'entité est inerte
     */
    record SpawnState(String definitionId, boolean inert) {}

    private AssemblyTags() {}

    /** {@return une copie des clés {@code axion:*} d'un NBT d'entité} */
    static CompoundTag extract(CompoundTag entity) {
        CompoundTag stored = new CompoundTag();
        for (String key : entity.getAllKeys()) {
            if (key.startsWith(PREFIX)) {
                Tag value = entity.get(key);
                if (value != null) {
                    stored.put(key, value.copy());
                }
            }
        }
        return stored;
    }

    /** Réécrit les clés gardées dans un NBT d'entité. */
    static void restore(CompoundTag stored, CompoundTag entity) {
        for (String key : stored.getAllKeys()) {
            Tag value = stored.get(key);
            if (value != null) {
                entity.put(key, value.copy());
            }
        }
    }

    /** {@return la version de schéma, si le NBT en porte une entière} */
    static OptionalInt version(CompoundTag stored) {
        return stored.contains(VERSION, Tag.TAG_INT)
                ? OptionalInt.of(stored.getInt(VERSION))
                : OptionalInt.empty();
    }

    /** {@return l'identifiant de definition, si le NBT en porte un} */
    static OptionalLong definition(CompoundTag stored) {
        return stored.contains(DEFINITION, Tag.TAG_LONG)
                ? OptionalLong.of(stored.getLong(DEFINITION))
                : OptionalLong.empty();
    }

    /** {@return les clés gardées, liées à une definition au schéma courant} */
    static CompoundTag bound(CompoundTag stored, long definitionHash) {
        CompoundTag out = stored.copy();
        out.putInt(VERSION, AssemblyBinding.NBT_SCHEMA);
        out.putLong(DEFINITION, definitionHash);
        return out;
    }

    /** {@return ce qu'on peut dire d'une definition non résolue} */
    static String describe(CompoundTag stored) {
        OptionalLong hash = definition(stored);
        return hash.isPresent()
                ? "definition " + HexFormat.of().toHexDigits(hash.getAsLong())
                : "sans definition";
    }

    /** Écrit les données d'apparition. */
    static void writeSpawn(FriendlyByteBuf buffer, SpawnState state) {
        buffer.writeUtf(state.definitionId(), MAX_ID_CHARS);
        buffer.writeBoolean(state.inert());
    }

    /** Lit les données d'apparition, bornées comme toute chaîne réseau. */
    static SpawnState readSpawn(FriendlyByteBuf buffer) {
        return new SpawnState(buffer.readUtf(MAX_ID_CHARS), buffer.readBoolean());
    }
}
