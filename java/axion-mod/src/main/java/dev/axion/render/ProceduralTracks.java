package dev.axion.render;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.axion.net.AssemblyState;
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
 * Le bloc {@code procedural} d'une definition (§18.3, ADR-159 D9, ADR-163 D1), évalué au client depuis l'état
 * répliqué. Logique pure, sans natif ni Minecraft : l'appelant lit le monde et le passe dans un {@link Frame}.
 *
 * <p>Ce jeu évalue les sources A.4 suivantes ; {@code <roue>} est le node d'une roue de {@code wheels},
 * {@code <siège>} le nom d'un siège de {@code seats} :
 *
 * <ul>
 *   <li>{@code wheel.<roue>.spin_angle} (rad, intégré au rendu), {@code .steer_angle} (rad), {@code .compression}
 *       (fraction du débattement), {@code .on_ground} (0 ou 1) ;</li>
 *   <li>{@code vehicle.speed} et {@code vehicle.forward_speed} (m/s, la seconde le long de l'avant, négative en
 *       reculant), {@code vehicle.throttle}, {@code .brake}, {@code .handbrake} (0 à 1), {@code vehicle.steer}
 *       (-1 à 1, positive à droite : la direction après sa vitesse de braquage, celle que les roues suivent) ;</li>
 *   <li>{@code engine.rpm} (tr/min), {@code engine.load} (0 à 1), {@code engine.running} (0 ou 1) ;</li>
 *   <li>{@code transmission.gear} (rang, négatif en marche arrière), {@code transmission.shifting} (0 ou 1) ;</li>
 *   <li>{@code seat.<siège>.occupied} (0 ou 1) ;</li>
 *   <li>{@code time.seconds} (secondes de l'horloge du serveur) ;</li>
 *   <li>{@code world.time} (heure du jour : 0 à minuit, 0,5 à midi), {@code world.sky_light} (lumière du ciel au
 *       bloc de l'assembly, 0 à 15, sans l'heure), {@code world.rain} (0 à 1).</li>
 * </ul>
 *
 * <p>Une piste de toute autre source, ou mal formée, est ignorée, et dite une seule fois au journal : les autres
 * familles viennent avec leur producteur.
 *
 * <p>La valeur d'une piste est sa source, passée par {@code map} (courbe par morceaux, tenue à ses bouts),
 * multipliée par {@code scale}, puis bornée par {@code clamp}, le tout en double. Plusieurs pistes sur un node
 * s'ajoutent en translation et se composent en rotation dans l'ordre de leur déclaration : la suivante tourne dans
 * le repère de la précédente, le braquage se déclare donc avant la rotation de la roue. Un angle est ramené dans
 * {@code [-π, π]} avant d'être composé : une rotation menée par {@code time.seconds} reste exacte après des jours.
 */
public final class ProceduralTracks {

    /** Aucune piste. */
    public static final ProceduralTracks NONE = new ProceduralTracks(new Track[0], new int[0]);

    /** Flottants d'un delta : translation x, y, z, puis rotation x, y, z, w. */
    public static final int DELTA_FLOATS = 7;

    /** Ce qu'une source lit. Celles d'une roue ou d'un siège portent un rang. */
    private enum Source {
        WHEEL_SPIN, WHEEL_STEER, WHEEL_COMPRESSION, WHEEL_ON_GROUND,
        SPEED, FORWARD_SPEED, THROTTLE, BRAKE, STEER, HANDBRAKE,
        RPM, LOAD, RUNNING,
        GEAR, SHIFTING,
        SEAT_OCCUPIED,
        SECONDS,
        WORLD_TIME, SKY_LIGHT, RAIN
    }

    /**
     * Une piste valide.
     *
     * @param slot rang de son node dans {@link #nodes}
     * @param rotation canal rotation, sinon translation
     * @param axis axe, de 0 à 2
     * @param index rang de la roue ou du siège source, 0 pour une autre source
     * @param source grandeur lue
     * @param scale multiplicateur
     * @param mapX abscisses de la courbe, strictement croissantes, ou {@code null}
     * @param mapY ordonnées de la courbe, ou {@code null}
     * @param low borne basse
     * @param high borne haute
     */
    private record Track(int slot, boolean rotation, int axis, int index, Source source, double scale, double[] mapX,
            double[] mapY, double low, double high) {}

    /**
     * Les deltas d'une image, à pousser au natif.
     *
     * @param nodes les nodes, rangs dans l'asset
     * @param deltas {@link #DELTA_FLOATS} flottants par node
     */
    public record Pose(int[] nodes, float[] deltas) {}

    /**
     * Ce qu'une image donne à lire aux pistes (ADR-163 D1).
     *
     * @param wheels état de chaque roue, dans l'ordre de la definition
     * @param spinAngles angle de chaque roue, voir {@link Spin#advance}
     * @param vehicle état du véhicule et vitesse du corps racine
     * @param seats occupation de chaque siège, dans l'ordre de la definition
     * @param seconds temps de l'horloge du serveur, en secondes
     * @param worldTime heure du jour, 0 à minuit, 0,5 à midi
     * @param skyLight lumière du ciel au bloc de l'assembly, de 0 à 15
     * @param rain pluie, de 0 à 1
     */
    public record Frame(List<Interpolation.WheelPose> wheels, double[] spinAngles, Interpolation.VehiclePose vehicle,
            boolean[] seats, double seconds, float worldTime, float skyLight, float rain) {

        /**
         * {@return un cadre où seules les roues ont un état} Le reste vaut zéro.
         *
         * @param wheels état de chaque roue
         * @param spinAngles angle de chaque roue
         */
        public static Frame ofWheels(List<Interpolation.WheelPose> wheels, double[] spinAngles) {
            return new Frame(wheels, spinAngles, Interpolation.VehiclePose.NONE, new boolean[0], 0.0, 0f, 0f, 0f);
        }
    }

    /**
     * L'heure du jour qu'A.4 publie, depuis celle de Minecraft (ADR-163 D1) : 0 à minuit, 0,5 à midi.
     *
     * @param dayTime temps du jour de Minecraft, en ticks ; 0 au lever, 6 h
     * @return l'heure du jour, dans [0, 1)
     */
    public static float worldTime(long dayTime) {
        return Math.floorMod(dayTime + 6000L, 24000L) / 24000f;
    }

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

        /** {@return l'angle de chaque roue à la dernière image, en radians, copie ; vide avant la première} */
        public double[] angles() {
            return angles.clone();
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
        Map<String, Integer> wheels = ranks(root, "wheels", "node");
        Map<String, Integer> seats = ranks(root, "seats", "name");
        Map<Integer, Integer> slots = new LinkedHashMap<>();
        List<Track> tracks = new ArrayList<>();
        Set<String> notes = new LinkedHashSet<>();
        for (JsonElement entry : entries) {
            if (entry instanceof JsonObject track) {
                read(track, wheels, seats, nodeNames, slots, tracks, notes);
            }
        }
        notes.forEach(journal);
        if (tracks.isEmpty()) {
            return NONE;
        }
        return new ProceduralTracks(
                tracks.toArray(new Track[0]), slots.keySet().stream().mapToInt(Integer::intValue).toArray());
    }

    /** Le rang de chaque élément d'une liste de la definition, par la clé qui le nomme. */
    private static Map<String, Integer> ranks(JsonObject root, String list, String key) {
        Map<String, Integer> ranks = new HashMap<>();
        if (root.get(list) instanceof JsonArray elements) {
            for (int rank = 0; rank < elements.size(); rank++) {
                if (elements.get(rank) instanceof JsonObject element && text(element, key) != null) {
                    ranks.putIfAbsent(text(element, key), rank);
                }
            }
        }
        return ranks;
    }

    private static void read(JsonObject entry, Map<String, Integer> wheels, Map<String, Integer> seats,
            List<String> names, Map<Integer, Integer> slots, List<Track> tracks, Set<String> notes) {
        String source = text(entry, "source");
        String name = text(entry, "node") != null ? text(entry, "node") : text(entry, "bone");
        Source kind = kind(source);
        Integer index = kind == null ? null : index(kind, source, wheels, seats);
        if (kind == null || index == null) {
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
        double[][] map = map(entry.get("map"));
        if (entry.has("map") && map == null) {
            notes.add("piste procédurale de « " + name + " » ignorée : map sans abscisses croissantes");
            return;
        }
        double[] clamp = clamp(entry.get("clamp"));
        tracks.add(new Track(slots.computeIfAbsent(node, key -> slots.size()), "rotation".equals(channel), axis,
                index, kind, entry.has("scale") ? entry.get("scale").getAsDouble() : 1.0,
                map == null ? null : map[0], map == null ? null : map[1],
                clamp[0], clamp[1]));
    }

    private static String text(JsonObject object, String key) {
        return object.get(key) != null && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : null;
    }

    /** {@return ce que lit une source A.4, ou {@code null} si ce jeu ne la prend pas en charge} */
    private static Source kind(String source) {
        if (source == null || source.indexOf('.') < 0) {
            return null;
        }
        String family = source.substring(0, source.indexOf('.'));
        String field = source.substring(source.lastIndexOf('.') + 1);
        boolean named = source.indexOf('.') != source.lastIndexOf('.');
        return switch (family) {
            case "wheel" -> !named ? null : switch (field) {
                case "spin_angle" -> Source.WHEEL_SPIN;
                case "steer_angle" -> Source.WHEEL_STEER;
                case "compression" -> Source.WHEEL_COMPRESSION;
                case "on_ground" -> Source.WHEEL_ON_GROUND;
                default -> null;
            };
            case "seat" -> named && "occupied".equals(field) ? Source.SEAT_OCCUPIED : null;
            case "vehicle" -> named ? null : switch (field) {
                case "speed" -> Source.SPEED;
                case "forward_speed" -> Source.FORWARD_SPEED;
                case "throttle" -> Source.THROTTLE;
                case "brake" -> Source.BRAKE;
                case "steer" -> Source.STEER;
                case "handbrake" -> Source.HANDBRAKE;
                default -> null;
            };
            case "engine" -> named ? null : switch (field) {
                case "rpm" -> Source.RPM;
                case "load" -> Source.LOAD;
                case "running" -> Source.RUNNING;
                default -> null;
            };
            case "transmission" -> named ? null : switch (field) {
                case "gear" -> Source.GEAR;
                case "shifting" -> Source.SHIFTING;
                default -> null;
            };
            case "time" -> !named && "seconds".equals(field) ? Source.SECONDS : null;
            case "world" -> named ? null : switch (field) {
                case "time" -> Source.WORLD_TIME;
                case "sky_light" -> Source.SKY_LIGHT;
                case "rain" -> Source.RAIN;
                default -> null;
            };
            default -> null;
        };
    }

    /** {@return le rang de la roue ou du siège qu'une source nomme, 0 pour une autre, {@code null} s'il manque} */
    private static Integer index(Source kind, String source, Map<String, Integer> wheels, Map<String, Integer> seats) {
        return switch (kind) {
            case WHEEL_SPIN, WHEEL_STEER, WHEEL_COMPRESSION, WHEEL_ON_GROUND -> wheels.get(element(source));
            case SEAT_OCCUPIED -> seats.get(element(source));
            default -> 0;
        };
    }

    /** {@return le nom de l'élément d'une source nommée : ce qui sépare la famille du champ} */
    private static String element(String source) {
        return source.substring(source.indexOf('.') + 1, source.lastIndexOf('.'));
    }

    private static int axis(String axis) {
        return axis == null ? -1 : "xyz".indexOf(axis.length() == 1 ? axis : "?");
    }

    /** La courbe {@code [[x, y]...]} en deux colonnes, ou {@code null} si elle n'est pas croissante en x. */
    private static double[][] map(JsonElement element) {
        if (!(element instanceof JsonArray points) || points.isEmpty()) {
            return null;
        }
        double[] xs = new double[points.size()];
        double[] ys = new double[points.size()];
        for (int rank = 0; rank < xs.length; rank++) {
            JsonArray point = points.get(rank).getAsJsonArray();
            xs[rank] = point.get(0).getAsDouble();
            ys[rank] = point.get(1).getAsDouble();
            if (rank > 0 && xs[rank] <= xs[rank - 1]) {
                return null;
            }
        }
        return new double[][] {xs, ys};
    }

    private static double[] clamp(JsonElement element) {
        if (element instanceof JsonArray pair && pair.size() == 2) {
            double a = pair.get(0).getAsDouble();
            double b = pair.get(1).getAsDouble();
            return new double[] {Math.min(a, b), Math.max(a, b)};
        }
        return new double[] {Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY};
    }

    /** {@return vrai s'il n'y a aucune piste à évaluer} */
    public boolean isEmpty() {
        return tracks.length == 0;
    }

    /**
     * Évalue les pistes à une image. Une piste dont la roue ou le siège n'a pas d'état laisse son node au repos.
     *
     * @param frame ce que l'image donne à lire
     * @return les deltas, ou {@code null} sans piste
     */
    public Pose evaluate(Frame frame) {
        if (tracks.length == 0) {
            return null;
        }
        float[] deltas = new float[nodes.length * DELTA_FLOATS];
        for (int slot = 0; slot < nodes.length; slot++) {
            deltas[slot * DELTA_FLOATS + 6] = 1f;
        }
        for (Track track : tracks) {
            Double raw = raw(track, frame);
            if (raw == null) {
                continue;
            }
            double value = value(track, raw);
            int at = track.slot() * DELTA_FLOATS;
            if (track.rotation()) {
                compose(deltas, at + 3, track.axis(), Math.IEEEremainder(value, 2.0 * Math.PI));
            } else {
                deltas[at + track.axis()] += (float) value;
            }
        }
        return new Pose(nodes.clone(), deltas);
    }

    /** {@return la valeur brute de la source d'une piste à cette image, {@code null} si sa roue ou son siège manque} */
    private static Double raw(Track track, Frame frame) {
        Interpolation.VehiclePose vehicle = frame.vehicle();
        return switch (track.source()) {
            case WHEEL_SPIN, WHEEL_STEER, WHEEL_COMPRESSION, WHEEL_ON_GROUND -> wheel(track, frame);
            case SEAT_OCCUPIED -> track.index() < frame.seats().length ? flag(frame.seats()[track.index()]) : null;
            case SPEED -> (double) vehicle.speed();
            case FORWARD_SPEED -> (double) vehicle.forwardSpeed();
            case THROTTLE -> (double) vehicle.throttle();
            case BRAKE -> (double) vehicle.brake();
            case STEER -> (double) vehicle.steer();
            case HANDBRAKE -> (double) vehicle.handbrake();
            case RPM -> (double) vehicle.rpm();
            case LOAD -> (double) vehicle.load();
            case RUNNING -> flag((vehicle.flags() & AssemblyState.Vehicle.RUNNING) != 0);
            case GEAR -> (double) vehicle.gear();
            case SHIFTING -> flag((vehicle.flags() & AssemblyState.Vehicle.SHIFTING) != 0);
            case SECONDS -> frame.seconds();
            case WORLD_TIME -> (double) frame.worldTime();
            case SKY_LIGHT -> (double) frame.skyLight();
            case RAIN -> (double) frame.rain();
        };
    }

    private static Double wheel(Track track, Frame frame) {
        if (track.index() >= frame.wheels().size()) {
            return null;
        }
        Interpolation.WheelPose wheel = frame.wheels().get(track.index());
        return switch (track.source()) {
            case WHEEL_SPIN -> track.index() < frame.spinAngles().length ? frame.spinAngles()[track.index()] : 0.0;
            case WHEEL_STEER -> (double) wheel.steer();
            case WHEEL_COMPRESSION -> (double) wheel.compression();
            default -> flag(wheel.onGround());
        };
    }

    private static double flag(boolean value) {
        return value ? 1.0 : 0.0;
    }

    private static double value(Track track, double raw) {
        double mapped = track.mapX() == null ? raw : interpolate(track.mapX(), track.mapY(), raw);
        return Math.max(track.low(), Math.min(track.high(), mapped * track.scale()));
    }

    private static double interpolate(double[] xs, double[] ys, double x) {
        if (x <= xs[0]) {
            return ys[0];
        }
        for (int rank = 1; rank < xs.length; rank++) {
            if (x <= xs[rank]) {
                double s = (x - xs[rank - 1]) / (xs[rank] - xs[rank - 1]);
                return ys[rank - 1] + (ys[rank] - ys[rank - 1]) * s;
            }
        }
        return ys[ys.length - 1];
    }

    /** {@code q ← q × r}, {@code r} étant une rotation de {@code angle} autour de l'axe {@code axis}. */
    private static void compose(float[] deltas, int at, int axis, double angle) {
        double half = angle * 0.5;
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
