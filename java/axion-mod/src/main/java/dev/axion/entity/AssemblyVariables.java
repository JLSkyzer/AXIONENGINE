package dev.axion.entity;

import dev.axion.definition.Seating;
import dev.axion.definition.Variables;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Les variables d'une assembly (ADR-163 D2), par instance : leurs valeurs, ce qui les a posées en dernier, et les
 * modes des commandes liées, appliqués sur la séquence des entrées de chaque siège. Logique pure, sans Forge ; le
 * serveur seul l'écrit, sur son thread autoritatif (INV-02).
 *
 * <p>Une valeur hors de sa variable — bornes ou type — est refusée et nommée, jamais tronquée. Les valeurs des
 * commandes liées et des interactions ont été vérifiées avec la definition ; celles de l'API et de la sauvegarde le
 * sont ici.
 *
 * <ul>
 *   <li>{@code toggle} : au front montant, passe de l'une à l'autre de ses deux valeurs ;</li>
 *   <li>{@code cycle} : au front montant, la valeur qui suit la courante dans sa liste, en boucle ; la première si la
 *       courante n'y est pas ;</li>
 *   <li>{@code hold} : tenue, sa seconde valeur ; relâchée — front descendant, siège quitté, entrées tues —, la
 *       première ;</li>
 *   <li>{@code press} : au front montant, sa seconde valeur pendant le tick qui suit, puis la première.</li>
 * </ul>
 *
 * <p>Plusieurs écrivains d'une même variable — deux commandes, l'API, une interaction — ne se composent pas : la
 * dernière écriture l'emporte.
 *
 * <p>Une variable peut régler un paramètre de l'assembly (ADR-167 D4) : son {@link Guard garde} juge alors chaque
 * écriture explicite — l'API, une commande pressée — avec ce qu'elle règle, et la refuse si un paramètre sortirait de
 * ses bornes. Un retour automatique — {@code hold} relâché, fin d'un {@code press} — passe toujours, comme un retrait.
 */
public final class AssemblyVariables {

    /** Ce qui a posé la valeur par défaut. */
    public static final String DEFAULT = "défaut";

    /** Ce qui a posé une valeur relue de la sauvegarde. */
    public static final String SAVE = "sauvegarde";

    private final Variables declared;
    private final double[] values;
    private final String[] writers;
    /** Les commandes tenues de chaque siège, à sa dernière entrée : un bit par commande. */
    private int[] held = new int[0];
    /**
     * Pour chaque variable qu'un {@code press} a posée, les débuts de tick qui restent avant qu'elle revienne à
     * {@link #pressRest} : 2 à la pose, 0 sans {@code press} en cours.
     */
    private final int[] pressTicks;
    private final double[] pressRest;
    private long version;
    private Guard guard = (index, next) -> null;

    /** Le garde des écritures (ADR-167 D4) : ce qu'une écriture de variable règle doit rester dans ses bornes. */
    @FunctionalInterface
    public interface Guard {
        /**
         * {@return pourquoi cette écriture est refusée, {@code null} si elle convient}
         *
         * @param index le rang de la variable écrite
         * @param next la valeur de chaque variable après l'écriture
         */
        String refusal(int index, double[] next);
    }

    /**
     * Les variables d'une assembly neuve, à leur valeur par défaut.
     *
     * @param declared les variables que sa definition déclare
     */
    public AssemblyVariables(Variables declared) {
        this.declared = declared;
        int size = declared.size();
        values = new double[size];
        writers = new String[size];
        pressTicks = new int[size];
        pressRest = new double[size];
        for (int i = 0; i < size; i++) {
            values[i] = declared.get(i).initial();
            writers[i] = DEFAULT;
        }
    }

    /**
     * Pose le garde des écritures explicites (ADR-167 D4).
     *
     * @param guard le garde
     */
    public void guard(Guard guard) {
        this.guard = Objects.requireNonNull(guard);
    }

    /** {@return les variables déclarées} */
    public Variables declared() {
        return declared;
    }

    /** {@return le nombre de variables} */
    public int size() {
        return values.length;
    }

    /**
     * {@return la valeur d'une variable}
     *
     * @param index son rang
     */
    public double get(int index) {
        return values[index];
    }

    /** {@return toutes les valeurs, dans l'ordre de la definition, copie} */
    public double[] values() {
        return values.clone();
    }

    /**
     * {@return ce qui l'a posée en dernier}
     *
     * @param index son rang
     */
    public String writer(int index) {
        return writers[index];
    }

    /** {@return un numéro qui change à chaque écriture qui change une valeur} */
    public long version() {
        return version;
    }

    /**
     * Pose une variable.
     *
     * @param index son rang
     * @param value la valeur
     * @param writer ce qui la pose, pour le diagnostic
     * @throws IllegalArgumentException si la variable refuse la valeur, ou si ce qu'elle règle sortirait de ses bornes
     *     (ADR-167 D4) ; rien n'est changé
     */
    public void set(int index, double value, String writer) {
        String refusal = declared.get(index).refusal(value);
        if (refusal == null) {
            refusal = judged(index, value);
        }
        if (refusal != null) {
            throw new IllegalArgumentException(refusal);
        }
        write(index, value, writer);
    }

    /** {@return pourquoi le garde refuse cette écriture, nommée par sa variable ; {@code null} s'il l'admet} */
    private String judged(int index, double value) {
        double[] next = values.clone();
        next[index] = value == 0.0 ? 0.0 : value;
        String refusal = guard.refusal(index, next);
        return refusal == null ? null : "variable « " + declared.get(index).name() + " » à " + value + " : " + refusal;
    }

    /** Écrit si le garde l'admet ; sinon, rien, et le refus s'ajoute à la liste. */
    private boolean guarded(int index, double value, String writer, List<String> refusals) {
        String refusal = judged(index, value);
        if (refusal != null) {
            refusals.add(refusal + " (" + writer + ")");
            return false;
        }
        write(index, value, writer);
        return true;
    }

    private void write(int index, double value, String writer) {
        // -0.0 et 0.0 sont une même valeur : la variable ne change pas, et ne part pas sur le réseau.
        double normal = value == 0.0 ? 0.0 : value;
        if (Double.compare(values[index], normal) != 0) {
            version++;
        }
        values[index] = normal;
        writers[index] = writer;
        pressTicks[index] = 0;
    }

    /**
     * Applique une entrée d'un siège (ADR-163 D2) : les commandes qu'il tient, comparées à celles de son entrée
     * précédente. Appelée dans l'ordre des entrées reçues.
     *
     * @param seat rang du siège
     * @param bindings les commandes que ce siège lie
     * @param mask les commandes tenues, un bit par commande (bit 0 : commande 1), déjà réduites à celles du siège
     * @param writer ce qui écrit, pour le diagnostic
     * @return les écritures que le garde a refusées (ADR-167 D4), à journaliser ; vide d'ordinaire
     */
    public List<String> input(int seat, List<Seating.Binding> bindings, int mask, String writer) {
        List<String> refusals = new ArrayList<>();
        if (seat >= held.length) {
            held = Arrays.copyOf(held, seat + 1);
        }
        int before = held[seat];
        held[seat] = mask;
        for (Seating.Binding binding : bindings) {
            int bit = 1 << (binding.command() - 1);
            boolean was = (before & bit) != 0;
            boolean now = (mask & bit) != 0;
            if (was == now) {
                continue;
            }
            String by = writer + " (commande " + binding.command() + ")";
            int variable = binding.variable();
            switch (binding.mode()) {
                case TOGGLE -> {
                    if (now) {
                        guarded(variable, values[variable] == binding.value(1) ? binding.value(0) : binding.value(1),
                                by, refusals);
                    }
                }
                case CYCLE -> {
                    if (now) {
                        guarded(variable, next(binding, values[variable]), by, refusals);
                    }
                }
                case HOLD -> {
                    if (now) {
                        guarded(variable, binding.value(1), by, refusals);
                    } else {
                        // Relâchée : un retour, qui passe toujours.
                        write(variable, binding.value(0), by);
                    }
                }
                case PRESS -> {
                    if (now && guarded(variable, binding.value(1), by, refusals)) {
                        pressTicks[variable] = 2;
                        pressRest[variable] = binding.value(0);
                    }
                }
                default -> throw new IllegalStateException();
            }
        }
        return refusals;
    }

    private static double next(Seating.Binding binding, double current) {
        for (int rank = 0; rank < binding.count(); rank++) {
            if (binding.value(rank) == current) {
                return binding.value((rank + 1) % binding.count());
            }
        }
        return binding.value(0);
    }

    /**
     * Le siège ne tient plus rien — il est quitté, ou ses entrées se taisent depuis {@code net.extrapolation_max_ms} :
     * ses commandes {@code hold} reviennent à leur première valeur. Sans commande tenue, rien ne change.
     *
     * @param seat rang du siège
     * @param bindings les commandes que ce siège lie
     * @param writer ce qui écrit, pour le diagnostic
     */
    public void release(int seat, List<Seating.Binding> bindings, String writer) {
        if (seat < held.length && held[seat] != 0) {
            input(seat, bindings, 0, writer);
        }
    }

    /**
     * Début d'un tick du serveur : une variable qu'un {@code press} a posée revient à sa première valeur au tick qui
     * suit celui où elle a valu la seconde.
     */
    public void tick() {
        for (int i = 0; i < pressTicks.length; i++) {
            if (pressTicks[i] > 0 && --pressTicks[i] == 0) {
                write(i, pressRest[i], writers[i]);
            }
        }
    }

    /**
     * L'état complet des variables à un instant, ce qui se cache compris : un client qui prédit le garde à chaque tick
     * de son historique, et y revient avant de rejouer (ADR-168 D6). Copie figée.
     *
     * @param values les valeurs
     * @param writers ce qui a posé chacune
     * @param held les commandes tenues de chaque siège, à sa dernière entrée
     * @param pressTicks pour chaque variable, les débuts de tick avant le retour d'un {@code press}
     * @param pressRest la valeur de retour de chaque {@code press} en cours
     * @param version le numéro d'écriture
     */
    public record State(double[] values, String[] writers, int[] held, int[] pressTicks, double[] pressRest,
            long version) {

        /** Copie les tableaux. */
        public State {
            values = values.clone();
            writers = writers.clone();
            held = held.clone();
            pressTicks = pressTicks.clone();
            pressRest = pressRest.clone();
        }

        @Override
        public double[] values() {
            return values.clone();
        }
    }

    /** {@return l'état complet, copie} */
    public State state() {
        return new State(values, writers, held, pressTicks, pressRest, version);
    }

    /**
     * Revient à un état gardé par {@link #state()} sur les mêmes variables.
     *
     * @param state l'état
     * @throws IllegalArgumentException s'il n'a pas le nombre de variables de cette table
     */
    public void resume(State state) {
        if (state.values.length != values.length) {
            throw new IllegalArgumentException(state.values.length + " valeurs pour " + values.length + " variables");
        }
        System.arraycopy(state.values, 0, values, 0, values.length);
        System.arraycopy(state.writers, 0, writers, 0, writers.length);
        System.arraycopy(state.pressTicks, 0, pressTicks, 0, pressTicks.length);
        System.arraycopy(state.pressRest, 0, pressRest, 0, pressRest.length);
        held = state.held.clone();
        version = state.version;
    }

    /**
     * Prend les valeurs que le serveur fait foi (ADR-168 D6) : chaque variable du masque qui en diffère la reçoit, sans
     * garde — le serveur l'a déjà jugée —, et un {@code press} en cours sur elle s'arrête. Les commandes tenues restent :
     * elles sont celles du client.
     *
     * @param serverValues les valeurs du serveur, une par variable déclarée
     * @param mask les variables qu'il a envoyées, un bit par rang
     * @param writer ce qui écrit, pour le diagnostic
     * @return vrai si une valeur a changé
     */
    public boolean rebase(double[] serverValues, long mask, String writer) {
        boolean changed = false;
        for (int i = 0; i < Math.min(values.length, serverValues.length) && i < Long.SIZE; i++) {
            if ((mask & 1L << i) != 0 && Double.compare(values[i], serverValues[i] == 0.0 ? 0.0 : serverValues[i]) != 0) {
                write(i, serverValues[i], writer);
                changed = true;
            }
        }
        return changed;
    }

    /**
     * {@return vrai si chaque variable du masque vaut celle du serveur}
     *
     * @param serverValues les valeurs du serveur, une par variable déclarée
     * @param mask les variables qu'il a envoyées, un bit par rang
     */
    public boolean agrees(double[] serverValues, long mask) {
        for (int i = 0; i < Math.min(values.length, serverValues.length) && i < Long.SIZE; i++) {
            if ((mask & 1L << i) != 0 && Double.compare(values[i], serverValues[i] == 0.0 ? 0.0 : serverValues[i]) != 0) {
                return false;
            }
        }
        return true;
    }

    /** {@return les valeurs des variables {@code persist}, par nom, dans l'ordre de la definition} */
    public Map<String, Double> persisted() {
        Map<String, Double> saved = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i++) {
            if (declared.get(i).persist()) {
                saved.put(declared.get(i).name(), values[i]);
            }
        }
        return saved;
    }

    /**
     * Reprend les valeurs d'une table d'avant — la definition a été recompilée : chaque variable qui existe encore
     * sous ce nom et accepte sa valeur la garde, avec son écrivain ; les autres restent à leur défaut. Les commandes
     * tenues repartent de rien.
     *
     * @param previous la table d'avant
     * @return les valeurs refusées, à journaliser
     */
    public List<String> adopt(AssemblyVariables previous) {
        List<String> refusals = new ArrayList<>();
        for (int old = 0; old < previous.size(); old++) {
            String name = previous.declared.get(old).name();
            int index = declared.indexOf(name);
            if (index < 0) {
                continue;
            }
            String refusal = declared.get(index).refusal(previous.values[old]);
            if (refusal != null) {
                refusals.add(refusal + ", laissée à son défaut");
                continue;
            }
            write(index, previous.values[old], previous.writers[old]);
        }
        return refusals;
    }

    /**
     * Rend les valeurs d'une sauvegarde aux variables {@code persist} qui les nomment. Une valeur que sa variable
     * refuse désormais — la definition a changé — laisse la variable à son défaut, et se dit ; un nom que la
     * definition ne déclare plus, ou qu'elle ne garde plus, est ignoré.
     *
     * @param saved les valeurs sauvegardées, par nom
     * @return les refus, à journaliser
     */
    public List<String> restore(Map<String, Double> saved) {
        List<String> refusals = new ArrayList<>();
        for (Map.Entry<String, Double> entry : saved.entrySet()) {
            int index = declared.indexOf(entry.getKey());
            if (index < 0 || !declared.get(index).persist()) {
                continue;
            }
            String refusal = declared.get(index).refusal(entry.getValue());
            if (refusal != null) {
                refusals.add(refusal + ", laissée à son défaut");
                continue;
            }
            write(index, entry.getValue(), SAVE);
        }
        return refusals;
    }
}
