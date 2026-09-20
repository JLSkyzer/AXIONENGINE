package dev.axion.physics;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Encodeur du flux de commandes de {@code SIM_IN} (IF-03, ADR-114).
 *
 * <p>Java écrit dans {@code SIM_IN} un {@code CommandStreamHeader} puis une suite
 * de commandes (chacune un {@code SimCommandHeader} — opcode + longueur de
 * payload — suivi de son payload {@code repr(C)}). La disposition est figée côté
 * natif ({@code crates/ax-model/src/dm/commands.rs}) et little-endian (R-271) ;
 * le test {@code SimCommandStreamTest} l'épingle ici.
 *
 * <p>Seul {@code SET_DIMENSION_ENV} est porté pour l'instant : régler
 * l'environnement d'une dimension ne dépend d'aucun corps. Les commandes qui
 * ciblent un handle (retrait, cinématique, impulsion) et {@code CREATE_ASSEMBLY}
 * arrivent avec la création de corps (C-32).
 */
public final class SimCommandStream {

    /** Version courante du protocole ({@code CommandStreamHeader::CURRENT_SCHEMA}). */
    public static final int CURRENT_SCHEMA = 1;

    /** Taille du {@code CommandStreamHeader}, en octets. */
    public static final int STREAM_HEADER_BYTES = 8;

    /** Taille d'un {@code SimCommandHeader} (opcode + payload_len), en octets. */
    public static final int COMMAND_HEADER_BYTES = 8;

    /** Opcode {@code SET_DIMENSION_ENV} (ADR-114). */
    public static final int OP_SET_DIMENSION_ENV = 5;

    /** Taille du payload {@code SetDimensionEnv}, en octets (remplissage compris). */
    public static final int SET_DIMENSION_ENV_BYTES = 48;

    /** Drapeau {@code FLUID_PRESENT} de {@code SetDimensionEnv}. */
    public static final int FLUID_PRESENT = 1;

    private final List<byte[]> commands = new ArrayList<>();

    /**
     * Ajoute une commande {@code SET_DIMENSION_ENV} (§10.6).
     *
     * @param dimension identifiant de la dimension visée
     * @param gravity gravité {@code [x, y, z]}, en m/s²
     * @param wind vent {@code [x, y, z]}, en m/s
     * @param fluidSurface altitude de la surface du fluide (ignorée si {@code !fluid})
     * @param fluidDensity masse volumique du fluide (ignorée si {@code !fluid})
     * @param fluid vrai si un fluide est présent ({@link #FLUID_PRESENT})
     * @return {@code this}, pour chaîner
     */
    public SimCommandStream setDimensionEnv(
            long dimension,
            float[] gravity,
            float[] wind,
            float fluidSurface,
            float fluidDensity,
            boolean fluid) {
        if (gravity.length != 3 || wind.length != 3) {
            throw new IllegalArgumentException("gravité et vent sont des vecteurs à 3 composantes");
        }
        ByteBuffer command = ByteBuffer.allocate(COMMAND_HEADER_BYTES + SET_DIMENSION_ENV_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        command.putInt(OP_SET_DIMENSION_ENV);
        command.putInt(SET_DIMENSION_ENV_BYTES);
        command.putLong(dimension);
        command.putFloat(gravity[0]);
        command.putFloat(gravity[1]);
        command.putFloat(gravity[2]);
        command.putFloat(wind[0]);
        command.putFloat(wind[1]);
        command.putFloat(wind[2]);
        command.putFloat(fluidSurface);
        command.putFloat(fluidDensity);
        command.putInt(fluid ? FLUID_PRESENT : 0);
        // Les 4 derniers octets du payload sont le remplissage, laissés à zéro.
        commands.add(command.array());
        return this;
    }

    /** {@return le nombre de commandes, à passer à {@code submit}} */
    public int count() {
        return commands.size();
    }

    /**
     * {@return le flux complet — en-tête puis commandes — prêt à écrire dans
     * {@code SIM_IN}}
     */
    public byte[] toBytes() {
        int total = STREAM_HEADER_BYTES;
        for (byte[] command : commands) {
            total += command.length;
        }
        ByteBuffer stream = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        stream.putInt(CURRENT_SCHEMA);
        stream.putInt(0); // _pad
        for (byte[] command : commands) {
            stream.put(command);
        }
        return stream.array();
    }
}
