package dev.axion.physics;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * Index de matériau des colliders d'une assembly (ADR-149 D2) — logique pure, sans Forge.
 *
 * <p>Chaque collider prend le matériau de sa part : celle dont le {@code root_node} est le node
 * qui porte le collider, ou son plus proche ancêtre qui soit la racine d'une part. Les enfants
 * d'un {@code Compound} suivent leur composé. Sans part, ou d'une part qui ne nomme pas de
 * matériau, il prend l'acier générique (index 0, R-1032) — le compilateur écrit {@code 0xFFFF}
 * partout. Le lien collider → part passe par la section {@code NODE}, comme pour
 * {@link RefitMarking} : {@code ColliderDesc.part} n'est pas rempli.
 *
 * <p>Une section {@code PHYS} incohérente est rendue inchangée : le natif la juge à la réception.
 * Une section {@code NODE} absente ou incohérente laisse tous les colliders à l'acier générique.
 */
public final class MaterialMarking {

    /** Index de l'acier générique dans la table de la session. */
    public static final int GENERIC_STEEL = 0;

    private static final int COLLIDER_BYTES = 104;
    private static final int MATERIAL_AT = 68;
    private static final int COMPOUND_TAG = 8;
    private static final int NODE_BYTES = 80;
    private static final int PARENT_AT = 8;
    private static final int COLLIDER_AT = 60;
    private static final int NONE = -1;

    private MaterialMarking() {}

    /**
     * {@return le matériau que nomme chaque part, par le nom de son {@code root_node}}
     *
     * @param definition racine JSON de la definition
     */
    public static Map<String, String> rootsOf(JsonObject definition) {
        Map<String, String> roots = new HashMap<>();
        JsonElement parts = definition.get("parts");
        if (parts == null || !parts.isJsonArray()) {
            return roots;
        }
        for (JsonElement element : parts.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject part = element.getAsJsonObject();
            JsonElement root = part.get("root_node");
            JsonElement material = part.get("material");
            if (root != null && root.isJsonPrimitive() && material != null && material.isJsonPrimitive()) {
                roots.put(root.getAsString(), material.getAsString());
            }
        }
        return roots;
    }

    /**
     * {@return une copie de la section PHYS, chaque collider à l'index du matériau de sa part}
     *
     * @param phys section {@code PHYS} de l'asset
     * @param nodes section {@code NODE} de l'asset, ou {@code null}
     * @param materials matériau de chaque part, par le nom de son {@code root_node} ({@link #rootsOf})
     * @param index index d'un matériau dans la table de la session
     */
    public static byte[] mark(
            byte[] phys, byte[] nodes, Map<String, String> materials, ToIntFunction<String> index) {
        byte[] marked = Arrays.copyOf(phys, phys.length);
        ByteBuffer p = ByteBuffer.wrap(marked).order(ByteOrder.LITTLE_ENDIAN);
        int colliders;
        int childrenAt;
        try {
            colliders = p.getInt(0);
            int pointsAt = 4 + colliders * COLLIDER_BYTES;
            childrenAt = pointsAt + 4 + p.getInt(pointsAt) * 12 + 4;
            int children = p.getInt(childrenAt - 4);
            if (colliders < 0 || children < 0 || childrenAt + children * COLLIDER_BYTES > marked.length) {
                return Arrays.copyOf(phys, phys.length);
            }
            for (int collider = 0; collider < colliders; collider++) {
                setMaterial(p, 4 + collider * COLLIDER_BYTES, GENERIC_STEEL);
            }
            for (int child = 0; child < children; child++) {
                setMaterial(p, childrenAt + child * COLLIDER_BYTES, GENERIC_STEEL);
            }
        } catch (IndexOutOfBoundsException incoherent) {
            return Arrays.copyOf(phys, phys.length);
        }
        if (materials.isEmpty() || nodes == null) {
            return marked;
        }
        byte[] generic = Arrays.copyOf(marked, marked.length);
        try {
            ByteBuffer n = ByteBuffer.wrap(nodes).order(ByteOrder.LITTLE_ENDIAN);
            int nodeCount = n.getInt(0);
            int refsAt = 8 + nodeCount * NODE_BYTES;
            int namesAt = refsAt + nodeCount * 8;
            if (nodeCount < 0 || namesAt > nodes.length) {
                return generic;
            }
            for (int node = 0; node < nodeCount; node++) {
                int collider = n.getInt(8 + node * NODE_BYTES + COLLIDER_AT);
                if (collider == NONE || collider < 0 || collider >= colliders) {
                    continue;
                }
                String material = partMaterial(n, node, nodeCount, refsAt, namesAt, materials);
                if (material == null) {
                    continue;
                }
                int value = index.applyAsInt(material);
                int at = 4 + collider * COLLIDER_BYTES;
                setMaterial(p, at, value);
                if (p.getInt(at) == COMPOUND_TAG) {
                    int first = p.getInt(at + 4);
                    int count = p.getInt(at + 8);
                    for (int child = first; child < first + count; child++) {
                        setMaterial(p, childrenAt + child * COLLIDER_BYTES, value);
                    }
                }
            }
            return marked;
        } catch (IndexOutOfBoundsException incoherent) {
            return generic;
        }
    }

    /** Le matériau de la part la plus proche qui contient le node, ou {@code null}. */
    private static String partMaterial(
            ByteBuffer nodes, int node, int nodeCount, int refsAt, int namesAt, Map<String, String> materials) {
        int current = node;
        // Une chaîne de parents plus longue que le nombre de nodes est un cycle : arrêt.
        for (int step = 0; step < nodeCount && current != NONE; step++) {
            if (current < 0 || current >= nodeCount) {
                return null;
            }
            int offset = nodes.getInt(refsAt + current * 8);
            int length = nodes.getInt(refsAt + current * 8 + 4);
            String name = new String(nodes.array(), namesAt + offset, length, StandardCharsets.UTF_8);
            String material = materials.get(name);
            if (material != null) {
                return material;
            }
            current = nodes.getInt(8 + current * NODE_BYTES + PARENT_AT);
        }
        return null;
    }

    private static void setMaterial(ByteBuffer phys, int colliderAt, int index) {
        phys.putShort(colliderAt + MATERIAL_AT, (short) index);
    }
}
