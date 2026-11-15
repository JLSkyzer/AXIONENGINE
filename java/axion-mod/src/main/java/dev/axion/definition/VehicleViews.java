package dev.axion.definition;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Les vues d'une definition compilée (ADR-170 D1, D3) : celles qu'elle déclare, ou, sans {@code cameras}, les deux
 * vues par défaut de chaque siège — le cockpit, à l'œil du joueur, et la poursuite à la taille du véhicule.
 *
 * <p>Logique pure, sans Forge. Une valeur absente vaut {@link Double#NaN} là où le client la calcule : le recul et la
 * hauteur d'une poursuite, faits à la taille de l'emprise ; le champ de vision, celui du joueur.
 *
 * @param views les vues, dans l'ordre de la definition
 */
public record VehicleViews(List<View> views) {

    /** Aucune vue : la caméra de Minecraft, telle quelle. */
    public static final VehicleViews NONE = new VehicleViews(List.of());

    /** Tous les sièges. */
    public static final long ALL_SEATS = -1L;

    /** ADR-170 D1 : le lissage d'une poursuite et son retour derrière le véhicule, par défaut, en millisecondes. */
    public static final double DEFAULT_SMOOTHING_MS = 200;
    public static final double DEFAULT_RECENTER_MS = 1500;

    /** Copie la liste. */
    public VehicleViews {
        views = List.copyOf(views);
    }

    /** La sorte d'une vue (ADR-170 D1). */
    public enum Kind {
        /** L'œil de l'occupant, à son siège. */
        COCKPIT,
        /** Derrière le véhicule. */
        FOLLOW,
        /** Attachée à un node du modèle. */
        FIXED;

        static Kind of(String key) {
            return valueOf(key.toUpperCase(java.util.Locale.ROOT));
        }
    }

    /**
     * Une vue.
     *
     * @param name son nom
     * @param kind sa sorte
     * @param node le node d'une vue fixe, -1 sinon
     * @param seats les sièges qu'elle sert, un bit par rang
     * @param eye le décalage de l'œil d'une vue cockpit, dans le repère du siège ; {@code null} : l'œil du joueur
     * @param distance le recul d'une poursuite, en mètres ; NaN : à la taille du véhicule
     * @param height la hauteur d'une poursuite, en mètres ; NaN : à la taille du véhicule
     * @param fov le champ de vision, en degrés ; NaN : celui du joueur
     * @param smoothingMs le lissage de la direction d'une poursuite
     * @param recenterMs le temps sans mouvement de souris après lequel une poursuite revient derrière
     * @param lookYaw de combien le regard tourne de part et d'autre, en degrés ; 0 : figé
     * @param lookPitch de combien il monte et descend, en degrés
     */
    public record View(String name, Kind kind, int node, long seats, float[] eye, double distance, double height,
            double fov, double smoothingMs, double recenterMs, double lookYaw, double lookPitch) {

        /** Copie l'œil. */
        public View {
            eye = eye == null ? null : eye.clone();
        }

        /** {@return vrai si elle sert ce siège} */
        public boolean serves(int seat) {
            return seat >= 0 && (seat >= Long.SIZE || (seats & (1L << seat)) != 0);
        }
    }

    /** {@return les vues d'un siège, dans l'ordre de la definition} */
    public List<View> forSeat(int seat) {
        return views.stream().filter(view -> view.serves(seat)).toList();
    }

    /**
     * Compile les vues d'une definition acceptée.
     *
     * @param root la definition
     * @param nodes le node que le natif a résolu pour chaque référence à l'asset, par chemin
     * @param seatRanks le rang de chaque siège, par nom
     * @return les vues ; {@link #NONE} sans siège
     */
    static VehicleViews of(JsonObject root, Map<String, Integer> nodes, Map<String, Integer> seatRanks) {
        if (seatRanks.isEmpty()) {
            return NONE;
        }
        if (!(root.get("cameras") instanceof JsonArray cameras)) {
            return new VehicleViews(List.of(
                    new View("cockpit", Kind.COCKPIT, -1, ALL_SEATS, null, Double.NaN, Double.NaN, Double.NaN,
                            DEFAULT_SMOOTHING_MS, DEFAULT_RECENTER_MS, 180, 90),
                    new View("poursuite", Kind.FOLLOW, -1, ALL_SEATS, null, Double.NaN, Double.NaN, Double.NaN,
                            DEFAULT_SMOOTHING_MS, DEFAULT_RECENTER_MS, 0, 0)));
        }
        List<View> views = new ArrayList<>();
        for (int index = 0; index < cameras.size(); index++) {
            JsonObject camera = cameras.get(index).getAsJsonObject();
            Kind kind = Kind.of(camera.get("kind").getAsString());
            long seats = ALL_SEATS;
            if (camera.get("seats") instanceof JsonArray named) {
                seats = 0;
                for (JsonElement seat : named) {
                    int rank = seatRanks.getOrDefault(seat.getAsString(), -1);
                    if (rank >= 0 && rank < Long.SIZE) {
                        seats |= 1L << rank;
                    }
                }
            }
            float[] eye = null;
            if (camera.get("eye") instanceof JsonArray xyz) {
                eye = new float[] {xyz.get(0).getAsFloat(), xyz.get(1).getAsFloat(), xyz.get(2).getAsFloat()};
            }
            int node = -1;
            if (kind == Kind.FIXED) {
                Integer resolved = nodes.get("$.cameras[" + index + "].node");
                if (resolved == null || resolved < 0) {
                    throw new IllegalArgumentException("$.cameras[" + index + "].node : node non résolu par le natif");
                }
                node = resolved;
            }
            // Le regard : libre au cockpit, figé sur une vue fixe, sauf bornes déclarées.
            double lookYaw = kind == Kind.COCKPIT ? 180 : 0;
            double lookPitch = kind == Kind.COCKPIT ? 90 : 0;
            if (camera.get("look") instanceof JsonObject look) {
                lookYaw = number(look, "yaw", 0);
                lookPitch = number(look, "pitch", 0);
            }
            views.add(new View(camera.get("name").getAsString(), kind, node, seats, eye,
                    number(camera, "distance", Double.NaN), number(camera, "height", Double.NaN),
                    number(camera, "fov", Double.NaN), number(camera, "smoothing_ms", DEFAULT_SMOOTHING_MS),
                    number(camera, "recenter_ms", DEFAULT_RECENTER_MS), lookYaw, lookPitch));
        }
        return new VehicleViews(views);
    }

    private static double number(JsonObject object, String key, double absent) {
        return object.get(key) instanceof com.google.gson.JsonPrimitive primitive && primitive.isNumber()
                ? primitive.getAsDouble()
                : absent;
    }
}
