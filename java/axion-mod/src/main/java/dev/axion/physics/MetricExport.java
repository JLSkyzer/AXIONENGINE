package dev.axion.physics;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Lecture de quelques valeurs dans l'export des métriques natives (R-502) : ce que relève le
 * cycle de simulation — palier de dégradation, pas décomposé —, par un seul parcours de l'export.
 */
final class MetricExport {

    private MetricExport() {
        throw new AssertionError("classe utilitaire, non instanciable");
    }

    /**
     * Lit la valeur des métriques demandées.
     *
     * @param json export JSON des métriques, ou {@code null}
     * @param names noms des métriques à lire
     * @return la valeur de celles que l'export porte — une métrique absente n'y est pas —, ou
     *     {@code null} si l'export est illisible : syntaxe fautive, forme inattendue, valeur non
     *     entière d'une métrique demandée
     */
    static Map<String, Long> values(String json, Set<String> names) {
        if (json == null) {
            return null;
        }
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject() || !root.getAsJsonObject().has("metrics")
                    || !root.getAsJsonObject().get("metrics").isJsonArray()) {
                return null;
            }
            Map<String, Long> values = new HashMap<>();
            for (JsonElement element : root.getAsJsonObject().getAsJsonArray("metrics")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject metric = element.getAsJsonObject();
                if (!metric.has("name") || !metric.has("value")) {
                    continue;
                }
                String name = metric.get("name").getAsString();
                if (names.contains(name)) {
                    values.put(name, metric.get("value").getAsLong());
                }
            }
            return values;
        } catch (RuntimeException unreadable) {
            // Syntaxe fautive, valeur non numérique : un export illisible ne dit rien.
            return null;
        }
    }
}
