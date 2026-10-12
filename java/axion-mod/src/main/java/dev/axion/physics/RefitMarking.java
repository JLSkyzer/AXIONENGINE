package dev.axion.physics;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Marquage {@code REFITTABLE} des colliders (R-623, ADR-130 : posé par Java) — logique pure, sans
 * Forge.
 *
 * <p>Tout collider d'un corps dynamique est marqué {@code REFITTABLE} si sa part porte au moins
 * une région de déformation ; {@code NO_REFIT}, posé par l'auteur, l'emporte. Une part se définit
 * par son {@code root_node} : ses colliders sont ceux que porte ce node ou un de ses descendants.
 * Le lien se fait par la section {@code NODE} de l'asset — node porteur d'un collider, chaîne de
 * ses parents, noms — et non par {@code ColliderDesc.part}, que le compilateur ne remplit pas
 * (toujours {@code 0xFFFF}, relevé le 2026-10-12). Les enfants d'un {@code Compound} suivent leur
 * composé. Un collider qu'aucun node ne porte — celui que la definition génère pour tout l'asset —
 * n'appartient à aucune part, et n'est pas marqué.
 *
 * <p>Dispositions lues, figées côté natif (ADR-110, ADR-115) :
 * <pre>
 * PHYS : u32 count | ColliderDesc[count] (104 o) | u32 points | [f32;3][points]
 *        | u32 children | ColliderDesc[children] (104 o)
 * NODE : u32 count | u32 names_size | NodeDesc[count] (80 o) | NameRef[count] (u32 offset, u32 len)
 *        | noms UTF-8
 * </pre>
 * Une section incohérente laisse PHYS inchangée : le natif la juge à la réception.
 */
public final class RefitMarking {

    /** {@code collider_flags::REFITTABLE}. */
    public static final int REFITTABLE = 1 << 3;

    /** {@code collider_flags::NO_REFIT}. */
    public static final int NO_REFIT = 1 << 4;

    private static final int COLLIDER_BYTES = 104;
    private static final int FLAGS_AT = 80;
    private static final int COMPOUND_TAG = 8;
    private static final int NODE_BYTES = 80;
    private static final int PARENT_AT = 8;
    private static final int COLLIDER_AT = 60;
    private static final int NONE = -1;

    private RefitMarking() {}

    /**
     * {@return les noms des {@code root_node} des parts qui portent au moins une région de
     * déformation}
     *
     * @param definition racine JSON de la definition
     */
    public static Set<String> rootsOf(JsonObject definition) {
        Set<String> roots = new HashSet<>();
        JsonElement parts = definition.get("parts");
        if (parts == null || !parts.isJsonArray()) {
            return roots;
        }
        for (JsonElement element : parts.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject part = element.getAsJsonObject();
            JsonElement regions = part.get("deform_regions");
            JsonElement root = part.get("root_node");
            if (regions != null && regions.isJsonArray() && !regions.getAsJsonArray().isEmpty()
                    && root != null && root.isJsonPrimitive()) {
                roots.add(root.getAsString());
            }
        }
        return roots;
    }

    /**
     * {@return une copie de la section PHYS, {@code REFITTABLE} posé sur les colliders des parts
     * déformables} Inchangée sans part déformable, ou si une section est incohérente.
     *
     * @param phys section {@code PHYS} de l'asset
     * @param nodes section {@code NODE} de l'asset, ou {@code null}
     * @param roots noms des {@code root_node} des parts déformables ({@link #rootsOf})
     */
    public static byte[] mark(byte[] phys, byte[] nodes, Set<String> roots) {
        byte[] marked = Arrays.copyOf(phys, phys.length);
        if (roots.isEmpty() || nodes == null) {
            return marked;
        }
        try {
            ByteBuffer p = ByteBuffer.wrap(marked).order(ByteOrder.LITTLE_ENDIAN);
            ByteBuffer n = ByteBuffer.wrap(nodes).order(ByteOrder.LITTLE_ENDIAN);
            int colliders = p.getInt(0);
            int pointsAt = 4 + colliders * COLLIDER_BYTES;
            int childrenAt = pointsAt + 4 + p.getInt(pointsAt) * 12 + 4;
            int nodeCount = n.getInt(0);
            int refsAt = 8 + nodeCount * NODE_BYTES;
            int namesAt = refsAt + nodeCount * 8;
            if (colliders < 0 || nodeCount < 0 || childrenAt > marked.length || namesAt > nodes.length) {
                return Arrays.copyOf(phys, phys.length);
            }
            for (int node = 0; node < nodeCount; node++) {
                int collider = n.getInt(8 + node * NODE_BYTES + COLLIDER_AT);
                if (collider == NONE || collider < 0 || collider >= colliders) {
                    continue;
                }
                if (inDeformablePart(n, node, nodeCount, refsAt, namesAt, roots)) {
                    int at = 4 + collider * COLLIDER_BYTES;
                    markOne(p, at);
                    if (p.getInt(at) == COMPOUND_TAG) {
                        int first = p.getInt(at + 4);
                        int count = p.getInt(at + 8);
                        for (int child = first; child < first + count; child++) {
                            markOne(p, childrenAt + child * COLLIDER_BYTES);
                        }
                    }
                }
            }
            return marked;
        } catch (IndexOutOfBoundsException incoherent) {
            return Arrays.copyOf(phys, phys.length);
        }
    }

    /** Vrai si le node, ou l'un de ses parents, est la racine d'une part déformable. */
    private static boolean inDeformablePart(
            ByteBuffer nodes, int node, int nodeCount, int refsAt, int namesAt, Set<String> roots) {
        int current = node;
        // Une chaîne de parents plus longue que le nombre de nodes est un cycle : arrêt.
        for (int step = 0; step < nodeCount && current != NONE; step++) {
            if (current < 0 || current >= nodeCount) {
                return false;
            }
            int offset = nodes.getInt(refsAt + current * 8);
            int length = nodes.getInt(refsAt + current * 8 + 4);
            String name = new String(nodes.array(), namesAt + offset, length, StandardCharsets.UTF_8);
            if (roots.contains(name)) {
                return true;
            }
            current = nodes.getInt(8 + current * NODE_BYTES + PARENT_AT);
        }
        return false;
    }

    /** Pose {@code REFITTABLE} sur un collider, sauf s'il est {@code NO_REFIT}. */
    private static void markOne(ByteBuffer phys, int at) {
        int flags = phys.getInt(at + FLAGS_AT);
        if ((flags & NO_REFIT) == 0) {
            phys.putInt(at + FLAGS_AT, flags | REFITTABLE);
        }
    }
}
