package dev.axion.definition;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * La révision d'une definition et ce qui la fait évoluer sous des assemblies sauvegardées (ADR-175 D3, schéma 7) : un
 * numéro, les renommages qui mènent d'une révision à la suivante, et les identifiants qu'elle remplace. Une migration ne
 * crée ni ne calcule rien : elle renomme ; la logique reste en Java (ADR-160 Q4). Logique pure, sans Forge.
 *
 * @param revision la révision de la definition, 1 sans {@code revision}
 * @param migrations les migrations, par {@code from} croissant
 * @param replaces les identifiants des definitions qu'elle remplace, dans l'ordre du document
 */
public record Revisions(int revision, List<Migration> migrations, List<String> replaces) {

    /** Ce qu'une migration renomme, dans l'ordre de la documentation. */
    public static final List<String> KINDS = List.of("nodes", "joints", "variables", "seats", "sockets", "parameters");

    /** Migrations d'une definition, au plus. */
    public static final int MAX_MIGRATIONS = 64;

    /** Renommages d'une catégorie dans une migration, au plus. */
    public static final int MAX_RENAMES = 256;

    /** Definitions qu'une definition remplace, au plus. */
    public static final int MAX_REPLACES = 8;

    /** Une definition sans révision déclarée. */
    public static final Revisions NONE = new Revisions(1, List.of(), List.of());

    /**
     * Les renommages qui mènent de la révision {@code from} à la suivante.
     *
     * @param from la révision de départ
     * @param renames pour chaque catégorie de {@link #KINDS}, ancien nom → nouveau nom
     */
    public record Migration(int from, Map<String, Map<String, String>> renames) {

        /** Copie les tables. */
        public Migration {
            Map<String, Map<String, String>> copy = new LinkedHashMap<>();
            renames.forEach((kind, names) -> copy.put(kind, Map.copyOf(names)));
            renames = java.util.Collections.unmodifiableMap(copy);
        }

        /**
         * {@return les renommages d'une catégorie, vides sans}
         *
         * @param kind une catégorie de {@link Revisions#KINDS}
         */
        public Map<String, String> of(String kind) {
            return renames.getOrDefault(kind, Map.of());
        }
    }

    /** Copie les listes. */
    public Revisions {
        migrations = List.copyOf(migrations);
        replaces = List.copyOf(replaces);
    }

    /**
     * {@return la révision et les migrations d'une definition acceptée au schéma 7, {@link #NONE} avant}
     *
     * @param root document de la definition
     */
    public static Revisions of(JsonObject root) {
        if (!(root.get("schema") instanceof JsonPrimitive schema) || schema.getAsInt() < 7) {
            return NONE;
        }
        int revision = root.get("revision") instanceof JsonPrimitive number && number.isNumber()
                ? number.getAsInt() : 1;
        List<Migration> migrations = new ArrayList<>();
        if (root.get("migrations") instanceof JsonArray declared) {
            for (JsonElement element : declared) {
                if (!(element instanceof JsonObject migration) || !(migration.get("from") instanceof JsonPrimitive from)
                        || !from.isNumber()) {
                    continue;
                }
                Map<String, Map<String, String>> renames = new LinkedHashMap<>();
                if (migration.get("rename") instanceof JsonObject rename) {
                    for (String kind : KINDS) {
                        if (rename.get(kind) instanceof JsonObject names) {
                            Map<String, String> table = new LinkedHashMap<>();
                            names.entrySet().forEach(entry -> {
                                if (entry.getValue() instanceof JsonPrimitive to && to.isString()) {
                                    table.put(entry.getKey(), to.getAsString());
                                }
                            });
                            renames.put(kind, table);
                        }
                    }
                }
                migrations.add(new Migration(from.getAsInt(), renames));
            }
        }
        migrations.sort(Comparator.comparingInt(Migration::from));
        List<String> replaces = new ArrayList<>();
        if (root.get("replaces") instanceof JsonArray ids) {
            ids.forEach(id -> {
                if (id instanceof JsonPrimitive text && text.isString()) {
                    replaces.add(text.getAsString());
                }
            });
        }
        return new Revisions(revision, migrations, replaces);
    }

    /**
     * {@return les migrations qui mènent un état sauvegardé à cette révision, dans l'ordre} Celles dont {@code from} va de
     * la révision sauvegardée à celle-ci, exclue : un état déjà à la révision courante n'en subit aucune.
     *
     * @param saved la révision que l'état gardait
     */
    public List<Migration> chain(int saved) {
        return migrations.stream().filter(migration -> migration.from() >= saved && migration.from() < revision)
                .toList();
    }
}
