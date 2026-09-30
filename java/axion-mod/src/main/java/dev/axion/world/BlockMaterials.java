package dev.axion.world;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mappage <b>data-driven</b> des blocs vers leur matériau physique de contact (C-38,
 * R-643).
 *
 * <p>Chaque tuile de collision porte le matériau <b>dominant</b> de ses blocs, résolu
 * ici : un bloc emprunte son frottement et sa restitution à une entrée directe par
 * identifiant, sinon à l'une de ses étiquettes (héritage par tag), sinon au défaut
 * générique. <b>Aucun nom de bloc n'est codé en dur</b> (R-643) : tout vient des données.
 *
 * <p>La classe est <b>pure</b> (sans Forge) : le chargement de la ressource et la lecture
 * des étiquettes d'un bloc vivent dans {@code dev.axion.forge} (R-401) et l'alimentent.
 */
public final class BlockMaterials {

    /** Matériau de contact d'un bloc : frottement et restitution (sous-ensemble DM-07). */
    public record Material(float friction, float restitution) {}

    /**
     * Défaut générique appliqué à un bloc sans entrée ni étiquette connue (R-643). Valeur
     * de matière neutre, pas un chiffre de performance ; surchargée par la ressource.
     */
    public static final Material GENERIC_DEFAULT = new Material(0.6f, 0.0f);

    private final Map<String, Material> byBlock;
    private final Map<String, Material> byTag;
    private final Material fallback;

    /**
     * @param byBlock matériau par identifiant de bloc exact
     * @param byTag matériau par étiquette
     * @param fallback matériau générique par défaut
     */
    public BlockMaterials(
            Map<String, Material> byBlock, Map<String, Material> byTag, Material fallback) {
        this.byBlock = Map.copyOf(byBlock);
        this.byTag = Map.copyOf(byTag);
        this.fallback = fallback;
    }

    /** {@return un mappage vide : tout bloc résout vers {@link #GENERIC_DEFAULT}} */
    public static BlockMaterials empty() {
        return new BlockMaterials(Map.of(), Map.of(), GENERIC_DEFAULT);
    }

    /**
     * Résout le matériau d'un bloc (R-643) : entrée directe par identifiant d'abord, sinon
     * première étiquette correspondante <b>dans l'ordre fourni</b> (l'appelant les donne
     * par priorité décroissante), sinon le défaut.
     *
     * @param blockId identifiant du bloc, par ex. {@code minecraft:ice}
     * @param tags étiquettes du bloc, par priorité décroissante
     * @return le matériau résolu, jamais {@code null}
     */
    public Material resolve(String blockId, List<String> tags) {
        Material direct = byBlock.get(blockId);
        if (direct != null) {
            return direct;
        }
        for (String tag : tags) {
            Material tagged = byTag.get(tag);
            if (tagged != null) {
                return tagged;
            }
        }
        return fallback;
    }

    /** {@return le matériau générique par défaut de ce mappage} */
    public Material fallback() {
        return fallback;
    }

    /**
     * Construit un mappage depuis un document JSON (déjà parsé par {@code StrictJson}).
     *
     * <p>Forme attendue — toutes les sections optionnelles :
     *
     * <pre>{@code
     * {
     *   "default": { "friction": 0.6, "restitution": 0.0 },
     *   "blocks":  { "minecraft:ice": { "friction": 0.05 } },
     *   "tags":    { "minecraft:logs": { "friction": 0.7 } }
     * }
     * }</pre>
     *
     * <p>Dans une entrée de matériau, {@code friction} est requise ; {@code restitution}
     * vaut {@code 0} par défaut. Les valeurs sont bornées aux plages DM-07 ({@code [0, 2]},
     * {@code [0, 1]}).
     *
     * @param root document JSON racine
     * @return le mappage construit
     * @throws IllegalArgumentException si la forme ou une entrée est invalide
     */
    public static BlockMaterials fromJson(JsonElement root) {
        if (root == null || !root.isJsonObject()) {
            throw new IllegalArgumentException("block_materials doit être un objet JSON");
        }
        JsonObject obj = root.getAsJsonObject();
        Material fallback =
                obj.has("default") ? readMaterial(obj.get("default"), "default") : GENERIC_DEFAULT;
        Map<String, Material> byBlock = readSection(obj, "blocks");
        Map<String, Material> byTag = readSection(obj, "tags");
        return new BlockMaterials(byBlock, byTag, fallback);
    }

    private static Map<String, Material> readSection(JsonObject obj, String name) {
        Map<String, Material> out = new LinkedHashMap<>();
        if (!obj.has(name)) {
            return out;
        }
        JsonElement section = obj.get(name);
        if (!section.isJsonObject()) {
            throw new IllegalArgumentException("block_materials." + name + " doit être un objet");
        }
        for (Map.Entry<String, JsonElement> entry : section.getAsJsonObject().entrySet()) {
            out.put(entry.getKey(), readMaterial(entry.getValue(), name + "." + entry.getKey()));
        }
        return out;
    }

    private static Material readMaterial(JsonElement element, String path) {
        if (element == null || !element.isJsonObject()) {
            throw new IllegalArgumentException(path + " doit être un objet {friction, restitution}");
        }
        JsonObject material = element.getAsJsonObject();
        if (!material.has("friction")) {
            throw new IllegalArgumentException(path + " : « friction » est requise");
        }
        float friction = clamp(readFloat(material, "friction", path), 0.0f, 2.0f);
        float restitution =
                material.has("restitution")
                        ? clamp(readFloat(material, "restitution", path), 0.0f, 1.0f)
                        : 0.0f;
        return new Material(friction, restitution);
    }

    private static float readFloat(JsonObject obj, String key, String path) {
        JsonElement value = obj.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(path + " : « " + key + " » doit être un nombre");
        }
        float f = value.getAsFloat();
        if (!Float.isFinite(f)) {
            throw new IllegalArgumentException(path + " : « " + key + " » doit être fini");
        }
        return f;
    }

    private static float clamp(float value, float lo, float hi) {
        return Math.max(lo, Math.min(hi, value));
    }
}
