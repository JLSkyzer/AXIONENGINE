package dev.axion.physics;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * L'état complet d'une assembly exportée (ADR-168 D1), tel que {@code SIM_OUT} le porte depuis son schéma 6 : une
 * section après les motopropulseurs, un enregistrement par assembly dont l'export est demandé
 * ({@link SimCommandStream#setStateExport}). Miroir de {@code ax_model::dm::state::AssemblyStateReport}.
 *
 * <p>Le bloc d'état est <strong>opaque pour Java</strong> : le serveur le porte tel quel au client qui pilote, qui le
 * rend au natif ({@link SimCommandStream#setAssemblyState}). Le natif le valide à la lecture.
 *
 * @param handleIndex rang du handle de l'assembly
 * @param handleGeneration génération du handle
 * @param status statut de sa dernière restauration : {@link #OK}, ou le code de son refus
 * @param state le bloc d'état, copie
 */
public record AssemblyStateReport(int handleIndex, int handleGeneration, int status, byte[] state) {

    /** Octets de l'en-tête de la section : le nombre d'entrées, puis quatre octets à zéro. */
    public static final int SECTION_HEADER_BYTES = 8;

    /** Octets de l'en-tête d'une entrée : handle, longueur, statut. */
    public static final int ENTRY_HEADER_BYTES = 16;

    /** Aucune restauration n'a échoué depuis le dernier export. */
    public static final int OK = 0;

    /** Un joint intact dans l'état, rompu ici : l'assembly doit se recréer. */
    public static final int IRRECONCILABLE = 8;

    /** Copie le bloc. */
    public AssemblyStateReport {
        state = state.clone();
    }

    @Override
    public byte[] state() {
        return state.clone();
    }

    /**
     * Lit la section des états à {@code at} ; vide si elle est tronquée ou démesurée.
     *
     * @param buffer la charge de {@code SIM_OUT}, en-tête compris
     * @param at le début de la section
     * @return les états, dans l'ordre du natif
     */
    public static List<AssemblyStateReport> decodeSection(ByteBuffer buffer, int at) {
        if (at < 0 || (long) at + SECTION_HEADER_BYTES > buffer.capacity()) {
            return List.of();
        }
        long count = Integer.toUnsignedLong(buffer.getInt(at));
        List<AssemblyStateReport> states = new ArrayList<>((int) Math.min(count, 64));
        long position = (long) at + SECTION_HEADER_BYTES;
        for (long i = 0; i < count; i++) {
            if (position + ENTRY_HEADER_BYTES > buffer.capacity()) {
                return List.of();
            }
            int base = (int) position;
            long length = Integer.toUnsignedLong(buffer.getInt(base + 8));
            long end = position + ENTRY_HEADER_BYTES + length;
            if (length > SimCommandStream.MAX_ASSEMBLY_STATE_BYTES || end > buffer.capacity()) {
                return List.of();
            }
            byte[] state = new byte[(int) length];
            buffer.get(base + ENTRY_HEADER_BYTES, state);
            states.add(new AssemblyStateReport(buffer.getInt(base), buffer.getInt(base + 4), buffer.getInt(base + 12),
                    state));
            position = end + (8 - length % 8) % 8;
        }
        return states;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof AssemblyStateReport that && handleIndex == that.handleIndex
                && handleGeneration == that.handleGeneration && status == that.status
                && java.util.Arrays.equals(state, that.state);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * handleIndex + handleGeneration) + java.util.Arrays.hashCode(state);
    }

    @Override
    public String toString() {
        return "AssemblyStateReport[" + handleIndex + "/" + handleGeneration + ", statut " + status + ", "
                + state.length + " octets]";
    }
}
