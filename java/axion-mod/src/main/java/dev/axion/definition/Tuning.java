package dev.axion.definition;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.axion.api.assembly.Parameters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Les paramètres réglables d'une definition (ADR-167 D1, D2) : pour chacun, sa base — la valeur de la definition,
 * surcharges de l'administrateur comprises, défauts de l'encodage compris —, son unité, la limite du schéma et la
 * plage que la definition se donne ({@code tuning.ranges}, schéma 4). Logique pure, sans Forge.
 *
 * <p>Un paramètre n'existe que si la definition déclare ce qu'il règle : {@code mass} quand elle déclare
 * {@code physics.mass} — sans elle, la masse vient des densités des colliders, que seul le natif connaît —, ceux d'un
 * véhicule quand elle a des roues (voir {@link VehicleSpec#parameters}).
 *
 * @param parameters les paramètres, dans l'ordre fixe de {@link Parameters}
 */
public record Tuning(List<Parameter> parameters) {

    /** Aucun paramètre. */
    public static final Tuning NONE = new Tuning(List.of());

    /** La plus grande valeur qu'un {@code f32} du natif porte. */
    private static final double FLOAT_MAX = Float.MAX_VALUE;

    /** Copie la liste : une table compilée ne change plus. */
    public Tuning {
        parameters = List.copyOf(parameters);
    }

    /**
     * Un paramètre réglable.
     *
     * @param name son nom ({@link Parameters})
     * @param unit son unité, vide pour un rapport ou un coefficient
     * @param base la valeur de la definition
     * @param limit la limite du schéma : une valeur hors d'elle donnerait une definition invalide
     * @param range la plage que la definition se donne, toute la droite sans {@code tuning.ranges}
     */
    public record Parameter(String name, String unit, double base, Parameters.Range limit, Parameters.Range range) {

        /** {@return les bornes effectives : l'intersection de la limite et de la plage} */
        public Parameters.Range bounds() {
            boolean lowFromRange = range.min() > limit.min()
                    || (range.min() == limit.min() && !range.minInclusive());
            boolean highFromRange = range.max() < limit.max()
                    || (range.max() == limit.max() && !range.maxInclusive());
            return new Parameters.Range(
                    lowFromRange ? range.min() : limit.min(),
                    lowFromRange ? range.minInclusive() : limit.minInclusive(),
                    highFromRange ? range.max() : limit.max(),
                    highFromRange ? range.maxInclusive() : limit.maxInclusive());
        }

        /**
         * {@return pourquoi cette valeur ne convient pas, {@code null} si elle convient} Elle doit tenir dans un
         * {@code f32}, le type du natif, et c'est ce {@code f32} qui doit être dans ses bornes : la valeur que le natif
         * reçoit, comme celle d'une definition.
         *
         * @param value la valeur effective envisagée
         */
        public String refusal(double value) {
            if (!Double.isFinite(value) || Math.abs(value) > FLOAT_MAX) {
                return "« " + name + " » vaudrait " + value + ", hors de ce que le moteur physique porte";
            }
            double sent = (float) value;
            if (!limit.contains(sent)) {
                return "« " + name + " » vaudrait " + value + (unit.isEmpty() ? "" : " " + unit) + ", hors de "
                        + limit + " (limite du schéma)";
            }
            if (!range.contains(sent)) {
                return "« " + name + " » vaudrait " + value + (unit.isEmpty() ? "" : " " + unit) + ", hors de "
                        + range + " (plage de la definition, tuning.ranges)";
            }
            return null;
        }
    }

    /** {@return toute la droite : la plage d'un paramètre que la definition ne borne pas} */
    static Parameters.Range unbounded() {
        return new Parameters.Range(Double.NEGATIVE_INFINITY, false, Double.POSITIVE_INFINITY, false);
    }

    /**
     * {@return un paramètre au moins égal à {@code min}, ou strictement au-dessus}
     *
     * @param name son nom
     * @param unit son unité
     * @param base sa base
     * @param min la borne basse
     * @param inclusive vrai si elle est admise
     */
    static Parameter above(String name, String unit, double base, double min, boolean inclusive) {
        return new Parameter(name, unit, base,
                new Parameters.Range(min, inclusive, Double.POSITIVE_INFINITY, false), unbounded());
    }

    /** {@return le nombre de paramètres} */
    public int size() {
        return parameters.size();
    }

    /**
     * {@return le rang d'un paramètre, {@code -1} si la definition ne l'a pas}
     *
     * @param name son nom
     */
    public int indexOf(String name) {
        for (int i = 0; i < parameters.size(); i++) {
            if (parameters.get(i).name().equals(name)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * {@return le paramètre de ce rang}
     *
     * @param index son rang
     */
    public Parameter get(int index) {
        return parameters.get(index);
    }

    /** {@return les noms des paramètres, dans leur ordre} */
    public List<String> names() {
        return parameters.stream().map(Parameter::name).toList();
    }

    /**
     * Lit les paramètres d'une definition acceptée : leurs bases, leurs limites, et la plage de {@code tuning.ranges}.
     *
     * @param root document de la definition
     * @return la table, {@link #NONE} sans paramètre
     */
    public static Tuning of(JsonObject root) {
        List<Parameter> found = new ArrayList<>();
        if (root.get("physics") instanceof JsonObject physics && physics.get("mass") != null) {
            // DM-08 : la masse déclarée d'une assembly, dans [0,001 ; 10⁶] kg.
            found.add(new Parameter(Parameters.MASS, "kg", physics.get("mass").getAsFloat(),
                    new Parameters.Range(0.001, true, 1e6, true), unbounded()));
        }
        if (VehicleSpec.declared(root)) {
            VehicleSpec.parameters(root, found);
        }
        if (found.isEmpty()) {
            return NONE;
        }
        JsonObject ranges = root.get("tuning") instanceof JsonObject tuning
                && tuning.get("ranges") instanceof JsonObject declared ? declared : new JsonObject();
        List<Parameter> bounded = new ArrayList<>(found.size());
        for (Parameter parameter : found) {
            bounded.add(ranges.get(parameter.name()) instanceof JsonObject range
                    ? new Parameter(parameter.name(), parameter.unit(), parameter.base(), parameter.limit(),
                            range(range))
                    : parameter);
        }
        return new Tuning(bounded);
    }

    /** {@return la plage d'une entrée de {@code tuning.ranges} : ses bornes, admises} */
    static Parameters.Range range(JsonObject range) {
        return new Parameters.Range(
                range.has("min") ? range.get("min").getAsDouble() : Double.NEGATIVE_INFINITY,
                range.has("min"),
                range.has("max") ? range.get("max").getAsDouble() : Double.POSITIVE_INFINITY,
                range.has("max"));
    }

    /**
     * Vérifie {@code tuning.ranges} (schéma 4) : chaque clé nomme un paramètre de la definition, et sa plage contient
     * sa base.
     *
     * @param root document de la definition, ses règles de forme déjà passées
     * @param fault reçoit le chemin et la cause de chaque faute
     */
    static void check(JsonObject root, java.util.function.BiConsumer<String, String> fault) {
        if (!(root.get("tuning") instanceof JsonObject tuning)
                || !(tuning.get("ranges") instanceof JsonObject ranges)) {
            return;
        }
        Tuning table = of(root);
        for (Map.Entry<String, JsonElement> entry : ranges.entrySet()) {
            String at = "$.tuning.ranges." + entry.getKey();
            int index = table.indexOf(entry.getKey());
            if (index < 0) {
                fault.accept(at, "paramètre « " + entry.getKey() + " » inconnu de cette definition ; elle a : "
                        + String.join(", ", table.names()));
                continue;
            }
            Parameters.Range range = range(entry.getValue().getAsJsonObject());
            Parameter parameter = table.get(index);
            if (range.min() > range.max()) {
                fault.accept(at, "min dépasse max");
            } else if (!range.contains(parameter.base())) {
                fault.accept(at, "la plage " + range + " doit contenir la base, " + parameter.base());
            }
        }
    }
}
