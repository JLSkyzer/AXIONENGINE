package dev.axion.physics;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;

/**
 * Groupes de collision d'une assembly (§10.4, R-980) — logique pure, sans Forge.
 *
 * <p>Le bloc {@code physics} d'une definition désigne ses groupes par nom :
 * {@code collision_group}, celui dont le corps est membre, et {@code collides_with}, ceux qu'il
 * heurte. Java les résout en bits — les huit réservés ici, les groupes tiers par leur registre
 * ({@code dev.axion.definition.CollisionGroupRegistry}, ADR-132 A1) — et les écrit sur chaque
 * {@code ColliderDesc} de la section {@code PHYS} ({@code group}, {@code mask}), que le natif
 * applique au corps. Deux corps interagissent si chacun est membre d'un groupe que l'autre heurte.
 *
 * <p>Disposition écrite, figée côté natif (ADR-115) :
 * <pre>
 * PHYS : u32 count | ColliderDesc[count] (104 o) | u32 points | [f32;3][points]
 *        | u32 children | ColliderDesc[children] (104 o)
 * ColliderDesc : group u32 à l'octet 72, mask u32 à l'octet 76
 * </pre>
 * Une section incohérente est rendue inchangée : le natif la juge à la réception.
 */
public final class CollisionFilter {

    /**
     * Les groupes réservés, dans l'ordre de leurs bits (R-980) — celui de {@code ReservedGroup}
     * côté natif, que {@code CollisionFilterTest} confronte à la source Rust.
     */
    public static final List<String> RESERVED = List.of(
            "world", "assembly", "part", "debris", "entity_proxy", "sensor", "particle", "debug");

    /** Masque qui retient tous les groupes. */
    public static final int ALL = -1;

    private static final int COLLIDER_BYTES = 104;
    private static final int GROUP_AT = 72;
    private static final int MASK_AT = 76;

    private CollisionFilter() {}

    /** {@return le bit d'un groupe réservé} */
    public static int bit(String group) {
        int index = RESERVED.indexOf(group);
        if (index < 0) {
            throw new IllegalArgumentException("groupe de collision inconnu : " + group);
        }
        return 1 << index;
    }

    /**
     * {@return une copie de la section PHYS, chaque collider — enfants de composés compris — sous
     * ces groupes}
     *
     * @param phys section {@code PHYS} de l'asset
     * @param membership groupes dont le corps est membre, en masque
     * @param mask groupes qu'il heurte
     */
    public static byte[] apply(byte[] phys, int membership, int mask) {
        byte[] filtered = Arrays.copyOf(phys, phys.length);
        try {
            ByteBuffer p = ByteBuffer.wrap(filtered).order(ByteOrder.LITTLE_ENDIAN);
            int colliders = p.getInt(0);
            int pointsAt = 4 + colliders * COLLIDER_BYTES;
            int childrenCountAt = pointsAt + 4 + p.getInt(pointsAt) * 12;
            int children = childrenCountAt + 4 <= filtered.length ? p.getInt(childrenCountAt) : 0;
            int childrenAt = childrenCountAt + 4;
            if (colliders < 0 || children < 0 || childrenAt + children * COLLIDER_BYTES > filtered.length) {
                return Arrays.copyOf(phys, phys.length);
            }
            for (int i = 0; i < colliders; i++) {
                write(p, 4 + i * COLLIDER_BYTES, membership, mask);
            }
            for (int i = 0; i < children; i++) {
                write(p, childrenAt + i * COLLIDER_BYTES, membership, mask);
            }
            return filtered;
        } catch (IndexOutOfBoundsException incoherent) {
            return Arrays.copyOf(phys, phys.length);
        }
    }

    private static void write(ByteBuffer phys, int at, int membership, int mask) {
        phys.putInt(at + GROUP_AT, membership);
        phys.putInt(at + MASK_AT, mask);
    }
}
