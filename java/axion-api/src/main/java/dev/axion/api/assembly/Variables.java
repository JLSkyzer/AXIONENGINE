package dev.axion.api.assembly;

import dev.axion.api.Experimental;
import java.util.List;

/**
 * Les variables de contenu d'une assembly (ADR-163 D2) : celles que sa definition déclare, par instance, que les
 * commandes liées des sièges, les interactions et l'API écrivent, et qu'une piste procédurale lit comme
 * {@code var.<nom>}.
 *
 * <p>Une valeur est un {@code double} ; un booléen vaut 0 ou 1. Une valeur hors des bornes ou du type de sa variable
 * est refusée et nommée, jamais tronquée. Le serveur seul les écrit (INV-02) ; elles partent aux joueurs selon leur
 * {@code replicate}, et sont sauvegardées selon leur {@code persist}. Plusieurs écrivains ne se composent pas : la
 * dernière écriture l'emporte.
 *
 * <p>Une variable déclarée {@code scope: "seat"} a une valeur par siège (ADR-174 D2) : elle se lit et s'écrit par
 * {@link #get(String, String)} et {@link #set(String, String, double)}, à un siège nommé ; {@link #get(String)} et
 * {@link #set(String, double)} la refusent.
 *
 * <p>Thread : autoritatif uniquement, en lecture comme en écriture ; ailleurs, {@link IllegalStateException}
 * (R-1755).
 */
@Experimental
public interface Variables {

    /**
     * {@return les noms des variables, dans l'ordre de la definition} Vide si elle n'en déclare pas, ou si elle n'est
     * pas encore compilée.
     */
    List<String> names();

    /**
     * {@return vrai si la definition déclare cette variable}
     *
     * @param name son nom
     */
    boolean has(String name);

    /**
     * {@return la valeur d'une variable de l'assembly}
     *
     * @param name son nom
     * @throws IllegalArgumentException si la definition ne la déclare pas, ou si elle est par siège
     */
    double get(String name);

    /**
     * {@return vrai si la variable a une valeur par siège ({@code scope: "seat"}, ADR-174 D2)}
     *
     * @param name son nom
     * @throws IllegalArgumentException si la definition ne la déclare pas
     * @throws UnsupportedOperationException si l'implémentation ne connaît pas la portée par siège
     */
    default boolean perSeat(String name) {
        throw new UnsupportedOperationException("portée par siège non prise en charge");
    }

    /**
     * {@return la valeur d'une variable par siège à ce siège (ADR-174 D2)}
     *
     * @param name son nom
     * @param seat le nom du siège
     * @throws IllegalArgumentException si la definition ne la déclare pas, si elle n'est pas par siège, ou si le siège
     *     n'existe pas
     * @throws UnsupportedOperationException si l'implémentation ne connaît pas la portée par siège
     */
    default double get(String name, String seat) {
        throw new UnsupportedOperationException("portée par siège non prise en charge");
    }

    /**
     * Pose une variable par siège à ce siège (ADR-174 D2).
     *
     * @param name son nom
     * @param seat le nom du siège
     * @param value la valeur
     * @throws IllegalArgumentException si la definition ne la déclare pas, si elle n'est pas par siège, si le siège
     *     n'existe pas, ou si la variable refuse la valeur ; rien n'est changé
     * @throws UnsupportedOperationException si l'implémentation ne connaît pas la portée par siège
     */
    default void set(String name, String seat, double value) {
        throw new UnsupportedOperationException("portée par siège non prise en charge");
    }

    /**
     * {@return la valeur d'une variable, en booléen : vrai si elle n'est pas nulle}
     *
     * @param name son nom
     * @throws IllegalArgumentException si la definition ne la déclare pas
     */
    boolean getBoolean(String name);

    /**
     * Pose une variable.
     *
     * <p>Coût : négligeable ; la valeur part aux joueurs avec le snapshot suivant.
     *
     * @param name son nom
     * @param value la valeur
     * @throws IllegalArgumentException si la definition ne la déclare pas, si elle est par siège, ou si la variable
     *     refuse la valeur ; rien n'est changé
     */
    void set(String name, double value);

    /**
     * Pose une variable à 1 ou à 0.
     *
     * @param name son nom
     * @param value la valeur
     * @throws IllegalArgumentException si la definition ne la déclare pas, ou si la variable refuse la valeur
     */
    void setBoolean(String name, boolean value);
}
