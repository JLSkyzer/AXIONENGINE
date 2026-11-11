package dev.axion.definition;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.toml.TomlParser;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.axion.api.content.ContentKind;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Les surcharges de l'administrateur du serveur (ADR-162 D8, ADR-165 D4, CAP-51) : des multiplicateurs bornés sur les
 * grandeurs du contenu enregistré, et des correspondances de blocs. Aucun nouveau contenu.
 *
 * <p>Elles s'appliquent aux documents du registre résolu, avant leurs lecteurs. Un document modifié doit passer les
 * mêmes règles que l'original ; s'il ne les passe plus, la surcharge est refusée et l'entrée garde sa valeur. Chaque
 * surcharge, appliquée ou refusée, est nommée. Le serveur envoie le contenu surchargé aux clients, qui n'ont pas
 * besoin du fichier : l'empreinte du registre change en conséquence.
 */
public final class ServerOverrides {

    /** Le nom du fichier, dans le dossier de configuration. */
    public static final String FILE_NAME = "axion-server-overrides.toml";

    /** Le plus petit multiplicateur admis (ADR-165 Q2). */
    public static final double MIN_FACTOR = 0.1;

    /** Le plus grand multiplicateur admis (ADR-165 Q2). */
    public static final double MAX_FACTOR = 10.0;

    /**
     * La clé du fichier de correspondances que les surcharges ajoutent au registre ; {@link BlockMaterialMappings} le
     * lit après tous les autres, si bien qu'il l'emporte sur les fichiers des mods.
     */
    public static final String BLOCK_MAPPING_KEY = "axion:axion/block_materials/server_overrides.json";

    /** Les clés d'une definition (ADR-165 Q3). */
    static final Set<String> DEFINITION_KEYS = Set.of("mass", "engine_torque", "brake_torque");

    /** Les clés d'un pneu. */
    static final Set<String> TIRE_KEYS = Set.of("grip");

    /** Les clés d'un matériau : ses grandeurs numériques de DM-07. */
    static final Set<String> MATERIAL_KEYS = Arrays.stream(PhysicsMaterials.Field.values())
            .filter(field -> field.kind() == PhysicsMaterials.Kind.NUMBER)
            .map(PhysicsMaterials.Field::jsonName)
            .collect(Collectors.toUnmodifiableSet());

    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");

    private static final String SOURCE = FILE_NAME;

    /** Aucune surcharge : le fichier est absent. */
    public static final ServerOverrides NONE = new ServerOverrides(Map.of(), Map.of(), Map.of(), Map.of(), List.of());

    private final Map<String, Map<String, Double>> materials;
    private final Map<String, Map<String, Double>> definitions;
    private final Map<String, Map<String, Double>> tires;
    private final Map<String, String> blocks;
    private final List<String> refusals;

    private ServerOverrides(Map<String, Map<String, Double>> materials, Map<String, Map<String, Double>> definitions,
            Map<String, Map<String, Double>> tires, Map<String, String> blocks, List<String> refusals) {
        this.materials = materials;
        this.definitions = definitions;
        this.tires = tires;
        this.blocks = blocks;
        this.refusals = List.copyOf(refusals);
    }

    /**
     * {@return les surcharges d'un fichier ; {@link #NONE} s'il est absent} Un fichier illisible n'en donne aucune et
     * le dit dans {@link #refusals()}.
     *
     * @param file le fichier
     */
    public static ServerOverrides read(Path file) {
        if (!Files.isRegularFile(file)) {
            return NONE;
        }
        try {
            return parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException failure) {
            return new ServerOverrides(Map.of(), Map.of(), Map.of(), Map.of(),
                    List.of(FILE_NAME + " illisible, aucune surcharge appliquée : " + failure.getMessage()));
        }
    }

    /**
     * {@return les surcharges d'un texte TOML} Une section, une clé ou une valeur hors des règles est refusée et
     * nommée ; le reste vaut.
     *
     * @param toml le texte
     * @throws RuntimeException si le texte n'est pas du TOML
     */
    public static ServerOverrides parse(String toml) {
        Config config = new TomlParser().parse(toml);
        Map<String, Map<String, Double>> materials = new LinkedHashMap<>();
        Map<String, Map<String, Double>> definitions = new LinkedHashMap<>();
        Map<String, Map<String, Double>> tires = new LinkedHashMap<>();
        Map<String, String> blocks = new LinkedHashMap<>();
        List<String> refusals = new ArrayList<>();
        for (UnmodifiableConfig.Entry section : config.entrySet()) {
            switch (section.getKey()) {
                case "physics_materials" -> factors(section, MATERIAL_KEYS, materials, refusals);
                case "definitions" -> factors(section, DEFINITION_KEYS, definitions, refusals);
                case "tires" -> factors(section, TIRE_KEYS, tires, refusals);
                case "block_materials" -> blocks(section, blocks, refusals);
                default -> refusals.add("section inconnue [" + section.getKey()
                        + "] ; admises : physics_materials, definitions, tires, block_materials");
            }
        }
        return new ServerOverrides(materials, definitions, tires, blocks, refusals);
    }

    private static void factors(UnmodifiableConfig.Entry section, Set<String> keys,
            Map<String, Map<String, Double>> out, List<String> refusals) {
        if (!(section.getValue() instanceof UnmodifiableConfig targets)) {
            refusals.add("[" + section.getKey() + "] : une table par entrée attendue, [" + section.getKey()
                    + ".\"<ns>:<chemin>\"]");
            return;
        }
        for (UnmodifiableConfig.Entry target : targets.entrySet()) {
            String where = section.getKey() + ".\"" + target.getKey() + "\"";
            if (!ID.matcher(target.getKey()).matches()) {
                refusals.add(where + " : identifiant attendu, <ns>:<chemin>");
                continue;
            }
            if (!(target.getValue() instanceof UnmodifiableConfig values)) {
                refusals.add(where + " : une table de multiplicateurs attendue");
                continue;
            }
            Map<String, Double> factors = new LinkedHashMap<>();
            for (UnmodifiableConfig.Entry value : values.entrySet()) {
                String at = where + "." + value.getKey();
                if (!keys.contains(value.getKey())) {
                    refusals.add(at + " : clé inconnue ; admises : " + keys.stream().sorted().toList());
                } else if (!(value.getValue() instanceof Number number)) {
                    refusals.add(at + " : un nombre attendu");
                } else if (!(number.doubleValue() >= MIN_FACTOR && number.doubleValue() <= MAX_FACTOR)) {
                    refusals.add(at + " : multiplicateur " + number + " hors de [" + MIN_FACTOR + ", " + MAX_FACTOR
                            + "]");
                } else {
                    factors.put(value.getKey(), number.doubleValue());
                }
            }
            if (!factors.isEmpty()) {
                out.put(target.getKey(), factors);
            }
        }
    }

    private static void blocks(UnmodifiableConfig.Entry section, Map<String, String> out, List<String> refusals) {
        if (!(section.getValue() instanceof UnmodifiableConfig entries)) {
            refusals.add("[block_materials] : une table de correspondances attendue");
            return;
        }
        for (UnmodifiableConfig.Entry entry : entries.entrySet()) {
            String block = entry.getKey();
            String at = "block_materials.\"" + block + "\"";
            String id = block.startsWith("#") ? block.substring(1) : block;
            if (!ID.matcher(id).matches()) {
                refusals.add(at + " : un bloc <ns>:<chemin>, ou une étiquette #<ns>:<chemin>, attendu");
            } else if (!(entry.getValue() instanceof String material) || !ID.matcher(material).matches()) {
                refusals.add(at + " : un matériau <ns>:<chemin> attendu");
            } else {
                out.put(block, material);
            }
        }
    }

    /** {@return ce que la lecture du fichier a refusé, chaque refus nommé} */
    public List<String> refusals() {
        return refusals;
    }

    /** {@return vrai si le fichier ne porte aucune surcharge valide} */
    public boolean isEmpty() {
        return materials.isEmpty() && definitions.isEmpty() && tires.isEmpty() && blocks.isEmpty();
    }

    /**
     * {@return les fichiers d'une famille, ceux que les surcharges touchent remplacés} Les surcharges appliquées et
     * refusées s'ajoutent aux deux listes. L'ordre des fichiers est gardé ; le fichier de correspondances de
     * l'administrateur s'ajoute à la fin.
     *
     * @param kind la famille
     * @param files les fichiers, par clé {@code <ns>:axion/<dossier>/<chemin>}
     * @param materialIds les matériaux enregistrés, que nomme une correspondance de blocs
     * @param applied les surcharges appliquées, dites
     * @param refused les surcharges refusées, avec leur cause
     */
    public Map<String, byte[]> apply(ContentKind kind, Map<String, byte[]> files, Set<String> materialIds,
            List<String> applied, List<String> refused) {
        return switch (kind) {
            case PHYSICS_MATERIAL -> multiply(kind, files, materials, applied, refused);
            case DEFINITION -> multiply(kind, files, definitions, applied, refused);
            case TIRE -> multiply(kind, files, tires, applied, refused);
            case BLOCK_MATERIAL -> mappings(files, materialIds, applied, refused);
            default -> files;
        };
    }

    private Map<String, byte[]> mappings(Map<String, byte[]> files, Set<String> materialIds, List<String> applied,
            List<String> refused) {
        JsonObject byBlock = new JsonObject();
        JsonObject byTag = new JsonObject();
        List<String> done = new ArrayList<>();
        blocks.forEach((block, material) -> {
            String at = "block_materials.\"" + block + "\"";
            if (!materialIds.contains(material)) {
                refused.add(at + " : matériau « " + material + " » inconnu ; aucun mod ne le déclare");
            } else if (block.startsWith("#")) {
                byTag.addProperty(block.substring(1), material);
                done.add(at + " → " + material);
            } else {
                byBlock.addProperty(block, material);
                done.add(at + " → " + material);
            }
        });
        if (done.isEmpty()) {
            return files;
        }
        JsonObject root = new JsonObject();
        root.addProperty("schema", BlockMaterialMappings.SCHEMA);
        root.add("by_block", byBlock);
        root.add("by_tag", byTag);
        Map<String, byte[]> out = new LinkedHashMap<>(files);
        out.put(BLOCK_MAPPING_KEY, CanonicalJson.of(root).getBytes(StandardCharsets.UTF_8));
        applied.addAll(done);
        return out;
    }

    private static Map<String, byte[]> multiply(ContentKind kind, Map<String, byte[]> files,
            Map<String, Map<String, Double>> overrides, List<String> applied, List<String> refused) {
        if (overrides.isEmpty()) {
            return files;
        }
        String section = section(kind);
        Map<String, byte[]> out = new LinkedHashMap<>(files);
        overrides.forEach((id, factors) -> {
            String where = section + ".\"" + id + "\"";
            int colon = id.indexOf(':');
            String key = id.substring(0, colon) + ":axion/" + kind.folder() + "/" + id.substring(colon + 1) + ".json";
            byte[] bytes = out.get(key);
            if (bytes == null) {
                refused.add(where + " : aucune entrée " + id + " (son mod est-il installé ?)");
                return;
            }
            JsonObject document;
            try {
                document = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (RuntimeException unreadable) {
                refused.add(where + " : l'entrée n'est pas un document lisible");
                return;
            }
            boolean changed = false;
            for (Map.Entry<String, Double> factor : factors.entrySet()) {
                String at = where + "." + factor.getKey() + " ×" + plain(factor.getValue());
                try {
                    JsonObject trial = document.deepCopy();
                    String done = switch (kind) {
                        case PHYSICS_MATERIAL -> material(trial, factor.getKey(), factor.getValue());
                        case DEFINITION -> definition(trial, factor.getKey(), factor.getValue());
                        default -> tire(trial, factor.getValue());
                    };
                    document = trial;
                    changed = true;
                    applied.add(at + " : " + done);
                } catch (IllegalArgumentException refusal) {
                    refused.add(at + " : " + refusal.getMessage());
                }
            }
            if (changed) {
                out.put(key, CanonicalJson.of(document).getBytes(StandardCharsets.UTF_8));
            }
        });
        return out;
    }

    private static String section(ContentKind kind) {
        return switch (kind) {
            case PHYSICS_MATERIAL -> "physics_materials";
            case DEFINITION -> "definitions";
            case TIRE -> "tires";
            default -> kind.folder();
        };
    }

    /** Multiplie une grandeur écrite d'un matériau ; le résultat reste dans la plage du champ. */
    private static String material(JsonObject document, String field, double factor) {
        PhysicsMaterials.Field described = PhysicsMaterials.Field.fromJson(field).orElseThrow();
        JsonElement value = document.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("le matériau n'écrit pas « " + field
                    + " » : une surcharge ne multiplie qu'une valeur écrite");
        }
        double before = value.getAsDouble();
        double after = product(before, factor);
        if (!described.admits(after)) {
            throw new IllegalArgumentException(plain(before) + " deviendrait " + plain(after) + ", hors de "
                    + described.range());
        }
        document.addProperty(field, after);
        return field + " " + plain(before) + " → " + plain(after);
    }

    /**
     * Multiplie les grandeurs d'une definition ; le résultat n'ajoute aucune faute de schéma. Un document déjà fautif
     * reste à refuser par le registre, pour ses propres fautes : la surcharge n'en est pas tenue pour cause.
     */
    private static String definition(JsonObject document, String key, double factor) {
        List<DefinitionChecker.Issue> faults = DefinitionChecker.check(document.deepCopy());
        List<String> changes = new ArrayList<>();
        switch (key) {
            case "mass" -> {
                scale(object(document, "physics"), "physics.", "mass", factor, changes);
                for (String list : List.of("bodies", "parts", "wheels")) {
                    if (document.get(list) instanceof JsonArray items) {
                        for (int i = 0; i < items.size(); i++) {
                            scale(items.get(i), list + "[" + i + "].", "mass", factor, changes);
                        }
                    }
                }
            }
            case "engine_torque" -> {
                JsonObject engine = object(object(document, "powertrain"), "engine");
                if (engine != null && engine.get("torque_curve") instanceof JsonArray curve) {
                    for (int i = 0; i < curve.size(); i++) {
                        if (curve.get(i) instanceof JsonArray point && point.size() == 2
                                && point.get(1).isJsonPrimitive() && point.get(1).getAsJsonPrimitive().isNumber()) {
                            double before = point.get(1).getAsDouble();
                            double after = product(before, factor);
                            point.set(1, new com.google.gson.JsonPrimitive(after));
                            changes.add("torque_curve[" + i + "] " + plain(before) + " → " + plain(after));
                        }
                    }
                }
            }
            default -> {
                JsonObject brakes = object(document, "brakes");
                for (String field : List.of("max_torque_front", "max_torque_rear", "handbrake_torque")) {
                    scale(brakes, "brakes.", field, factor, changes);
                }
            }
        }
        if (changes.isEmpty()) {
            throw new IllegalArgumentException("la definition n'écrit aucune grandeur que « " + key + " » multiplie");
        }
        for (DefinitionChecker.Issue issue : DefinitionChecker.check(document)) {
            if (!faults.contains(issue)) {
                throw new IllegalArgumentException("le résultat ne passe plus le schéma : E-" + (-issue.code()) + " "
                        + issue.path() + " : " + issue.message());
            }
        }
        return String.join(", ", changes);
    }

    /** Multiplie l'adhérence d'un pneu ; elle reste strictement positive. */
    private static String tire(JsonObject document, double factor) {
        List<String> changes = new ArrayList<>();
        for (String field : List.of("mu_long", "mu_lat")) {
            scale(document, "", field, factor, changes);
        }
        if (changes.isEmpty()) {
            throw new IllegalArgumentException("le pneu n'écrit ni mu_long ni mu_lat");
        }
        return String.join(", ", changes);
    }

    private static JsonObject object(JsonElement parent, String name) {
        return parent instanceof JsonObject object && object.get(name) instanceof JsonObject child ? child : null;
    }

    private static void scale(JsonElement parent, String prefix, String field, double factor, List<String> changes) {
        if (parent instanceof JsonObject object && object.get(field) != null && object.get(field).isJsonPrimitive()
                && object.get(field).getAsJsonPrimitive().isNumber()) {
            double before = object.get(field).getAsDouble();
            double after = product(before, factor);
            object.addProperty(field, after);
            changes.add(prefix + field + " " + plain(before) + " → " + plain(after));
        }
    }

    private static String plain(double value) {
        return CanonicalJson.number(value);
    }

    /**
     * {@return le produit, arrondi à douze chiffres significatifs} L'arrondi efface le bruit binaire de la
     * multiplication — 12 × 1,2 donne 14,4, et non 14,399999999999999 — sans rien perdre qui compte pour une grandeur
     * physique.
     */
    static double product(double value, double factor) {
        double raw = value * factor;
        if (!Double.isFinite(raw) || raw == 0.0) {
            return raw;
        }
        return new java.math.BigDecimal(raw).round(new java.math.MathContext(12)).doubleValue();
    }

    /** {@return le modèle commenté que le serveur écrit quand le fichier manque : aucune surcharge} */
    public static String template() {
        return String.join(System.lineSeparator(),
                "# " + FILE_NAME + " — les surcharges de l'administrateur du serveur (ADR-162 D8, ADR-165 D4).",
                "# Des multiplicateurs bornés, de " + plain(MIN_FACTOR) + " à " + plain(MAX_FACTOR)
                        + ", et des correspondances de blocs ; aucun nouveau contenu.",
                "# Le serveur envoie aux clients le contenu surchargé : ils n'ont pas besoin de ce fichier.",
                "# Chaque surcharge, appliquée ou refusée, s'écrit au journal du serveur ; /axion status en donne le"
                        + " compte.",
                "# Le fichier se relit au démarrage du serveur et à /reload.",
                "#",
                "# [physics_materials.\"axion:ice\"]",
                "# friction = 1.5            # une grandeur numérique du matériau (DM-07)",
                "#",
                "# [definitions.\"monmod:patrouille\"]",
                "# mass = 1.2                # toutes les masses : physics.mass, bodies, parts, wheels",
                "# engine_torque = 0.9       # chaque couple de powertrain.engine.torque_curve",
                "# brake_torque = 1.1        # brakes.max_torque_front, max_torque_rear, handbrake_torque",
                "#",
                "# [tires.\"axion:route\"]",
                "# grip = 1.1                # mu_long et mu_lat",
                "#",
                "# [block_materials]",
                "# \"create:andesite_casing\" = \"axion:steel\"   # un bloc d'un mod tiers",
                "# \"#create:casing\" = \"axion:steel\"           # une étiquette de blocs",
                "");
    }

    // --- Le compte des dernières surcharges, pour /axion status.

    private static final Map<ContentKind, int[]> LAST = new EnumMap<>(ContentKind.class);

    /**
     * Note ce qu'un rechargement a fait des surcharges d'une famille.
     *
     * @param kind la famille
     * @param applied surcharges appliquées
     * @param refused surcharges refusées
     */
    public static synchronized void record(ContentKind kind, int applied, int refused) {
        LAST.put(kind, new int[] {applied, refused});
    }

    /** {@return le compte des dernières surcharges, pour {@code /axion status}} */
    public static synchronized String summary() {
        int applied = LAST.values().stream().mapToInt(counts -> counts[0]).sum();
        int refused = LAST.values().stream().mapToInt(counts -> counts[1]).sum();
        return String.format(Locale.ROOT, "%d appliquée(s), %d refusée(s)", applied, refused);
    }
}
