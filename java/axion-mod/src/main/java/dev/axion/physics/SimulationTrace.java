package dev.axion.physics;

import java.io.IOException;
import java.io.Writer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Trace de la simulation, tick par tick, en CSV (C-71, {@code /axion debug trace}) : de quoi lire
 * ce qu'a fait un corps — sa trajectoire, ses vitesses, ses contacts —, plutôt que le déduire.
 *
 * <p>Une ligne {@code etat} par corps et par tick : position monde, rotation {@code [x, y, z, w]},
 * vitesses linéaire et angulaire monde, et drapeaux bruts de DM-08 ({@code body_state_flags} du
 * natif : 1 dort, 2 touche le sol, 4 dans un fluide, 8 vitesse bornée). Une ligne
 * {@code evenement} par événement du tick : son genre, les deux assemblies, le point et la
 * normale, l'impulsion et la vitesse d'approche. Séparateur {@code ;}, nombres au point décimal.
 *
 * <p>Écrite et vidée à chaque tick, pour rester lisible pendant la partie et survivre à un arrêt
 * brutal. Utilisée sur le thread du serveur seulement.
 */
public final class SimulationTrace implements AutoCloseable {

    /** Colonnes de la trace, dans l'ordre. */
    public static final String HEADER = "tick;type;assembly;x;y;z;qx;qy;qz;qw;vx;vy;vz;wx;wy;wz;"
            + "flags;genre;autre;nx;ny;nz;impulsion;approche;data";

    private static final int COLUMNS = HEADER.split(";").length;

    private final Writer out;
    private long lines;

    /**
     * Ouvre une trace sur {@code out} et y écrit l'en-tête.
     *
     * @param out destination, fermée avec la trace
     * @throws IOException si l'en-tête ne peut être écrit
     */
    public SimulationTrace(Writer out) throws IOException {
        this.out = out;
        out.write(HEADER);
        out.write('\n');
        out.flush();
    }

    /**
     * Écrit les états et les événements d'un tick, puis vide la destination.
     *
     * @param tick numéro du tick
     * @param bodies états des corps du tick
     * @param events événements du tick
     * @throws IOException si l'écriture échoue
     */
    public void record(long tick, List<BodyState> bodies, List<PhysicsEvent> events)
            throws IOException {
        StringBuilder text = new StringBuilder();
        for (BodyState body : bodies) {
            String[] fields = emptyLine(tick, "etat", body.handleIndex());
            double[] position = body.position();
            float[] rotation = body.rotation();
            float[] velocity = body.linearVelocity();
            float[] angular = body.angularVelocity();
            for (int i = 0; i < 3; i++) {
                fields[3 + i] = number(position[i]);
                fields[10 + i] = number(velocity[i]);
                fields[13 + i] = number(angular[i]);
            }
            for (int i = 0; i < 4; i++) {
                fields[6 + i] = number(rotation[i]);
            }
            fields[16] = Integer.toString(body.flags());
            text.append(String.join(";", fields)).append('\n');
        }
        for (PhysicsEvent event : events) {
            String[] fields = emptyLine(tick, "evenement", event.assemblyAIndex());
            float[] point = event.point();
            float[] normal = event.normal();
            for (int i = 0; i < 3; i++) {
                fields[3 + i] = number(point[i]);
                fields[19 + i] = number(normal[i]);
            }
            fields[17] = Integer.toString(event.kind());
            fields[18] = Integer.toString(event.assemblyBIndex());
            fields[22] = number(event.impulse());
            fields[23] = number(event.relativeVelocity());
            fields[24] = Integer.toString(event.data());
            text.append(String.join(";", fields)).append('\n');
        }
        out.write(text.toString());
        out.flush();
        lines += bodies.size() + events.size();
    }

    /** {@return le nombre de lignes écrites, en-tête non compris} */
    public long lines() {
        return lines;
    }

    @Override
    public void close() throws IOException {
        out.close();
    }

    private static String[] emptyLine(long tick, String type, int assembly) {
        String[] fields = new String[COLUMNS];
        Arrays.fill(fields, "");
        fields[0] = Long.toString(tick);
        fields[1] = type;
        fields[2] = Integer.toString(assembly);
        return fields;
    }

    private static String number(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }
}
