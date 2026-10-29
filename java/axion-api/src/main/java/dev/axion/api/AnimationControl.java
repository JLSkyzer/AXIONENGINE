package dev.axion.api;

import java.util.List;
import java.util.Optional;

/**
 * Pilotage des animations d'une assembly (C-37, ADR-155 D5).
 *
 * <p>Une assembly a quatre couches (R-1440), appliquées dans l'ordre sur sa pose de repos : 0 {@code BASE}
 * et 3 {@code OVERRIDE} remplacent la pose, 1 et 2 s'y ajoutent. Chacune joue un clip de l'asset à la fois,
 * passe de l'un à l'autre par un fondu, et a sa vitesse. Le serveur décide ; l'état de chaque couche voyage
 * aux clients, qui le rejouent (ADR-155 D4).
 *
 * <p>Thread : autoritatif. Une méthode qui change l'animation, appelée ailleurs, lève
 * {@link IllegalStateException} (R-1755).
 */
@Stable
public interface AnimationControl {

    /** {@return le nom de chaque clip de l'asset, dans son ordre ; vide pour un clip sans nom} */
    List<String> clips();

    /**
     * Joue un clip depuis son début, à la vitesse de la couche, par un fondu depuis ce qu'elle montrait.
     * Si le fondu porterait l'assembly au-delà de 256 pistes actives, il est coupé (R-200).
     *
     * @param clip nom du clip ; un nom que plusieurs clips portent joue le premier
     * @param layer couche, de 0 à 3
     * @param weight poids, de 0 à 1
     * @param fadeSeconds durée du fondu, en secondes ; 0 : aucun
     * @throws IllegalArgumentException si le clip est inconnu, la couche hors de 0..3, le poids hors de
     *     [0, 1], le fondu négatif ou non fini, ou si le clip porterait l'assembly au-delà de 256 pistes
     *     actives même fondu coupé
     */
    void play(String clip, int layer, float weight, float fadeSeconds);

    /**
     * Arrête une couche par un fondu vers la pose neutre.
     *
     * @param layer couche, de 0 à 3
     * @param fadeSeconds durée du fondu, en secondes ; 0 : aucun
     * @throws IllegalArgumentException si la couche est hors de 0..3, ou le fondu négatif ou non fini
     */
    void stop(int layer, float fadeSeconds);

    /**
     * Règle la vitesse d'une couche : celle du clip qui y joue, sans saut de son temps, et celle des clips
     * qu'elle jouera.
     *
     * @param layer couche, de 0 à 3
     * @param speed vitesse ; 1 : celle du clip, négative : à rebours
     * @throws IllegalArgumentException si la couche est hors de 0..3, ou la vitesse non finie
     */
    void setSpeed(int layer, float speed);

    /**
     * {@return le nom du clip que joue une couche, ou vide si elle est arrêtée}
     *
     * @param layer couche, de 0 à 3
     * @throws IllegalArgumentException si la couche est hors de 0..3
     */
    Optional<String> playing(int layer);
}
