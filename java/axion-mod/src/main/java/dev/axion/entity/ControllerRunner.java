package dev.axion.entity;

import dev.axion.api.assembly.Modifier;
import dev.axion.api.vehicle.ControllerOutput;
import dev.axion.definition.VehicleExtensions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.LongSupplier;

/**
 * Fait tourner les contrôleurs de véhicule (ADR-167 D9) et mesure leur coût, contrôleur par contrôleur (R-1770).
 * Logique pure, sans Forge ; une instance par session du serveur, appelée sur son thread autoritatif, entre deux pas
 * (R-1771).
 *
 * <p><strong>Un appel</strong> : chaque contrôleur d'un véhicule lit le même état du début du tick et écrit dans sa
 * propre sortie. Ce qu'il écrit ne vaut que s'il rend la main normalement ; une exception écarte ses sorties du tick.
 * Puis les sorties s'assemblent dans l'ordre des contrôleurs : les modificateurs (cause : l'identifiant du
 * contrôleur), le couple de chaque roue (sommé), les écritures de variables.
 *
 * <p><strong>Sa santé</strong> : un appel qui dépasse le budget, ou qui lève une exception, compte une faute ; un appel
 * sain remet le compte à zéro. Au nombre de fautes consécutives toléré, le contrôleur est désactivé pour la session,
 * sur tous les véhicules, avec un message {@code E-1011} qui le nomme ainsi que son mod ; le véhicule roule alors sans
 * ses sorties, ce qui est son repli.
 */
public final class ControllerRunner {

    /** Une exception se journalise au plus une fois par seconde et par contrôleur. */
    private static final long EXCEPTION_LOG_NANOS = 1_000_000_000L;

    /**
     * Le budget d'un contrôleur (options {@code [vehicle]}).
     *
     * @param budgetNanos la durée d'un appel au-delà de laquelle il compte une faute, en nanosecondes
     * @param strikes le nombre de fautes consécutives qui le désactivent
     */
    public record Limits(long budgetNanos, int strikes) {
        /** Refuse un budget ou un nombre de fautes non positif. */
        public Limits {
            if (budgetNanos <= 0 || strikes <= 0) {
                throw new IllegalArgumentException("budget et fautes positifs attendus : " + budgetNanos + ", "
                        + strikes);
            }
        }
    }

    /**
     * Le coût mesuré d'un contrôleur et son état, pour le diagnostic (ADR-167 D10).
     *
     * @param id son identifiant
     * @param owner son mod
     * @param calls ses appels mesurés
     * @param totalNanos leur durée cumulée
     * @param worstNanos le plus long
     * @param lastNanos le dernier
     * @param strikes ses fautes consécutives en cours
     * @param disabled vrai s'il est désactivé pour la session
     * @param reason pourquoi, {@code null} s'il est actif
     */
    public record Cost(String id, String owner, long calls, long totalNanos, long worstNanos, long lastNanos,
            int strikes, boolean disabled, String reason) {

        /** {@return la durée moyenne d'un appel, en nanosecondes ; 0 sans appel} */
        public double meanNanos() {
            return calls == 0 ? 0.0 : (double) totalNanos / calls;
        }
    }

    /**
     * Une écriture de variable par un contrôleur.
     *
     * @param controller l'identifiant du contrôleur
     * @param variable le nom de la variable
     * @param value la valeur
     */
    public record Write(String controller, String variable, double value) {}

    /**
     * Les sorties d'un tick, assemblées.
     *
     * @param modifiers les modificateurs du tick, dans l'ordre des contrôleurs
     * @param torques le couple de chaque roue, en N·m, sommé sur les contrôleurs
     * @param writes les écritures de variables, dans l'ordre des contrôleurs
     * @param journal les lignes à journaliser : exceptions, à débit limité, et désactivations
     */
    public record Outcome(List<Modifier> modifiers, float[] torques, List<Write> writes, List<String> journal) {
        /** Copie les listes et le tableau. */
        public Outcome {
            modifiers = List.copyOf(modifiers);
            torques = torques.clone();
            writes = List.copyOf(writes);
            journal = List.copyOf(journal);
        }

        @Override
        public float[] torques() {
            return torques.clone();
        }
    }

    /** La santé d'un contrôleur pendant la session. */
    private static final class Health {
        private final String owner;
        private long calls;
        private long totalNanos;
        private long worstNanos;
        private long lastNanos;
        private int strikes;
        private String reason;
        private long exceptionLoggedAt = Long.MIN_VALUE;
        private int exceptionsHushed;

        private Health(String owner) {
            this.owner = owner;
        }
    }

    private final Map<String, Health> health = new TreeMap<>();
    private final LongSupplier clock;
    private Limits limits;

    /**
     * @param limits le budget de chaque contrôleur
     * @param clock l'horloge, en nanosecondes
     */
    public ControllerRunner(Limits limits, LongSupplier clock) {
        this.limits = Objects.requireNonNull(limits);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * Change le budget (rechargement de la configuration) : il vaut dès l'appel suivant.
     *
     * @param limits le budget
     */
    public void limits(Limits limits) {
        this.limits = Objects.requireNonNull(limits);
    }

    /**
     * Fait tourner les contrôleurs d'un véhicule pour un tick.
     *
     * @param controllers ses contrôleurs, dans leur ordre : ceux de sa definition, puis ceux de l'API
     * @param view son état au début du tick
     * @return les sorties assemblées
     */
    public Outcome run(List<VehicleExtensions.Controller> controllers, VehicleSnapshot view) {
        List<Modifier> modifiers = new ArrayList<>();
        float[] torques = new float[view.wheels().size()];
        List<Write> writes = new ArrayList<>();
        List<String> journal = new ArrayList<>();
        for (VehicleExtensions.Controller controller : controllers) {
            Health state = health.computeIfAbsent(controller.id(), id -> new Health(controller.owner()));
            if (state.reason != null) {
                continue;
            }
            Output out = new Output(controller.id(), view);
            Throwable failure = null;
            long start = clock.getAsLong();
            try {
                controller.controller().control(view, out);
            } catch (Exception | LinkageError | StackOverflowError thrown) {
                failure = thrown;
            }
            long spent = clock.getAsLong() - start;
            out.closed = true;
            measure(controller, state, spent, failure, journal);
            if (failure != null) {
                continue;
            }
            modifiers.addAll(out.modifiers.values());
            for (int wheel = 0; wheel < torques.length; wheel++) {
                torques[wheel] += out.torques[wheel];
            }
            for (Map.Entry<String, Double> write : out.variables.entrySet()) {
                writes.add(new Write(controller.id(), write.getKey(), write.getValue()));
            }
        }
        return new Outcome(modifiers, torques, writes, journal);
    }

    /** Compte l'appel, sa durée et sa faute éventuelle ; désactive le contrôleur à la dernière tolérée. */
    private void measure(VehicleExtensions.Controller controller, Health state, long spent, Throwable failure,
            List<String> journal) {
        state.calls++;
        state.totalNanos += spent;
        state.worstNanos = Math.max(state.worstNanos, spent);
        state.lastNanos = spent;
        boolean over = spent > limits.budgetNanos();
        if (failure == null && !over) {
            state.strikes = 0;
            return;
        }
        state.strikes++;
        String fault = failure != null
                ? "exception " + failure.getClass().getName() + (failure.getMessage() == null ? ""
                        : " : " + failure.getMessage())
                : "appel de " + micros(spent) + " µs, au-delà du budget de " + micros(limits.budgetNanos()) + " µs";
        if (state.strikes >= limits.strikes()) {
            state.reason = state.strikes + " faute(s) consécutive(s), la dernière : " + fault;
            journal.add("E-1011 contrôleur " + controller.id() + " (mod " + controller.owner()
                    + ") désactivé pour la session : " + state.reason + " ; ses véhicules roulent sans ses sorties");
            return;
        }
        if (failure != null) {
            long now = clock.getAsLong();
            if (state.exceptionLoggedAt != Long.MIN_VALUE && now - state.exceptionLoggedAt < EXCEPTION_LOG_NANOS) {
                state.exceptionsHushed++;
                return;
            }
            journal.add("contrôleur " + controller.id() + " (mod " + controller.owner() + ") : " + fault
                    + " ; ses sorties de ce tick sont écartées (faute " + state.strikes + " sur " + limits.strikes()
                    + ")" + (state.exceptionsHushed == 0 ? "" : " ; " + state.exceptionsHushed
                            + " exception(s) tue(s) depuis la précédente"));
            state.exceptionLoggedAt = now;
            state.exceptionsHushed = 0;
        }
    }

    private static String micros(long nanos) {
        return String.format(java.util.Locale.ROOT, "%.1f", nanos / 1000.0);
    }

    /**
     * {@return le coût mesuré d'un contrôleur, vide s'il n'a jamais été appelé}
     *
     * @param id son identifiant
     */
    public Optional<Cost> cost(String id) {
        Health state = health.get(id);
        return state == null ? Optional.empty() : Optional.of(costOf(id, state));
    }

    /** {@return le coût de chaque contrôleur appelé pendant la session, triés par identifiant} */
    public List<Cost> costs() {
        List<Cost> costs = new ArrayList<>(health.size());
        health.forEach((id, state) -> costs.add(costOf(id, state)));
        return costs;
    }

    private static Cost costOf(String id, Health state) {
        return new Cost(id, state.owner, state.calls, state.totalNanos, state.worstNanos, state.lastNanos,
                state.strikes, state.reason != null, state.reason);
    }

    /** Ce qu'un contrôleur écrit pendant un appel. */
    private static final class Output implements ControllerOutput {
        private final String cause;
        private final VehicleSnapshot view;
        private final Map<String, Modifier> modifiers = new LinkedHashMap<>();
        private final float[] torques;
        private final Map<String, Double> variables = new LinkedHashMap<>();
        private boolean closed;

        private Output(String cause, VehicleSnapshot view) {
            this.cause = cause;
            this.view = view;
            torques = new float[view.wheels().size()];
        }

        private void open() {
            if (closed) {
                throw new IllegalStateException("sortie du contrôleur " + cause
                        + " : valable pendant son appel seulement");
            }
        }

        @Override
        public void modify(String parameter, Modifier.Operation operation, double amount) {
            open();
            Objects.requireNonNull(parameter, "parameter");
            Objects.requireNonNull(operation, "operation");
            if (!view.hasParameter(parameter)) {
                throw new IllegalArgumentException("paramètre « " + parameter + " » inconnu de ce véhicule");
            }
            if (!Double.isFinite(amount)) {
                throw new IllegalArgumentException("quantité non finie pour « " + parameter + " » : " + amount);
            }
            modifiers.put(parameter, new Modifier(cause, parameter, operation, amount));
        }

        @Override
        public void wheelTorque(int wheel, double torque) {
            open();
            Objects.checkIndex(wheel, torques.length);
            if (!Double.isFinite(torque) || Math.abs(torque) > Float.MAX_VALUE) {
                throw new IllegalArgumentException("couple de la roue " + wheel + " : " + torque
                        + ", non fini ou hors de ce que le moteur physique porte");
            }
            torques[wheel] = (float) torque;
        }

        @Override
        public void variable(String name, double value) {
            open();
            Objects.requireNonNull(name, "name");
            if (!view.hasVariable(name)) {
                throw new IllegalArgumentException("variable « " + name + " » inconnue de ce véhicule");
            }
            variables.put(name, value);
        }
    }
}
