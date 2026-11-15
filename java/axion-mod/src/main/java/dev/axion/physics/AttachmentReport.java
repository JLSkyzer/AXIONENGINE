package dev.axion.physics;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * L'état d'une attache à la fin d'un tick (ADR-171 D6), décodé de {@code SIM_OUT} (miroir de
 * {@code AttachmentReport::write_le}) : <strong>24 octets</strong> little-endian (R-271) — l'identifiant et les
 * drapeaux ({@code u32}), puis la longueur, la distance, la force et le couple ({@code f32}). Un enregistrement par
 * attache vivante, dans l'ordre de leurs identifiants, et une dernière fois pour celle qui vient de rompre.
 *
 * <p>Dans {@code SIM_OUT} (schéma 7), la section suit celle des états complets exportés : leur nombre
 * ({@code u32}), 4 octets réservés, puis les enregistrements ({@link #decodeSection}).
 *
 * @param id l'identifiant de l'attache
 * @param flags drapeaux, {@link #BROKEN}
 * @param length {@code rope}, {@code winch} : la longueur de la corde, en blocs ; 0 sinon
 * @param distance la distance entre les deux ancrages, en blocs
 * @param force la force transmise au dernier sous-pas, en newtons
 * @param torque le couple transmis, en newtons-mètres
 */
public record AttachmentReport(int id, int flags, float length, float distance, float force, float torque) {

    /** Taille de la structure sur la frontière, en octets. */
    public static final int BYTES = 24;

    /** Taille de l'en-tête de la section : le nombre d'attaches et 4 octets réservés. */
    public static final int SECTION_HEADER_BYTES = 8;

    /** Drapeau : l'attache a rompu à ce tick ({@code attachment_report_flags::BROKEN}). */
    public static final int BROKEN = 1;

    /** {@return vrai si l'attache a rompu à ce tick} */
    public boolean broken() {
        return (flags & BROKEN) != 0;
    }

    /**
     * Décode la section des attaches qui commence à l'octet {@code base}.
     *
     * <p>Le nombre annoncé est une donnée venue du natif : une section que le tampon ne couvre pas, en-tête compris,
     * donne une liste vide plutôt qu'une lecture hors de la charge.
     *
     * @param buffer tampon en little-endian (R-271)
     * @param base offset du premier octet de la section
     * @return les états, dans l'ordre du natif ; vide si la section est tronquée
     */
    public static List<AttachmentReport> decodeSection(ByteBuffer buffer, int base) {
        long records = (long) base + SECTION_HEADER_BYTES;
        if (base < 0 || records > buffer.capacity()) {
            return List.of();
        }
        long count = Integer.toUnsignedLong(buffer.getInt(base));
        if (records + count * BYTES > buffer.capacity()) {
            return List.of();
        }
        List<AttachmentReport> reports = new ArrayList<>((int) count);
        for (int i = 0; i < count; i++) {
            int at = (int) records + i * BYTES;
            reports.add(new AttachmentReport(buffer.getInt(at), buffer.getInt(at + 4), buffer.getFloat(at + 8),
                    buffer.getFloat(at + 12), buffer.getFloat(at + 16), buffer.getFloat(at + 20)));
        }
        return reports;
    }
}
