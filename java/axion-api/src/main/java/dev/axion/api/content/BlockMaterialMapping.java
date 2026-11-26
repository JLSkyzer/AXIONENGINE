package dev.axion.api.content;

import dev.axion.api.Experimental;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Des correspondances bloc vers matériau physique écrites en Java (§11.4, R-1040, ADR-180) : ce que dit un fichier
 * {@code block_materials/<nom>.json}. Elles s'enregistrent par {@link ContentRegistrar#registerBlockMaterials}, pour un
 * mod qui les calcule de ses blocs plutôt que de les écrire une à une.
 *
 * <p>L'ordre des étiquettes est leur priorité : il est gardé tel que posé. Les identifiants et les matériaux se
 * vérifient au chargement, comme ceux d'un fichier : une entrée fautive est refusée, nommée, et les autres restent.
 *
 * @param defaultMaterial le matériau d'un bloc que rien ne nomme, ou {@code null} pour ne pas le changer
 * @param tags le matériau par étiquette de bloc ({@code <ns>:<chemin>}), la première posée étant la plus prioritaire
 * @param blocks le matériau par bloc ({@code <ns>:<chemin>}), qui prime sur ses étiquettes
 * @param covers les blocs sans collision dont le matériau couvre le bloc qu'ils surmontent (ADR-180)
 */
@Experimental
public record BlockMaterialMapping(
        String defaultMaterial, Map<String, String> tags, Map<String, String> blocks, List<String> covers) {

    /**
     * Copie et fige les listes, dans l'ordre reçu.
     *
     * @param defaultMaterial le matériau d'un bloc que rien ne nomme, ou {@code null}
     * @param tags le matériau par étiquette, la première prioritaire
     * @param blocks le matériau par bloc
     * @param covers les blocs couvrants
     * @throws NullPointerException si une liste, une clé ou un matériau manque
     */
    public BlockMaterialMapping {
        tags = ordered(tags, "tags");
        blocks = ordered(blocks, "blocks");
        covers = List.copyOf(covers);
    }

    /** {@return un constructeur de correspondances vide} */
    public static Builder builder() {
        return new Builder();
    }

    private static Map<String, String> ordered(Map<String, String> entries, String name) {
        Objects.requireNonNull(entries, name);
        Map<String, String> copy = new LinkedHashMap<>();
        entries.forEach((key, material) -> copy.put(
                Objects.requireNonNull(key, name), Objects.requireNonNull(material, name + " " + key)));
        return Collections.unmodifiableMap(copy);
    }

    /** Pose les correspondances une à une ; une clé reposée garde sa place et prend le nouveau matériau. */
    public static final class Builder {

        private String defaultMaterial;
        private final Map<String, String> tags = new LinkedHashMap<>();
        private final Map<String, String> blocks = new LinkedHashMap<>();
        private final Set<String> covers = new LinkedHashSet<>();

        private Builder() {}

        /**
         * {@return ce constructeur}
         *
         * @param material le matériau d'un bloc que rien ne nomme
         */
        public Builder defaultMaterial(String material) {
            this.defaultMaterial = Objects.requireNonNull(material, "material");
            return this;
        }

        /**
         * {@return ce constructeur}
         *
         * @param tag une étiquette de bloc ; les premières posées priment
         * @param material son matériau
         */
        public Builder tag(String tag, String material) {
            tags.put(Objects.requireNonNull(tag, "tag"), Objects.requireNonNull(material, "material"));
            return this;
        }

        /**
         * {@return ce constructeur}
         *
         * @param block un bloc
         * @param material son matériau, qui prime sur ses étiquettes
         */
        public Builder block(String block, String material) {
            blocks.put(Objects.requireNonNull(block, "block"), Objects.requireNonNull(material, "material"));
            return this;
        }

        /**
         * {@return ce constructeur}
         *
         * @param blocks des blocs sans collision dont le matériau couvre le bloc qu'ils surmontent
         */
        public Builder cover(String... blocks) {
            for (String block : blocks) {
                covers.add(Objects.requireNonNull(block, "block"));
            }
            return this;
        }

        /** {@return les correspondances posées, figées} */
        public BlockMaterialMapping build() {
            return new BlockMaterialMapping(defaultMaterial, tags, blocks, new ArrayList<>(covers));
        }
    }
}
