package dev.axion.camera;

import dev.axion.definition.VehicleViews;

/**
 * Les prises de vue d'un véhicule (ADR-170 D1) : où se pose la caméra et où elle regarde, à chaque image, pour la vue
 * active. Logique pure, sans Minecraft : les angles suivent la convention de Minecraft — le lacet en degrés, l'avant
 * {@code (-sin lacet, 0, cos lacet)}, le tangage positif vers le bas.
 *
 * <p>Une instance par joueur local : la poursuite garde sa direction lissée et son orbite d'une image à l'autre.
 */
public final class ViewRig {

    /** ADR-170 D1 : la poursuite par défaut, faite à la plus grande dimension horizontale de l'emprise. */
    public static final double FOLLOW_BASE = 3.0;
    public static final double FOLLOW_PER_SIZE = 1.6;
    public static final double HEIGHT_BASE = 1.0;
    public static final double HEIGHT_PER_SIZE = 0.45;

    /** Le point que la poursuite regarde, au-dessus de la pose du véhicule, en mètres. */
    public static final double FOCUS_HEIGHT = 0.6;

    /** Sous cette vitesse horizontale, en m/s, la poursuite suit le cap du véhicule plutôt que sa vitesse. */
    public static final double MOVING_SPEED = 1.0;

    /** Le retour derrière le véhicule, une fois commencé : sa constante de temps, en millisecondes. */
    public static final double RECENTER_TAU_MS = 250;

    /** Ce qu'un obstacle laisse entre lui et la caméra, en mètres. */
    public static final double CLIP_MARGIN = 0.2;

    /** Un rayon de la caméra vers le monde : la part du segment libre, 1 sans obstacle. */
    @FunctionalInterface
    public interface Clip {

        /**
         * {@return la part du segment, de 0 à 1, parcourue avant le premier obstacle}
         *
         * @param from le départ
         * @param to l'arrivée
         */
        double free(double[] from, double[] to);
    }

    /**
     * Une prise de vue.
     *
     * @param x la position de la caméra
     * @param y la position de la caméra
     * @param z la position de la caméra
     * @param yaw son lacet, en degrés
     * @param pitch son tangage, en degrés
     */
    public record Shot(double x, double y, double z, float yaw, float pitch) {}

    private boolean ready;
    private double dirX;
    private double dirZ;
    private double orbit;
    private double lastRelative = Double.NaN;
    private double lastMouseMillis = Double.NEGATIVE_INFINITY;

    /** Oublie la poursuite : la prochaine image repart du cap du véhicule. */
    public void reset() {
        ready = false;
        orbit = 0;
        lastRelative = Double.NaN;
        lastMouseMillis = Double.NEGATIVE_INFINITY;
    }

    /**
     * {@return la prise de vue d'une poursuite à cette image}
     *
     * @param view la vue
     * @param target la pose du véhicule à l'image
     * @param headingYaw le lacet de son avant, en degrés
     * @param velocity sa vitesse, en m/s
     * @param size la plus grande dimension horizontale de son emprise, en mètres
     * @param relativeYaw le lacet du regard du joueur relativement au véhicule, en degrés : la souris
     * @param nowMillis l'heure de l'image, en millisecondes
     * @param dtMillis le temps depuis l'image précédente
     * @param clip le monde, pour ne pas passer derrière un bloc
     */
    public Shot follow(VehicleViews.View view, double[] target, double headingYaw, double[] velocity, double size,
            double relativeYaw, double nowMillis, double dtMillis, Clip clip) {
        double headX = -Math.sin(Math.toRadians(headingYaw));
        double headZ = Math.cos(Math.toRadians(headingYaw));
        double wantX = headX;
        double wantZ = headZ;
        double speed = Math.hypot(velocity[0], velocity[2]);
        // En marche avant, la caméra suit la trajectoire ; en marche arrière ou à l'arrêt, le cap.
        if (speed > MOVING_SPEED && velocity[0] * headX + velocity[2] * headZ > 0) {
            wantX = velocity[0] / speed;
            wantZ = velocity[2] / speed;
        }
        if (!ready) {
            dirX = wantX;
            dirZ = wantZ;
            ready = true;
        } else {
            double alpha = view.smoothingMs() <= 0 ? 1 : 1 - Math.exp(-Math.max(0, dtMillis) / view.smoothingMs());
            dirX += (wantX - dirX) * alpha;
            dirZ += (wantZ - dirZ) * alpha;
            double norm = Math.hypot(dirX, dirZ);
            if (norm < 1e-9) {
                dirX = wantX;
                dirZ = wantZ;
            } else {
                dirX /= norm;
                dirZ /= norm;
            }
        }
        // L'orbite suit la souris ; sans elle depuis recenter_ms, elle revient derrière.
        if (!Double.isNaN(lastRelative)) {
            double delta = wrap(relativeYaw - lastRelative);
            if (Math.abs(delta) > 1e-3) {
                orbit = wrap(orbit + delta);
                lastMouseMillis = nowMillis;
            }
        }
        lastRelative = relativeYaw;
        if (nowMillis - lastMouseMillis > view.recenterMs()) {
            orbit *= Math.exp(-Math.max(0, dtMillis) / RECENTER_TAU_MS);
        }
        double turn = Math.toRadians(orbit);
        // Le lacet tourne l'avant (-sin, cos) : le même sens que la souris.
        double camX = dirX * Math.cos(turn) - dirZ * Math.sin(turn);
        double camZ = dirX * Math.sin(turn) + dirZ * Math.cos(turn);
        double distance = Double.isNaN(view.distance()) ? FOLLOW_BASE + FOLLOW_PER_SIZE * size : view.distance();
        double height = Double.isNaN(view.height()) ? HEIGHT_BASE + HEIGHT_PER_SIZE * size : view.height();
        double[] focus = {target[0], target[1] + FOCUS_HEIGHT, target[2]};
        double[] wanted = {target[0] - camX * distance, target[1] + height, target[2] - camZ * distance};
        double free = Math.max(0, Math.min(1, clip.free(focus, wanted)));
        double length = Math.sqrt(square(wanted[0] - focus[0]) + square(wanted[1] - focus[1])
                + square(wanted[2] - focus[2]));
        double kept = free >= 1 ? 1 : Math.max(0, free - CLIP_MARGIN / Math.max(length, 1e-6));
        double x = focus[0] + (wanted[0] - focus[0]) * kept;
        double y = focus[1] + (wanted[1] - focus[1]) * kept;
        double z = focus[2] + (wanted[2] - focus[2]) * kept;
        return lookAt(x, y, z, focus);
    }

    /**
     * {@return le regard borné d'une vue cockpit ou fixe : un lacet de base, plus celui du joueur relativement au
     * véhicule, dans ±lookYaw ; un tangage de base, plus celui du joueur, dans ±lookPitch}
     *
     * @param baseYaw le lacet de la vue, en degrés
     * @param basePitch son tangage
     * @param relativeYaw le lacet du regard du joueur relativement au véhicule
     * @param playerPitch le tangage du regard du joueur
     * @param lookYaw la borne du lacet, 180 : libre
     * @param lookPitch la borne du tangage, 90 : libre
     */
    public static float[] bounded(double baseYaw, double basePitch, double relativeYaw, double playerPitch,
            double lookYaw, double lookPitch) {
        double yaw = lookYaw >= 180 ? relativeYaw : clamp(wrap(relativeYaw), lookYaw);
        double pitch = clamp(playerPitch, lookPitch);
        return new float[] {(float) (baseYaw + yaw), (float) Math.max(-90, Math.min(90, basePitch + pitch))};
    }

    /**
     * {@return le lacet et le tangage, en degrés, d'une direction donnée dans le monde}
     *
     * @param x sa composante x
     * @param y sa composante y
     * @param z sa composante z
     */
    public static float[] angles(double x, double y, double z) {
        return new float[] {(float) Math.toDegrees(Math.atan2(-x, z)),
            (float) -Math.toDegrees(Math.atan2(y, Math.hypot(x, z)))};
    }

    /**
     * {@return un point d'un repère tourné : {@code position + rotation × local}}
     *
     * @param position l'origine du repère
     * @param rotation sa rotation {@code (x, y, z, w)}
     * @param local le point, dans le repère
     */
    public static double[] transform(double[] position, float[] rotation, float[] local) {
        double qx = rotation[0];
        double qy = rotation[1];
        double qz = rotation[2];
        double qw = rotation[3];
        double vx = local[0];
        double vy = local[1];
        double vz = local[2];
        // v + 2w(q × v) + 2 q × (q × v)
        double cx = qy * vz - qz * vy;
        double cy = qz * vx - qx * vz;
        double cz = qx * vy - qy * vx;
        double ex = qy * cz - qz * cy;
        double ey = qz * cx - qx * cz;
        double ez = qx * cy - qy * cx;
        return new double[] {position[0] + vx + 2 * (qw * cx + ex), position[1] + vy + 2 * (qw * cy + ey),
            position[2] + vz + 2 * (qw * cz + ez)};
    }

    private static Shot lookAt(double x, double y, double z, double[] focus) {
        float[] angles = angles(focus[0] - x, focus[1] - y, focus[2] - z);
        return new Shot(x, y, z, angles[0], angles[1]);
    }

    private static double wrap(double degrees) {
        double wrapped = degrees % 360;
        if (wrapped >= 180) {
            wrapped -= 360;
        } else if (wrapped < -180) {
            wrapped += 360;
        }
        return wrapped;
    }

    private static double clamp(double value, double bound) {
        return Math.max(-bound, Math.min(bound, value));
    }

    private static double square(double value) {
        return value * value;
    }
}
