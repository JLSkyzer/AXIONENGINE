package dev.axion.net;

import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * L'interpolation d'une assembly chez le client (21.7, ADR-150 D7), telle quelle.
 *
 * <ul>
 *   <li>Entre deux snapshots : position par Hermite (positions et vitesses), rotation par slerp, emprise
 *       par interpolation linéaire.</li>
 *   <li>Au-delà du dernier : extrapolation par les vitesses, bornée à {@code net.extrapolation_max_ms},
 *       puis figement.</li>
 *   <li>Au retour d'un snapshot : l'écart entre ce qui était affiché et ce qui doit l'être se résorbe en
 *       {@link #CORRECTION_MILLIS} — ou d'un coup au-delà de {@code net.snap_threshold}. En interpolation,
 *       un snapshot nouveau ne change pas ce qui s'affiche : seule la sortie d'une extrapolation corrige.</li>
 * </ul>
 *
 * <p>Le temps de rendu — le temps du serveur estimé, moins {@code interpolation_delay_ms} — est fourni par
 * l'appelant ; le temps local, qui mesure la correction, aussi. Une instance par assembly, sur le thread du
 * client.
 */
public final class Interpolation {

    /** Durée de la correction douce au retour d'un snapshot, en millisecondes (21.7). */
    public static final double CORRECTION_MILLIS = 200.0;

    /** Nombre maximal de snapshots gardés : de quoi couvrir largement le retard d'interpolation. */
    static final int MAX_SAMPLES = 64;

    /** En deçà, un écart d'affichage n'est pas une correction à faire, en blocs. */
    private static final double EPSILON = 1e-6;

    /** Comment la pose a été obtenue. */
    public enum Mode {
        /** Avant le premier snapshot gardé : il est tenu. */
        HELD,
        /** Entre deux snapshots. */
        INTERPOLATED,
        /** Au-delà du dernier, dans la borne d'extrapolation. */
        EXTRAPOLATED,
        /** Au-delà de la borne : figée. */
        FROZEN
    }

    /**
     * Une pose à afficher.
     *
     * @param x position x, en blocs
     * @param y position y, en blocs
     * @param z position z, en blocs
     * @param rotation quaternion {@code (x, y, z, w)}
     * @param boundsMin coin minimal de l'emprise, relatif à la position, ou {@code null}
     * @param boundsMax coin maximal de l'emprise, relatif à la position, ou {@code null}
     * @param mode comment elle a été obtenue
     */
    public record Pose(double x, double y, double z, float[] rotation, float[] boundsMin, float[] boundsMax,
            Mode mode) {}

    private record Sample(double millis, double[] position, float[] velocity, float[] rotation, float[] angular,
            float[] boundsMin, float[] boundsMax) {}

    private final double extrapolationMaxMillis;
    private final double snapThreshold;
    private final ArrayDeque<Sample> samples = new ArrayDeque<>();
    private double[] errorPosition;
    private float[] errorRotation;
    private double correctionStart;

    /**
     * @param extrapolationMaxMillis {@code net.extrapolation_max_ms}
     * @param snapThreshold {@code net.snap_threshold}, en blocs
     */
    public Interpolation(double extrapolationMaxMillis, double snapThreshold) {
        this.extrapolationMaxMillis = extrapolationMaxMillis;
        this.snapThreshold = snapThreshold;
    }

    /**
     * Reçoit l'état d'un snapshot.
     *
     * @param serverTick tick du serveur dont il est l'état
     * @param state état reçu
     * @param renderMillis temps de rendu courant, en millisecondes du serveur
     * @param nowMillis temps local, en millisecondes
     */
    public void push(long serverTick, AssemblyState state, double renderMillis, double nowMillis) {
        double millis = serverTick * ServerClock.TICK_MILLIS;
        if (!samples.isEmpty() && millis <= samples.peekLast().millis()) {
            return;
        }
        // Ce qui s'afficherait à cet instant sans ce snapshot, et ce qui doit s'afficher avec : comparés au
        // même temps de rendu, l'écart ne contient que ce que le snapshot apprend.
        Pose shown = samples.isEmpty() ? null : corrected(compute(renderMillis), nowMillis);
        samples.addLast(new Sample(millis, state.positionBlocks(), state.linearVelocity(), state.quaternion(),
                state.angularVelocity(), state.boundsMin(), state.boundsMax()));
        while (samples.size() > MAX_SAMPLES) {
            samples.removeFirst();
        }
        if (shown == null) {
            return;
        }
        Pose raw = compute(renderMillis);
        double dx = shown.x() - raw.x();
        double dy = shown.y() - raw.y();
        double dz = shown.z() - raw.z();
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        float[] rotationError = Quaternions.multiply(shown.rotation(), Quaternions.conjugate(raw.rotation()));
        boolean rotated = 1.0 - Math.abs(rotationError[3]) > EPSILON;
        if (distance > snapThreshold) {
            errorPosition = null;
            errorRotation = null;
        } else if (distance > EPSILON || rotated) {
            errorPosition = new double[] {dx, dy, dz};
            errorRotation = rotationError;
            correctionStart = nowMillis;
        }
    }

    /**
     * La pose à afficher.
     *
     * @param renderMillis temps de rendu, en millisecondes du serveur
     * @param nowMillis temps local, en millisecondes
     * @return la pose, ou {@code null} avant tout snapshot
     */
    public Pose sample(double renderMillis, double nowMillis) {
        // Ce qui précède le snapshot qui encadre le temps de rendu ne servira plus.
        while (samples.size() > 2) {
            Iterator<Sample> it = samples.iterator();
            it.next();
            if (it.next().millis() <= renderMillis) {
                samples.removeFirst();
            } else {
                break;
            }
        }
        Pose raw = compute(renderMillis);
        if (raw == null) {
            return null;
        }
        if (errorPosition != null && nowMillis - correctionStart >= CORRECTION_MILLIS) {
            errorPosition = null;
            errorRotation = null;
        }
        return corrected(raw, nowMillis);
    }

    /** {@return la pose du tampon, avec ce qui reste de la correction en cours} */
    private Pose corrected(Pose raw, double nowMillis) {
        if (errorPosition == null) {
            return raw;
        }
        double weight = 1.0 - (nowMillis - correctionStart) / CORRECTION_MILLIS;
        if (weight <= 0) {
            return raw;
        }
        weight = Math.min(1.0, weight);
        float[] rotation = Quaternions.multiply(
                Quaternions.slerp(Quaternions.IDENTITY, errorRotation, (float) weight), raw.rotation());
        return new Pose(raw.x() + errorPosition[0] * weight, raw.y() + errorPosition[1] * weight,
                raw.z() + errorPosition[2] * weight, rotation, raw.boundsMin(), raw.boundsMax(), raw.mode());
    }

    /** {@return le nombre de snapshots gardés, borné par {@link #MAX_SAMPLES}} */
    int buffered() {
        return samples.size();
    }

    /** {@return la pose du tampon au temps de rendu, sans correction ; {@code null} s'il est vide} */
    private Pose compute(double renderMillis) {
        if (samples.isEmpty()) {
            return null;
        }
        Sample first = samples.peekFirst();
        if (renderMillis <= first.millis()) {
            return pose(first, first.position(), first.rotation(), Mode.HELD);
        }
        Sample before = null;
        for (Sample sample : samples) {
            if (sample.millis() > renderMillis) {
                return hermite(before, sample, renderMillis);
            }
            before = sample;
        }
        Sample last = samples.peekLast();
        double ahead = renderMillis - last.millis();
        Mode mode = ahead <= extrapolationMaxMillis ? Mode.EXTRAPOLATED : Mode.FROZEN;
        double seconds = Math.min(ahead, extrapolationMaxMillis) / 1000.0;
        double[] position = new double[3];
        for (int axis = 0; axis < 3; axis++) {
            position[axis] = last.position()[axis] + last.velocity()[axis] * seconds;
        }
        float[] rotation = Quaternions.integrate(last.rotation(), last.angular(), (float) seconds);
        return pose(last, position, rotation, mode);
    }

    private Pose hermite(Sample a, Sample b, double renderMillis) {
        double span = b.millis() - a.millis();
        double s = (renderMillis - a.millis()) / span;
        double seconds = span / 1000.0;
        double s2 = s * s;
        double s3 = s2 * s;
        double h00 = 2 * s3 - 3 * s2 + 1;
        double h10 = s3 - 2 * s2 + s;
        double h01 = -2 * s3 + 3 * s2;
        double h11 = s3 - s2;
        double[] position = new double[3];
        for (int axis = 0; axis < 3; axis++) {
            position[axis] = h00 * a.position()[axis] + h10 * seconds * a.velocity()[axis]
                    + h01 * b.position()[axis] + h11 * seconds * b.velocity()[axis];
        }
        float[] rotation = Quaternions.slerp(a.rotation(), b.rotation(), (float) s);
        float[] min = lerp(a.boundsMin(), b.boundsMin(), s);
        float[] max = lerp(a.boundsMax(), b.boundsMax(), s);
        return new Pose(position[0], position[1], position[2], rotation, min, max, Mode.INTERPOLATED);
    }

    private static Pose pose(Sample sample, double[] position, float[] rotation, Mode mode) {
        return new Pose(position[0], position[1], position[2], rotation, sample.boundsMin(), sample.boundsMax(), mode);
    }

    private static float[] lerp(float[] a, float[] b, double s) {
        if (a == null || b == null) {
            return b != null ? b : a;
        }
        return new float[] {
            (float) (a[0] + (b[0] - a[0]) * s), (float) (a[1] + (b[1] - a[1]) * s), (float) (a[2] + (b[2] - a[2]) * s),
        };
    }

    /** Les quelques opérations sur les quaternions {@code (x, y, z, w)} dont l'interpolation a besoin. */
    static final class Quaternions {

        static final float[] IDENTITY = {0f, 0f, 0f, 1f};

        private Quaternions() {
            throw new AssertionError("non instanciable");
        }

        static float[] multiply(float[] a, float[] b) {
            return new float[] {
                a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
                a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
                a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
                a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2],
            };
        }

        static float[] conjugate(float[] q) {
            return new float[] {-q[0], -q[1], -q[2], q[3]};
        }

        /** Le plus court chemin de {@code a} à {@code b}, à la fraction {@code t}. */
        static float[] slerp(float[] a, float[] b, float t) {
            double dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3];
            float sign = dot < 0 ? -1f : 1f;
            dot = Math.abs(dot);
            double wa;
            double wb;
            if (dot > 0.9995) {
                wa = 1 - t;
                wb = t;
            } else {
                double theta = Math.acos(dot);
                double sin = Math.sin(theta);
                wa = Math.sin((1 - t) * theta) / sin;
                wb = Math.sin(t * theta) / sin;
            }
            float[] q = new float[4];
            for (int c = 0; c < 4; c++) {
                q[c] = (float) (wa * a[c] + wb * sign * b[c]);
            }
            return normalize(q);
        }

        /** {@code q} tournée par la vitesse angulaire monde {@code omega} (rad/s) pendant {@code seconds}. */
        static float[] integrate(float[] q, float[] omega, float seconds) {
            double speed = Math.sqrt(omega[0] * omega[0] + omega[1] * omega[1] + omega[2] * omega[2]);
            if (speed * seconds < 1e-9) {
                return q.clone();
            }
            double half = speed * seconds / 2;
            double scale = Math.sin(half) / speed;
            float[] delta = {(float) (omega[0] * scale), (float) (omega[1] * scale), (float) (omega[2] * scale),
                (float) Math.cos(half)};
            return normalize(multiply(delta, q));
        }

        private static float[] normalize(float[] q) {
            double norm = Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]);
            return new float[] {(float) (q[0] / norm), (float) (q[1] / norm), (float) (q[2] / norm),
                (float) (q[3] / norm)};
        }
    }
}
