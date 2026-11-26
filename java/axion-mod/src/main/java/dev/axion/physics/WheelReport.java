package dev.axion.physics;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * L'état d'une roue à la fin d'un tick (ADR-159 D9), décodé de {@code SIM_OUT} (miroir de
 * {@code WheelReport::write_le}) : <strong>40 octets</strong> little-endian (R-271) — le handle de
 * l'assembly (index puis génération, {@code u32}), le rang de la roue et ses drapeaux ({@code u16}), la vitesse de
 * rotation, la compression, le braquage, les deux glissements et l'usure ({@code f32}), le matériau touché
 * ({@code u16}, ADR-181) et 2 octets réservés. Un enregistrement par roue d'un véhicule éveillé.
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
 * @param wear usure du pneu, dans [0, 1] (ADR-176 D4)
 * @param material index du matériau touché dans la table de la session, {@link SimCommandStream#NO_MATERIAL} en l'air
 *     ou sans matériau (ADR-181)
 */
public record WheelReport(
        int handleIndex, int handleGeneration, int index, int flags, float spinSpeed, float compression,
        float steerAngle, float slipLong, float slipLat, float wear, int material) {

    /** Un rapport sans matériau touché. */
    public WheelReport(int handleIndex, int handleGeneration, int index, int flags, float spinSpeed,
            float compression, float steerAngle, float slipLong, float slipLat, float wear) {
        this(handleIndex, handleGeneration, index, flags, spinSpeed, compression, steerAngle, slipLong, slipLat, wear,
                SimCommandStream.NO_MATERIAL);
    }

    /** Un rapport sans usure : ce que rend un pneu qui ne s'use pas. */
    public WheelReport(int handleIndex, int handleGeneration, int index, int flags, float spinSpeed,
            float compression, float steerAngle, float slipLong, float slipLat) {
        this(handleIndex, handleGeneration, index, flags, spinSpeed, compression, steerAngle, slipLong, slipLat, 0f);
    }

    /**
     * Taille de la structure sur la frontière, en octets : 32 depuis les glissements ({@code SIM_OUT} schéma 5), 40
     * depuis l'usure du pneu (ADR-176 D4) : 4 octets réservés gardent les enregistrements alignés sur 8.
     */
    public static final int BYTES = 40;

    /** Sa taille avant les glissements ({@code SIM_OUT} schémas 3 et 4) ; ils y valent 0. */
    public static final int BYTES_WITHOUT_SLIPS = 24;

    /** Le schéma de {@code SIM_OUT} depuis lequel chaque roue porte ses glissements (ADR-167 D9). */
    public static final int SLIPS_SINCE_SCHEMA = 5;

    /** Le schéma de {@code SIM_OUT} depuis lequel chaque roue porte le matériau qu'elle touche (ADR-181 D1). */
    public static final int MATERIALS_SINCE_SCHEMA = 8;

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
        return decode(buffer, base, BYTES, true);
    }

    private static WheelReport decode(ByteBuffer buffer, int base, int bytes, boolean material) {
        boolean slips = bytes >= BYTES;
        return new WheelReport(
                buffer.getInt(base),
                buffer.getInt(base + 4),
                Short.toUnsignedInt(buffer.getShort(base + 8)),
                Short.toUnsignedInt(buffer.getShort(base + 10)),
                buffer.getFloat(base + 12),
                buffer.getFloat(base + 16),
                buffer.getFloat(base + 20),
                slips ? buffer.getFloat(base + 24) : 0f,
                slips ? buffer.getFloat(base + 28) : 0f,
                slips ? buffer.getFloat(base + 32) : 0f,
                material ? Short.toUnsignedInt(buffer.getShort(base + 36)) : SimCommandStream.NO_MATERIAL);
    }

    /**
     * {@return la taille d'un enregistrement dans un {@code SIM_OUT} de ce schéma}
     *
     * @param schema le schéma lu dans l'en-tête
     */
    public static int bytes(int schema) {
        return schema >= SLIPS_SINCE_SCHEMA ? BYTES : BYTES_WITHOUT_SLIPS;
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
        return decodeSection(buffer, base, BYTES);
    }

    /**
     * Décode la section des roues, ses enregistrements de la taille donnée.
     *
     * @param buffer tampon en little-endian
     * @param base offset de l'en-tête de la section
     * @param bytes taille d'un enregistrement, voir {@link #bytes(int)}
     * @return les roues, vide si la section dépasse le tampon
     */
    public static List<WheelReport> decodeSection(ByteBuffer buffer, int base, int bytes) {
        return decodeSection(buffer, base, bytes, bytes >= BYTES);
    }

    /**
     * Décode la section des roues, ses enregistrements de la taille donnée.
     *
     * @param buffer tampon en little-endian
     * @param base offset de l'en-tête de la section
     * @param bytes taille d'un enregistrement, voir {@link #bytes(int)}
     * @param materials vrai si l'enregistrement porte le matériau touché, depuis {@link #MATERIALS_SINCE_SCHEMA}
     * @return les roues, vide si la section dépasse le tampon
     */
    public static List<WheelReport> decodeSection(ByteBuffer buffer, int base, int bytes, boolean materials) {
        long records = (long) base + SECTION_HEADER_BYTES;
        if (base < 0 || records > buffer.capacity()) {
            return List.of();
        }
        long count = Integer.toUnsignedLong(buffer.getInt(base));
        if (records + count * bytes > buffer.capacity()) {
            return List.of();
        }
        List<WheelReport> wheels = new ArrayList<>((int) count);
        for (int rank = 0; rank < count; rank++) {
            wheels.add(decode(buffer, (int) records + rank * bytes, bytes, materials && bytes >= BYTES));
        }
        return wheels;
    }
}
