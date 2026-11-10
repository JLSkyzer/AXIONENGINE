package dev.axion.render;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.axion.net.Interpolation;
import dev.axion.net.ServerClock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Le bloc {@code procedural} d'une definition (§18.3, ADR-159 D9), évalué au client depuis l'état répliqué
 * des roues. Logique pure, sans natif ni Minecraft.
 *
 * <p>Ce jeu évalue les sources A.4 {@code wheel.<nom>.spin_angle}, {@code .steer_angle} et
 * {@code .compression}, {@code <nom>} étant le node d'une roue de {@code wheels}. Une piste de toute autre
 * source, ou mal formée, est ignorée, et dite une seule fois au journal : les autres familles viennent
 * avec leur producteur.
 *
 * <p>La valeur d'une piste est sa source, passée par {@code map} (courbe par morceaux, tenue à ses bouts),
 * multipliée par {@code scale}, puis bornée par {@code clamp}. Plusieurs pistes sur un node s'ajoutent en
 * translation et se composent en rotation dans l'ordre de leur déclaration : la suivante tourne dans le
 * repère de la précédente, le braquage se déclare donc avant la rotation de la roue.
 */
public final class ProceduralTracks {

    /** Aucune piste. */
    public static final ProceduralTracks NONE = new ProceduralTracks(new Track[0], new int[0]);

    /** Flottants d'un delta : translation x, y, z, puis rotation x, y, z, w. */
    public static final int DELTA_FLOATS = 7;

    private enum Source {
        SPIN, STEER, COMPRESSION
    }

    /**
     * Une piste valide.
     *
     * @param slot rang de son node dans {@link #nodes}
     * @param rotation canal rotation, sinon translation
     * @param axis axe, de 0 à 2
     * @param wheel rang de la roue source
     * @param source grandeur lue
     * @param scale multiplicateur
     * @param mapX abscisses de la courbe, strictement croissantes, ou {@code null}
     * @param mapY ordonnées de la courbe, ou {@code null}
     * @param low borne basse
     * @param high borne haute
     */
    private record Track(int slot, boolean rotation, int axis, int wheel, Source source, float scale, float[] mapX,
            float[] mapY, float low, float high) {}

    /**
     * Les deltas d'une image, à pousser au natif.
     *
     * @param nodes les nodes, rangs dans l'asset
     * @param deltas {@link #DELTA_FLOATS} flottants par node
     */
    public record Pose(int[] nodes, float[] deltas) {}

    /**
     * L'angle de chaque roue d'une assembly, intégré au rendu (ADR-159 D9) : une fois par image, depuis la
     * vitesse répliquée. Une par assembly, sur le thread de rendu.
     */
    public static final class Spin {

        private double[] angles = new double[0];
        private double lastTick = Double.NaN;

        /**
         * Avance les angles jusqu'au tick de rendu.
         *
         * @param renderTick tick de rendu de l'image, fraction comprise
         * @param wheels état de chaque roue à cette image
         * @return l'angle de chaque roue, en radians ; le tableau n'est pas à garder
         */
        public double[] advance(double renderTick, List<Interpolation.WheelPose> wheels) {
            if (angles.length != wheels.size()) {
                angles = new double[wheels.size()];
            }
            double seconds = Double.isNaN(lastTick)
                    ? 0.0
                    : Math.max(0.0, renderTick - lastTick) * ServerClock.TICK_MILLIS / 1000.0;
            lastTick = renderTick;
            for (int wheel = 0; wheel < angles.length; wheel++) {
                // Le reste d'une division garde l'angle petit : le flottant ne perd rien à la longue.
                angles[wheel] = Math.IEEEremainder(angles[wheel] + wheels.get(wheel).spin() * seconds, 2.0 * Math.PI);
            }
            return angles;
        }
    }

    private final Track[] tracks;
    private final int[] nodes;

    private ProceduralTracks(Track[] tracks, int[] nodes) {
        this.tracks = tracks;
        this.nodes = nodes;
    }

    /**
     * Lit le bloc {@code procedural} d'une definition.
     *
     * @param root document de la definition
     * @param nodeNames noms des nodes de l'asset, dans leur ordre
     * @param journal reçoit le motif de chaque piste ignorée, une fois par motif
     * @return les pistes, {@link #NONE} s'il n'y en a aucune de valide
     */
    public static ProceduralTracks of(JsonObject root, List<String> nodeNames, Consumer<String> journal) {
        if (!(root.get("procedural") instanceof JsonArray entries)) {
            return NONE;
        }
        Map<String, Integer> wheels = wheelRanks(root);
        Map<Integer, Integer> slots = new LinkedHashMap<>();
        List<Track> tracks = new ArrayList<>();
        Set<String> notes = new LinkedHashSet<>();
        for (JsonElement entry : entries) {
            if (entry instanceof JsonObject track) {
                read(track, wheels, nodeNames, slots, tracks, notes);
            }
        }
        notes.forEach(journal);
        if (tracks.isEmpty()) {
            return NONE;
        }
        return new ProceduralTracks(
                tracks.toArray(new Track[0]), slots.keySet().stream().mapToInt(Integer::intValue).toArray());
    }

    private static Map<String, Integer> wheelRanks(JsonObject root) {
        Map<String, Integer> ranks = new HashMap<>();
        if (root.get("wheels") instanceof JsonArray list) {
            for (int rank = 0; rank < list.size(); rank++) {
                if (list.get(rank) instanceof JsonObject wheel && text(wheel, "node") != null) {
                    ranks.put(text(wheel, "node"), rank);
                }
            }
        }
        return ranks;
    }

    private static void read(JsonObject entry, Map<String, Integer> wheels, List<String> names,
            Map<Integer, Integer> slots, List<Track> tracks, Set<String> notes) {
        String source = text(entry, "source");
        String name = text(entry, "node") != null ? text(entry, "node") : text(entry, "bone");
        Source kind = kind(source);
        Integer wheel = kind == null ? null : wheels.get(wheelName(source));
        if (kind == null || wheel == null) {
            notes.add("piste procédurale ignorée : source « " + source + " » non prise en charge");
            return;
        }
        int node = name == null ? -1 : names.indexOf(name);
        if (node < 0) {
            notes.add("piste procédurale ignorée : node « " + name + " » absent de l'asset");
            return;
        }
        String channel = text(entry, "channel");
        int axis = axis(text(entry, "axis"));
        if (!("rotation".equals(channel) || "translation".equals(channel)) || axis < 0) {
            notes.add("piste procédurale de « " + name + " » ignorée : canal ou axe inconnu");
            return;
        }
        float[][] map = map(entry.get("map"));
        if (entry.has("map") && map == null) {
            notes.add("piste procédurale de « " + name + " » ignorée : map sans abscisses croissantes");
            return;
        }
        float[] clamp = clamp(entry.get("clamp"));
        tracks.add(new Track(slots.computeIfAbsent(node, key -> slots.size()), "rotation".equals(channel), axis,
                wheel, kind, entry.has("scale") ? entry.get("scale").getAsFloat() : 1f,
                map == null ? null : map[0], map == null ? null : map[1],
                clamp[0], clamp[1]));
    }

    private static String text(JsonObject object, String key) {
        return object.get(key) != null && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : null;
    }

    private static Source kind(String source) {
        if (source == null || !source.startsWith("wheel.")) {
            return null;
        }
        return switch (source.substring(source.lastIndexOf('.') + 1)) {
            case "spin_angle" -> Source.SPIN;
            case "steer_angle" -> Source.STEER;
            case "compression" -> Source.COMPRESSION;
            default -> null;
        };
    }

    private static String wheelName(String source) {
        return source.substring("wheel.".length(), source.lastIndexOf('.'));
    }

    private static int axis(String axis) {
        return axis == null ? -1 : "xyz".indexOf(axis.length() == 1 ? axis : "?");
    }

    /** La courbe {@code [[x, y]...]} en deux colonnes, ou {@code null} si elle n'est pas croissante en x. */
    private static float[][] map(JsonElement element) {
        if (!(element instanceof JsonArray points) || points.isEmpty()) {
            return null;
        }
        float[] xs = new float[points.size()];
        float[] ys = new float[points.size()];
        for (int rank = 0; rank < xs.length; rank++) {
            JsonArray point = points.get(rank).getAsJsonArray();
            xs[rank] = point.get(0).getAsFloat();
            ys[rank] = point.get(1).getAsFloat();
            if (rank > 0 && xs[rank] <= xs[rank - 1]) {
                return null;
            }
        }
        return new float[][] {xs, ys};
    }

    private static float[] clamp(JsonElement element) {
        if (element instanceof JsonArray pair && pair.size() == 2) {
            float a = pair.get(0).getAsFloat();
            float b = pair.get(1).getAsFloat();
            return new float[] {Math.min(a, b), Math.max(a, b)};
        }
        return new float[] {Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY};
    }

    /** {@return vrai s'il n'y a aucune piste à évaluer} */
    public boolean isEmpty() {
        return tracks.length == 0;
    }

    /**
     * Évalue les pistes à une image.
     *
     * @param wheels état de chaque roue
     * @param spinAngles angle de chaque roue, voir {@link Spin#advance}
     * @return les deltas, ou {@code null} sans piste ou sans état de roue
     */
    public Pose evaluate(List<Interpolation.WheelPose> wheels, double[] spinAngles) {
        if (tracks.length == 0 || wheels.isEmpty()) {
            return null;
        }
        float[] deltas = new float[nodes.length * DELTA_FLOATS];
        for (int slot = 0; slot < nodes.length; slot++) {
            deltas[slot * DELTA_FLOATS + 6] = 1f;
        }
        for (Track track : tracks) {
            if (track.wheel() >= wheels.size()) {
                continue;
            }
            Interpolation.WheelPose wheel = wheels.get(track.wheel());
            float raw = switch (track.source()) {
                case SPIN -> track.wheel() < spinAngles.length ? (float) spinAngles[track.wheel()] : 0f;
                case STEER -> wheel.steer();
                case COMPRESSION -> wheel.compression();
            };
            float value = value(track, raw);
            int at = track.slot() * DELTA_FLOATS;
            if (track.rotation()) {
                compose(deltas, at + 3, track.axis(), value);
            } else {
                deltas[at + track.axis()] += value;
            }
        }
        return new Pose(nodes.clone(), deltas);
    }

    private static float value(Track track, float raw) {
        float mapped = track.mapX() == null ? raw : interpolate(track.mapX(), track.mapY(), raw);
        return Math.max(track.low(), Math.min(track.high(), mapped * track.scale()));
    }

    private static float interpolate(float[] xs, float[] ys, float x) {
        if (x <= xs[0]) {
            return ys[0];
        }
        for (int rank = 1; rank < xs.length; rank++) {
            if (x <= xs[rank]) {
                float s = (x - xs[rank - 1]) / (xs[rank] - xs[rank - 1]);
                return ys[rank - 1] + (ys[rank] - ys[rank - 1]) * s;
            }
        }
        return ys[ys.length - 1];
    }

    /** {@code q ← q × r}, {@code r} étant une rotation de {@code angle} autour de l'axe {@code axis}. */
    private static void compose(float[] deltas, int at, int axis, float angle) {
        float half = angle * 0.5f;
        float sin = (float) Math.sin(half);
        float cos = (float) Math.cos(half);
        float rx = axis == 0 ? sin : 0f;
        float ry = axis == 1 ? sin : 0f;
        float rz = axis == 2 ? sin : 0f;
        float x = deltas[at];
        float y = deltas[at + 1];
        float z = deltas[at + 2];
        float w = deltas[at + 3];
        deltas[at] = w * rx + x * cos + y * rz - z * ry;
        deltas[at + 1] = w * ry - x * rz + y * cos + z * rx;
        deltas[at + 2] = w * rz + x * ry - y * rx + z * cos;
        deltas[at + 3] = w * cos - x * rx - y * ry - z * rz;
    }
}
