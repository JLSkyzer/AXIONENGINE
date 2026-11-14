package dev.axion.net;

import dev.axion.config.AxionConfig;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

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
 *   <li>Les corps autres que le corps racine (ADR-151 D9), en poses relatives à lui, s'interpolent au même
 *       temps de rendu : position linéaire et rotation par slerp, aucune vitesse relative n'étant
 *       transmise ; au-delà du dernier snapshot, ils sont tenus.</li>
 *   <li>Les roues (ADR-159 D9) s'interpolent linéairement avec le corps, et sont tenues au-delà du dernier
 *       snapshot. Une assembly endormie, ou figée au-delà de la borne d'extrapolation, a des roues immobiles.</li>
 *   <li>L'état du véhicule (ADR-163 D1) aussi : régime, charge et entrées linéairement, rapport et drapeaux du
 *       snapshot suivant ; sa vitesse est celle du corps racine, nulle une fois figée.</li>
 *   <li>Les variables de contenu (ADR-163 D2) ne s'interpolent pas : ce sont celles du snapshot suivant, tenues
 *       au-delà du dernier.</li>
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
     * @param bodies les corps autres que le corps racine, en poses relatives à lui (ADR-151 D9)
     * @param wheels l'état de chaque roue, dans l'ordre de la definition (ADR-159 D9)
     * @param vehicle l'état du véhicule et la vitesse du corps racine (ADR-163 D1)
     * @param variables les variables de contenu reçues (ADR-163 D2)
     */
    public record Pose(double x, double y, double z, float[] rotation, float[] boundsMin, float[] boundsMax,
            Mode mode, List<BodyPose> bodies, List<WheelPose> wheels, VehiclePose vehicle,
            AssemblyState.VariableValues variables) {

        /**
         * La pose d'une assembly à un seul corps.
         *
         * @param x position x, en blocs
         * @param y position y, en blocs
         * @param z position z, en blocs
         * @param rotation quaternion {@code (x, y, z, w)}
         * @param boundsMin coin minimal de l'emprise, relatif à la position, ou {@code null}
         * @param boundsMax coin maximal de l'emprise, relatif à la position, ou {@code null}
         * @param mode comment elle a été obtenue
         */
        public Pose(double x, double y, double z, float[] rotation, float[] boundsMin, float[] boundsMax, Mode mode) {
            this(x, y, z, rotation, boundsMin, boundsMax, mode, List.of(), List.of(), VehiclePose.NONE,
                    AssemblyState.VariableValues.NONE);
        }
    }

    /**
     * La pose d'un corps autre que le corps racine, relative à lui, dans son repère (ADR-151 D8).
     *
     * @param node node racine du corps dans l'asset
     * @param offset position relative, en blocs
     * @param rotation rotation relative {@code (x, y, z, w)}
     */
    public record BodyPose(int node, double[] offset, float[] rotation) {}

    /**
     * L'état affiché d'une roue (ADR-159 D9).
     *
     * @param compression compression relative au modèle posé, en fraction du débattement
     * @param spin vitesse de rotation, en rad/s
     * @param steer braquage, en radians
     * @param onGround elle touche le sol
     */
    public record WheelPose(float compression, float spin, float steer, boolean onGround) {}

    /**
     * L'état affiché d'un véhicule (ADR-163 D1), et la vitesse de son corps racine.
     *
     * @param rpm régime, en tr/min
     * @param load charge du moteur, dans [0, 1]
     * @param gear rapport engagé, négatif en marche arrière
     * @param flags drapeaux de {@link AssemblyState.Vehicle}
     * @param throttle accélérateur, dans [0, 1]
     * @param brake frein, dans [0, 1]
     * @param steer direction, dans [-1, 1], positive à droite
     * @param handbrake frein à main, dans [0, 1]
     * @param speed vitesse du corps racine, en m/s
     * @param forwardSpeed sa composante le long de l'avant du véhicule, son {@code -Z}, en m/s : négative en reculant
     */
    public record VehiclePose(float rpm, float load, int gear, int flags, float throttle, float brake, float steer,
            float handbrake, float speed, float forwardSpeed) {

        /** Rien : ni véhicule, ni vitesse. */
        public static final VehiclePose NONE = new VehiclePose(0f, 0f, 0, 0, 0f, 0f, 0f, 0f, 0f, 0f);
    }

    private record Sample(double millis, double[] position, float[] velocity, float[] rotation, float[] angular,
            float[] boundsMin, float[] boundsMax, List<BodyPose> bodies, List<WheelPose> wheels,
            AssemblyState.Vehicle vehicle, AssemblyState.VariableValues variables) {}

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
     * {@return une interpolation réglée par la configuration du client : le handshake ne porte ni
     * l'extrapolation ni le seuil de téléportation}
     *
     * @param client configuration du client
     */
    public static Interpolation of(AxionConfig client) {
        return new Interpolation(client.getInt("net.extrapolation_max_ms"), client.getFloat("net.snap_threshold"));
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
        samples.addLast(sample(millis, state));
        while (samples.size() > MAX_SAMPLES) {
            samples.removeFirst();
        }
        if (shown == null) {
            return;
        }
        correctFrom(shown, compute(renderMillis), nowMillis);
    }

    /**
     * Pose l'état d'un tick, qu'il soit neuf ou qu'il remplace celui du même tick : la piste prédite du véhicule piloté
     * (ADR-168, 6c-C), dont un rejeu réécrit les ticks. Un tick plus ancien que tout ce qui est gardé est ignoré.
     * L'écart que le remplacement fait à l'écran se résorbe en {@link #CORRECTION_MILLIS}, ou d'un coup au-delà de
     * {@code net.snap_threshold} ; un état qui ne change pas ce qui s'affiche ne relance pas la correction en cours.
     *
     * @param tick le tick, dans l'unité de {@link ServerClock#TICK_MILLIS}
     * @param state l'état
     * @param renderMillis temps de rendu courant, dans la même unité
     * @param nowMillis temps local, en millisecondes
     */
    public void put(long tick, AssemblyState state, double renderMillis, double nowMillis) {
        double millis = tick * ServerClock.TICK_MILLIS;
        Sample sample = sample(millis, state);
        if (samples.isEmpty() || millis > samples.peekLast().millis()) {
            Pose shown = samples.isEmpty() ? null : corrected(compute(renderMillis), nowMillis);
            Pose before = samples.isEmpty() ? null : compute(renderMillis);
            samples.addLast(sample);
            while (samples.size() > MAX_SAMPLES) {
                samples.removeFirst();
            }
            if (shown != null && changed(before, compute(renderMillis))) {
                correctFrom(shown, compute(renderMillis), nowMillis);
            }
            return;
        }
        List<Sample> kept = new ArrayList<>(samples);
        int at = -1;
        for (int i = 0; i < kept.size(); i++) {
            if (kept.get(i).millis() == millis) {
                at = i;
                break;
            }
        }
        if (at < 0) {
            return;
        }
        Pose shown = corrected(compute(renderMillis), nowMillis);
        Pose before = compute(renderMillis);
        kept.set(at, sample);
        samples.clear();
        samples.addAll(kept);
        Pose after = compute(renderMillis);
        if (changed(before, after)) {
            correctFrom(shown, after, nowMillis);
        }
    }

    /**
     * Part d'une pose affichée par ailleurs : l'écart entre elle et ce que le tampon donne se résorbe en
     * {@link #CORRECTION_MILLIS}, ou d'un coup au-delà de {@code net.snap_threshold}. Le véhicule piloté passe ainsi de
     * l'interpolation à sa pose prédite, et retour, sans saut (ADR-168, 6c-C).
     *
     * @param shown la pose affichée jusque-là ; {@code null} : rien à résorber
     * @param renderMillis temps de rendu courant
     * @param nowMillis temps local, en millisecondes
     */
    public void blend(Pose shown, double renderMillis, double nowMillis) {
        Pose raw = compute(renderMillis);
        if (shown == null || raw == null) {
            return;
        }
        correctFrom(shown, raw, nowMillis);
    }

    private Sample sample(double millis, AssemblyState state) {
        List<BodyPose> bodies = new ArrayList<>(state.bodies().size());
        for (AssemblyState.Body body : state.bodies()) {
            bodies.add(new BodyPose(body.node(), body.offsetBlocks(), body.quaternion()));
        }
        return new Sample(millis, state.positionBlocks(), state.linearVelocity(), state.quaternion(),
                state.angularVelocity(), state.boundsMin(), state.boundsMax(), List.copyOf(bodies), wheels(state),
                state.vehicle(), state.variables());
    }

    /** {@return vrai si les deux poses ne s'affichent pas au même endroit} */
    private static boolean changed(Pose before, Pose after) {
        if (before == null || after == null) {
            return before != after;
        }
        double dx = before.x() - after.x();
        double dy = before.y() - after.y();
        double dz = before.z() - after.z();
        float[] turn = Quaternions.multiply(before.rotation(), Quaternions.conjugate(after.rotation()));
        return Math.sqrt(dx * dx + dy * dy + dz * dz) > EPSILON || 1.0 - Math.abs(turn[3]) > EPSILON;
    }

    /** L'écart entre la pose affichée et celle du tampon : une correction douce, ou rien au-delà du seuil. */
    private void correctFrom(Pose shown, Pose raw, double nowMillis) {
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
                raw.z() + errorPosition[2] * weight, rotation, raw.boundsMin(), raw.boundsMax(), raw.mode(),
                raw.bodies(), raw.wheels(), raw.vehicle(), raw.variables());
    }

    /**
     * {@return le dernier état reçu, tel quel — l'autorité la plus récente que le client connaisse —, en mode
     * {@link Mode#HELD} ; {@code null} s'il n'a rien reçu} L'overlay {@code network} le montre à côté de la pose
     * dessinée.
     */
    public Pose latest() {
        Sample last = samples.peekLast();
        return last == null ? null : pose(last, last.position(), last.rotation(), Mode.HELD);
    }

    /**
     * {@return la pose du tampon à un temps du serveur, sans correction : interpolée, ou extrapolée au plus de
     * {@code net.extrapolation_max_ms} puis tenue ; {@code null} avant tout snapshot} Les proxies du monde prédit s'y
     * posent au tick prédit de chaque pas (ADR-168, précision 6c-C2).
     *
     * @param serverMillis le temps, en millisecondes du serveur
     */
    public Pose at(double serverMillis) {
        return compute(serverMillis);
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
        float[] velocity = lerp(a.velocity(), b.velocity(), s);
        return new Pose(position[0], position[1], position[2], rotation, min, max, Mode.INTERPOLATED,
                bodies(a.bodies(), b.bodies(), s), wheels(a.wheels(), b.wheels(), s),
                vehicle(a.vehicle(), b.vehicle(), s, velocity, rotation), b.variables());
    }

    /**
     * L'état du véhicule de {@code b}, ses valeurs continues interpolées depuis {@code a} à la fraction {@code s} ;
     * sa vitesse, celle du corps racine.
     */
    private static VehiclePose vehicle(AssemblyState.Vehicle a, AssemblyState.Vehicle b, double s, float[] velocity,
            float[] rotation) {
        float t = (float) s;
        return new VehiclePose(a.rpm() + (b.rpm() - a.rpm()) * t, a.load() + (b.load() - a.load()) * t, b.gear(),
                b.flags(), a.throttle() + (b.throttle() - a.throttle()) * t, a.brake() + (b.brake() - a.brake()) * t,
                a.steer() + (b.steer() - a.steer()) * t, a.handbrake() + (b.handbrake() - a.handbrake()) * t,
                (float) Math.sqrt(velocity[0] * velocity[0] + velocity[1] * velocity[1] + velocity[2] * velocity[2]),
                forwardSpeed(velocity, rotation));
    }

    /**
     * {@return la composante de la vitesse le long de {@code -Z} tourné par {@code q} : l'avant du véhicule}
     *
     * @param velocity la vitesse monde, en m/s
     * @param q l'orientation, {@code x, y, z, w}
     */
    public static float forwardSpeed(float[] velocity, float[] q) {
        float fx = -2f * (q[0] * q[2] + q[3] * q[1]);
        float fy = -2f * (q[1] * q[2] - q[3] * q[0]);
        float fz = -(1f - 2f * (q[0] * q[0] + q[1] * q[1]));
        return velocity[0] * fx + velocity[1] * fy + velocity[2] * fz;
    }

    /**
     * Les corps de {@code b}, chacun interpolé depuis le même node de {@code a} à la fraction {@code s} :
     * position linéaire, rotation par slerp. Un corps que {@code a} n'a pas est pris tel quel.
     */
    private static List<BodyPose> bodies(List<BodyPose> a, List<BodyPose> b, double s) {
        if (b.isEmpty()) {
            return b;
        }
        List<BodyPose> bodies = new ArrayList<>(b.size());
        for (BodyPose to : b) {
            BodyPose from = null;
            for (BodyPose candidate : a) {
                if (candidate.node() == to.node()) {
                    from = candidate;
                    break;
                }
            }
            if (from == null) {
                bodies.add(to);
                continue;
            }
            double[] offset = new double[3];
            for (int axis = 0; axis < 3; axis++) {
                offset[axis] = from.offset()[axis] + (to.offset()[axis] - from.offset()[axis]) * s;
            }
            bodies.add(new BodyPose(to.node(), offset, Quaternions.slerp(from.rotation(), to.rotation(), (float) s)));
        }
        return List.copyOf(bodies);
    }

    /** Les roues d'un snapshot ; une assembly endormie a des roues immobiles. */
    private static List<WheelPose> wheels(AssemblyState state) {
        AssemblyState.Wheels wheels = state.wheels();
        List<WheelPose> poses = new ArrayList<>(wheels.count());
        for (int wheel = 0; wheel < wheels.count(); wheel++) {
            poses.add(new WheelPose(wheels.compression(wheel), state.sleeping() ? 0f : wheels.spin(wheel),
                    wheels.steer(wheel), wheels.onGround(wheel)));
        }
        return List.copyOf(poses);
    }

    /** Les roues de {@code b}, interpolées linéairement depuis celles de {@code a} ; un autre nombre de roues : {@code b}. */
    private static List<WheelPose> wheels(List<WheelPose> a, List<WheelPose> b, double s) {
        if (a.size() != b.size()) {
            return b;
        }
        List<WheelPose> wheels = new ArrayList<>(b.size());
        for (int wheel = 0; wheel < b.size(); wheel++) {
            WheelPose from = a.get(wheel);
            WheelPose to = b.get(wheel);
            wheels.add(new WheelPose(
                    (float) (from.compression() + (to.compression() - from.compression()) * s),
                    (float) (from.spin() + (to.spin() - from.spin()) * s),
                    (float) (from.steer() + (to.steer() - from.steer()) * s),
                    to.onGround()));
        }
        return List.copyOf(wheels);
    }

    private static Pose pose(Sample sample, double[] position, float[] rotation, Mode mode) {
        boolean frozen = mode == Mode.FROZEN;
        return new Pose(position[0], position[1], position[2], rotation, sample.boundsMin(), sample.boundsMax(), mode,
                sample.bodies(), frozen ? stopped(sample.wheels()) : sample.wheels(),
                vehicle(sample.vehicle(), sample.vehicle(), 1.0, frozen ? new float[3] : sample.velocity(), rotation),
                sample.variables());
    }

    /** Figée au-delà de la borne : ce qui tourne s'arrête. */
    private static List<WheelPose> stopped(List<WheelPose> wheels) {
        List<WheelPose> still = new ArrayList<>(wheels.size());
        for (WheelPose wheel : wheels) {
            still.add(new WheelPose(wheel.compression(), 0f, wheel.steer(), wheel.onGround()));
        }
        return List.copyOf(still);
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
