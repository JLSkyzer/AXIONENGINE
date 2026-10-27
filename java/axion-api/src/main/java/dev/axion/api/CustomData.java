package dev.axion.api;

import java.util.Optional;
import java.util.Set;

/**
 * Données personnalisées qu'un mod tiers attache à une assembly (PARTIE 22.1, ADR-153 D7).
 *
 * <p>Chaque mod écrit sous son espace de noms un tableau d'octets qu'il encode comme il veut ; AXION
 * n'en lit pas le contenu. L'ensemble est persisté avec l'assembly, dans une table CBOR, et plafonné
 * par {@code persistence.max_custom_bytes} : une écriture qui le dépasserait est refusée sans rien
 * changer. Une table persistée plus grosse que le plafond — plafond baissé depuis — reste intacte ;
 * seule une écriture qui l'agrandirait est refusée. Une table illisible reste intacte, et rien ne
 * s'y écrit.
 *
 * <p>Thread : autoritatif.
 */
@Stable
public interface CustomData {

    /** {@return les espaces de noms qui ont des données, triés} */
    Set<String> namespaces();

    /**
     * {@return une copie des données d'un espace de noms, ou vide s'il n'en a pas}
     *
     * @param namespace espace de noms d'un mod, aux règles d'un identifiant de ressource
     *     ({@code [a-z0-9_.-]+})
     * @throws IllegalArgumentException si l'espace de noms n'en suit pas les règles
     */
    Optional<byte[]> get(String namespace);

    /**
     * Écrit les données d'un espace de noms, à la place des précédentes.
     *
     * @param namespace espace de noms d'un mod ({@code [a-z0-9_.-]+})
     * @param value les octets, copiés
     * @return faux, sans rien changer, si la table encodée dépasserait le plafond — ou la taille
     *     qu'elle a déjà, si elle le dépasse — ou si la table est illisible ou en lecture seule
     * @throws IllegalArgumentException si l'espace de noms n'en suit pas les règles
     * @throws NullPointerException si {@code value} est nul
     */
    boolean put(String namespace, byte[] value);

    /**
     * Retire les données d'un espace de noms ; sans effet s'il n'en a pas, ou si la table est
     * illisible ou en lecture seule.
     *
     * @param namespace espace de noms d'un mod ({@code [a-z0-9_.-]+})
     * @throws IllegalArgumentException si l'espace de noms n'en suit pas les règles
     */
    void remove(String namespace);
}
