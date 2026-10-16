package dev.axion.definition;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.axion.asset.AssetSource;
import dev.axion.world.BlockMaterials;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Mappages bloc → matériau physique, {@code data/<ns>/axion/block_materials/**.json} (§11.4,
 * R-643, R-1040, R-1041, ADR-145 point 4).
 *
 * <p>Un fichier porte {@code schema} 1 et, chacun facultatif, {@code default}, {@code by_tag} et
 * {@code by_block}, qui nomment des matériaux du registre {@code axion:physics_material}. Les
 * fichiers se fusionnent dans l'ordre de leurs identifiants : une entrée d'un fichier plus tardif
 * remplace celle d'un plus précoce, ce que les notes disent ; {@code default} aussi. La priorité
 * des étiquettes est leur ordre de déclaration — fichier après fichier, et une étiquette reprise
 * par un fichier plus tardif garde sa place. Une entrée qui nomme un matériau inconnu est refusée,
 * nommée ; les autres entrées du fichier restent.
 *
 * <p>Ce que le mappage garde de chaque matériau est son contact (groupe CONTACT de DM-07), ce que
 * les tuiles transmettent en M3. Sans {@code default} nulle part, le défaut est l'acier générique
 * (R-1032).
 */
public final class BlockMaterialMappings {

    /** Préfixe des mappages, relatif à la racine du gestionnaire de données. */
    public static final String PREFIX = "axion/block_materials/";

    /** Extension des mappages. */
    public static final String EXTENSION = ".json";

    /** Seule version de format lue. */
    public static final int SCHEMA = 1;

    private static final Set<String> KEYS = Set.of("schema", "default", "by_tag", "by_block");

    private BlockMaterialMappings() {}

    /**
     * Le mappage chargé, et ce que le chargement a dit.
     *
     * @param mapping le mappage fusionné, contact résolu
     * @param refusals fichiers et entrées refusés, nommés et localisés
     * @param notes entrées remplacées par un fichier plus tardif
     */
    public record Result(BlockMaterials mapping, List<String> refusals, List<String> notes) {

        /** Copies figées. */
        public Result {
            refusals = List.copyOf(refusals);
            notes = List.copyOf(notes);
        }
    }

    /**
     * Charge et fusionne les mappages d'une source.
     *
     * @param source ressources sous {@link #PREFIX}
     * @param materials matériaux que les entrées nomment
     * @return le mappage et ce que le chargement a dit
     */
    public static Result load(AssetSource source, PhysicsMaterials materials) {
        List<String> refusals = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        SortedMap<String, String> byId = new TreeMap<>();
        for (String location : source.list()) {
            try {
                byId.put(idOf(location), location);
            } catch (DefinitionException refusal) {
                refusals.add(refusal.codeName() + " " + location + " — " + refusal.path()
                        + " : " + refusal.getMessage());
            }
        }

        Map<String, String> byBlock = new LinkedHashMap<>();
        Map<String, String> byTag = new LinkedHashMap<>();
        Map<String, String> origin = new LinkedHashMap<>();
        String fallback = null;
        String fallbackOrigin = null;
        for (String location : byId.values()) {
            JsonObject root;
            try {
                root = document(source.read(location));
            } catch (DefinitionException refusal) {
                refusals.add(refusal.codeName() + " " + location + " — " + refusal.path()
                        + " : " + refusal.getMessage());
                continue;
            } catch (IOException failure) {
                refusals.add("E-7001 " + location + " — illisible : " + failure.getMessage());
                continue;
            }
            JsonElement declaredDefault = root.get("default");
            if (declaredDefault != null) {
                String material = entry(location, "$.default", declaredDefault, materials, refusals);
                if (material != null) {
                    if (fallback != null) {
                        notes.add(location + " — $.default : « " + material + " » remplace « "
                                + fallback + " » de " + fallbackOrigin);
                    }
                    fallback = material;
                    fallbackOrigin = location;
                }
            }
            section(location, root, "by_tag", byTag, origin, materials, refusals, notes);
            section(location, root, "by_block", byBlock, origin, materials, refusals, notes);
        }

        BlockMaterials mapping = new BlockMaterials(
                contacts(byBlock, materials),
                contacts(byTag, materials),
                contact(fallback == null ? PhysicsMaterials.genericSteel() : materials.resolve(fallback)));
        return new Result(mapping, refusals, notes);
    }

    /** Fusionne une section : une clé reprise remplace l'entrée et garde sa place. */
    private static void section(
            String location,
            JsonObject root,
            String name,
            Map<String, String> merged,
            Map<String, String> origin,
            PhysicsMaterials materials,
            List<String> refusals,
            List<String> notes) {
        JsonElement section = root.get(name);
        if (section == null) {
            return;
        }
        String at = StrictJson.member("$", name);
        if (!(section instanceof JsonObject entries)) {
            refusals.add("E-7001 " + location + " — " + at + " : attendu un objet");
            return;
        }
        for (Map.Entry<String, JsonElement> member : entries.entrySet()) {
            String key = member.getKey();
            String path = StrictJson.member(at, key);
            if (!DefinitionRegistry.isId(key)) {
                refusals.add("E-7001 " + location + " — " + path + " : « " + key
                        + " » n'est pas un identifiant <ns>:<chemin>");
                continue;
            }
            String material = entry(location, path, member.getValue(), materials, refusals);
            if (material == null) {
                continue;
            }
            String slot = name + " " + key;
            String previous = merged.put(key, material);
            if (previous != null) {
                notes.add(location + " — " + path + " : « " + material + " » remplace « " + previous
                        + " » de " + origin.get(slot));
            }
            origin.put(slot, location);
        }
    }

    /** {@return le matériau qu'une entrée nomme, ou {@code null} après un refus} */
    private static String entry(
            String location, String path, JsonElement value, PhysicsMaterials materials,
            List<String> refusals) {
        if (!(value instanceof JsonPrimitive primitive) || !primitive.isString()) {
            refusals.add("E-7001 " + location + " — " + path + " : attendu l'identifiant d'un matériau");
            return null;
        }
        String material = primitive.getAsString();
        if (!materials.knows(material)) {
            refusals.add("E-7001 " + location + " — " + path + " : matériau « " + material
                    + " » inconnu ; aucun data/<ns>/" + PhysicsMaterials.PREFIX + " ne le déclare");
            return null;
        }
        return material;
    }

    private static Map<String, BlockMaterials.Material> contacts(
            Map<String, String> named, PhysicsMaterials materials) {
        Map<String, BlockMaterials.Material> out = new LinkedHashMap<>();
        named.forEach((key, material) -> out.put(key, contact(materials.resolve(material))));
        return out;
    }

    private static BlockMaterials.Material contact(PhysicsMaterials.Material material) {
        return new BlockMaterials.Material((float) material.friction(), (float) material.restitution());
    }

    /** {@return la racine d'un mappage, forme et version jugées} */
    private static JsonObject document(byte[] content) throws DefinitionException {
        JsonElement parsed = StrictJson.parse(content);
        if (!parsed.isJsonObject()) {
            throw DefinitionException.invalid("$", "un mappage bloc → matériau est un objet JSON");
        }
        JsonObject root = parsed.getAsJsonObject();
        for (String key : root.keySet()) {
            if (!KEYS.contains(key)) {
                throw DefinitionException.invalid(StrictJson.member("$", key),
                        "champ inconnu ; admis : schema, default, by_tag, by_block");
            }
        }
        JsonElement schema = root.get("schema");
        if (schema == null) {
            throw DefinitionException.invalid("$.schema", "champ obligatoire");
        }
        if (!(schema instanceof JsonPrimitive primitive) || !primitive.isNumber()
                || primitive.getAsBigDecimal().compareTo(BigDecimal.valueOf(SCHEMA)) != 0) {
            throw DefinitionException.unknownSchema("$.schema", "version " + schema
                    + " inconnue, seule " + SCHEMA + " est lue");
        }
        return root;
    }

    /** {@return l'identifiant d'un mappage, l'ordre de fusion} */
    private static String idOf(String location) throws DefinitionException {
        int colon = location.indexOf(':');
        String path = colon < 0 ? "" : location.substring(colon + 1);
        if (!path.startsWith(PREFIX)
                || !path.endsWith(EXTENSION)
                || path.length() <= PREFIX.length() + EXTENSION.length()) {
            throw DefinitionException.invalid(
                    "$", "ressource hors de <ns>:" + PREFIX + "<nom>" + EXTENSION);
        }
        return location.substring(0, colon) + ":"
                + path.substring(PREFIX.length(), path.length() - EXTENSION.length());
    }
}
