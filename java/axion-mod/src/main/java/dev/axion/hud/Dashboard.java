package dev.axion.hud;

import dev.axion.definition.VehicleDashboard;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Le tableau de bord à l'écran (ADR-170 D4), en logique pure : où chaque élément se place, et ce qu'il écrit ou
 * montre. Les tailles sont en pixels de l'interface de Minecraft, avant l'échelle du joueur.
 */
public final class Dashboard {

    /** La marge au bord de l'écran, et l'écart entre deux éléments d'une même ancre. */
    public static final int MARGIN = 8;
    public static final int GAP = 6;

    /** Le défaut : vitesse, régime, rapport. */
    public static final int DEFAULT_WIDTH = 96;
    public static final int DEFAULT_HEIGHT = 44;

    /** La hauteur d'une ligne de texte, et celle d'une barre. */
    public static final int LINE = 10;
    public static final int BAR = 6;

    /** L'arc d'un cadran, de part et d'autre du haut, en degrés. */
    public static final double DIAL_SWEEP = 120;

    private static final double KMH_PER_MS = 3.6;
    private static final double MPH_PER_MS = 2.2369362920544;

    /**
     * Un rectangle de l'écran.
     *
     * @param x son bord gauche
     * @param y son bord haut
     * @param width sa largeur
     * @param height sa hauteur
     */
    public record Box(int x, int y, int width, int height) {}

    /**
     * La place de chaque élément.
     *
     * @param standard celle du défaut, {@code null} s'il ne s'affiche pas
     * @param gauges celle de chaque jauge, dans l'ordre de la definition
     */
    public record Layout(Box standard, List<Box> gauges) {}

    private Dashboard() {}

    /**
     * {@return la place du défaut et de chaque jauge, sur un écran de cette taille} Une jauge sans décalage s'empile sur
     * les éléments de son ancre, verticalement — vers le haut depuis le bas, vers le bas sinon —, jamais vers le centre
     * de l'écran, où la barre d'objets et le réticule se tiennent ; le défaut est le premier de l'angle bas droit.
     *
     * @param dashboard le tableau de bord
     * @param screenWidth la largeur de l'écran, en pixels de l'interface, échelle du joueur comprise
     * @param screenHeight sa hauteur
     */
    public static Layout layout(VehicleDashboard dashboard, int screenWidth, int screenHeight) {
        Map<VehicleDashboard.Anchor, Integer> pile = new EnumMap<>(VehicleDashboard.Anchor.class);
        Box standard = null;
        if (dashboard.showDefault()) {
            standard = place(VehicleDashboard.Anchor.BOTTOM_RIGHT, 0, 0, DEFAULT_WIDTH, DEFAULT_HEIGHT, screenWidth,
                    screenHeight);
            pile.put(VehicleDashboard.Anchor.BOTTOM_RIGHT, DEFAULT_HEIGHT + GAP);
        }
        List<Box> gauges = new ArrayList<>();
        for (VehicleDashboard.Gauge gauge : dashboard.gauges()) {
            int[] size = size(gauge);
            if (!Double.isNaN(gauge.offsetX())) {
                gauges.add(place(gauge.anchor(), gauge.offsetX(), gauge.offsetY(), size[0], size[1], screenWidth,
                        screenHeight));
                continue;
            }
            VehicleDashboard.Anchor anchor = gauge.anchor();
            int done = pile.getOrDefault(anchor, 0);
            double dy = anchor.row() > 0 ? -done : done;
            gauges.add(place(anchor, 0, dy, size[0], size[1], screenWidth, screenHeight));
            pile.put(anchor, done + size[1] + GAP);
        }
        return new Layout(standard, gauges);
    }

    /** {@return la largeur et la hauteur d'une jauge, libellé compris} */
    public static int[] size(VehicleDashboard.Gauge gauge) {
        int label = gauge.label() == null ? 0 : LINE;
        return switch (gauge.kind()) {
            case DIAL -> new int[] {gauge.size(), gauge.size() + label};
            case BAR -> new int[] {gauge.size() * 2, BAR + LINE + label};
            case TEXT -> new int[] {gauge.size() * 2, LINE + label};
            case LIGHT -> new int[] {gauge.size() * 2, Math.max(LINE, gauge.size() / 3)};
        };
    }

    /**
     * {@return un rectangle accroché à une ancre de l'écran, décalé}
     *
     * @param anchor l'ancre
     * @param offsetX le décalage
     * @param offsetY le décalage
     * @param width la largeur
     * @param height la hauteur
     * @param screenWidth la largeur de l'écran
     * @param screenHeight sa hauteur
     */
    public static Box place(VehicleDashboard.Anchor anchor, double offsetX, double offsetY, int width, int height,
            int screenWidth, int screenHeight) {
        int x = switch (anchor.column()) {
            case -1 -> MARGIN;
            case 0 -> (screenWidth - width) / 2;
            default -> screenWidth - MARGIN - width;
        };
        int y = switch (anchor.row()) {
            case -1 -> MARGIN;
            case 0 -> (screenHeight - height) / 2;
            default -> screenHeight - MARGIN - height;
        };
        return new Box(x + (int) Math.round(offsetX), y + (int) Math.round(offsetY), width, height);
    }

    /** {@return la part d'une valeur entre deux bornes, dans [0, 1] ; 0 pour une valeur inconnue} */
    public static double fraction(double value, double min, double max) {
        if (Double.isNaN(value) || !(max > min)) {
            return 0;
        }
        return Math.max(0, Math.min(1, (value - min) / (max - min)));
    }

    /** {@return l'angle de l'aiguille d'un cadran, en degrés depuis le haut, sens horaire, de -120 à 120} */
    public static double needle(double value, double min, double max) {
        return -DIAL_SWEEP + 2 * DIAL_SWEEP * fraction(value, min, max);
    }

    /** {@return vrai si un voyant est allumé : sa valeur atteint le seuil} */
    public static boolean lit(double value, double threshold) {
        return !Double.isNaN(value) && value >= threshold;
    }

    /**
     * {@return la vitesse affichée, dans l'unité du joueur, arrondie, avec son unité}
     *
     * @param metresPerSecond la vitesse, en m/s
     * @param unit {@code kmh} ou {@code mph}
     */
    public static String speed(double metresPerSecond, String unit) {
        boolean miles = "mph".equals(unit);
        double shown = Math.abs(metresPerSecond) * (miles ? MPH_PER_MS : KMH_PER_MS);
        return Math.round(shown) + (miles ? " mph" : " km/h");
    }

    /** {@return le rapport affiché : R en marche arrière, N au point mort, son rang sinon} */
    public static String gear(int gear) {
        return gear < 0 ? "R" : gear == 0 ? "N" : Integer.toString(gear);
    }

    /** {@return le régime affiché, arrondi à la dizaine ; son unité se traduit ({@code axion.hud.rpm})} */
    public static String rpm(double rpm) {
        return Long.toString(Math.round(Math.max(0, rpm) / 10.0) * 10);
    }

    /**
     * {@return la valeur d'une jauge écrite : une décimale sous 10, entière au-delà, suivie de son unité}
     *
     * @param value la valeur, NaN si inconnue
     * @param unit son unité, {@code null} sans
     */
    public static String value(double value, String unit) {
        String text = Double.isNaN(value) ? "—"
                : Math.abs(value) < 10 ? String.format(Locale.ROOT, "%.1f", value)
                : Long.toString(Math.round(value));
        return unit == null || unit.isEmpty() ? text : text + " " + unit;
    }
}
