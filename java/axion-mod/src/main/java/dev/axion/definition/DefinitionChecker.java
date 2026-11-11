package dev.axion.definition;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.axion.definition.SchemaNode.Any;
import dev.axion.definition.SchemaNode.Arr;
import dev.axion.definition.SchemaNode.Bool;
import dev.axion.definition.SchemaNode.Dict;
import dev.axion.definition.SchemaNode.Field;
import dev.axion.definition.SchemaNode.Nullable;
import dev.axion.definition.SchemaNode.Num;
import dev.axion.definition.SchemaNode.Obj;
import dev.axion.definition.SchemaNode.Text;
import dev.axion.definition.SchemaNode.TextRule;
import dev.axion.definition.SchemaOne.SourceFamily;
import dev.axion.physics.AssemblyJoints;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Vérifie une definition contre le schéma 1 (C-27, étapes 2 et 3).
 *
 * <p>Toutes les fautes sont relevées, pas seulement la première : un auteur qui
 * corrige sa definition veut la liste, et en découvrir une par rechargement
 * transforme dix minutes de travail en une heure.
 *
 * <p>Aucune valeur n'est interprétée comme code (R-581) : les chaînes à
 * grammaire sont confrontées à leur liste fermée, jamais évaluées.
 */
final class DefinitionChecker {

    /**
     * Faute relevée.
     *
     * @param code code de l'ANNEXE A.1
     * @param path chemin JSON
     * @param message ce qui ne va pas
     */
    record Issue(int code, String path, String message) {}

    private record Reference(NameCategory category, String name, String path) {}

    /** Longueur maximale d'un nom, en octets (C-22). */
    static final int MAX_NAME_BYTES = 64;

    private final List<Issue> issues = new ArrayList<>();
    private final Map<NameCategory, Map<String, String>> declared =
            new EnumMap<>(NameCategory.class);
    private final List<Reference> references = new ArrayList<>();

    /** Version du schéma lu. */
    private int version = 1;

    private DefinitionChecker() {}

    /**
     * {@return les fautes d'une definition, dans l'ordre du document}
     *
     * @param root document, dont {@code schema}, {@code asset} et {@code kind}
     *     ont déjà été vérifiés
     */
    static List<Issue> check(JsonObject root) {
        DefinitionChecker checker = new DefinitionChecker();
        checker.version = versionOf(root);
        checker.walk(SchemaOne.rootOf(checker.version), root, "$");
        checker.resolve();
        checker.checkLinks(root);
        checker.checkZoneParents(root);
        checker.checkSeats(root);
        checker.checkInteractions(root);
        if (checker.version >= 2 && checker.issues.isEmpty()) {
            checker.checkJoints(root);
        }
        if (checker.version >= 3 && checker.issues.isEmpty()) {
            checker.checkVariables(root);
        }
        if (checker.issues.isEmpty()) {
            VehicleSpec.check(root, checker::invalid);
        }
        return List.copyOf(checker.issues);
    }

    /**
     * Un nom qu'une definition donne à un élément de son asset, que le natif résout à la
     * compilation (étape 4 de C-27, ADR-156 D2).
     *
     * @param category {@link NameCategory#NODE}, {@link NameCategory#CLIP} ou {@link NameCategory#BONE}
     * @param name le nom
     * @param path son chemin JSON, pour le message d'un refus
     */
    record AssetReference(NameCategory category, String name, String path) {}

    /**
     * {@return les noms de nodes, de clips et d'os qu'une definition acceptée désigne, dans l'ordre
     * du document} Les meshes, les colliders et les régions n'en sont pas : l'asset ne garde pas leurs
     * noms, ou C-28 peut les générer (ADR-156, tranche 1).
     *
     * @param root document d'une definition acceptée aux étapes 1 à 3
     */
    static List<AssetReference> assetReferences(JsonObject root) {
        DefinitionChecker checker = new DefinitionChecker();
        checker.version = versionOf(root);
        checker.walk(SchemaOne.rootOf(checker.version), root, "$");
        List<AssetReference> found = new ArrayList<>();
        for (Reference reference : checker.references) {
            NameCategory category = reference.category();
            if (category == NameCategory.NODE || category == NameCategory.CLIP || category == NameCategory.BONE) {
                found.add(new AssetReference(category, reference.name(), reference.path()));
            }
        }
        // ADR-159 : une roue déclare son nom, qui est celui d'un node de l'asset.
        if (root.get("wheels") instanceof JsonArray wheels) {
            for (int i = 0; i < wheels.size(); i++) {
                if (wheels.get(i) instanceof JsonObject wheel && string(wheel, "node") != null) {
                    found.add(new AssetReference(NameCategory.NODE, string(wheel, "node"), "$.wheels[" + i + "].node"));
                }
            }
        }
        return List.copyOf(found);
    }

    /**
     * ADR-151 D3 : au schéma 2, chaque joint est cohérent avec son type (C-34). Sa forme est déjà
     * juste : la règle ne lit que des champs du bon type.
     */
    private void checkJoints(JsonObject root) {
        if (!(root.get("joints") instanceof JsonArray joints)) {
            return;
        }
        for (int i = 0; i < joints.size(); i++) {
            AssemblyJoints.Fault fault = AssemblyJoints.check(joints.get(i).getAsJsonObject());
            if (fault != null) {
                invalid("$.joints[" + i + "]." + fault.field(), fault.message());
            }
        }
    }

    private void walk(SchemaNode node, JsonElement value, String path) {
        if (node instanceof Any) {
            return;
        }
        if (node instanceof Nullable nullable) {
            if (!value.isJsonNull()) {
                walk(nullable.node(), value, path);
            }
            return;
        }
        if (node instanceof Obj object) {
            walkObject(object, value, path);
        } else if (node instanceof Arr array) {
            walkArray(array, value, path);
        } else if (node instanceof Dict dict) {
            walkDict(dict, value, path);
        } else if (node instanceof Text text) {
            if (value instanceof JsonPrimitive primitive && primitive.isString()) {
                text(text.rule(), primitive.getAsString(), path);
            } else {
                invalid(path, "attendu une chaîne");
            }
        } else if (node instanceof Num number) {
            walkNumber(number, value, path);
        } else if (node instanceof Bool) {
            if (!(value instanceof JsonPrimitive primitive) || !primitive.isBoolean()) {
                invalid(path, "attendu un booléen");
            }
        }
    }

    private void walkObject(Obj object, JsonElement value, String path) {
        if (!value.isJsonObject()) {
            invalid(path, "attendu un objet");
            return;
        }
        JsonObject members = value.getAsJsonObject();
        for (Map.Entry<String, JsonElement> member : members.entrySet()) {
            String child = StrictJson.member(path, member.getKey());
            Field field = object.fields().get(member.getKey());
            if (field == null) {
                invalid(child, "clé inconnue du schéma " + version);
            } else {
                walk(field.node(), member.getValue(), child);
            }
        }
        for (Map.Entry<String, Field> field : object.fields().entrySet()) {
            if (field.getValue().required() && !members.has(field.getKey())) {
                invalid(StrictJson.member(path, field.getKey()), "champ obligatoire");
            }
        }
    }

    private void walkArray(Arr array, JsonElement value, String path) {
        if (!value.isJsonArray()) {
            invalid(path, "attendu un tableau");
            return;
        }
        JsonArray items = value.getAsJsonArray();
        if (items.size() < array.min()) {
            invalid(path, "au moins " + array.min() + " élément(s), " + items.size() + " reçu(s)");
        }
        if (items.size() > array.max()) {
            String message = "au plus " + array.max() + " élément(s), " + items.size() + " reçu(s)";
            if (array.limit()) {
                // R-190 : un plafond d'assembly dépassé porte E-3050.
                issues.add(new Issue(DefinitionException.LIMIT, path, message));
            } else {
                invalid(path, message);
            }
        }
        for (int index = 0; index < items.size(); index++) {
            walk(array.item(), items.get(index), path + "[" + index + "]");
        }
    }

    private void walkDict(Dict dict, JsonElement value, String path) {
        if (!value.isJsonObject()) {
            invalid(path, "attendu un objet");
            return;
        }
        JsonObject members = value.getAsJsonObject();
        if (members.size() > dict.max()) {
            issues.add(new Issue(DefinitionException.LIMIT, path,
                    "au plus " + dict.max() + " entrée(s), " + members.size() + " reçue(s)"));
        }
        for (Map.Entry<String, JsonElement> member : members.entrySet()) {
            String child = StrictJson.member(path, member.getKey());
            text(dict.key(), member.getKey(), child);
            walk(dict.value(), member.getValue(), child);
        }
    }

    private void walkNumber(Num number, JsonElement value, String path) {
        if (!(value instanceof JsonPrimitive primitive) || !primitive.isNumber()) {
            invalid(path, "attendu un nombre");
            return;
        }
        BigDecimal exact = primitive.getAsBigDecimal();
        if (number.integer() && exact.stripTrailingZeros().scale() > 0) {
            invalid(path, "attendu un entier");
            return;
        }
        double real = exact.doubleValue();
        boolean belowMin = number.exclusiveMin() ? real <= number.min() : real < number.min();
        if (belowMin || real > number.max()) {
            invalid(path, exact.toPlainString() + " hors de " + bounds(number));
        }
    }

    private void text(TextRule rule, String value, String path) {
        if (rule instanceof TextRule.OneOf oneOf) {
            if (!oneOf.values().contains(value)) {
                invalid(path, "« " + value + " » inconnu ; admis : "
                        + String.join(", ", oneOf.values()));
            }
        } else if (rule instanceof TextRule.Declares declares) {
            if (validName(value, path)) {
                Map<String, String> names =
                        declared.computeIfAbsent(declares.category(), key -> new LinkedHashMap<>());
                String first = names.putIfAbsent(value, path);
                if (first != null) {
                    invalid(path, "nom « " + value + " » déjà déclaré en " + first);
                }
            }
        } else if (rule instanceof TextRule.Refers refers) {
            if (validName(value, path)) {
                references.add(new Reference(refers.category(), value, path));
            }
        } else if (rule instanceof TextRule.Id) {
            id(value, path);
        } else if (rule instanceof TextRule.Source) {
            source(value, path);
        } else if (rule instanceof TextRule.CollisionGroup group) {
            if (!group.reserved().contains(value) && !DefinitionRegistry.isId(value)) {
                invalid(path, CollisionGroupRegistry.notAGroup(value));
            }
        }
    }

    /** ADR-109, point 7 : non vide, ASCII imprimable, 64 octets au plus. */
    private boolean validName(String value, String path) {
        if (value.isEmpty()) {
            invalid(path, "nom vide");
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 0x20 || character > 0x7E) {
                invalid(path, "nom « " + value + " » hors de l'ASCII imprimable");
                return false;
            }
        }
        if (value.length() > MAX_NAME_BYTES) {
            invalid(path, "nom de " + value.length() + " octets, maximum " + MAX_NAME_BYTES);
            return false;
        }
        return true;
    }

    private void id(String value, String path) {
        if (!DefinitionRegistry.isId(value)) {
            invalid(path, "« " + value + " » n'est pas un identifiant <ns>:<chemin>");
        }
    }

    /**
     * {@return le schéma qu'une definition déclare} Le numéro a déjà été jugé (R-1780) : 2 lit le schéma 2 (ADR-151
     * D3), 3 le schéma 3 (ADR-163 D2), tout le reste le 1.
     */
    private static int versionOf(JsonObject root) {
        if (root.get("schema") instanceof JsonPrimitive schema && schema.isNumber()) {
            double number = schema.getAsDouble();
            if (number == 3) {
                return 3;
            }
            if (number == 2) {
                return 2;
            }
        }
        return 1;
    }

    /** ANNEXE A.4 : famille, nom éventuel, champ. */
    private void source(String value, String path) {
        for (SourceFamily family : SchemaOne.SOURCES) {
            String head = family.prefix() + ".";
            if (!value.startsWith(head)) {
                continue;
            }
            String rest = value.substring(head.length());
            if (!family.named()) {
                if (!family.fields().contains(rest)) {
                    invalid(path, "« " + rest + " » inconnu pour " + family.prefix()
                            + " ; admis : " + String.join(", ", family.fields()));
                }
                return;
            }
            if (family.fields().isEmpty()) {
                // var.<n> : au schéma 3, une variable déclarée (ADR-163 D2) ; avant, un nom libre.
                if (rest.isEmpty()) {
                    invalid(path, "variable sans nom");
                } else if (version >= 3 && validName(rest, path)) {
                    references.add(new Reference(NameCategory.VARIABLE, rest, path));
                }
                return;
            }
            int dot = rest.lastIndexOf('.');
            if (dot <= 0) {
                invalid(path, "attendu " + family.prefix() + ".<nom>.<champ>");
                return;
            }
            String name = rest.substring(0, dot);
            String field = rest.substring(dot + 1);
            if (!family.fields().contains(field)) {
                invalid(path, "« " + field + " » inconnu pour " + family.prefix()
                        + " ; admis : " + String.join(", ", family.fields()));
                return;
            }
            if (validName(name, path) && family.category() != null) {
                references.add(new Reference(family.category(), name, path));
            }
            return;
        }
        invalid(path, "source « " + value + " » absente de l'ANNEXE A.4");
    }

    /** Toute référence interne désigne un nom déclaré dans la definition. */
    private void resolve() {
        for (Reference reference : references) {
            if (!reference.category().internal()) {
                // Node, mesh, région : résolus contre l'asset compilé.
                continue;
            }
            Map<String, String> names = declared.getOrDefault(reference.category(), Map.of());
            if (!names.containsKey(reference.name())) {
                String label = reference.category().label();
                String article = "aeiouéè".indexOf(label.charAt(0)) >= 0 ? "d'" : "de ";
                invalid(reference.path(), "« " + reference.name() + " » : pas " + article + label
                        + " de ce nom dans la definition");
            }
        }
    }

    /** DM-14 : une liaison relie deux parts distinctes. */
    private void checkLinks(JsonObject root) {
        JsonElement links = root.get("structural_links");
        if (links == null || !links.isJsonArray()) {
            return;
        }
        for (int index = 0; index < links.getAsJsonArray().size(); index++) {
            JsonElement link = links.getAsJsonArray().get(index);
            if (!link.isJsonObject()) {
                continue;
            }
            JsonElement a = link.getAsJsonObject().get("a");
            JsonElement b = link.getAsJsonObject().get("b");
            if (a != null && a.equals(b)) {
                invalid("$.structural_links[" + index + "].b",
                        "une liaison relie deux parts distinctes");
            }
        }
    }

    /**
     * ADR-158 D1 : un siège a un node, qui lui donne sa position ; une definition a un conducteur au
     * plus ; chaque contrôle est l'un de ceux de {@code VehicleInput} (12.8).
     */
    private void checkSeats(JsonObject root) {
        if (!(root.get("seats") instanceof JsonArray seats)) {
            return;
        }
        String driver = null;
        for (int i = 0; i < seats.size(); i++) {
            if (!(seats.get(i) instanceof JsonObject seat)) {
                continue;
            }
            String path = "$.seats[" + i + "]";
            if (seat.get("node") == null) {
                invalid(path, "un siège est porté par un node, qui lui donne sa position (ADR-158 D1)");
            }
            if ("driver".equals(string(seat, "role"))) {
                if (driver != null) {
                    invalid(path + ".role", "un seul siège conducteur par definition, déjà " + driver
                            + " (ADR-158 D1)");
                } else {
                    driver = path;
                }
            }
            if (seat.get("controls") instanceof JsonArray controls) {
                for (int k = 0; k < controls.size(); k++) {
                    if (controls.get(k) instanceof JsonPrimitive control && control.isString()
                            && Seating.Control.of(control.getAsString()) == null) {
                        invalid(path + ".controls[" + k + "]", "contrôle inconnu « " + control.getAsString()
                                + " » ; attendu throttle, brake, steer, clutch, handbrake ou gear");
                    }
                }
            }
        }
    }

    /**
     * ADR-158 D6 : une interaction est portée par un node, où le joueur clique ; {@code sit} nomme son
     * siège, {@code toggle_animation} et {@code play_animation} leur clip.
     */
    private void checkInteractions(JsonObject root) {
        if (!(root.get("interaction") instanceof JsonArray interactions)) {
            return;
        }
        for (int i = 0; i < interactions.size(); i++) {
            if (!(interactions.get(i) instanceof JsonObject interaction)) {
                continue;
            }
            String path = "$.interaction[" + i + "]";
            if (interaction.get("node") == null) {
                invalid(path, "une interaction est portée par le node où le joueur clique (ADR-158 D6)");
            }
            String action = string(interaction, "action");
            if ("sit".equals(action) && interaction.get("seat") == null) {
                invalid(path, "l'action « sit » nomme le siège où elle assoit");
            }
            if (("toggle_animation".equals(action) || "play_animation".equals(action))
                    && interaction.get("animation") == null) {
                invalid(path, "l'action « " + action + " » nomme le clip qu'elle joue");
            }
        }
    }

    /**
     * ADR-163 D2 : le défaut et les bornes d'une variable sont de son type, le défaut dans les bornes ; une commande
     * liée l'est une fois par siège, à une variable que ce siège peut écrire, avec des valeurs que la variable
     * accepte ; une interaction {@code set_variable} nomme une variable et une valeur qu'elle accepte.
     */
    private void checkVariables(JsonObject root) {
        Variables variables = Variables.of(root);
        if (root.get("variables") instanceof JsonObject declared) {
            for (Map.Entry<String, JsonElement> entry : declared.entrySet()) {
                String path = StrictJson.member("$.variables", entry.getKey());
                JsonObject spec = entry.getValue().getAsJsonObject();
                boolean bool = "bool".equals(string(spec, "type"));
                if (bool && (spec.has("min") || spec.has("max"))) {
                    invalid(path, "un booléen n'a pas de bornes : il vaut 0 ou 1");
                    continue;
                }
                if (spec.has("min") && spec.has("max") && spec.get("min").getAsDouble() > spec.get("max").getAsDouble()) {
                    invalid(path + ".min", "borne basse au-dessus de la borne haute");
                    continue;
                }
                if ("int".equals(string(spec, "type"))) {
                    for (String bound : new String[] {"min", "max"}) {
                        if (spec.has(bound) && spec.get(bound).getAsDouble() != Math.rint(spec.get(bound).getAsDouble())) {
                            invalid(path + "." + bound, "une variable entière a des bornes entières");
                        }
                    }
                }
                if (spec.has("default")) {
                    literal(variables.get(variables.indexOf(entry.getKey())), spec.get("default"), path + ".default");
                }
            }
        }
        if (root.get("seats") instanceof JsonArray seats) {
            for (int i = 0; i < seats.size(); i++) {
                if (seats.get(i) instanceof JsonObject seat && seat.get("bindings") instanceof JsonArray bindings) {
                    checkBindings(variables, "driver".equals(string(seat, "role")), bindings, "$.seats[" + i + "]");
                }
            }
        }
        if (root.get("procedural") instanceof JsonArray tracks) {
            for (int i = 0; i < tracks.size(); i++) {
                if (tracks.get(i) instanceof JsonObject track) {
                    checkTrackAxis(track, "$.procedural[" + i + "]");
                }
            }
        }
        if (root.get("interaction") instanceof JsonArray interactions) {
            for (int i = 0; i < interactions.size(); i++) {
                if (!(interactions.get(i) instanceof JsonObject interaction)
                        || !"set_variable".equals(string(interaction, "action"))) {
                    continue;
                }
                String path = "$.interaction[" + i + "]";
                int variable = variables.indexOf(string(interaction, "variable"));
                if (variable < 0 || interaction.get("value") == null) {
                    invalid(path, "l'action « set_variable » nomme sa variable et la valeur qu'elle pose");
                } else {
                    literal(variables.get(variable), interaction.get("value"), path + ".value");
                }
            }
        }
    }

    /**
     * ADR-163 D3 : l'axe d'une piste convient à son canal — {@code x}, {@code y} ou {@code z} pour {@code rotation} et
     * {@code translation}, {@code r}, {@code g} ou {@code b} pour {@code color}, aucun pour {@code visibility} et
     * {@code emissive}.
     */
    private void checkTrackAxis(JsonObject track, String path) {
        String channel = string(track, "channel");
        String axis = string(track, "axis");
        if (channel == null) {
            return;
        }
        boolean spatial = "rotation".equals(channel) || "translation".equals(channel);
        boolean ok = switch (channel) {
            case "rotation", "translation" -> axis != null && "xyz".contains(axis);
            case "color" -> axis != null && "rgb".contains(axis);
            default -> axis == null;
        };
        if (!ok) {
            invalid(path + ".axis", "le canal « " + channel + " » prend "
                    + (spatial ? "l'axe x, y ou z" : "color".equals(channel) ? "la composante r, g ou b" : "aucun axe"));
        }
    }

    private void checkBindings(Variables variables, boolean driver, JsonArray bindings, String seat) {
        Map<Integer, String> commands = new HashMap<>();
        for (int k = 0; k < bindings.size(); k++) {
            JsonObject binding = bindings.get(k).getAsJsonObject();
            String path = seat + ".bindings[" + k + "]";
            int command = binding.get("command").getAsInt();
            String first = commands.putIfAbsent(command, path);
            if (first != null) {
                invalid(path + ".command", "commande " + command + " déjà liée par ce siège en " + first);
            }
            Variables.Variable variable = variables.get(variables.indexOf(string(binding, "variable")));
            if (variable.write() == Variables.Write.NONE
                    || (variable.write() == Variables.Write.DRIVER && !driver)) {
                invalid(path + ".variable", "variable « " + variable.name() + " » que ce siège ne peut pas écrire"
                        + " (write : " + variable.write().name().toLowerCase(java.util.Locale.ROOT) + ")");
                continue;
            }
            String mode = string(binding, "mode");
            JsonArray values = binding.get("values") instanceof JsonArray list ? list : null;
            if ("cycle".equals(mode) && (values == null || values.size() < 2)) {
                invalid(path + ".values", "le mode « cycle » parcourt au moins deux valeurs");
                continue;
            }
            if (!"cycle".equals(mode) && values != null && values.size() != 2) {
                invalid(path + ".values", "le mode « " + mode + " » prend deux valeurs : relâchée, puis pressée");
                continue;
            }
            if (values == null && variable.type() != Variables.Type.BOOL
                    && (variable.refusal(0.0) != null || variable.refusal(1.0) != null)) {
                invalid(path + ".values", "sans valeurs, la commande pose 0 puis 1, que « " + variable.name()
                        + " » refuse");
            }
            if (values != null) {
                for (int v = 0; v < values.size(); v++) {
                    literal(variable, values.get(v), path + ".values[" + v + "]");
                }
            }
        }
    }

    /** Un littéral — booléen ou nombre — que la variable accepte. */
    private void literal(Variables.Variable variable, JsonElement value, String path) {
        if (!(value instanceof JsonPrimitive primitive) || !(primitive.isBoolean() || primitive.isNumber())) {
            invalid(path, "attendu un booléen ou un nombre");
            return;
        }
        if (primitive.isBoolean() && variable.type() != Variables.Type.BOOL) {
            invalid(path, "variable « " + variable.name() + " » de type " + variable.type().key()
                    + " : un nombre est attendu");
            return;
        }
        String refusal = variable.refusal(Variables.value(primitive));
        if (refusal != null) {
            invalid(path, refusal);
        }
    }

    /** Les zones parentes ne forment pas de cycle. */
    private void checkZoneParents(JsonObject root) {
        JsonElement zones = root.get("damage_zones");
        if (zones == null || !zones.isJsonArray()) {
            return;
        }
        // Ordre du document : un refus qui énumère ses fautes dans un ordre
        // différent à chaque rechargement se relit mal et se compare mal.
        Map<String, String> parents = new HashMap<>();
        Map<String, Integer> ranks = new LinkedHashMap<>();
        JsonArray list = zones.getAsJsonArray();
        for (int index = 0; index < list.size(); index++) {
            if (!list.get(index).isJsonObject()) {
                continue;
            }
            JsonObject zone = list.get(index).getAsJsonObject();
            String name = string(zone, "name");
            String parent = string(zone, "parent");
            if (name != null) {
                ranks.putIfAbsent(name, index);
                if (parent != null) {
                    parents.putIfAbsent(name, parent);
                }
            }
        }
        for (Map.Entry<String, Integer> zone : ranks.entrySet()) {
            Set<String> seen = new HashSet<>();
            String current = parents.get(zone.getKey());
            while (current != null && seen.add(current)) {
                if (current.equals(zone.getKey())) {
                    invalid("$.damage_zones[" + zone.getValue() + "].parent",
                            "la chaîne des zones parentes revient sur « " + zone.getKey() + " »");
                    break;
                }
                current = parents.get(current);
            }
        }
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value instanceof JsonPrimitive primitive && primitive.isString()
                ? primitive.getAsString()
                : null;
    }

    private static String bounds(Num number) {
        String low = number.exclusiveMin() ? "]" : "[";
        return low + format(number.min()) + ", " + format(number.max()) + "]";
    }

    private static String format(double value) {
        if (Double.isInfinite(value)) {
            return value > 0 ? "+∞" : "−∞";
        }
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    private void invalid(String path, String message) {
        issues.add(new Issue(DefinitionException.INVALID, path, message));
    }
}
