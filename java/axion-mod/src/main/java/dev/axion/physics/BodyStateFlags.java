package dev.axion.physics;

/**
 * Drapeaux d'un {@code BodyState} (DM-08), champ {@code flags}.
 *
 * <p><strong>Fichier généré — ne pas modifier à la main.</strong> La source est
 * {@code crates/ax-model/src/dm/physics.rs} ({@code body_state_flags}). Régénérer avec :
 *
 * <pre>cargo run -p axion-codegen --bin gen_java_config</pre>
 */
public final class BodyStateFlags {

    private BodyStateFlags() {
        throw new AssertionError("classe de constantes, non instanciable");
    }

    /** Le corps dort. */
    public static final int SLEEPING = 1;

    /** Le corps touche le sol (collision avec le groupe {@code world}). */
    public static final int TOUCHING_GROUND = 2;

    /** Le corps est immergé dans un fluide. */
    public static final int IN_FLUID = 4;

    /** Une vitesse a été clampée à sa borne (R-180). */
    public static final int CLAMPED = 8;

    /** Le corps est déformé. */
    public static final int DEFORMED = 16;

    /** Le corps est endommagé. */
    public static final int DAMAGED = 32;
}
