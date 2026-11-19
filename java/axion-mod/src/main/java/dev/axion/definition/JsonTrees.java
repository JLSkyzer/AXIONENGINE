package dev.axion.definition;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Un morceau de definition rendu en arbre Java, tel que l'API le donne à un mod (ADR-174 D6) : un objet devient une
 * {@code Map<String, Object>} dans l'ordre du document, un tableau une {@code List<Object>}, un nombre un
 * {@code Double}, un texte un {@code String}, un booléen un {@code Boolean}, {@code null} {@code null}. Immuable.
 */
public final class JsonTrees {

    private JsonTrees() {}

    /**
     * {@return l'arbre d'un objet JSON, vide sans}
     *
     * @param object l'objet, ou {@code null}
     */
    public static Map<String, Object> of(JsonObject object) {
        if (object == null) {
            return Map.of();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> tree = (Map<String, Object>) value(object);
        return tree;
    }

    private static Object value(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element instanceof JsonObject object) {
            Map<String, Object> members = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> member : object.entrySet()) {
                members.put(member.getKey(), value(member.getValue()));
            }
            return Collections.unmodifiableMap(members);
        }
        if (element instanceof JsonArray array) {
            List<Object> items = new ArrayList<>(array.size());
            for (JsonElement item : array) {
                items.add(value(item));
            }
            return Collections.unmodifiableList(items);
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            return primitive.getAsBoolean();
        }
        if (primitive.isNumber()) {
            return primitive.getAsDouble();
        }
        return primitive.getAsString();
    }
}
