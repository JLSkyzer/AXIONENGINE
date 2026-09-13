package dev.axion.definition;

import com.google.gson.GsonBuilder;
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
import java.util.Map;
import java.util.StringJoiner;

/**
 * Publie le schéma de definition 1 en JSON Schema (R-1783).
 *
 * <p>Le fichier {@code docs/schema/definition-1.json} n'est pas écrit à la main :
 * il est rendu depuis {@link SchemaOne}, l'arbre même que le validateur parcourt.
 * Un test vérifie que le fichier publié est identique au rendu ; une
 * modification du schéma qui oublierait le fichier casse donc le build.
 *
 * <p>JSON Schema n'exprime pas tout ce que vérifie AXION : unicité des noms,
 * résolution des références, liaison entre deux parts distinctes, cycles de
 * zones parentes. Ces règles sont signalées par des annotations {@code x-axion-*},
 * que tout validateur JSON Schema ignore, et restent vérifiées au chargement.
 */
public final class SchemaExport {

    /** Dialecte du schéma publié. */
    static final String DIALECT = "https://json-schema.org/draft/2020-12/schema";

    /** Nom interne : ASCII imprimable, 1 à 64 octets (ADR-109, point 7). */
    static final String NAME_PATTERN = "^[ -~]{1,64}$";

    /** Identifiant de ressource {@code <ns>:<chemin>}. */
    static final String ID_PATTERN = "^[a-z0-9_.-]+:[a-z0-9/._-]+$";

    /** {@code asset} : identifiant dont le chemin est relatif à {@code axion/} (ADR-109, point 3). */
    static final String ASSET_PATTERN = "^[a-z0-9_.-]+:(?!axion/)[a-z0-9/._-]+$";

    private SchemaExport() {}

    /**
     * {@return le schéma publié, tel qu'il doit figurer dans le dépôt}
     *
     * <p>Rendu déterministe : l'ordre des clés est celui de la documentation, et
     * le fichier se termine par un saut de ligne.
     */
    public static String render() {
        return new GsonBuilder()
                .setPrettyPrinting()
                .disableHtmlEscaping()
                .create()
                .toJson(document()) + "\n";
    }

    /** {@return le schéma publié, sous forme d'arbre} */
    static JsonObject document() {
        JsonObject document = new JsonObject();
        document.addProperty("$schema", DIALECT);
        document.addProperty("title", "AXION ENGINE — definition, schéma 1");
        document.addProperty("description",
                "Forme publiée du schéma de definition 1 (R-1783), rendue depuis le validateur "
                        + "d'AXION et lue selon l'ADR-109. AXION vérifie en plus, au chargement, "
                        + "ce que JSON Schema n'exprime pas : unicité des noms par catégorie "
                        + "(x-axion-declares), résolution des références internes "
                        + "(x-axion-refers), liaison entre deux parts distinctes, absence de "
                        + "cycle de zones parentes, module vehicles actif pour kind vehicle. Les "
                        + "noms d'asset (node, mesh, région, clip, os, collider) se résolvent "
                        + "contre l'asset compilé. Un plafond d'assembly dépassé porte E-3050 "
                        + "(x-axion-code), une version de schéma inconnue E-7003, toute autre "
                        + "faute E-7001.");

        JsonObject root = node(SchemaOne.ROOT);
        for (Map.Entry<String, JsonElement> member : root.entrySet()) {
            document.add(member.getKey(), member.getValue());
        }

        // Ces trois champs sont vérifiés avant le parcours de l'arbre, avec leurs
        // codes propres : l'arbre les porte comme valeurs quelconques, le schéma
        // publié leur rend leur forme.
        JsonObject properties = document.getAsJsonObject("properties");
        properties.add("schema", schemaField());
        properties.add("asset", assetField());
        properties.add("kind", kindField());
        return document;
    }

    private static JsonObject node(SchemaNode node) {
        JsonObject out = new JsonObject();
        if (node instanceof Any) {
            return out;
        }
        if (node instanceof Nullable nullable) {
            JsonArray choices = new JsonArray();
            choices.add(node(nullable.node()));
            JsonObject nothing = new JsonObject();
            nothing.addProperty("type", "null");
            choices.add(nothing);
            out.add("anyOf", choices);
        } else if (node instanceof Obj object) {
            out.addProperty("type", "object");
            JsonObject properties = new JsonObject();
            JsonArray required = new JsonArray();
            for (Map.Entry<String, Field> field : object.fields().entrySet()) {
                properties.add(field.getKey(), node(field.getValue().node()));
                if (field.getValue().required()) {
                    required.add(field.getKey());
                }
            }
            out.add("properties", properties);
            if (required.size() > 0) {
                out.add("required", required);
            }
            out.addProperty("additionalProperties", false);
        } else if (node instanceof Arr array) {
            out.addProperty("type", "array");
            out.add("items", node(array.item()));
            if (array.min() > 0) {
                out.addProperty("minItems", array.min());
            }
            if (array.max() != Integer.MAX_VALUE) {
                out.addProperty("maxItems", array.max());
                if (array.limit()) {
                    out.addProperty("x-axion-code", "E-3050");
                }
            }
        } else if (node instanceof Dict dict) {
            out.addProperty("type", "object");
            JsonObject keys = text(dict.key());
            // `size()` et non `isEmpty()` : Minecraft 1.20.1 embarque un Gson
            // antérieur à `JsonObject.isEmpty()`.
            if (keys.size() > 0) {
                keys.addProperty("type", "string");
                out.add("propertyNames", keys);
            }
            out.add("additionalProperties", node(dict.value()));
            if (dict.max() != Integer.MAX_VALUE) {
                out.addProperty("maxProperties", dict.max());
                out.addProperty("x-axion-code", "E-3050");
            }
        } else if (node instanceof Text text) {
            out.addProperty("type", "string");
            for (Map.Entry<String, JsonElement> member : text(text.rule()).entrySet()) {
                out.add(member.getKey(), member.getValue());
            }
        } else if (node instanceof Num number) {
            out.addProperty("type", number.integer() ? "integer" : "number");
            if (Double.isFinite(number.min())) {
                out.add(number.exclusiveMin() ? "exclusiveMinimum" : "minimum", bound(number.min()));
            }
            if (Double.isFinite(number.max())) {
                out.add("maximum", bound(number.max()));
            }
        } else if (node instanceof Bool) {
            out.addProperty("type", "boolean");
        }
        return out;
    }

    /** Contraintes d'une chaîne, sans son type. */
    private static JsonObject text(TextRule rule) {
        JsonObject out = new JsonObject();
        if (rule instanceof TextRule.OneOf oneOf) {
            JsonArray values = new JsonArray();
            oneOf.values().forEach(values::add);
            out.add("enum", values);
        } else if (rule instanceof TextRule.Declares declares) {
            out.addProperty("pattern", NAME_PATTERN);
            out.addProperty("x-axion-declares", declares.category().jsonName());
        } else if (rule instanceof TextRule.Refers refers) {
            out.addProperty("pattern", NAME_PATTERN);
            out.addProperty("x-axion-refers", refers.category().jsonName());
        } else if (rule instanceof TextRule.Id) {
            out.addProperty("pattern", ID_PATTERN);
        } else if (rule instanceof TextRule.Source) {
            out.addProperty("pattern", sourcePattern());
        }
        return out;
    }

    /**
     * {@return l'expression des sources procédurales de l'ANNEXE A.4}
     *
     * <p>Même grammaire que le vérificateur : famille, nom éventuel en ASCII
     * imprimable de 1 à 64 octets, champ fermé ; {@code var.<n>} admet tout nom
     * non vide. Familles et champs ne contiennent que {@code [a-z_]}, et
     * s'écrivent donc tels quels dans l'expression.
     */
    static String sourcePattern() {
        StringJoiner alternatives = new StringJoiner("|", "^(?:", ")$");
        for (SourceFamily family : SchemaOne.SOURCES) {
            String fields = "(?:" + String.join("|", family.fields()) + ")";
            if (!family.named()) {
                alternatives.add(family.prefix() + "\\." + fields);
            } else if (family.fields().isEmpty()) {
                alternatives.add(family.prefix() + "\\..+");
            } else {
                alternatives.add(family.prefix() + "\\.[ -~]{1,64}\\." + fields);
            }
        }
        return alternatives.toString();
    }

    private static JsonObject schemaField() {
        JsonObject out = new JsonObject();
        out.addProperty("type", "integer");
        out.addProperty("const", DefinitionRegistry.SCHEMA);
        out.addProperty("x-axion-code", "E-7003");
        return out;
    }

    private static JsonObject assetField() {
        JsonObject out = new JsonObject();
        out.addProperty("type", "string");
        out.addProperty("pattern", ASSET_PATTERN);
        return out;
    }

    private static JsonObject kindField() {
        JsonObject out = new JsonObject();
        out.addProperty("type", "string");
        JsonArray values = new JsonArray();
        for (AssemblyKind kind : AssemblyKind.values()) {
            values.add(kind.json());
        }
        out.add("enum", values);
        return out;
    }

    /** Une borne entière s'écrit sans décimale : {@code 64}, pas {@code 64.0}. */
    private static JsonPrimitive bound(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return new JsonPrimitive((long) value);
        }
        return new JsonPrimitive(value);
    }
}
