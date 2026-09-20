package dev.axion.physics;

import java.nio.ByteBuffer;

/**
 * État d'un corps rapporté par le cycle de simulation (DM-08, IF-03).
 *
 * <p>Décodage du contrat figé côté natif ({@code crates/ax-model/src/dm/physics.rs},
 * {@code BodyState::write_le}) : <strong>80 octets</strong>, little-endian
 * (R-271), avec 4 octets de remplissage final. La position est en coordonnées
 * <strong>monde</strong> ({@code f64}, R-462), recomposée par le natif depuis la
 * simulation {@code f32} et son origine flottante ; vitesses en repère monde.
 *
 * <p>Les offsets sont verrouillés côté Rust par un test de disposition ; le test
 * Java {@code BodyStateTest} épingle la lecture ici. Le champ {@code flags} est
 * exposé brut : sa sémantique (SLEEPING, CLAMPED, …) vit dans le DM natif et
 * sera générée pour Java, comme {@code BufferKinds}, quand un consommateur la
 * réclamera.
 *
 * @param handleIndex index du handle d'assembly (DM-01)
 * @param handleGeneration génération du handle d'assembly
 * @param position position monde {@code [x, y, z]} en blocs ({@code f64})
 * @param rotation quaternion {@code [x, y, z, w]} (R-461)
 * @param linearVelocity vitesse linéaire monde {@code [x, y, z]}, en m/s
 * @param angularVelocity vitesse angulaire monde {@code [x, y, z]}, en rad/s
 * @param flags drapeaux d'état, interprétés selon le DM natif
 */
public record BodyState(
        int handleIndex,
        int handleGeneration,
        double[] position,
        float[] rotation,
        float[] linearVelocity,
        float[] angularVelocity,
        int flags) {

    /** Taille de la structure sur la frontière, en octets (remplissage compris). */
    public static final int BYTES = 80;

    /**
     * Décode un état depuis un tampon little-endian, à l'octet {@code base}.
     *
     * <p>Ne consomme pas la position du tampon : la lecture est absolue, si bien
     * que décoder un tableau d'états n'exige qu'une boucle sur {@code base}.
     *
     * @param buffer tampon en little-endian (R-271) contenant au moins
     *     {@code base + }{@link #BYTES} octets
     * @param base offset du premier octet de l'état
     * @return l'état décodé
     */
    public static BodyState decode(ByteBuffer buffer, int base) {
        return new BodyState(
                buffer.getInt(base),
                buffer.getInt(base + 4),
                new double[] {
                    buffer.getDouble(base + 8),
                    buffer.getDouble(base + 16),
                    buffer.getDouble(base + 24),
                },
                new float[] {
                    buffer.getFloat(base + 32),
                    buffer.getFloat(base + 36),
                    buffer.getFloat(base + 40),
                    buffer.getFloat(base + 44),
                },
                new float[] {
                    buffer.getFloat(base + 48),
                    buffer.getFloat(base + 52),
                    buffer.getFloat(base + 56),
                },
                new float[] {
                    buffer.getFloat(base + 60),
                    buffer.getFloat(base + 64),
                    buffer.getFloat(base + 68),
                },
                buffer.getInt(base + 72));
    }

    /**
     * {@return la clé de routage du handle}
     *
     * <p>Génération en poids fort, index en poids faible — même composition que
     * le routage natif ({@code SimDriver}), pour indexer un corps côté Java.
     */
    public long handleKey() {
        return (Integer.toUnsignedLong(handleGeneration) << 32) | Integer.toUnsignedLong(handleIndex);
    }
}
