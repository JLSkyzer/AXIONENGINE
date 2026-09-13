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

    private DefinitionChecker() {}

    /**
     * {@return les fautes d'une definition, dans l'ordre du document}
     *
     * @param root document, dont {@code schema}, {@code asset} et {@code kind}
     *     ont déjà été vérifiés
     */
    static List<Issue> check(JsonObject root) {
        DefinitionChecker checker = new DefinitionChecker();
        checker.walk(SchemaOne.ROOT, root, "$");
        checker.resolve();
        checker.checkLinks(root);
        checker.checkZoneParents(root);
        return List.copyOf(checker.issues);
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
                invalid(child, "clé inconnue du schéma 1");
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
        int colon = value.indexOf(':');
        boolean valid = colon > 0
                && DefinitionRegistry.NAMESPACE.matcher(value.substring(0, colon)).matches()
                && DefinitionRegistry.RESOURCE_PATH.matcher(value.substring(colon + 1)).matches();
        if (!valid) {
            invalid(path, "« " + value + " » n'est pas un identifiant <ns>:<chemin>");
        }
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
                // var.<n> : le nom est libre, défini par l'API ou par une action.
                if (rest.isEmpty()) {
                    invalid(path, "variable sans nom");
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
