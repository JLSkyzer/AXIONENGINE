package dev.axion.diag;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Les métriques que Java mesure, fusionnées à l'export des métriques natives (R-500, R-502,
 * ADR-133 D1) — logique pure, sans Forge.
 *
 * <p>Certains budgets se mesurent côté Java : la préparation du rendu, la mémoire GPU, les
 * instances et triangles d'une frame, le hook de tick à vide. Le natif en déclare déjà les
 * métriques ({@code axion.budget.<budget>.consumed} et {@code .overruns}) ; à l'export, une
 * métrique Java de même nom en donne la <b>valeur</b>, sous le type et l'unité de la déclaration
 * native. Les autres s'ajoutent à la liste, avec leur type et leur unité. Le schéma de l'export
 * ne change pas.
 *
 * <p>Les valeurs s'écrivent sur n'importe quel thread — le rendu, le tick —, et se lisent sur
 * celui de la commande d'export.
 */
public final class JavaMetrics {

    /** Le registre du processus : un seul runtime existe par JVM. */
    public static final JavaMetrics GLOBAL = new JavaMetrics();

    /** Type d'une métrique, tel que l'export l'écrit. */
    public enum Kind {
        /** Valeur courante. */
        GAUGE("gauge"),
        /** Nombre d'occurrences depuis le lancement. */
        COUNTER("counter");

        private final String json;

        Kind(String json) {
            this.json = json;
        }
    }

    /** Une métrique : un nom, un type, une unité, une valeur. */
    public static final class Metric {
        private final String name;
        private final Kind kind;
        private final String unit;
        private final AtomicLong value = new AtomicLong();

        private Metric(String name, Kind kind, String unit) {
            this.name = name;
            this.kind = kind;
            this.unit = unit;
        }

        /** Pose la valeur. */
        public void set(long amount) {
            value.set(amount);
        }

        /** Ajoute à la valeur. */
        public void add(long amount) {
            value.addAndGet(amount);
        }

        /** Ajoute un. */
        public void increment() {
            value.incrementAndGet();
        }

        /** {@return la valeur} */
        public long value() {
            return value.get();
        }

        /** {@return le nom} */
        public String name() {
            return name;
        }
    }

    /**
     * Les deux métriques d'un budget (INV-19), sous les noms que le natif leur donne.
     *
     * @param consumed consommation : jauge, dans l'unité du budget
     * @param overruns dépassements : compteur
     */
    public record Budget(Metric consumed, Metric overruns) {

        /**
         * Rapporte une consommation, et compte un dépassement si le plafond est franchi.
         *
         * @param amount consommation, dans l'unité du budget
         * @param limit plafond configuré ; {@code 0} : aucun, et rien n'est compté
         * @return vrai si le budget est dépassé
         */
        public boolean report(long amount, long limit) {
            consumed.set(amount);
            boolean exceeded = limit > 0 && amount > limit;
            if (exceeded) {
                overruns.increment();
            }
            return exceeded;
        }
    }

    /** Par nom, dans l'ordre de déclaration. */
    private final Map<String, Metric> metrics = new LinkedHashMap<>();

    /** {@return une jauge, déclarée au premier appel} */
    public Metric gauge(String name, String unit) {
        return declare(name, Kind.GAUGE, unit);
    }

    /** {@return un compteur, déclaré au premier appel} */
    public Metric counter(String name, String unit) {
        return declare(name, Kind.COUNTER, unit);
    }

    /**
     * {@return les métriques d'un budget}
     *
     * @param key chemin de configuration, {@code budgets.<nom>}
     * @param unit unité de sa consommation
     */
    public Budget budget(String key, String unit) {
        String name = key.startsWith("budgets.") ? key.substring("budgets.".length()) : key;
        return new Budget(
                gauge("axion.budget." + name + ".consumed", unit),
                counter("axion.budget." + name + ".overruns", "count"));
    }

    private synchronized Metric declare(String name, Kind kind, String unit) {
        Metric existing = metrics.get(name);
        if (existing != null) {
            if (existing.kind != kind) {
                throw new IllegalArgumentException(name + " est déjà déclarée comme " + existing.kind.json);
            }
            return existing;
        }
        Metric metric = new Metric(name, kind, unit);
        metrics.put(name, metric);
        return metric;
    }

    private synchronized List<Metric> snapshot() {
        return new ArrayList<>(metrics.values());
    }

    /**
     * {@return l'export natif, enrichi des métriques Java} Une métrique de même nom qu'une entrée
     * native en remplace la valeur ; les autres s'ajoutent, et {@code metric_count} suit. Un export
     * illisible est rendu tel quel : la fusion ne doit pas faire perdre ce que le natif a dit.
     *
     * @param nativeJson export JSON du natif, ou {@code null}
     */
    public String merge(String nativeJson) {
        if (nativeJson == null) {
            return null;
        }
        JsonObject root;
        JsonArray list;
        try {
            JsonElement parsed = JsonParser.parseString(nativeJson);
            if (!parsed.isJsonObject() || !(parsed.getAsJsonObject().get("metrics") instanceof JsonArray array)) {
                return nativeJson;
            }
            root = parsed.getAsJsonObject();
            list = array;
        } catch (RuntimeException unreadable) {
            return nativeJson;
        }
        Map<String, JsonObject> byName = new HashMap<>();
        for (JsonElement element : list) {
            if (element instanceof JsonObject entry && entry.get("name") != null) {
                byName.put(entry.get("name").getAsString(), entry);
            }
        }
        for (Metric metric : snapshot()) {
            JsonObject existing = byName.get(metric.name);
            if (existing != null) {
                existing.addProperty("value", metric.value());
            } else {
                JsonObject entry = new JsonObject();
                entry.addProperty("name", metric.name);
                entry.addProperty("kind", metric.kind.json);
                entry.addProperty("unit", metric.unit);
                entry.addProperty("value", metric.value());
                list.add(entry);
            }
        }
        root.addProperty("metric_count", list.size());
        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root);
    }
}
