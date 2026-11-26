package dev.axion.physics;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * L'état du motopropulseur d'un véhicule à la fin d'un tick (ADR-163 D1), décodé de {@code SIM_OUT} (miroir de
 * {@code VehicleReport::write_le}) : <strong>40 octets</strong> little-endian (R-271) — le handle de l'assembly
 * (index puis génération, {@code u32}), le régime et la charge ({@code f32}), le rapport ({@code i8}), les drapeaux
 * ({@code u8}), 2 octets réservés, la position de la direction, puis le contenu des 4 réserves ({@code f32}, ADR-182).
 * Un enregistrement par véhicule éveillé.
 *
 * <p>Dans {@code SIM_OUT} (schéma 4), la section des véhicules suit celle des roues : leur nombre ({@code u32}),
 * 4 octets réservés, puis les enregistrements ({@link #decodeSection}).
 *
 * @param handleIndex index du handle d'assembly (DM-01)
 * @param handleGeneration génération du handle d'assembly
 * @param rpm régime du moteur, en tr/min
 * @param load charge du moteur dans [0, 1]
 * @param gear rang du rapport engagé, négatif en marche arrière, 0 au point mort
 * @param flags drapeaux {@link #SHIFTING}, {@link #RUNNING}, {@link #DRIVER}
 * @param steer position de la direction dans [-1, 1], positive à droite : la consigne après la vitesse de braquage
 *     (ADR-159 D7), celle que les roues suivent
 * @param store0 le contenu de la réserve de rang 0, dans son unité ; 0 sans (ADR-176 D3, ADR-182)
 * @param store1 celui de la réserve de rang 1
 * @param store2 celui de la réserve de rang 2
 * @param store3 celui de la réserve de rang 3
 */
public record VehicleReport(
        int handleIndex, int handleGeneration, float rpm, float load, int gear, int flags, float steer, float store0,
        float store1, float store2, float store3) {

    /** Taille de la structure sur la frontière, en octets. */
    public static final int BYTES = 40;

    /** Taille de l'en-tête de la section : le nombre de véhicules et 4 octets réservés. */
    public static final int SECTION_HEADER_BYTES = 8;

    /** Drapeau : un changement de rapport est en cours ({@code vehicle_report_flags::SHIFTING}). */
    public static final int SHIFTING = 1;

    /** Drapeau : le moteur tourne ({@code vehicle_report_flags::RUNNING}). */
    public static final int RUNNING = 2;

    /** Drapeau : le siège conducteur est occupé ({@code vehicle_report_flags::DRIVER}). */
    public static final int DRIVER = 4;

    /**
     * Décode un véhicule à la position {@code base} du tampon.
     *
     * @param buffer tampon en little-endian (R-271)
     * @param base offset du premier octet de l'enregistrement
     * @return l'état décodé
     */
    public static VehicleReport decode(ByteBuffer buffer, int base) {
        return new VehicleReport(
                buffer.getInt(base),
                buffer.getInt(base + 4),
                buffer.getFloat(base + 8),
                buffer.getFloat(base + 12),
                buffer.get(base + 16),
                Byte.toUnsignedInt(buffer.get(base + 17)),
                buffer.getFloat(base + 20),
                buffer.getFloat(base + 24),
                buffer.getFloat(base + 28),
                buffer.getFloat(base + 32),
                buffer.getFloat(base + 36));
    }

    /** {@return le contenu de la réserve au rang {@code slot} ; 0 hors des 4 rangs} */
    public float store(int slot) {
        return switch (slot) {
            case 0 -> store0;
            case 1 -> store1;
            case 2 -> store2;
            case 3 -> store3;
            default -> 0f;
        };
    }

    /**
     * Décode la section des véhicules qui commence à l'octet {@code base}.
     *
     * <p>Le nombre annoncé est une donnée venue du natif : une section que le tampon ne couvre pas, en-tête compris,
     * donne une liste vide plutôt qu'une lecture hors de la charge.
     *
     * @param buffer tampon en little-endian (R-271)
     * @param base offset du premier octet de la section
     * @return les états, dans l'ordre du natif ; vide si la section est tronquée
     */
    public static List<VehicleReport> decodeSection(ByteBuffer buffer, int base) {
        long records = (long) base + SECTION_HEADER_BYTES;
        if (base < 0 || records > buffer.capacity()) {
            return List.of();
        }
        long count = Integer.toUnsignedLong(buffer.getInt(base));
        if (records + count * BYTES > buffer.capacity()) {
            return List.of();
        }
        List<VehicleReport> vehicles = new ArrayList<>((int) count);
        for (int rank = 0; rank < count; rank++) {
            vehicles.add(decode(buffer, (int) records + rank * BYTES));
        }
        return vehicles;
    }
}
