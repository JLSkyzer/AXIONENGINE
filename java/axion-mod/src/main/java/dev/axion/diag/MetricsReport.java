package dev.axion.diag;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import java.util.ArrayList;
import java.util.List;

/**
 * Lecture de l'export de métriques natif (C-15, C-71).
 *
 * <p>Le natif produit le JSON de R-502 ; c'est ici qu'on le rend lisible dans
 * une console. Le document complet part dans un fichier — {@code /axion metrics
 * export} — parce qu'il compte plusieurs dizaines de lignes, et qu'un chat de
 * Minecraft n'est pas un endroit où lire cela.
 *
 * <p>Le résumé ne montre que les métriques <strong>non nulles</strong>. Une
 * métrique à zéro n'est pas une information : elle dit qu'un composant n'a
 * encore rien fait, ce que la liste des composants dit déjà. Leur nombre est
 * rappelé pour que leur absence ne passe pas pour un oubli.
 *
 * <p>Le JSON est parsé par Gson, présent dans Minecraft. Aucune bibliothèque
 * n'est embarquée pour cela : la table 32.2 écarte explicitement un analyseur
 * JSON du runtime pour cette raison.
 */
public final class MetricsReport {

    /** Nombre maximal de métriques détaillées dans un résumé. */
    private static final int MAX_LINES = 24;

    private MetricsReport() {
        throw new AssertionError("classe utilitaire, non instanciable");
    }

    /**
     * Compose le résumé lisible d'un export.
     *
     * @param json export produit par le natif
     * @return les lignes du résumé, dans l'ordre d'affichage
     */
    public static List<String> summary(String json) {
        List<String> lines = new ArrayList<>();

        JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) {
                lines.add("AXION : export de métriques inattendu");
                return List.copyOf(lines);
            }
            root = parsed.getAsJsonObject();
        } catch (JsonSyntaxException failure) {
            // Un export illisible est un défaut du producteur : le dire vaut
            // mieux que de n'afficher qu'une liste vide.
            lines.add("AXION : export de métriques illisible — " + failure.getMessage());
            return List.copyOf(lines);
        }

        JsonArray metrics =
                root.has("metrics") && root.get("metrics").isJsonArray()
                        ? root.getAsJsonArray("metrics")
                        : new JsonArray();

        List<String> actives = new ArrayList<>();
        for (JsonElement element : metrics) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject metric = element.getAsJsonObject();
            long value = metric.has("value") ? metric.get("value").getAsLong() : 0L;
            if (value == 0L) {
                continue;
            }
            actives.add(render(metric, value));
        }

        lines.add("AXION ENGINE — métriques");
        lines.add("  déclarées : " + metrics.size() + ", non nulles : " + actives.size());
        if (actives.isEmpty()) {
            lines.add("  aucune activité mesurée pour l'instant");
        } else {
            actives.stream().limit(MAX_LINES).forEach(line -> lines.add("  " + line));
            if (actives.size() > MAX_LINES) {
                lines.add("  … " + (actives.size() - MAX_LINES)
                        + " de plus — /axion metrics export pour tout voir");
            }
        }
        return List.copyOf(lines);
    }

    private static String render(JsonObject metric, long value) {
        String name = metric.has("name") ? metric.get("name").getAsString() : "?";
        String unit = metric.has("unit") ? metric.get("unit").getAsString() : "";

        StringBuilder line = new StringBuilder(name).append(" : ").append(value);
        if (!unit.isBlank() && !"count".equals(unit)) {
            line.append(' ').append(unit);
        }
        // Une durée porte son nombre de mesures et son maximum : une somme
        // seule ne dit pas si elle vient d'une mesure ou de mille.
        if (metric.has("count")) {
            line.append(" (").append(metric.get("count").getAsLong()).append(" mesures");
            if (metric.has("max")) {
                line.append(", max ").append(metric.get("max").getAsLong()).append(" ns");
            }
            line.append(')');
        }
        return line.toString();
    }
}
