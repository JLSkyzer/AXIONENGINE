package dev.axion.physics;

import java.nio.ByteBuffer;

/**
 * Événement physique rapporté par le cycle de simulation (§10.7, IF-03).
 *
 * <p>Décodage du contrat figé côté natif ({@code crates/ax-model/src/dm/physics.rs},
 * {@code PhysicsEvent::write_le}) : <strong>76 octets</strong>, little-endian
 * (R-271). Un événement porte deux identités d'assembly (A et B ; B vaut le
 * handle absent pour un événement mono-corps comme SLEEP/WAKE), un point et une
 * normale de contact monde, et les données de contact de R-615.
 *
 * <p>Les matériaux sont des {@code u16} lus non signés. Le champ {@code kind}
 * est exposé brut : sa sémantique vit dans le DM natif ({@code event_kind}) et
 * sera générée pour Java quand un consommateur la réclamera. Le test Java
 * {@code PhysicsEventTest} épingle la lecture.
 *
 * @param kind code d'événement (voir {@code event_kind} natif)
 * @param assemblyAIndex index du handle de l'assembly A
 * @param assemblyAGeneration génération du handle de l'assembly A
 * @param assemblyBIndex index du handle de l'assembly B (0 si absent)
 * @param assemblyBGeneration génération du handle de l'assembly B (0 si absent)
 * @param nodeA node de A concerné
 * @param nodeB node de B concerné
 * @param point point de contact monde {@code [x, y, z]}
 * @param normal normale de contact {@code [x, y, z]}
 * @param impulse impulsion normale, en N·s
 * @param tangentImpulse norme de l'impulsion tangentielle, en N·s
 * @param relativeVelocity vitesse relative au point projetée sur la normale, en m/s
 * @param effectiveMass masse effective au contact le long de la normale, en kg
 * @param materialA matériau de A (non signé)
 * @param materialB matériau de B (non signé)
 * @param data charge utile propre à l'événement
 */
public record PhysicsEvent(
        int kind,
        int assemblyAIndex,
        int assemblyAGeneration,
        int assemblyBIndex,
        int assemblyBGeneration,
        int nodeA,
        int nodeB,
        float[] point,
        float[] normal,
        float impulse,
        float tangentImpulse,
        float relativeVelocity,
        float effectiveMass,
        int materialA,
        int materialB,
        int data) {

    /** Taille de la structure sur la frontière, en octets. */
    public static final int BYTES = 76;

    /**
     * Décode un événement depuis un tampon little-endian, à l'octet {@code base}.
     *
     * @param buffer tampon en little-endian (R-271) contenant au moins
     *     {@code base + }{@link #BYTES} octets
     * @param base offset du premier octet de l'événement
     * @return l'événement décodé
     */
    public static PhysicsEvent decode(ByteBuffer buffer, int base) {
        return new PhysicsEvent(
                buffer.getInt(base),
                buffer.getInt(base + 4),
                buffer.getInt(base + 8),
                buffer.getInt(base + 12),
                buffer.getInt(base + 16),
                buffer.getInt(base + 20),
                buffer.getInt(base + 24),
                new float[] {
                    buffer.getFloat(base + 28),
                    buffer.getFloat(base + 32),
                    buffer.getFloat(base + 36),
                },
                new float[] {
                    buffer.getFloat(base + 40),
                    buffer.getFloat(base + 44),
                    buffer.getFloat(base + 48),
                },
                buffer.getFloat(base + 52),
                buffer.getFloat(base + 56),
                buffer.getFloat(base + 60),
                buffer.getFloat(base + 64),
                Short.toUnsignedInt(buffer.getShort(base + 68)),
                Short.toUnsignedInt(buffer.getShort(base + 70)),
                buffer.getInt(base + 72));
    }
}
