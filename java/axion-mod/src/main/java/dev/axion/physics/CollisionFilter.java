package dev.axion.physics;

import java.util.List;

/**
 * Groupes de collision d'une assembly (§10.4, R-980) — logique pure, sans Forge.
 *
 * <p>Le bloc {@code physics} d'une definition désigne ses groupes par nom :
 * {@code collision_group}, celui dont le corps est membre, et {@code collides_with}, ceux qu'il
 * heurte. Java les résout en bits — les huit réservés ici, les groupes tiers par leur registre
 * ({@code dev.axion.definition.CollisionGroupRegistry}, ADR-132 A1) — et les écrit dans le
 * {@code DEFN} de la definition ; la compilation native les pose sur chacun de ses colliders
 * (ADR-156 D3). Deux corps interagissent si chacun est membre d'un groupe que l'autre heurte.
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

    private CollisionFilter() {}

    /** {@return le bit d'un groupe réservé} */
    public static int bit(String group) {
        int index = RESERVED.indexOf(group);
        if (index < 0) {
            throw new IllegalArgumentException("groupe de collision inconnu : " + group);
        }
        return 1 << index;
    }
}
