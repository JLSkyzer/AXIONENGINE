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
            sockets(definition.root(), lines);
            content(definition.root(), lines);
            return List.copyOf(lines);
        }
        return registry.refusal(id)
                .map(refusal -> List.of("AXION : " + id + " refusée — " + refusal))
                .orElse(List.of("AXION : aucune definition « " + id + " », ni chargée ni refusée : aucun mod ne "
                        + "l'enregistre (vérifier le modId et le chemin)"));
    }

    /**
     * Ce qu'une definition émet et voit (ADR-174, axe Diagnostic) : ses événements et à qui chacun part, ses capteurs,
     * et les actions et interactions de mods qu'elle nomme.
     */
    static void content(JsonObject root, List<String> lines) {
        dev.axion.definition.Revisions revisions = dev.axion.definition.Revisions.of(root);
        if (revisions.revision() > 1 || !revisions.migrations().isEmpty() || !revisions.replaces().isEmpty()) {
            lines.add("  révision : " + revisions.revision() + (revisions.migrations().isEmpty() ? ""
                    : ", migrations depuis " + revisions.migrations().stream()
                            .map(migration -> String.valueOf(migration.from())).collect(java.util.stream.Collectors.joining(", ")))
                    + (revisions.replaces().isEmpty() ? "" : ", remplace " + String.join(", ", revisions.replaces())));
        }
        dev.axion.definition.ContentEvents events = dev.axion.definition.ContentEvents.of(root);
        if (!events.declared().isEmpty()) {
            List<String> named = new ArrayList<>();
            for (dev.axion.definition.ContentEvents.Event event : events.declared()) {
                named.add(event.name() + " (" + event.replicate().name().toLowerCase(java.util.Locale.ROOT) + ")");
            }
            lines.add("  événements : " + String.join(", ", named));
        }
        for (dev.axion.definition.Sensors.Sensor sensor : dev.axion.definition.Sensors.of(root).declared()) {
            float[] size = sensor.size();
            lines.add("  capteur " + sensor.name() + " : au node " + sensor.node() + ", "
                    + (sensor.shape() == dev.axion.definition.Sensors.Shape.SPHERE ? "sphère de rayon " + size[0]
                            : "boîte " + size[0] + " × " + size[1] + " × " + size[2]));
        }
        java.util.Set<String> mods = new java.util.TreeSet<>();
        if (root.get("interaction") instanceof JsonArray interactions) {
            for (JsonElement element : interactions) {
                if (!(element instanceof JsonObject interaction)) {
                    continue;
                }
                if (interaction.get("action") instanceof com.google.gson.JsonPrimitive action
                        && action.getAsString().contains(":")) {
                    mods.add("interaction " + action.getAsString());
                }
                if (interaction.get("actions") instanceof JsonArray steps) {
                    for (JsonElement step : steps) {
                        if (step instanceof JsonObject object) {
                            object.keySet().stream().filter(key -> key.contains(":"))
                                    .forEach(key -> mods.add("action " + key));
                        }
                    }
                }
            }
        }
        if (!mods.isEmpty()) {
            lines.add("  de mods : " + String.join(", ", mods));
        }
    }

    /**
     * Les sockets d'une definition (ADR-171, axe Diagnostic) : pour chacun, son nom, les types d'attache qu'il accepte, et
     * ce qu'il déclare de ses réglages — étiquette et partenaires, seuils, désalignement, cône, corde, treuil, aimant,
     * transferts. Ce qu'il ne déclare pas prend le défaut d'ADR-171 D2, que le rapport ne répète pas.
     */
    static void sockets(JsonObject root, List<String> lines) {
        if (!(root.get("sockets") instanceof JsonArray sockets) || sockets.isEmpty()) {
            return;
        }
        for (JsonElement element : sockets) {
            if (!(element instanceof JsonObject socket) || !socket.has("name")) {
                continue;
            }
            List<String> parts = new ArrayList<>();
            if (socket.get("accepts") instanceof JsonArray accepts) {
                parts.add("accepte " + String.join(", ", accepts.asList().stream().map(JsonElement::getAsString)
                        .toList()));
            }
            for (String key : List.of("tag", "max_force", "max_torque", "misalignment", "anchor")) {
                if (socket.has(key)) {
                    parts.add(key + " " + socket.get(key).getAsString());
                }
            }
            if (socket.get("mates") instanceof JsonArray mates) {
                parts.add("mates " + String.join(", ", mates.asList().stream().map(JsonElement::getAsString)
                        .toList()));
            }
            for (String block : List.of("hitch", "rope", "winch", "magnet", "transfer")) {
                if (socket.get(block) instanceof JsonObject declared) {
                    List<String> fields = new ArrayList<>();
                    declared.entrySet().forEach(entry -> fields.add(entry.getKey() + " " + entry.getValue()));
                    parts.add(block + " (" + String.join(", ", fields) + ")");
                }
            }
            lines.add("  socket " + socket.get("name").getAsString() + " : " + String.join(" ; ", parts));
        }
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
