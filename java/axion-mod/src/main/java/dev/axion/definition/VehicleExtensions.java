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

    private final Map<String, Controller> controllers;
    private final Map<String, Parameter> parameters;

    /**
     * @param controllers les contrôleurs, par identifiant
     * @param parameters les paramètres d'addon, par nom
     */
    public VehicleExtensions(Map<String, Controller> controllers, Map<String, Parameter> parameters) {
        this.controllers = Map.copyOf(controllers);
        this.parameters = Map.copyOf(parameters);
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

    /** {@return le nombre de contrôleurs et de paramètres enregistrés} */
    public int size() {
        return controllers.size() + parameters.size();
    }
}
