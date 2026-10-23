package dev.axion.physics;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * État d'un joint rapporté par le cycle de simulation (C-34, ADR-151 D7).
 *
 * <p>Décodage du contrat figé côté natif ({@code crates/ax-model/src/dm/joints.rs},
 * {@code JointState::write_le}) : <strong>24 octets</strong> little-endian (R-271) — le handle
 * de l'assembly (index puis génération, {@code u32}), le rang du joint et ses drapeaux
 * ({@code u16}), puis la fraction de plage, la force et le couple ({@code f32}). Un
 * enregistrement par joint d'une assembly dont un corps est éveillé, rompu compris.
 *
 * <p>Dans {@code SIM_OUT} (schéma 2), la section des joints suit les emprises des corps : leur
 * nombre ({@code u32}), 4 octets réservés, puis les enregistrements ({@link #decodeSection}).
 *
 * @param handleIndex index du handle d'assembly (DM-01)
 * @param handleGeneration génération du handle d'assembly
 * @param index rang du joint dans la definition
 * @param flags drapeaux {@link #BROKEN} et {@link #JAMMED}
 * @param fraction fraction de sa plage qui lui reste : 1 libre, 0 bloqué (D6)
 * @param force force qu'il transmet au dernier sous-pas, en newtons
 * @param torque couple qu'il transmet autour de son ancrage au dernier sous-pas, en
 *     newtons-mètres
 */
public record JointState(
        int handleIndex, int handleGeneration, int index, int flags, float fraction, float force, float torque) {

    /** Taille de la structure sur la frontière, en octets. */
    public static final int BYTES = 24;

    /** Taille de l'en-tête de la section : le nombre de joints et 4 octets réservés. */
    public static final int SECTION_HEADER_BYTES = 8;

    /** Drapeau : le joint est rompu (D5) ({@code joint_state_flags::BROKEN}). */
    public static final int BROKEN = 1;

    /** Drapeau : le joint est grippé, il lui reste moins que toute sa plage (D6). */
    public static final int JAMMED = 1 << 1;

    /** {@return vrai si le joint est rompu} */
    public boolean broken() {
        return (flags & BROKEN) != 0;
    }

    /** {@return vrai si le joint est grippé} */
    public boolean jammed() {
        return (flags & JAMMED) != 0;
    }

    /**
     * Décode un état depuis un tampon little-endian, à l'octet {@code base}.
     *
     * <p>Lecture absolue, comme {@link BodyState#decode} : la position du tampon n'est pas
     * consommée.
     *
     * @param buffer tampon en little-endian (R-271) contenant au moins
     *     {@code base + }{@link #BYTES} octets
     * @param base offset du premier octet de l'état
     * @return l'état décodé
     */
    public static JointState decode(ByteBuffer buffer, int base) {
        return new JointState(
                buffer.getInt(base),
                buffer.getInt(base + 4),
                Short.toUnsignedInt(buffer.getShort(base + 8)),
                Short.toUnsignedInt(buffer.getShort(base + 10)),
                buffer.getFloat(base + 12),
                buffer.getFloat(base + 16),
                buffer.getFloat(base + 20));
    }

    /**
     * Décode la section des joints qui commence à l'octet {@code base}.
     *
     * <p>Le nombre annoncé est une donnée venue du natif : une section que le tampon ne couvre
     * pas, en-tête compris, donne une liste vide plutôt qu'une lecture hors de la charge.
     *
     * @param buffer tampon en little-endian (R-271)
     * @param base offset du premier octet de la section
     * @return les états, dans l'ordre du natif ; vide si la section est tronquée
     */
    public static List<JointState> decodeSection(ByteBuffer buffer, int base) {
        long records = (long) base + SECTION_HEADER_BYTES;
        if (base < 0 || records > buffer.capacity()) {
            return List.of();
        }
        long count = Integer.toUnsignedLong(buffer.getInt(base));
        if (records + count * BYTES > buffer.capacity()) {
            return List.of();
        }
        List<JointState> joints = new ArrayList<>((int) count);
        for (int rank = 0; rank < count; rank++) {
            joints.add(decode(buffer, (int) records + rank * BYTES));
        }
        return joints;
    }
}
