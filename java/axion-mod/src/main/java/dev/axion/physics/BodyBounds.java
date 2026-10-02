package dev.axion.physics;

import java.nio.ByteBuffer;

/**
 * Emprise d'un corps rapporté par le cycle de simulation (ajout à DM-08, ADR-120).
 *
 * <p>Boîte englobante alignée sur les axes du monde, <strong>relative à la position</strong>
 * du {@link BodyState} de même rang, en blocs : la hitbox vanilla d'une assembly
 * (R-702) s'obtient en ajoutant {@code min}/{@code max} à cette position {@code f64}.
 * Union des emprises de tous les colliders du corps, calculée par le natif depuis la pose
 * rapportée ; garanties natives : composantes finies, {@code min <= max} par axe, la boîte
 * nulle pour une emprise incalculable.
 *
 * <p>Décodage du contrat figé côté natif ({@code crates/ax-model/src/dm/physics.rs},
 * {@code BodyBounds::write_le}) : <strong>24 octets</strong> little-endian (R-271),
 * {@code min[3]} puis {@code max[3]} en {@code f32}. Dans {@code SIM_OUT} (schéma 1), le
 * tableau des emprises suit celui des états, dans le même ordre.
 *
 * @param min coin minimal {@code [x, y, z]}, relatif à la position du corps
 * @param max coin maximal {@code [x, y, z]}, relatif à la position du corps
 */
public record BodyBounds(float[] min, float[] max) {

    /** Taille de la structure sur la frontière, en octets. */
    public static final int BYTES = 24;

    /**
     * Plus grande composante admise, en blocs (ADR-120) : au-delà de ce qu'un asset valide —
     * dont C-22 borne l'AABB à 512 blocs — peut atteindre en tournant.
     */
    public static final float MAX_EXTENT = 1024.0f;

    /**
     * {@return vrai si l'emprise est utilisable comme hitbox}
     *
     * <p>Contrôle d'une donnée venue du natif, comme toute donnée externe : composantes
     * finies, {@code min <= max} sur chaque axe, aucune au-delà de {@link #MAX_EXTENT}. La
     * boîte nulle d'une emprise incalculable passe : elle dit honnêtement « aucune
     * étendue ».
     */
    public boolean isPlausible() {
        for (int axis = 0; axis < 3; axis++) {
            float lo = min[axis];
            float hi = max[axis];
            if (!Float.isFinite(lo)
                    || !Float.isFinite(hi)
                    || lo > hi
                    || Math.abs(lo) > MAX_EXTENT
                    || Math.abs(hi) > MAX_EXTENT) {
                return false;
            }
        }
        return true;
    }

    /**
     * Décode une emprise depuis un tampon little-endian, à l'octet {@code base}.
     *
     * <p>Lecture absolue, comme {@link BodyState#decode} : la position du tampon n'est pas
     * consommée.
     *
     * @param buffer tampon en little-endian (R-271) contenant au moins
     *     {@code base + }{@link #BYTES} octets
     * @param base offset du premier octet de l'emprise
     * @return l'emprise décodée
     */
    public static BodyBounds decode(ByteBuffer buffer, int base) {
        return new BodyBounds(
                new float[] {
                    buffer.getFloat(base),
                    buffer.getFloat(base + 4),
                    buffer.getFloat(base + 8),
                },
                new float[] {
                    buffer.getFloat(base + 12),
                    buffer.getFloat(base + 16),
                    buffer.getFloat(base + 20),
                });
    }
}
