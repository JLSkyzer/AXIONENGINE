package dev.axion.diag;

import dev.axion.api.assembly.Parameters;
import dev.axion.definition.Tuning;
import dev.axion.entity.AssemblyParameters;
import java.util.ArrayList;
import java.util.List;

/**
 * Le rapport de {@code /axion params} (C-71, ADR-167 D10) : pour chaque paramètre d'une assembly, sa valeur et sa
 * base, puis chaque modificateur posé avec son opération, sa quantité, son origine et, s'il est inactif, sa raison ;
 * enfin ses contrôleurs de véhicule, avec leur mod, leur coût mesuré et leur état (D9).
 *
 * <p>Composé hors de toute API de plateforme, comme {@link DefinitionsReport} : des lignes, vérifiables sans démarrer
 * le jeu.
 */
public final class ParametersReport {

    /** Le nombre maximal de lignes qu'une commande affiche dans le chat. */
    static final int MAX_LINES = 96;

    private ParametersReport() {
        throw new AssertionError("classe utilitaire, non instanciable");
    }

    /**
     * Compose le rapport.
     *
     * @param definition l'identifiant de la definition de l'assembly
     * @param table ses paramètres
     * @param only le seul paramètre à montrer, avec ses bornes ; {@code null} pour tous
     * @return les lignes du rapport
     */
    public static List<String> lines(String definition, AssemblyParameters table, String only) {
        return lines(definition, table, only, List.of(), List.of(), dev.axion.definition.VehicleExtensions.NONE);
    }

    /**
     * Compose le rapport, contrôleurs compris (ADR-167 D10) : ils se montrent quand aucun paramètre n'est précisé.
     *
     * @param definition l'identifiant de la definition de l'assembly
     * @param table ses paramètres
     * @param only le seul paramètre à montrer, avec ses bornes ; {@code null} pour tous
     * @param controllers ses contrôleurs, dans leur ordre
     * @param costs le coût mesuré de chaque contrôleur appelé pendant la session
     * @param extensions les contrôleurs que les mods enregistrent
     * @return les lignes du rapport
     */
    public static List<String> lines(String definition, AssemblyParameters table, String only, List<String> controllers,
            List<dev.axion.entity.ControllerRunner.Cost> costs, dev.axion.definition.VehicleExtensions extensions) {
        List<String> lines = new ArrayList<>();
        List<Parameters.Applied> applied = table.applied();
        lines.add("AXION ENGINE — " + definition + " : " + table.tuning().size() + " paramètre(s), "
                + applied.size() + " modificateur(s)");
        Tuning tuning = table.tuning();
        for (int i = 0; i < tuning.size(); i++) {
            Tuning.Parameter parameter = tuning.get(i);
            if (only != null && !only.equals(parameter.name())) {
                continue;
            }
            double value = table.value(i);
            String unit = parameter.unit().isEmpty() ? "" : " " + parameter.unit();
            lines.add("  " + parameter.name() + " = " + number(value) + unit
                    + (Double.compare(value, parameter.base()) != 0 ? " (base " + number(parameter.base()) + ")" : "")
                    + (only != null ? " ; bornes " + parameter.bounds() : ""));
            for (Parameters.Applied entry : applied) {
                if (entry.modifier().parameter().equals(parameter.name())) {
                    lines.add(line(entry));
                }
            }
        }
        for (Parameters.Applied entry : applied) {
            String parameter = entry.modifier().parameter();
            if (tuning.indexOf(parameter) < 0 && (only == null || only.equals(parameter))) {
                lines.add("  " + parameter + " — absent de la definition :");
                lines.add(line(entry));
            }
        }
        if (only == null && !controllers.isEmpty()) {
            lines.add("  contrôleurs (" + controllers.size() + ") :");
            for (String id : controllers) {
                lines.add(controller(id, costs, extensions));
            }
        }
        if (lines.size() > MAX_LINES) {
            int hidden = lines.size() - MAX_LINES;
            lines.subList(MAX_LINES, lines.size()).clear();
            lines.add("  … et " + hidden + " ligne(s) de plus : préciser un paramètre");
        }
        return List.copyOf(lines);
    }

    /** {@return la ligne d'un contrôleur : son mod, puis son état et son coût mesuré} */
    private static String controller(String id, List<dev.axion.entity.ControllerRunner.Cost> costs,
            dev.axion.definition.VehicleExtensions extensions) {
        var registered = extensions.controller(id);
        if (registered.isEmpty()) {
            return "    " + id + " — inactif : aucun mod ne l'enregistre";
        }
        String head = "    " + id + " (mod " + registered.get().owner() + ") — ";
        var cost = costs.stream().filter(entry -> entry.id().equals(id)).findFirst();
        if (cost.isEmpty()) {
            return head + "actif, pas encore appelé";
        }
        var measured = cost.get();
        String figures = measured.calls() + " appel(s), moyenne " + micros(measured.meanNanos()) + " µs, pire "
                + micros(measured.worstNanos()) + " µs";
        return head + (measured.disabled() ? "désactivé (E-1011) : " + measured.reason() + " ; " : "actif, ")
                + figures;
    }

    private static String micros(double nanos) {
        return String.format(java.util.Locale.ROOT, "%.1f", nanos / 1000.0);
    }

    /** {@return la ligne d'un modificateur posé} */
    private static String line(Parameters.Applied entry) {
        return "    " + (entry.active() ? "" : "inactif — ") + entry.modifier().cause() + " "
                + entry.modifier().operation().key() + " " + number(entry.modifier().amount()) + " ("
                + entry.origin() + ")" + (entry.active() ? "" : " : " + entry.reason());
    }

    private static String number(double value) {
        return value == Math.rint(value) && Math.abs(value) < 1e15
                ? Long.toString((long) value)
                : Double.toString(value);
    }
}
