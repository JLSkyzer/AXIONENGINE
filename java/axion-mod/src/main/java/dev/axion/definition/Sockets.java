package dev.axion.definition;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

/**
 * Les sockets d'attache et les interactions {@code attach} et {@code detach} (ADR-171 D2, D4, schéma 6) : ce que JSON
 * Schema n'exprime pas, jugé avec le socket ou l'interaction entière. Le schéma a déjà jugé les formes, les bornes de
 * chaque champ et les noms ; le natif résout le node de chaque socket et exige son rôle {@code socket}.
 *
 * <ul>
 *   <li>Les paramètres d'un type ({@code hitch}, {@code rope}, {@code winch}, {@code magnet}) ne se déclarent que sur
 *       un socket qui accepte ce type : un bloc d'une autre sorte se refuse en le nommant, plutôt que d'être
 *       ignoré.</li>
 *   <li>Un treuil enroule de {@code min_length} à {@code max_length}, la première sous la seconde.</li>
 *   <li>Un {@code tag} et chaque {@code mates} sont des noms simples.</li>
 *   <li>Une interaction {@code attach} ou {@code detach} nomme son socket ; les types qu'elle permet sont des types
 *       que ce socket accepte ; seule {@code attach} a une distance.</li>
 * </ul>
 *
 * <p>Logique pure, sans Forge.
 */
final class Sockets {

    /** ADR-171 D2 : les sockets d'une definition, au plus. */
    static final int MAX = 32;

    /** ADR-171 D1 : les types d'attache, dans l'ordre de leur rang ({@code SEATED} retiré, Q1). */
    static final Set<String> KINDS = orderedKinds();

    /** Le bloc de paramètres de chaque type qui en a un. */
    private static final Map<String, String> BLOCKS = Map.of("hitch", "hitch", "rope", "rope", "winch", "winch",
            "magnet", "magnet");

    /** ADR-171 D2 : un nom simple, pour la forme d'un socket et celles qu'il épouse. */
    private static final Pattern SIMPLE = Pattern.compile("[a-z0-9_.-]{1,64}");

    private Sockets() {}

    private static Set<String> orderedKinds() {
        Set<String> kinds = new java.util.LinkedHashSet<>(List.of("rigid", "hitch", "rope", "winch", "magnet"));
        return java.util.Collections.unmodifiableSet(kinds);
    }

    /**
     * Juge les sockets et les interactions d'attache d'une definition de schéma 6.
     *
     * @param root la definition
     * @param fault reçoit le chemin et le message de chaque faute
     */
    static void check(JsonObject root, BiConsumer<String, String> fault) {
        java.util.Map<String, Set<String>> accepted = new java.util.HashMap<>();
        if (root.get("sockets") instanceof JsonArray sockets) {
            for (int index = 0; index < sockets.size(); index++) {
                JsonObject socket = sockets.get(index).getAsJsonObject();
                String path = "$.sockets[" + index + "]";
                Set<String> accepts = strings(socket.getAsJsonArray("accepts"));
                accepted.put(socket.get("name").getAsString(), accepts);
                checkSocket(socket, accepts, path, fault);
            }
        }
        if (root.get("interaction") instanceof JsonArray interactions) {
            for (int index = 0; index < interactions.size(); index++) {
                if (interactions.get(index) instanceof JsonObject interaction) {
                    checkInteraction(interaction, accepted, "$.interaction[" + index + "]", fault);
                }
            }
        }
    }

    private static void checkSocket(JsonObject socket, Set<String> accepts, String path,
            BiConsumer<String, String> fault) {
        String name = socket.get("name").getAsString();
        for (Map.Entry<String, String> block : BLOCKS.entrySet()) {
            if (socket.has(block.getValue()) && !accepts.contains(block.getKey())) {
                fault.accept(path + "." + block.getValue(), "le socket « " + name + " » déclare des paramètres « "
                        + block.getKey() + " » sans accepter ce type (accepts)");
            }
        }
        if (socket.get("winch") instanceof JsonObject winch && winch.has("min_length") && winch.has("max_length")
                && winch.get("min_length").getAsDouble() >= winch.get("max_length").getAsDouble()) {
            fault.accept(path + ".winch.max_length", "le treuil du socket « " + name + " » enroule de "
                    + winch.get("min_length").getAsDouble() + " à " + winch.get("max_length").getAsDouble()
                    + " m : max_length doit dépasser min_length");
        }
        if (socket.has("tag") && !SIMPLE.matcher(socket.get("tag").getAsString()).matches()) {
            fault.accept(path + ".tag", "« " + socket.get("tag").getAsString()
                    + " » n'est pas un nom simple ([a-z0-9_.-], 64 au plus)");
        }
        if (socket.get("mates") instanceof JsonArray mates) {
            for (int i = 0; i < mates.size(); i++) {
                String mate = mates.get(i).getAsString();
                if (!SIMPLE.matcher(mate).matches()) {
                    fault.accept(path + ".mates[" + i + "]", "« " + mate
                            + " » n'est pas un nom simple ([a-z0-9_.-], 64 au plus)");
                }
            }
        }
    }

    private static void checkInteraction(JsonObject interaction, java.util.Map<String, Set<String>> accepted,
            String path, BiConsumer<String, String> fault) {
        String action = interaction.get("action").getAsString();
        boolean attach = "attach".equals(action);
        if (!attach && !"detach".equals(action)) {
            for (String field : List.of("socket", "kinds", "distance")) {
                if (interaction.has(field)) {
                    fault.accept(path + "." + field, "« " + field + " » n'appartient qu'aux actions attach et detach");
                }
            }
            return;
        }
        if (!interaction.has("socket")) {
            fault.accept(path, "l'action « " + action + " » nomme le socket qu'elle attache ou détache");
            return;
        }
        if (!attach) {
            for (String field : List.of("kinds", "distance")) {
                if (interaction.has(field)) {
                    fault.accept(path + "." + field, "« " + field + " » n'appartient qu'à l'action attach");
                }
            }
            return;
        }
        Set<String> accepts = accepted.getOrDefault(interaction.get("socket").getAsString(), Set.of());
        if (interaction.get("kinds") instanceof JsonArray kinds) {
            for (int i = 0; i < kinds.size(); i++) {
                String kind = kinds.get(i).getAsString();
                if (!accepts.contains(kind)) {
                    fault.accept(path + ".kinds[" + i + "]", "le socket « " + interaction.get("socket").getAsString()
                            + " » n'accepte pas le type « " + kind + " »");
                }
            }
        }
    }

    private static Set<String> strings(JsonArray array) {
        Set<String> values = new HashSet<>();
        if (array != null) {
            for (JsonElement element : array) {
                values.add(element.getAsString());
            }
        }
        return values;
    }
}
