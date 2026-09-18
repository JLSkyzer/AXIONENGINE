package dev.axion.diag;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;

/**
 * Écrit un résultat de benchmark en jeu au format schéma 2 (PARTIE 30.4).
 *
 * <p>Le fichier est le même format que celui du crate {@code ax-bench} et du
 * pont JMH : {@code tools/bench} le lit, le vérifie (R-2231) et le compare. Le
 * harnais est marqué {@code axion-ingame} dans {@code config.harness} pour qu'il
 * ne soit jamais comparé aux mesures d'un autre harnais du même B-xx (ADR-111).
 *
 * <p>Ces chiffres servent de diagnostic, pas de référence publiée (R-2252) : ils
 * proviennent de la machine du joueur, pas d'un matériel de référence.
 */
public final class SchemaTwoWriter {

    /** Numéro de schéma du format (PARTIE 30.4). */
    private static final int SCHEMA_VERSION = 2;

    /** Version du harnais en jeu (ADR-111) ; le nom {@code axion-ingame} distingue. */
    private static final int HARNESS_VERSION = 1;

    /** Nom du harnais, pour ne comparer qu'entre mesures du même harnais. */
    private static final String HARNESS = "axion-ingame";

    private SchemaTwoWriter() {
        throw new AssertionError("classe utilitaire, non instanciable");
    }

    /**
     * Écrit un résultat au format schéma 2.
     *
     * @param benchmark identifiant B-xx (p. ex. {@code B-07})
     * @param method nom de la routine mesurée (p. ex. {@code ffi})
     * @param commit empreinte du commit, ou version du mod à défaut (provenance)
     * @param result mesures du scénario
     * @param path fichier de destination
     * @throws IOException si l'écriture échoue
     */
    public static void write(String benchmark, String method, String commit, BenchRunner.Result result, Path path)
            throws IOException {
        JsonObject json = build(benchmark, method, commit, result);
        String pretty = new GsonBuilder().setPrettyPrinting().create().toJson(json) + "\n";
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(path, pretty.getBytes(StandardCharsets.UTF_8));
    }

    /** Construit l'objet schéma 2, sans écrire — séparé pour être testable. */
    static JsonObject build(String benchmark, String method, String commit, BenchRunner.Result result) {
        long[] samples = result.samplesNs().clone();
        Arrays.sort(samples);

        JsonObject platform = new JsonObject();
        platform.addProperty("os", os());
        platform.addProperty("cpu", cpu());
        platform.addProperty("cores", Runtime.getRuntime().availableProcessors());
        platform.addProperty("gpu", "");
        platform.addProperty("driver", "");
        platform.addProperty("jvm", System.getProperty("java.version", ""));

        JsonObject config = new JsonObject();
        config.addProperty("harness_version", HARNESS_VERSION);
        config.addProperty("harness", HARNESS);
        config.addProperty("method", method);
        config.addProperty("batch_calls", result.calls());

        JsonObject parameters = new JsonObject();
        parameters.addProperty("scenario", method);

        JsonArray samplesArray = new JsonArray();
        for (long sample : samples) {
            samplesArray.add(sample);
        }
        JsonObject run = new JsonObject();
        run.add("samples_ns", samplesArray);
        JsonArray runs = new JsonArray();
        runs.add(run);

        JsonObject stats = new JsonObject();
        stats.addProperty("p50_ns", percentile(samples, 50));
        stats.addProperty("p95_ns", percentile(samples, 95));
        stats.addProperty("p99_ns", percentile(samples, 99));
        stats.addProperty("min_ns", samples[0]);
        stats.addProperty("max_ns", samples[samples.length - 1]);
        stats.addProperty("stddev_ns", stddev(samples));

        JsonObject json = new JsonObject();
        json.addProperty("schema", SCHEMA_VERSION);
        json.addProperty("benchmark", benchmark);
        json.addProperty("commit", commit);
        json.addProperty("date", now());
        json.add("platform", platform);
        json.add("config", config);
        json.add("parameters", parameters);
        json.add("runs", runs);
        json.add("stats", stats);
        return json;
    }

    /** Centile au rang le plus proche sur un tableau trié (comme ax-bench). */
    private static long percentile(long[] sorted, int p) {
        int rank = (int) Math.ceil(p / 100.0 * sorted.length);
        rank = Math.max(1, Math.min(sorted.length, rank));
        return sorted[rank - 1];
    }

    /** Écart-type de population, en ns. */
    private static double stddev(long[] samples) {
        double sum = 0.0;
        for (long sample : samples) {
            sum += sample;
        }
        double mean = sum / samples.length;
        double variance = 0.0;
        for (long sample : samples) {
            double delta = sample - mean;
            variance += delta * delta;
        }
        return Math.sqrt(variance / samples.length);
    }

    /** Système, dans la forme attendue du schéma ({@code windows}/{@code linux}/{@code macos}). */
    private static String os() {
        String name = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (name.startsWith("windows")) {
            return "windows";
        }
        if (name.startsWith("mac") || name.startsWith("darwin")) {
            return "macos";
        }
        if (name.startsWith("linux")) {
            return "linux";
        }
        return name.isEmpty() ? "inconnu" : name;
    }

    /**
     * Modèle de processeur. {@code PROCESSOR_IDENTIFIER} le donne sous Windows ;
     * ailleurs, l'architecture sert de repli — jamais vide, pour que R-2231
     * accepte le résultat.
     */
    private static String cpu() {
        String identifier = System.getenv("PROCESSOR_IDENTIFIER");
        if (identifier != null && !identifier.isBlank()) {
            return identifier;
        }
        return System.getProperty("os.arch", "inconnu");
    }

    /** Date ISO 8601 en UTC, à la seconde. */
    private static String now() {
        return OffsetDateTime.now(ZoneOffset.UTC)
                .truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
