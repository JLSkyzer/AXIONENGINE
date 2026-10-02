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
