package dev.axion.entity;

import dev.axion.definition.Seating;
import dev.axion.definition.Variables;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
     * @throws IllegalArgumentException si la variable refuse la valeur ; rien n'est changé
     */
    public void set(int index, double value, String writer) {
        String refusal = declared.get(index).refusal(value);
        if (refusal != null) {
            throw new IllegalArgumentException(refusal);
        }
        write(index, value, writer);
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
     */
    public void input(int seat, List<Seating.Binding> bindings, int mask, String writer) {
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
                        write(variable, values[variable] == binding.value(1) ? binding.value(0) : binding.value(1), by);
                    }
                }
                case CYCLE -> {
                    if (now) {
                        write(variable, next(binding, values[variable]), by);
                    }
                }
                case HOLD -> write(variable, binding.value(now ? 1 : 0), by);
                case PRESS -> {
                    if (now) {
                        write(variable, binding.value(1), by);
                        pressTicks[variable] = 2;
                        pressRest[variable] = binding.value(0);
                    }
                }
                default -> throw new IllegalStateException();
            }
        }
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
