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
 * normale, l'impulsion et la vitesse d'approche. Une ligne {@code tuile} par section de collision
 * du monde posée ou retirée (C-38), avant les états du tick qu'elle sert : sa section, posée
 * ({@code genre} 0) ou retirée (1), ses boîtes de collision ({@code champ} pour un champ de
 * hauteurs, R-641) et d'eau, les sections encore en file, et si un corps l'occupait déjà
 * ({@code urgence}). Séparateur {@code ;}, nombres au point décimal.
 *
 * <p>Écrite et vidée à chaque tick, pour rester lisible pendant la partie et survivre à un arrêt
 * brutal. Utilisée sur le thread du serveur seulement.
 */
public final class SimulationTrace implements AutoCloseable {

    /** Colonnes de la trace, dans l'ordre. */
    public static final String HEADER = "tick;type;assembly;x;y;z;qx;qy;qz;qw;vx;vy;vz;wx;wy;wz;"
            + "flags;genre;autre;nx;ny;nz;impulsion;approche;data;section;boites;eau;file;urgence";

    /**
     * Une section de collision du monde posée ou retirée à un tick (C-38).
     *
     * @param x index de section sur x
     * @param y index de section sur y
     * @param z index de section sur z
     * @param released vrai si la section est retirée, faux si elle est posée
     * @param boxes boîtes de collision posées, ou {@link #HEIGHTFIELD}
     * @param water boîtes d'eau posées
     * @param urgent vrai si un corps occupait la section, ou allait y entrer
     */
    public record Tile(int x, int y, int z, boolean released, int boxes, int water, boolean urgent) {

        /** Valeur de {@code boxes} d'une section posée en champ de hauteurs (R-641). */
        public static final int HEIGHTFIELD = -1;

        /**
         * {@return une section posée}
         *
         * @param x index de section sur x
         * @param y index de section sur y
         * @param z index de section sur z
         * @param boxes boîtes de collision, ou {@link #HEIGHTFIELD}
         * @param water boîtes d'eau
         * @param urgent vrai si un corps l'occupait
         */
        public static Tile built(int x, int y, int z, int boxes, int water, boolean urgent) {
            return new Tile(x, y, z, false, boxes, water, urgent);
        }

        /**
         * {@return une section retirée}
         *
         * @param x index de section sur x
         * @param y index de section sur y
         * @param z index de section sur z
         */
        public static Tile released(int x, int y, int z) {
            return new Tile(x, y, z, true, 0, 0, false);
        }
    }

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
            String[] fields = emptyLine(tick, "etat");
            fields[2] = Integer.toString(body.handleIndex());
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
            String[] fields = emptyLine(tick, "evenement");
            fields[2] = Integer.toString(event.assemblyAIndex());
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

    /**
     * Écrit les sections posées et retirées d'un tick, puis vide la destination. Sans section,
     * rien n'est écrit.
     *
     * @param tick numéro du tick
     * @param tiles sections posées et retirées, dans l'ordre de leurs commandes
     * @param pending sections encore en file après ce tick
     * @throws IOException si l'écriture échoue
     */
    public void recordTiles(long tick, List<Tile> tiles, int pending) throws IOException {
        if (tiles.isEmpty()) {
            return;
        }
        StringBuilder text = new StringBuilder();
        for (Tile tile : tiles) {
            String[] fields = emptyLine(tick, "tuile");
            fields[17] = tile.released() ? "1" : "0";
            fields[25] = tile.x() + "," + tile.y() + "," + tile.z();
            if (!tile.released()) {
                fields[26] = tile.boxes() == Tile.HEIGHTFIELD ? "champ" : Integer.toString(tile.boxes());
                fields[27] = Integer.toString(tile.water());
                fields[29] = tile.urgent() ? "1" : "0";
            }
            fields[28] = Integer.toString(pending);
            text.append(String.join(";", fields)).append('\n');
        }
        out.write(text.toString());
        out.flush();
        lines += tiles.size();
    }

    /** {@return le nombre de lignes écrites, en-tête non compris} */
    public long lines() {
        return lines;
    }

    @Override
    public void close() throws IOException {
        out.close();
    }

    private static String[] emptyLine(long tick, String type) {
        String[] fields = new String[COLUMNS];
        Arrays.fill(fields, "");
        fields[0] = Long.toString(tick);
        fields[1] = type;
        return fields;
    }

    private static String number(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }
}
