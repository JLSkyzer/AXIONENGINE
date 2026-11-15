package dev.axion.definition;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Le tableau de bord d'une definition compilée (ADR-170 D4) : le défaut — vitesse, régime, rapport —, ses jauges
 * déclarées, les widgets d'addons qu'il nomme, et les sièges qui le voient. Sans {@code dashboard}, le défaut seul,
 * au conducteur seul (Q2).
 *
 * <p>Logique pure, sans Forge.
 *
 * @param showDefault le défaut s'affiche
 * @param seats les sièges qui le voient, un bit par rang
 * @param gauges ses jauges, dans l'ordre de la definition
 * @param widgets les widgets d'addons qu'il nomme, {@code <mod>:<nom>}
 */
public record VehicleDashboard(boolean showDefault, long seats, List<Gauge> gauges, List<String> widgets) {

    /** Aucun tableau de bord. */
    public static final VehicleDashboard NONE = new VehicleDashboard(false, 0L, List.of(), List.of());

    /** ADR-170 D4 : la taille d'une jauge par défaut, en pixels de l'interface. */
    public static final int DEFAULT_SIZE = 48;

    /** Copie les listes. */
    public VehicleDashboard {
        gauges = List.copyOf(gauges);
        widgets = List.copyOf(widgets);
    }

    /** La sorte d'une jauge. */
    public enum Kind {
        /** Une aiguille sur un arc. */
        DIAL,
        /** Une barre qui se remplit. */
        BAR,
        /** La valeur écrite. */
        TEXT,
        /** Un voyant, allumé au seuil. */
        LIGHT
    }

    /** Où une jauge s'accroche sur l'écran. */
    public enum Anchor {
        TOP_LEFT, TOP, TOP_RIGHT, LEFT, CENTER, RIGHT, BOTTOM_LEFT, BOTTOM, BOTTOM_RIGHT;

        static Anchor of(String key) {
            return valueOf(key.toUpperCase(Locale.ROOT));
        }

        /** {@return la colonne de l'ancre : -1 à gauche, 0 au milieu, 1 à droite} */
        public int column() {
            return ordinal() % 3 - 1;
        }

        /** {@return la ligne de l'ancre : -1 en haut, 0 au milieu, 1 en bas} */
        public int row() {
            return ordinal() / 3 - 1;
        }
    }

    /**
     * Une jauge.
     *
     * @param name son nom
     * @param kind sa sorte
     * @param source sa source, de l'ANNEXE A.4
     * @param min sa borne basse ; le seuil d'un voyant (0,5 sans)
     * @param max sa borne haute
     * @param label la clé de traduction de son libellé, {@code null} sans
     * @param unit son unité, écrite après la valeur, {@code null} sans
     * @param anchor son ancre
     * @param offsetX son décalage, en pixels de l'interface ; NaN : placée à la suite des autres
     * @param offsetY son décalage
     * @param size sa taille, en pixels de l'interface
     */
    public record Gauge(String name, Kind kind, String source, double min, double max, String label, String unit,
            Anchor anchor, double offsetX, double offsetY, int size) {}

    /** {@return vrai si le siège de ce rang voit le tableau de bord} */
    public boolean serves(int seat) {
        return seat >= 0 && seat < Long.SIZE && (seats & (1L << seat)) != 0;
    }

    /** {@return vrai s'il n'a rien à montrer} */
    public boolean isEmpty() {
        return !showDefault && gauges.isEmpty() && widgets.isEmpty();
    }

    /**
     * Compile le tableau de bord d'une definition acceptée.
     *
     * @param root la definition
     * @param seatRanks le rang de chaque siège, par nom
     * @param drivers les sièges conducteurs, un bit par rang
     * @return le tableau de bord ; {@link #NONE} sans siège
     */
    static VehicleDashboard of(JsonObject root, Map<String, Integer> seatRanks, long drivers) {
        if (seatRanks.isEmpty()) {
            return NONE;
        }
        if (!(root.get("dashboard") instanceof JsonObject dashboard)) {
            return drivers == 0 ? NONE : new VehicleDashboard(true, drivers, List.of(), List.of());
        }
        boolean showDefault = !(dashboard.get("default") instanceof JsonPrimitive flag) || flag.getAsBoolean();
        long seats = drivers;
        if (dashboard.get("seats") instanceof JsonArray named) {
            seats = 0;
            for (JsonElement seat : named) {
                int rank = seatRanks.getOrDefault(seat.getAsString(), -1);
                if (rank >= 0 && rank < Long.SIZE) {
                    seats |= 1L << rank;
                }
            }
        }
        List<Gauge> gauges = new ArrayList<>();
        if (dashboard.get("gauges") instanceof JsonArray declared) {
            for (JsonElement element : declared) {
                JsonObject gauge = element.getAsJsonObject();
                Kind kind = Kind.valueOf(gauge.get("kind").getAsString().toUpperCase(Locale.ROOT));
                double offsetX = Double.NaN;
                double offsetY = Double.NaN;
                if (gauge.get("offset") instanceof JsonArray offset) {
                    offsetX = offset.get(0).getAsDouble();
                    offsetY = offset.get(1).getAsDouble();
                }
                gauges.add(new Gauge(gauge.get("name").getAsString(), kind, gauge.get("source").getAsString(),
                        number(gauge, "min", kind == Kind.LIGHT ? 0.5 : 0.0), number(gauge, "max", 1.0),
                        text(gauge, "label"), text(gauge, "unit"),
                        gauge.has("anchor") ? Anchor.of(gauge.get("anchor").getAsString()) : Anchor.BOTTOM_RIGHT,
                        offsetX, offsetY, (int) Math.round(number(gauge, "size", DEFAULT_SIZE))));
            }
        }
        List<String> widgets = new ArrayList<>();
        if (dashboard.get("widgets") instanceof JsonArray named) {
            named.forEach(widget -> widgets.add(widget.getAsString()));
        }
        return new VehicleDashboard(showDefault, seats, gauges, widgets);
    }

    private static double number(JsonObject object, String key, double absent) {
        return object.get(key) instanceof JsonPrimitive primitive && primitive.isNumber()
                ? primitive.getAsDouble()
                : absent;
    }

    private static String text(JsonObject object, String key) {
        return object.get(key) instanceof JsonPrimitive primitive && primitive.isString()
                ? primitive.getAsString()
                : null;
    }
}
