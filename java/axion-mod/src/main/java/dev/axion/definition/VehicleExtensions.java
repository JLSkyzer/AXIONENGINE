package dev.axion.definition;

import dev.axion.api.assembly.Parameters;
import dev.axion.api.vehicle.VehicleController;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Ce que les mods ont enregistré pour les véhicules (ADR-167 D1, D9) : les contrôleurs, registre
 * {@code axion:vehicle_controller} (23.3), et les paramètres propres à chaque mod. Logique pure, sans Forge.
 *
 * <p>L'enregistrement en publie une instance d'un bloc ({@link #publish}) ; le runtime, le chargement des definitions et
 * l'entité la lisent ({@link #current}). Elle ne change plus ensuite : un rechargement en publie une autre.
 */
public final class VehicleExtensions {

    /** Rien d'enregistré. */
    public static final VehicleExtensions NONE = new VehicleExtensions(Map.of(), Map.of());

    private static volatile VehicleExtensions current = NONE;

    /**
     * Un contrôleur enregistré.
     *
     * @param id son identifiant, {@code <modId>:<chemin>}
     * @param owner le mod qui l'a enregistré
     * @param controller le contrôleur
     */
    public record Controller(String id, String owner, VehicleController controller) {
        /** Refuse un champ absent. */
        public Controller {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(controller, "controller");
        }
    }

    /**
     * Un paramètre d'addon enregistré (ADR-167 D1) : il existe sur un véhicule dès qu'un contrôleur de son mod y est
     * attaché (Q6, Killian, 2026-11-12).
     *
     * @param id son nom, {@code <modId>:<chemin>}
     * @param owner le mod qui l'a enregistré
     * @param unit son unité, vide pour un facteur
     * @param base sa valeur sans réglage
     * @param limit les valeurs qu'il peut prendre
     */
    public record Parameter(String id, String owner, String unit, double base, Parameters.Range limit) {
        /** Refuse un champ absent, une base non finie ou hors de ses bornes. */
        public Parameter {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(unit, "unit");
            Objects.requireNonNull(limit, "limit");
            if (!Double.isFinite(base) || !limit.contains(base)) {
                throw new IllegalArgumentException("paramètre « " + id + " » : la base " + base + " doit être finie et"
                        + " dans " + limit);
            }
        }

        /** {@return le paramètre tel qu'une table d'instance le compose : ses bornes sont sa limite} */
        public Tuning.Parameter tuning() {
            return new Tuning.Parameter(id, unit, base, limit,
                    new Parameters.Range(Double.NEGATIVE_INFINITY, false, Double.POSITIVE_INFINITY, false));
        }
    }

    /**
     * Un widget du tableau de bord enregistré (ADR-170 D6).
     *
     * @param id son identifiant, {@code <modId>:<chemin>}
     * @param owner le mod qui l'a enregistré
     * @param widget le widget
     */
    public record Widget(String id, String owner, dev.axion.api.hud.DashboardWidget widget) {
        /** Refuse un champ absent. */
        public Widget {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(widget, "widget");
        }
    }

    private final Map<String, Controller> controllers;
    private final Map<String, Parameter> parameters;
    private final Map<String, Widget> widgets;
    private final boolean codeAbsent;

    /**
     * @param controllers les contrôleurs, par identifiant
     * @param parameters les paramètres d'addon, par nom
     */
    public VehicleExtensions(Map<String, Controller> controllers, Map<String, Parameter> parameters) {
        this(controllers, parameters, Map.of());
    }

    /**
     * @param controllers les contrôleurs, par identifiant
     * @param parameters les paramètres d'addon, par nom
     * @param widgets les widgets du tableau de bord, par identifiant (ADR-170 D6)
     */
    public VehicleExtensions(Map<String, Controller> controllers, Map<String, Parameter> parameters,
            Map<String, Widget> widgets) {
        this(controllers, parameters, widgets, false);
    }

    private VehicleExtensions(Map<String, Controller> controllers, Map<String, Parameter> parameters,
            Map<String, Widget> widgets, boolean codeAbsent) {
        this.controllers = Map.copyOf(controllers);
        this.parameters = Map.copyOf(parameters);
        this.widgets = Map.copyOf(widgets);
        this.codeAbsent = codeAbsent;
    }

    /**
     * {@return des extensions vides, dont le code n'est pas chargé} : celles de la vérification hors jeu (CAP-42,
     * R-1757). Un contrôleur, un widget ou un paramètre d'addon que rien n'enregistre n'y refuse pas la definition :
     * il n'a pas pu être jugé, et la validation le dit en avertissement. En jeu, le code est là et il se juge.
     */
    public static VehicleExtensions withoutCode() {
        return new VehicleExtensions(Map.of(), Map.of(), Map.of(), true);
    }

    /**
     * Une action ou une interaction qu'un mod enregistre (ADR-174 D6).
     *
     * @param id son identifiant, {@code <modId>:<nom>}
     * @param owner le mod qui la livre
     * @param handler un {@link dev.axion.api.assembly.ActionHandler} ou un
     *     {@link dev.axion.api.assembly.InteractionHandler}
     */
    public record Handler(String id, String owner, Object handler) {

        /** Refuse un champ absent. */
        public Handler {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(handler, "handler");
        }
    }

    private Map<String, Handler> actions = Map.of();
    private Map<String, Handler> interactions = Map.of();

    /**
     * {@return ces extensions, avec les actions et les interactions des mods (ADR-174 D6)}
     *
     * @param registeredActions les actions, par identifiant
     * @param registeredInteractions les interactions, par identifiant
     */
    public VehicleExtensions withHandlers(Map<String, Handler> registeredActions,
            Map<String, Handler> registeredInteractions) {
        VehicleExtensions copy = new VehicleExtensions(controllers, parameters, widgets, codeAbsent);
        copy.actions = Map.copyOf(registeredActions);
        copy.interactions = Map.copyOf(registeredInteractions);
        return copy;
    }

    /**
     * {@return une action enregistrée}
     *
     * @param id son identifiant
     */
    public Optional<Handler> action(String id) {
        return Optional.ofNullable(actions.get(id));
    }

    /**
     * {@return une interaction enregistrée}
     *
     * @param id son identifiant
     */
    public Optional<Handler> interaction(String id) {
        return Optional.ofNullable(interactions.get(id));
    }

    /**
     * {@return l'interaction à qui AXION délègue {@code open_container} : la première qu'un mod enregistre sous ce nom,
     * dans l'ordre des mods}
     */
    public Optional<Handler> container() {
        return interactions.values().stream().filter(handler -> handler.id().endsWith(":open_container"))
                .sorted(java.util.Comparator.comparing(Handler::id)).findFirst();
    }

    /** {@return vrai hors jeu : le code des mods n'est pas chargé, leurs extensions ne se jugent pas} */
    public boolean codeAbsent() {
        return codeAbsent;
    }

    /**
     * {@return un widget enregistré}
     *
     * @param id son identifiant
     */
    public Optional<Widget> widget(String id) {
        return Optional.ofNullable(widgets.get(id));
    }

    /** {@return celles que l'enregistrement a publiées en dernier, {@link #NONE} avant} */
    public static VehicleExtensions current() {
        return current;
    }

    /**
     * Publie ce que les mods ont enregistré.
     *
     * @param extensions les contrôleurs et paramètres
     */
    public static void publish(VehicleExtensions extensions) {
        current = Objects.requireNonNull(extensions);
    }

    /**
     * {@return un contrôleur enregistré}
     *
     * @param id son identifiant
     */
    public Optional<Controller> controller(String id) {
        return Optional.ofNullable(controllers.get(id));
    }

    /** {@return tous les contrôleurs, triés par identifiant} */
    public Collection<Controller> controllers() {
        return new TreeMap<>(controllers).values();
    }

    /**
     * {@return les paramètres que les mods de ces contrôleurs ont enregistrés, triés par nom, chacun une fois} : ceux qui
     * existent sur un véhicule qui porte ces contrôleurs (Q6).
     *
     * @param attached les identifiants des contrôleurs attachés
     */
    public List<Parameter> parametersFor(Collection<String> attached) {
        List<String> owners = attached.stream()
                .map(controllers::get)
                .filter(Objects::nonNull)
                .map(Controller::owner)
                .distinct()
                .toList();
        List<Parameter> found = new ArrayList<>();
        for (Parameter parameter : new TreeMap<>(parameters).values()) {
            if (owners.contains(parameter.owner())) {
                found.add(parameter);
            }
        }
        return found;
    }

    /** {@return le nombre de contrôleurs, de paramètres et de widgets enregistrés} */
    public int size() {
        return controllers.size() + parameters.size() + widgets.size();
    }
}
