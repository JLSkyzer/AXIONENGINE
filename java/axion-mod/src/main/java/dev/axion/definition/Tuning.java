package dev.axion.definition;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.axion.api.assembly.Modifier;
import dev.axion.api.assembly.Parameters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Les paramètres réglables d'une definition (ADR-167 D1, D2) : pour chacun, sa base — la valeur de la definition,
 * surcharges de l'administrateur comprises, défauts de l'encodage compris —, son unité, la limite du schéma et la
 * plage que la definition se donne ({@code tuning.ranges}, schéma 4). Logique pure, sans Forge.
 *
 * <p>Un paramètre n'existe que si la definition déclare ce qu'il règle : {@code mass} quand elle déclare
 * {@code physics.mass} — sans elle, la masse vient des densités des colliders, que seul le natif connaît —, ceux d'un
 * véhicule quand elle a des roues (voir {@link VehicleSpec#parameters}).
 *
 * <p>Une variable de la definition peut régler un paramètre par un modificateur déclaré ({@code tuning.modifiers},
 * ADR-167 D4) : voir {@link Declared}.
 *
 * <p>Une definition nomme aussi ses contrôleurs ({@code vehicle.controllers}, ADR-167 D9) : les paramètres que leurs
 * mods ont enregistrés s'ajoutent à la table, après ceux d'AXION (Q6, Killian, 2026-11-12).
 *
 * @param parameters les paramètres : ceux d'AXION dans l'ordre fixe de {@link Parameters}, puis ceux des addons triés
 *     par nom
 * @param declared les modificateurs déclarés, dans l'ordre de la definition
 * @param controllers les contrôleurs que la definition nomme, dans son ordre
 */
public record Tuning(List<Parameter> parameters, List<Declared> declared, List<String> controllers) {

    /** Aucun paramètre. */
    public static final Tuning NONE = new Tuning(List.of(), List.of(), List.of());

    /** Contrôleurs qu'une definition nomme, au plus (ADR-167 D3). */
    public static final int MAX_CONTROLLERS = 8;

    /** Modificateurs déclarés par une definition, au plus (ADR-167 D4). */
    public static final int MAX_DECLARED = 64;

    /** La plus grande valeur qu'un {@code f32} du natif porte. */
    private static final double FLOAT_MAX = Float.MAX_VALUE;

    /** Le nom d'un modificateur déclaré : il fait la cause {@code <id de la definition>/<nom>}. */
    private static final Pattern DECLARED_NAME = Pattern.compile("[a-z0-9_.-]+");

    /** L'ordre de composition : celui des identifiants de cause. */
    private static final Comparator<Modifier> BY_CAUSE = Comparator.comparing(Modifier::cause);

    /** Copie les listes : une table compilée ne change plus. */
    public Tuning {
        parameters = List.copyOf(parameters);
        declared = List.copyOf(declared);
        controllers = List.copyOf(controllers);
    }

    /**
     * Les paramètres d'une definition sans modificateur déclaré ni contrôleur.
     *
     * @param parameters les paramètres, dans l'ordre fixe de {@link Parameters}
     */
    public Tuning(List<Parameter> parameters) {
        this(parameters, List.of(), List.of());
    }

    /**
     * Les paramètres d'une definition sans contrôleur.
     *
     * @param parameters les paramètres, dans l'ordre fixe de {@link Parameters}
     * @param declared les modificateurs déclarés
     */
    public Tuning(List<Parameter> parameters, List<Declared> declared) {
        this(parameters, declared, List.of());
    }

    /**
     * {@return cette table, avec les paramètres d'addon qui lui manquent} : ceux des mods des contrôleurs qu'une
     * instance a attachés par l'API (Q6). Ceux qu'elle a déjà restent à leur place.
     *
     * @param extra les paramètres d'addon à ajouter
     */
    public Tuning with(List<VehicleExtensions.Parameter> extra) {
        List<Parameter> all = new ArrayList<>(parameters);
        for (VehicleExtensions.Parameter parameter : extra) {
            if (indexOf(parameter.id()) < 0) {
                all.add(parameter.tuning());
            }
        }
        return all.size() == parameters.size() ? this : new Tuning(all, declared, controllers);
    }

    /**
     * Un modificateur déclaré (ADR-167 D4) : une variable de la definition règle un paramètre. Sa quantité suit la
     * valeur de la variable <strong>arrondie en {@code f32}</strong> — celle que les joueurs reçoivent —, si bien que
     * serveur et clients composent au bit près : {@code values[valeur - minimum]} pour une variable {@code int} ou
     * {@code bool}, {@code scale × valeur} sinon.
     *
     * @param cause {@code <id de la definition>/<nom>}
     * @param parameter le paramètre réglé
     * @param operation l'opération
     * @param variable le rang de la variable dans la definition
     * @param values la quantité pour chaque valeur de la variable, depuis son minimum ; {@code null} avec {@code scale}
     * @param scale la quantité par unité de la variable, sans {@code values}
     * @param minimum la plus petite valeur de la variable, d'où {@code values} part
     * @param initial la valeur par défaut de la variable
     */
    public record Declared(String cause, String parameter, Modifier.Operation operation, int variable, double[] values,
            double scale, double minimum, double initial) {

        /** Copie les quantités. */
        public Declared {
            values = values == null ? null : values.clone();
        }

        /**
         * {@return la quantité pour cette valeur de la variable}
         *
         * @param value la valeur de la variable, telle que le serveur la tient
         */
        public double amount(double value) {
            double sent = (float) value;
            return values != null ? values[(int) Math.round(sent - minimum)] : scale * sent;
        }

        /**
         * {@return le modificateur que cette valeur de la variable pose}
         *
         * @param value la valeur de la variable
         */
        public Modifier modifier(double value) {
            return new Modifier(cause, parameter, operation, amount(value));
        }

        @Override
        public double[] values() {
            return values == null ? null : values.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Declared that && cause.equals(that.cause) && parameter.equals(that.parameter)
                    && operation == that.operation && variable == that.variable && Arrays.equals(values, that.values)
                    && Double.compare(scale, that.scale) == 0 && Double.compare(minimum, that.minimum) == 0
                    && Double.compare(initial, that.initial) == 0;
        }

        @Override
        public int hashCode() {
            return 31 * (31 * cause.hashCode() + parameter.hashCode()) + Arrays.hashCode(values);
        }

        @Override
        public String toString() {
            return "Declared[" + cause + " " + parameter + " " + operation.key() + " "
                    + (values != null ? Arrays.toString(values) : "scale " + scale) + "]";
        }
    }

    /**
     * {@return la composition d'une base avec des modificateurs} (ADR-167 D3) : la seule fonction qui calcule une
     * valeur effective, à la compilation, au serveur et chez le client. Chaque somme et chaque produit se calculent
     * dans l'ordre des identifiants de cause.
     *
     * @param base la base
     * @param modifiers les modificateurs actifs, dans un ordre quelconque
     */
    public static double compose(double base, List<Modifier> modifiers) {
        List<Modifier> ordered = new ArrayList<>(modifiers);
        ordered.sort(BY_CAUSE);
        double added = 0.0;
        double rate = 0.0;
        double total = 1.0;
        for (Modifier modifier : ordered) {
            if (modifier.operation() == Modifier.Operation.ADD) {
                added += modifier.amount();
            }
        }
        for (Modifier modifier : ordered) {
            if (modifier.operation() == Modifier.Operation.MULTIPLY_BASE) {
                rate += modifier.amount();
            }
        }
        for (Modifier modifier : ordered) {
            if (modifier.operation() == Modifier.Operation.MULTIPLY_TOTAL) {
                total *= 1.0 + modifier.amount();
            }
        }
        return (base + added) * (1.0 + rate) * total;
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
     * Lit les paramètres d'une definition acceptée : leurs bases, leurs limites, la plage de {@code tuning.ranges}, et
     * ses modificateurs déclarés.
     *
     * @param id l'identifiant de la definition, qui préfixe la cause de ses modificateurs déclarés
     * @param root document de la definition
     * @return la table, {@link #NONE} sans paramètre
     */
    public static Tuning of(String id, JsonObject root) {
        return of(id, root, VehicleExtensions.NONE);
    }

    /**
     * Lit les paramètres d'une definition acceptée, ceux des mods de ses contrôleurs compris (ADR-167 D9, Q6).
     *
     * @param id l'identifiant de la definition
     * @param root document de la definition
     * @param extensions les contrôleurs et paramètres d'addon enregistrés
     * @return la table, {@link #NONE} sans paramètre ni contrôleur
     */
    public static Tuning of(String id, JsonObject root, VehicleExtensions extensions) {
        List<Parameter> found = new ArrayList<>();
        if (root.get("physics") instanceof JsonObject physics && physics.get("mass") != null) {
            // DM-08 : la masse déclarée d'une assembly, dans [0,001 ; 10⁶] kg.
            found.add(new Parameter(Parameters.MASS, "kg", physics.get("mass").getAsFloat(),
                    new Parameters.Range(0.001, true, 1e6, true), unbounded()));
        }
        if (VehicleSpec.declared(root)) {
            VehicleSpec.parameters(root, found);
        }
        List<String> controllers = controllers(root);
        for (VehicleExtensions.Parameter parameter : extensions.parametersFor(controllers)) {
            found.add(parameter.tuning());
        }
        if (found.isEmpty() && controllers.isEmpty()) {
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
        return new Tuning(bounded, declared(id, root), controllers);
    }

    /** {@return les contrôleurs qu'une definition nomme ({@code vehicle.controllers}), dans son ordre} */
    static List<String> controllers(JsonObject root) {
        if (!(root.get("vehicle") instanceof JsonObject vehicle)
                || !(vehicle.get("controllers") instanceof com.google.gson.JsonArray list)) {
            return List.of();
        }
        return list.asList().stream().map(JsonElement::getAsString).toList();
    }

    /** {@return les modificateurs déclarés d'une definition acceptée, dans son ordre} */
    private static List<Declared> declared(String id, JsonObject root) {
        if (!(root.get("tuning") instanceof JsonObject tuning)
                || !(tuning.get("modifiers") instanceof JsonObject modifiers)) {
            return List.of();
        }
        Variables variables = Variables.of(root);
        List<Declared> found = new ArrayList<>(modifiers.size());
        for (Map.Entry<String, JsonElement> entry : modifiers.entrySet()) {
            JsonObject modifier = entry.getValue().getAsJsonObject();
            int rank = variables.indexOf(modifier.get("variable").getAsString());
            Variables.Variable variable = variables.get(rank);
            found.add(new Declared(id + "/" + entry.getKey(), modifier.get("parameter").getAsString(),
                    operation(modifier), rank, values(modifier),
                    modifier.has("scale") ? modifier.get("scale").getAsDouble() : Double.NaN,
                    minimum(variable), variable.initial()));
        }
        return found;
    }

    private static Modifier.Operation operation(JsonObject modifier) {
        return Modifier.Operation.valueOf(modifier.get("operation").getAsString().toUpperCase(Locale.ROOT));
    }

    private static double[] values(JsonObject modifier) {
        if (!modifier.has("values")) {
            return null;
        }
        return modifier.getAsJsonArray("values").asList().stream().mapToDouble(JsonElement::getAsDouble).toArray();
    }

    /** {@return la plus petite valeur d'une variable, d'où {@code values} part : 0 pour un booléen} */
    private static double minimum(Variables.Variable variable) {
        return variable.type() == Variables.Type.BOOL ? 0.0 : variable.min();
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
     * Vérifie {@code tuning} (schéma 4) : chaque clé de {@code ranges} nomme un paramètre de la definition, et sa plage
     * contient sa base ; puis chaque modificateur déclaré ({@link #checkDeclared}).
     *
     * @param root document de la definition, ses règles de forme déjà passées
     * @param fault reçoit le chemin et la cause de chaque faute
     */
    static void check(JsonObject root, VehicleExtensions extensions,
            java.util.function.BiConsumer<String, String> fault) {
        List<String> controllers = controllers(root);
        if (!controllers.isEmpty() && !VehicleSpec.declared(root)) {
            fault.accept("$.vehicle.controllers", "des contrôleurs de véhicule demandent un véhicule : des roues");
        }
        for (int index = 0; index < controllers.size(); index++) {
            if (controllers.indexOf(controllers.get(index)) != index) {
                fault.accept("$.vehicle.controllers[" + index + "]", "contrôleur « " + controllers.get(index)
                        + " » déjà nommé");
            }
        }
        if (!(root.get("tuning") instanceof JsonObject tuning)) {
            return;
        }
        // L'identifiant ne sert qu'à nommer les causes ; la forme de la cause se juge sur le nom du modificateur.
        Tuning table = of("axion:verification", root, extensions);
        if (tuning.get("ranges") instanceof JsonObject ranges) {
            checkRanges(ranges, table, fault);
        }
        if (tuning.get("modifiers") instanceof JsonObject modifiers) {
            checkDeclared(root, modifiers, table, fault);
        }
    }

    private static void checkRanges(JsonObject ranges, Tuning table, java.util.function.BiConsumer<String, String> fault) {
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

    /**
     * Vérifie {@code tuning.modifiers} (ADR-167 D4) : chaque modificateur nomme un paramètre de la definition et une
     * variable {@code replicate: all} — chaque joueur compose ce paramètre (Q5, Killian, 2026-11-12) ; il donne sa
     * quantité par {@code values} (une par valeur d'une variable {@code int} ou {@code bool}) ou par {@code scale}
     * (variable bornée), pas les deux ; aucune quantité ne fait seule sortir le paramètre de ses bornes ; et aux
     * valeurs par défaut des variables, les modificateurs déclarés d'un paramètre l'y laissent ensemble.
     */
    private static void checkDeclared(JsonObject root, JsonObject modifiers, Tuning table,
            java.util.function.BiConsumer<String, String> fault) {
        Variables variables = Variables.of(root);
        boolean sound = true;
        for (Map.Entry<String, JsonElement> entry : modifiers.entrySet()) {
            String at = "$.tuning.modifiers." + entry.getKey();
            JsonObject modifier = entry.getValue().getAsJsonObject();
            String refusal = declaredRefusal(entry.getKey(), modifier, table, variables);
            if (refusal != null) {
                fault.accept(at, refusal);
                sound = false;
            }
        }
        if (!sound) {
            return;
        }
        for (int i = 0; i < table.size(); i++) {
            Parameter parameter = table.get(i);
            List<Modifier> atRest = table.declared().stream()
                    .filter(declared -> declared.parameter().equals(parameter.name()))
                    .map(declared -> declared.modifier(declared.initial()))
                    .toList();
            String refusal = atRest.isEmpty() ? null : parameter.refusal(compose(parameter.base(), atRest));
            if (refusal != null) {
                fault.accept("$.tuning.modifiers", "aux valeurs par défaut des variables, les modificateurs déclarés"
                        + " ensemble : " + refusal);
            }
        }
    }

    /** {@return pourquoi ce modificateur déclaré est refusé, {@code null} s'il convient} */
    private static String declaredRefusal(String name, JsonObject modifier, Tuning table, Variables variables) {
        if (!DECLARED_NAME.matcher(name).matches()) {
            return "nom « " + name + " » : minuscules, chiffres, « _ », « . » et « - » seulement — il fait la cause"
                    + " <id de la definition>/<nom>";
        }
        String parameterName = modifier.get("parameter").getAsString();
        int index = table.indexOf(parameterName);
        if (index < 0) {
            return "paramètre « " + parameterName + " » inconnu de cette definition ; elle a : "
                    + String.join(", ", table.names());
        }
        Variables.Variable variable = variables.get(variables.indexOf(modifier.get("variable").getAsString()));
        if (variable.replicate() != Variables.Replicate.ALL) {
            return "variable « " + variable.name() + " » en replicate: " + variable.replicate().name().toLowerCase(
                    Locale.ROOT) + " : une variable qui règle un paramètre doit être replicate: all, pour que chaque"
                    + " joueur le compose";
        }
        boolean hasValues = modifier.has("values");
        boolean hasScale = modifier.has("scale");
        if (hasValues == hasScale) {
            return "values ou scale, l'un des deux";
        }
        double[] amounts;
        if (hasValues) {
            if (variable.type() == Variables.Type.FLOAT) {
                return "values demande une variable int ou bool ; « " + variable.name() + " » est float : scale";
            }
            double count = variable.type() == Variables.Type.BOOL ? 2 : variable.max() - variable.min() + 1;
            amounts = values(modifier);
            if (amounts.length != count) {
                return "values : " + amounts.length + " quantité(s) pour " + (long) count + " valeur(s) de « "
                        + variable.name() + " »";
            }
        } else {
            if (!Double.isFinite(variable.min()) || !Double.isFinite(variable.max())) {
                return "scale demande une variable bornée ; « " + variable.name() + " » ne l'est pas";
            }
            double scale = modifier.get("scale").getAsDouble();
            amounts = new double[] {scale * (float) variable.min(), scale * (float) variable.max()};
        }
        Parameter parameter = table.get(index);
        Modifier.Operation operation = operation(modifier);
        for (double amount : amounts) {
            if (!Double.isFinite(amount)) {
                return "quantité non finie";
            }
            String refusal = parameter.refusal(compose(parameter.base(),
                    List.of(new Modifier("axion:verification/" + name, parameterName, operation, amount))));
            if (refusal != null) {
                return "avec la quantité " + amount + ", seule : " + refusal;
            }
        }
        return null;
    }
}
