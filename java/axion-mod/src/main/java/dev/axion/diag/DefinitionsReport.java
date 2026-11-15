package dev.axion.diag;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.axion.definition.Definition;
import dev.axion.definition.DefinitionRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Rapports de {@code /axion defs list | info} (C-71, CAP-42) : voir en jeu les definitions chargées, et pourquoi une
 * autre a été refusée.
 *
 * <p>Composés hors de toute API de plateforme, comme {@link AssetsReport} : des listes de lignes, vérifiables sans
 * démarrer le jeu.
 */
public final class DefinitionsReport {

    /** Le nombre maximal de refus, et d'identifiants par mod, qu'une commande affiche dans le chat. */
    static final int MAX_LINES = 32;

    private DefinitionsReport() {
        throw new AssertionError("classe utilitaire, non instanciable");
    }

    /**
     * Compose {@code /axion defs list} : le compte, les refus d'abord — c'est eux qu'on cherche —, puis les
     * definitions chargées, mod par mod.
     *
     * @param registry le registre effectif des definitions
     * @return les lignes du rapport
     */
    public static List<String> list(DefinitionRegistry registry) {
        List<String> lines = new ArrayList<>();
        List<String> refusals = registry.refusals();
        lines.add("AXION ENGINE — definitions : " + registry.size() + " chargée(s), " + refusals.size()
                + " refusée(s)");
        refusals.stream().limit(MAX_LINES).forEach(refusal -> lines.add("  refusée : " + refusal));
        if (refusals.size() > MAX_LINES) {
            lines.add("  … et " + (refusals.size() - MAX_LINES) + " autre(s) refus : voir le journal du jeu");
        }
        Map<String, List<String>> parMod = new TreeMap<>();
        for (String id : registry.ids()) {
            int colon = id.indexOf(':');
            parMod.computeIfAbsent(id.substring(0, colon), mod -> new ArrayList<>()).add(id.substring(colon + 1));
        }
        parMod.forEach((mod, noms) -> {
            String montres = String.join(", ", noms.subList(0, Math.min(noms.size(), MAX_LINES)));
            lines.add("  " + mod + " (" + noms.size() + ") : " + montres
                    + (noms.size() > MAX_LINES ? ", …" : ""));
        });
        return List.copyOf(lines);
    }

    /**
     * Compose {@code /axion defs info <id>} : ce qu'est une definition chargée, ou pourquoi elle a été refusée.
     *
     * @param registry le registre effectif des definitions
     * @param id identifiant, {@code <modId>:<chemin>}
     * @param source pour la clé d'une entrée enregistrée, son propriétaire et sa source, si on les connaît
     * @return les lignes du rapport
     */
    public static List<String> info(DefinitionRegistry registry, String id, Function<String, Optional<String>> source) {
        Optional<Definition> loaded = registry.get(id);
        if (loaded.isPresent()) {
            Definition definition = loaded.get();
            List<String> lines = new ArrayList<>();
            lines.add("AXION ENGINE — " + id);
            lines.add("  genre : " + definition.kind());
            lines.add("  modèle : " + definition.asset());
            lines.add("  schéma : " + definition.schema());
            lines.add("  enregistrée par : " + source.apply(definition.location()).orElse(definition.location()));
            lines.add("  empreinte : " + definition.sha256().substring(0, 16));
            seated(definition.root(), lines);
            return List.copyOf(lines);
        }
        return registry.refusal(id)
                .map(refusal -> List.of("AXION : " + id + " refusée — " + refusal))
                .orElse(List.of("AXION : aucune definition « " + id + " », ni chargée ni refusée : aucun mod ne "
                        + "l'enregistre (vérifier le modId et le chemin)"));
    }

    /**
     * Les vues et le tableau de bord d'une definition qui a des sièges (ADR-170, axe Diagnostic) : ce que voit son
     * occupant, déclaré ou par défaut.
     */
    static void seated(JsonObject root, List<String> lines) {
        if (!(root.get("seats") instanceof JsonArray seats) || seats.isEmpty()) {
            return;
        }
        if (root.get("cameras") instanceof JsonArray cameras) {
            List<String> views = new ArrayList<>();
            for (JsonElement camera : cameras) {
                JsonObject view = camera.getAsJsonObject();
                views.add(view.get("name").getAsString() + " (" + view.get("kind").getAsString() + ")");
            }
            lines.add("  vues : " + String.join(", ", views));
        } else {
            lines.add("  vues : cockpit et poursuite, par défaut");
        }
        JsonObject dashboard = root.get("dashboard") instanceof JsonObject declared ? declared : new JsonObject();
        List<String> parts = new ArrayList<>();
        if (!dashboard.has("default") || dashboard.get("default").getAsBoolean()) {
            parts.add("défaut");
        }
        if (dashboard.get("gauges") instanceof JsonArray gauges && !gauges.isEmpty()) {
            List<String> names = new ArrayList<>();
            gauges.forEach(gauge -> names.add(gauge.getAsJsonObject().get("name").getAsString()));
            parts.add("jauges " + String.join(", ", names));
        }
        if (dashboard.get("widgets") instanceof JsonArray widgets && !widgets.isEmpty()) {
            List<String> names = new ArrayList<>();
            widgets.forEach(widget -> names.add(widget.getAsString()));
            parts.add("widgets " + String.join(", ", names));
        }
        String seen = dashboard.get("seats") instanceof JsonArray named
                ? " ; sièges " + String.join(", ", named.asList().stream().map(JsonElement::getAsString).toList())
                : " ; conducteur seul";
        lines.add("  tableau de bord : " + (parts.isEmpty() ? "aucun" : String.join(" ; ", parts) + seen));
    }
}
