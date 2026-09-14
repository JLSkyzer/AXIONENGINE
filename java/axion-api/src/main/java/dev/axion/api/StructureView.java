package dev.axion.api;

import java.util.Collection;
import java.util.Set;

/**
 * Vue en lecture du graphe structurel d'une assembly (§23.2, DM-14).
 *
 * <p>Lectures d'un instantané autoritatif. Thread : autoritatif ou rendu. Coût :
 * négligeable, sauf {@link #connectedParts()} qui parcourt le graphe. Échec :
 * aucun ; un nom de liaison ou de part inconnu rend une valeur neutre.
 */
@Stable
public interface StructureView {

    /**
     * {@return l'intégrité d'une liaison, dans {@code [0, 1]}}
     *
     * @param link nom de la liaison structurelle
     */
    float integrity(String link);

    /** {@return les noms des liaisons structurelles de l'assembly}. */
    Collection<String> linkNames();

    /**
     * {@return vrai si la part est détachée de l'assembly}
     *
     * @param part nom de la part
     */
    boolean isDetached(String part);

    /**
     * {@return les parts encore connectées à la racine}
     *
     * <p>Graphe résiduel après d'éventuelles ruptures. Coût : proportionnel au
     * nombre de parts.
     */
    Set<String> connectedParts();
}
