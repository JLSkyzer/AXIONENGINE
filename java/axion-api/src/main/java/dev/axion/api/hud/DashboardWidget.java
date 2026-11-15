package dev.axion.api.hud;

import dev.axion.api.Experimental;
import dev.axion.api.vehicle.VehicleView;

/**
 * Un widget du tableau de bord (ADR-170 D6, CAP-11) : ce qu'un addon dessine à l'écran du joueur assis dans un véhicule
 * dont la definition le nomme ({@code dashboard.widgets}).
 *
 * <p>Un mod l'enregistre pendant sa phase d'enregistrement
 * ({@link dev.axion.api.content.ContentRegistrar#registerDashboardWidget}), des deux côtés, comme un contrôleur ; seul le
 * client l'appelle, à chaque image, sur le thread de rendu. Il ne dessine que sur sa toile, dans le rectangle qu'elle lui
 * donne, et ne garde rien d'une image à l'autre qu'il ne puisse retrouver dans la vue du véhicule.
 *
 * <p><strong>Son coût</strong> est mesuré à chaque image ({@code hud.widget_budget_ns}). Un dépassement ou une
 * exception compte une faute ; vingt de suite le désactivent pour la session, avec un message qui le nomme ainsi que
 * son mod ({@code E-1011}).
 */
@Experimental
@FunctionalInterface
public interface DashboardWidget {

    /** Où le widget s'accroche sur l'écran. */
    enum Anchor {
        /** En haut à gauche. */
        TOP_LEFT,
        /** En haut, au milieu. */
        TOP,
        /** En haut à droite. */
        TOP_RIGHT,
        /** À gauche, au milieu. */
        LEFT,
        /** Au centre. */
        CENTER,
        /** À droite, au milieu. */
        RIGHT,
        /** En bas à gauche. */
        BOTTOM_LEFT,
        /** En bas, au milieu. */
        BOTTOM,
        /** En bas à droite. */
        BOTTOM_RIGHT
    }

    /**
     * Dessine le widget pour cette image.
     *
     * @param canvas sa toile : son rectangle, en pixels de l'interface, origine en haut à gauche
     * @param vehicle le véhicule tel que le joueur le voit à cette image : prédit pour le conducteur
     */
    void draw(DashboardCanvas canvas, VehicleView vehicle);

    /** {@return la largeur que le widget demande, en pixels de l'interface} */
    default int width() {
        return 64;
    }

    /** {@return la hauteur que le widget demande, en pixels de l'interface} */
    default int height() {
        return 32;
    }

    /** {@return l'ancre du widget ; il s'empile sur les autres éléments de cette ancre} */
    default Anchor anchor() {
        return Anchor.BOTTOM_LEFT;
    }
}
