package dev.axion.world;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mappage <b>data-driven</b> des blocs vers le contact de leur matériau physique (C-38, R-643,
 * §11.4, ADR-145).
 *
 * <p>Chaque tuile de collision porte le matériau <b>dominant</b> de ses blocs, résolu ici dans
 * l'ordre de R-1040 : une entrée par identifiant de bloc, sinon la première entrée par étiquette
 * — dans l'<b>ordre de déclaration</b> du mappage, l'auteur rangeant ses étiquettes de la plus
 * précise à la plus générale — que le bloc porte, sinon le défaut. <b>Aucun nom de bloc n'est
 * codé en dur</b> (R-643) : tout vient des données.
 *
 * <p>La classe est <b>pure</b> (sans Forge) et ne garde que le groupe CONTACT de chaque matériau,
 * ce que les tuiles transmettent en M3 ; le mappage se construit depuis les fichiers
 * {@code block_materials} et les matériaux physiques qu'ils nomment
 * ({@code dev.axion.definition.BlockMaterialMappings}). La lecture des étiquettes d'un bloc vit
 * dans {@code dev.axion.forge} (R-401).
 */
public final class BlockMaterials {

    /** Contact d'un matériau : frottement et restitution (groupe CONTACT de DM-07). */
    public record Material(float friction, float restitution) {}

    /**
     * Contact du solveur sans matériau déclaré — son frottement et sa restitution par défaut —,
     * celui d'un mappage vide, avant tout chargement de données.
     */
    public static final Material SOLVER_DEFAULT = new Material(0.5f, 0.0f);

    private final Map<String, Material> byBlock;
    private final Map<String, Material> byTag;
    private final Material fallback;

    /**
     * @param byBlock contact par identifiant de bloc exact
     * @param byTag contact par étiquette, dans l'ordre de priorité
     * @param fallback contact par défaut
     */
    public BlockMaterials(
            Map<String, Material> byBlock, Map<String, Material> byTag, Material fallback) {
        this.byBlock = Map.copyOf(byBlock);
        // L'ordre des étiquettes est la priorité : une copie qui le garde.
        this.byTag = Collections.unmodifiableMap(new LinkedHashMap<>(byTag));
        this.fallback = fallback;
    }

    /** {@return un mappage vide : tout bloc résout vers {@link #SOLVER_DEFAULT}} */
    public static BlockMaterials empty() {
        return new BlockMaterials(Map.of(), Map.of(), SOLVER_DEFAULT);
    }

    /**
     * Résout le contact d'un bloc (R-1040) : entrée par identifiant d'abord, sinon la première
     * entrée par étiquette, dans l'ordre du mappage, que le bloc porte, sinon le défaut.
     *
     * @param blockId identifiant du bloc, par ex. {@code minecraft:ice}
     * @param tags étiquettes du bloc, dans un ordre quelconque
     * @return le contact résolu, jamais {@code null}
     */
    public Material resolve(String blockId, Collection<String> tags) {
        Material direct = byBlock.get(blockId);
        if (direct != null) {
            return direct;
        }
        for (Map.Entry<String, Material> tagged : byTag.entrySet()) {
            if (tags.contains(tagged.getKey())) {
                return tagged.getValue();
            }
        }
        return fallback;
    }

    /** {@return le contact par défaut de ce mappage} */
    public Material fallback() {
        return fallback;
    }
}
