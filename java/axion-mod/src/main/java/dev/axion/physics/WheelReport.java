package dev.axion.physics;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * L'état d'une roue à la fin d'un tick (ADR-159 D9), décodé de {@code SIM_OUT} (miroir de
 * {@code WheelReport::write_le}) : <strong>24 octets</strong> little-endian (R-271) — le handle de
 * l'assembly (index puis génération, {@code u32}), le rang de la roue et ses drapeaux ({@code u16}), puis
 * la vitesse de rotation, la compression et le braquage ({@code f32}). Un enregistrement par roue d'un
 * véhicule éveillé.
 *
 * <p>Dans {@code SIM_OUT} (schéma 3), la section des roues suit celle des joints : leur nombre
 * ({@code u32}), 4 octets réservés, puis les enregistrements ({@link #decodeSection}).
 *
 * @param handleIndex index du handle d'assembly (DM-01)
 * @param handleGeneration génération du handle d'assembly
 * @param index rang de la roue dans la definition
 * @param flags drapeau {@link #ON_GROUND}
 * @param spinSpeed vitesse de rotation, en rad/s, positive en marche avant
 * @param compression compression relative à la pose du modèle, en fraction du débattement : 0 à sa
 *     position dans le modèle posé, positive quand la roue monte
 * @param steerAngle angle de braquage, en radians
 */
public record WheelReport(
        int handleIndex, int handleGeneration, int index, int flags, float spinSpeed, float compression,
        float steerAngle) {

    /** Taille de la structure sur la frontière, en octets. */
    public static final int BYTES = 24;

    /** Taille de l'en-tête de la section : le nombre de roues et 4 octets réservés. */
    public static final int SECTION_HEADER_BYTES = 8;

    /** Drapeau : le rayon de la roue touche le sol ({@code wheel_report_flags::ON_GROUND}). */
    public static final int ON_GROUND = 1;

    /** {@return vrai si le rayon de la roue touche le sol} */
    public boolean onGround() {
        return (flags & ON_GROUND) != 0;
    }

    /**
     * Décode une roue à la position {@code base} du tampon.
     *
     * @param buffer tampon en little-endian (R-271)
     * @param base offset du premier octet de l'enregistrement
     * @return l'état décodé
     */
    public static WheelReport decode(ByteBuffer buffer, int base) {
        return new WheelReport(
                buffer.getInt(base),
                buffer.getInt(base + 4),
                Short.toUnsignedInt(buffer.getShort(base + 8)),
                Short.toUnsignedInt(buffer.getShort(base + 10)),
                buffer.getFloat(base + 12),
                buffer.getFloat(base + 16),
                buffer.getFloat(base + 20));
    }

    /**
     * Décode la section des roues qui commence à l'octet {@code base}.
     *
     * <p>Le nombre annoncé est une donnée venue du natif : une section que le tampon ne couvre pas,
     * en-tête compris, donne une liste vide plutôt qu'une lecture hors de la charge.
     *
     * @param buffer tampon en little-endian (R-271)
     * @param base offset du premier octet de la section
     * @return les états, dans l'ordre du natif ; vide si la section est tronquée
     */
    public static List<WheelReport> decodeSection(ByteBuffer buffer, int base) {
        long records = (long) base + SECTION_HEADER_BYTES;
        if (base < 0 || records > buffer.capacity()) {
            return List.of();
        }
        long count = Integer.toUnsignedLong(buffer.getInt(base));
        if (records + count * BYTES > buffer.capacity()) {
            return List.of();
        }
        List<WheelReport> wheels = new ArrayList<>((int) count);
        for (int rank = 0; rank < count; rank++) {
            wheels.add(decode(buffer, (int) records + rank * BYTES));
        }
        return wheels;
    }
}
