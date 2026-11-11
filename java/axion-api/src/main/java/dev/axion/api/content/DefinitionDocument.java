package dev.axion.api.content;

import dev.axion.api.Experimental;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Une definition construite en Java (ADR-162 D3, D4) : le même document qu'un fichier JSON de definition, sous forme
 * d'arbre de valeurs, sans dépendance.
 *
 * <p>Le builder généré depuis le schéma en produit un ; {@link #of} en accepte un écrit à la main. Les valeurs
 * admises sont celles de JSON : {@link String}, {@link Boolean}, les nombres finis — rangés en {@link Long} pour
 * {@code Byte}, {@code Short}, {@code Integer} et {@code Long}, en {@link Double} pour {@code Float} et
 * {@code Double} —, les {@link List} de valeurs, les {@link Map} à clés textuelles, et {@code null}, le
 * {@code null} de JSON (un mesh absent, par exemple). Ni lambda, ni autre objet : une definition part chez le client
 * (R-581).
 *
 * <p>Le document est copié et figé à la construction. Sa validation est celle d'un fichier : le même schéma, au
 * chargement, et la même forme canonique, donc la même empreinte qu'un fichier de même contenu (CAP-47).
 */
@Experimental
public final class DefinitionDocument {

    private final Map<String, Object> root;

    private DefinitionDocument(Map<String, Object> root) {
        this.root = root;
    }

    /**
     * {@return un document, copie figée de cet arbre}
     *
     * @param root l'objet racine de la definition
     * @throws IllegalArgumentException si une valeur n'est pas une valeur JSON finie ; le message nomme son chemin
     */
    public static DefinitionDocument of(Map<String, ?> root) {
        Objects.requireNonNull(root, "root");
        return new DefinitionDocument(object(root, "$"));
    }

    /** {@return l'objet racine, non modifiable, dans l'ordre où ses clés ont été posées} */
    public Map<String, Object> root() {
        return root;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof DefinitionDocument document && root.equals(document.root);
    }

    @Override
    public int hashCode() {
        return root.hashCode();
    }

    private static Map<String, Object> object(Map<?, ?> map, String path) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException(path + " : clé non textuelle « " + entry.getKey() + " »");
            }
            copy.put(key, value(entry.getValue(), path + "." + key));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Object value(Object value, String path) {
        if (value == null || value instanceof String || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return ((Number) value).longValue();
        }
        if (value instanceof Float || value instanceof Double) {
            double number = ((Number) value).doubleValue();
            if (!Double.isFinite(number)) {
                throw new IllegalArgumentException(path + " : nombre non fini (" + number + ")");
            }
            return number;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (int i = 0; i < list.size(); i++) {
                copy.add(value(list.get(i), path + "[" + i + "]"));
            }
            return Collections.unmodifiableList(copy);
        }
        if (value instanceof Map<?, ?> map) {
            return object(map, path);
        }
        throw new IllegalArgumentException(path + " : " + value.getClass().getName()
                + " n'est pas une valeur de definition");
    }
}
