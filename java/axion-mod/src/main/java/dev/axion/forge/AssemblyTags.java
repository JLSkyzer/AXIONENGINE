package dev.axion.forge;

import dev.axion.entity.AssemblyBinding;
import dev.axion.net.SnapshotMessage;
import java.util.HexFormat;
import java.util.OptionalInt;
import java.util.OptionalLong;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;

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

    /** Orientation du corps : quaternion {@code (x, y, z, w)}, quatre flottants (R-461). */
    static final String ROTATION = "axion:rot";

    /** En deçà, un quaternion n'a pas de direction : il ne décrit aucune rotation. */
    private static final float MIN_QUATERNION_NORM_SQUARED = 1e-6f;

    /** Longueur maximale d'un identifiant transmis, celle des chaînes réseau de Minecraft. */
    static final int MAX_ID_CHARS = 32767;

    /**
     * Ce que le client reçoit à l'apparition : {@code S2C_AssemblySpawn} (ADR-150 D2), la position mise à
     * part, que Minecraft porte.
     *
     * @param definitionId identifiant de la definition, ou description d'une
     *     definition inconnue
     * @param inert l'entité est inerte
     * @param rotation orientation du corps
     * @param boundsMin coin minimal de l'emprise, relatif à la position, ou {@code null} si aucune
     * @param boundsMax coin maximal de l'emprise, relatif à la position, ou {@code null} si aucune
     */
    record SpawnState(String definitionId, boolean inert, Quaternionf rotation, Vector3f boundsMin, Vector3f boundsMax) {}

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

    /**
     * {@return l'orientation gardée, normalisée, ou l'identité}
     *
     * <p>Un NBT sans orientation — entité d'avant cette clé, ou créée par commande — décrit
     * un corps droit. Une orientation inexploitable (mauvais type, composante non finie,
     * quaternion nul) aussi : un corps recréé avec une rotation absurde serait pire qu'un
     * corps redressé.
     */
    static Quaternionf rotation(CompoundTag stored) {
        ListTag list = stored.getList(ROTATION, Tag.TAG_FLOAT);
        if (list.size() == 4) {
            Quaternionf rotation =
                    new Quaternionf(list.getFloat(0), list.getFloat(1), list.getFloat(2), list.getFloat(3));
            float norm = rotation.lengthSquared();
            if (Float.isFinite(norm) && norm > MIN_QUATERNION_NORM_SQUARED) {
                return rotation.normalize();
            }
        }
        return new Quaternionf();
    }

    /** Pose l'orientation dans les clés gardées (§22.2). */
    static void putRotation(CompoundTag stored, Quaternionfc rotation) {
        ListTag list = new ListTag();
        list.add(FloatTag.valueOf(rotation.x()));
        list.add(FloatTag.valueOf(rotation.y()));
        list.add(FloatTag.valueOf(rotation.z()));
        list.add(FloatTag.valueOf(rotation.w()));
        stored.put(ROTATION, list);
    }

    /** {@return ce qu'on peut dire d'une definition non résolue} */
    static String describe(CompoundTag stored) {
        OptionalLong hash = definition(stored);
        return hash.isPresent()
                ? "definition " + HexFormat.of().toHexDigits(hash.getAsLong())
                : "sans definition";
    }

    /**
     * Écrit les données d'apparition : definition, état inerte, version du descripteur d'états
     * procéduraux (vide en version 1, ADR-150 D6), orientation, puis l'emprise si elle existe.
     */
    static void writeSpawn(FriendlyByteBuf buffer, SpawnState state) {
        buffer.writeUtf(state.definitionId(), MAX_ID_CHARS);
        buffer.writeBoolean(state.inert());
        buffer.writeByte(SnapshotMessage.DESCRIPTOR_VERSION);
        Quaternionf q = state.rotation();
        buffer.writeFloat(q.x).writeFloat(q.y).writeFloat(q.z).writeFloat(q.w);
        boolean bounded = state.boundsMin() != null && state.boundsMax() != null;
        buffer.writeBoolean(bounded);
        if (bounded) {
            for (Vector3f corner : new Vector3f[] {state.boundsMin(), state.boundsMax()}) {
                buffer.writeFloat(corner.x).writeFloat(corner.y).writeFloat(corner.z);
            }
        }
    }

    /**
     * Lit les données d'apparition, bornées comme toute chaîne réseau. Une orientation non finie ou nulle
     * donne l'identité ; une emprise non finie ou retournée, aucune emprise — la boîte provisoire.
     */
    static SpawnState readSpawn(FriendlyByteBuf buffer) {
        String definitionId = buffer.readUtf(MAX_ID_CHARS);
        boolean inert = buffer.readBoolean();
        buffer.readUnsignedByte(); // descripteur : vide en version 1, rien à lire
        Quaternionf rotation = new Quaternionf(buffer.readFloat(), buffer.readFloat(), buffer.readFloat(),
                buffer.readFloat());
        float norm = rotation.lengthSquared();
        if (Float.isFinite(norm) && norm > MIN_QUATERNION_NORM_SQUARED) {
            rotation.normalize();
        } else {
            rotation.identity();
        }
        Vector3f min = null;
        Vector3f max = null;
        if (buffer.readBoolean()) {
            min = new Vector3f(buffer.readFloat(), buffer.readFloat(), buffer.readFloat());
            max = new Vector3f(buffer.readFloat(), buffer.readFloat(), buffer.readFloat());
            if (!min.isFinite() || !max.isFinite() || min.x > max.x || min.y > max.y || min.z > max.z) {
                min = null;
                max = null;
            }
        }
        return new SpawnState(definitionId, inert, rotation, min, max);
    }
}
