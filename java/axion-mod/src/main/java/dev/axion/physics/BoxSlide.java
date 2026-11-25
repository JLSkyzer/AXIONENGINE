package dev.axion.physics;

/**
 * La boîte d'une entité qui glisse contre des colliders qu'elle ne peut pas traverser (ADR-178 D1, CAP-14) — la logique
 * seule, sans Minecraft : ce qu'{@code Entity.collide} rendra à vanilla pour une assembly.
 *
 * <p>Le balayage — ce que la boîte heurte en premier — vient d'un {@link Sweeper} ; la classe en fait un déplacement :
 * jusqu'au contact, puis ce qui reste, privé de sa part qui entre dans la surface, jusqu'à quatre fois ; puis, si
 * l'entité est au sol et butée à l'horizontale, la marche (les {@code 0,6} bloc de vanilla), tentée comme lui : monter,
 * avancer, redescendre, et garder le meilleur des deux déplacements. Sur une surface qui se marche ({@link #WALKABLE}), ce
 * qui descend s'y arrête sans glisser : une assembly qui penche ne se vide pas de ce qu'elle porte. Le déplacement rendu n'est
 * jamais plus long que
 * celui qu'on demande, et laisse un jeu de {@link #SKIN} bloc au contact : une boîte posée ne s'enfonce pas d'un tick
 * à l'autre.
 */
public final class BoxSlide {

    /** Le jeu laissé au contact, en blocs : de quoi ne pas démarrer le tick suivant dans la surface. */
    public static final double SKIN = 1.0e-4;

    /** La normale verticale à partir de laquelle une surface se marche : plus raide que 45°, une boîte n'y tient pas. */
    public static final double WALKABLE = 0.7;

    /** Le nombre de glissements enchaînés d'un déplacement : un sol, un mur, un angle, et un dernier. */
    public static final int MAX_ITERATIONS = 4;

    /** En deçà, un déplacement est nul. */
    private static final double EPSILON = 1.0e-9;

    /**
     * Le premier contact d'un déplacement.
     *
     * @param fraction la part du déplacement parcourue avant le contact, de 0 à 1
     * @param nx composante x de la normale, unitaire, du collider vers la boîte
     * @param ny composante y de la normale
     * @param nz composante z de la normale
     */
    public record Hit(double fraction, double nx, double ny, double nz) {}

    /** Ce que heurte une boîte : le balayage que la physique du natif fait. */
    @FunctionalInterface
    public interface Sweeper {
        /**
         * @param box la boîte : coin minimal puis maximal, six valeurs
         * @param mx le déplacement en x
         * @param my le déplacement en y
         * @param mz le déplacement en z
         * @return le premier contact, ou {@code null} si le chemin est libre
         */
        Hit sweep(double[] box, double mx, double my, double mz);
    }

    private BoxSlide() {}

    /**
     * Le déplacement qu'une boîte peut faire, contre les colliders que balaie {@code sweeper}.
     *
     * @param box la boîte au départ : coin minimal puis maximal
     * @param motion le déplacement voulu, trois valeurs
     * @param sweeper ce que la boîte heurte
     * @param stepHeight la hauteur qu'elle enjambe ; 0 pour ne rien enjamber
     * @param onGround vrai si l'entité est au sol : seule elle monte une marche
     * @return le déplacement rendu, trois valeurs, jamais plus long que le déplacement voulu
     */
    public static double[] slide(double[] box, double[] motion, Sweeper sweeper, double stepHeight, boolean onGround) {
        double[] direct = pass(box, motion[0], motion[1], motion[2], sweeper);
        boolean blocked = Math.hypot(direct[0], direct[2]) < Math.hypot(motion[0], motion[2]) - EPSILON;
        if (!blocked || stepHeight <= 0.0 || !onGround && motion[1] >= 0.0) {
            return direct;
        }
        double[] stepped = stepUp(box, motion, sweeper, stepHeight);
        return Math.hypot(stepped[0], stepped[2]) > Math.hypot(direct[0], direct[2]) + EPSILON ? stepped : direct;
    }

    /** Glisse jusqu'à {@link #MAX_ITERATIONS} fois : au contact, ce qui reste perd sa part qui entre dans la surface. */
    private static double[] pass(double[] box, double mx, double my, double mz, Sweeper sweeper) {
        double[] at = box.clone();
        double[] total = new double[3];
        double rx = mx;
        double ry = my;
        double rz = mz;
        for (int step = 0; step < MAX_ITERATIONS; step++) {
            double length = Math.sqrt(rx * rx + ry * ry + rz * rz);
            if (length < EPSILON) {
                break;
            }
            Hit hit = sweeper.sweep(at, rx, ry, rz);
            if (hit == null) {
                total[0] += rx;
                total[1] += ry;
                total[2] += rz;
                break;
            }
            // Avancer jusqu'au contact, moins le jeu : jamais en arrière.
            double fraction = Math.max(0.0, Math.min(1.0, hit.fraction()) - SKIN / length);
            double ax = rx * fraction;
            double ay = ry * fraction;
            double az = rz * fraction;
            total[0] += ax;
            total[1] += ay;
            total[2] += az;
            move(at, ax, ay, az);
            double left = 1.0 - Math.min(1.0, hit.fraction());
            rx *= left;
            ry *= left;
            rz *= left;
            // La gravité ne fait pas glisser sur ce qui se marche ; un pas vers le haut d'une pente la monte encore.
            if (hit.ny() >= WALKABLE && ry < 0.0) {
                ry = 0.0;
            }
            double into = rx * hit.nx() + ry * hit.ny() + rz * hit.nz();
            if (into < 0.0) {
                rx -= into * hit.nx();
                ry -= into * hit.ny();
                rz -= into * hit.nz();
            }
        }
        return total;
    }

    /** Monte, avance, redescend — comme vanilla —, et rend le déplacement net. */
    private static double[] stepUp(double[] box, double[] motion, Sweeper sweeper, double stepHeight) {
        double[] up = pass(box, 0.0, stepHeight, 0.0, sweeper);
        if (up[1] < EPSILON) {
            return new double[3];
        }
        double[] raised = box.clone();
        move(raised, up[0], up[1], up[2]);
        double[] across = pass(raised, motion[0], 0.0, motion[2], sweeper);
        move(raised, across[0], across[1], across[2]);
        // Redescendre de ce qu'on est monté, et de la chute demandée.
        double[] down = pass(raised, 0.0, Math.min(0.0, motion[1]) - up[1], 0.0, sweeper);
        return new double[] {across[0], up[1] + across[1] + down[1], across[2]};
    }

    private static void move(double[] box, double dx, double dy, double dz) {
        box[0] += dx;
        box[1] += dy;
        box[2] += dz;
        box[3] += dx;
        box[4] += dy;
        box[5] += dz;
    }
}
