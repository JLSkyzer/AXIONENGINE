package dev.axion.api.assembly;

import dev.axion.api.Experimental;
import java.util.List;

/**
 * Les paramètres réglables d'une assembly (ADR-167) : des grandeurs de sa definition — sa masse, le couple de son
 * moteur, ses freins, l'adhérence de ses pneus… — qu'une instance règle à chaud, sans toucher aux autres instances.
 *
 * <p>Chaque paramètre a une <strong>base</strong>, la valeur de la definition, et une <strong>valeur</strong>
 * effective : la base composée avec les {@link Modifier modificateurs} de toutes ses causes. Un mod d'améliorations
 * pose les siens sous son espace de noms, et les retire cause par cause.
 *
 * <p><strong>Les bornes.</strong> Une valeur effective reste une valeur que la definition aurait pu déclarer (la
 * limite de son schéma), et dans la plage que la definition se donne ({@code tuning.ranges}). Un modificateur qui en
 * ferait sortir son paramètre est refusé et nommé, jamais tronqué. Retirer une cause réussit toujours : un
 * modificateur que ce retrait mettrait hors bornes est gardé <em>inactif</em>, avec sa raison, et redevient actif
 * quand il rentre dans ses bornes.
 *
 * <p><strong>Ce qui existe.</strong> Un paramètre n'existe que si la definition déclare ce qu'il règle : {@link #MASS}
 * si elle déclare {@code physics.mass}, ceux d'un véhicule s'il a des roues, ceux de la boîte s'il a un groupe
 * motopropulseur, et un rapport par rang de {@code gears}. {@link #names()} les donne.
 *
 * <p>Le réglage est sauvegardé avec l'assembly, et part aux joueurs qui la suivent. Thread : autoritatif uniquement ;
 * ailleurs, {@link IllegalStateException} (R-1755).
 */
@Experimental
public interface Parameters {

    /** La masse de l'assembly, en kg ; chaque corps est mis à l'échelle, centre de masse gardé. */
    String MASS = "mass";

    /** Le couple maximal du moteur, en N·m ; la courbe de couple est mise à l'échelle, sa forme gardée. */
    String ENGINE_TORQUE = "engine.torque";

    /** Le régime du limiteur, en tr/min ; en boîte automatique, les seuils de passage suivent. */
    String ENGINE_MAX_RPM = "engine.max_rpm";

    /** Le rapport de pont. */
    String FINAL_DRIVE = "transmission.final_drive";

    /** La durée d'un passage de rapport, en secondes. */
    String SHIFT_TIME = "transmission.shift_time";

    /** Le couple de freinage des roues avant, en N·m. */
    String BRAKES_FRONT = "brakes.front";

    /** Le couple de freinage des roues arrière, en N·m. */
    String BRAKES_REAR = "brakes.rear";

    /** Le couple du frein à main, en N·m. */
    String BRAKES_HANDBRAKE = "brakes.handbrake";

    /** Un facteur de l'adhérence de chaque pneu ({@code mu_long} et {@code mu_lat}) ; 1 : celle qu'il déclare. */
    String TIRES_GRIP = "tires.grip";

    /** Un facteur de la raideur de chaque suspension ; 1 : celle qu'elle déclare. */
    String SUSPENSION_STIFFNESS = "suspension.stiffness";

    /** Un facteur de l'amortissement de chaque suspension, en compression et en détente ; 1 : celui qu'elle déclare. */
    String SUSPENSION_DAMPING = "suspension.damping";

    /** Le braquage maximal, en radians. */
    String STEERING_MAX_ANGLE = "steering.max_angle";

    /** Le coefficient de traînée. */
    String AERO_DRAG = "aero.drag";

    /** L'appui sur l'essieu avant, dans l'unité de la definition. */
    String AERO_DOWNFORCE_FRONT = "aero.downforce_front";

    /** L'appui sur l'essieu arrière, dans l'unité de la definition. */
    String AERO_DOWNFORCE_REAR = "aero.downforce_rear";

    /**
     * {@return le nom du paramètre d'un rapport de boîte}
     *
     * @param rank son rang, compté depuis le point mort comme l'entrée {@code gear} : 1 pour la première, -1 pour la
     *     marche arrière
     * @throws IllegalArgumentException pour le rang 0, le point mort, qui n'a pas de rapport à régler
     */
    static String gear(int rank) {
        if (rank == 0) {
            throw new IllegalArgumentException("le point mort n'a pas de rapport à régler");
        }
        return "transmission.gear." + rank;
    }

    /**
     * Les bornes d'un paramètre : l'intersection de la limite du schéma et de la plage de la definition.
     *
     * @param min la borne basse, {@link Double#NEGATIVE_INFINITY} sans
     * @param minInclusive vrai si la borne basse est admise
     * @param max la borne haute, {@link Double#POSITIVE_INFINITY} sans
     * @param maxInclusive vrai si la borne haute est admise
     */
    record Range(double min, boolean minInclusive, double max, boolean maxInclusive) {

        /**
         * {@return vrai si la valeur est dans les bornes}
         *
         * @param value la valeur
         */
        public boolean contains(double value) {
            return (minInclusive ? value >= min : value > min) && (maxInclusive ? value <= max : value < max);
        }

        @Override
        public String toString() {
            return (minInclusive ? "[" : "]") + bound(min) + ", " + bound(max) + (maxInclusive ? "]" : "[");
        }

        private static String bound(double value) {
            if (Double.isInfinite(value)) {
                return value > 0 ? "+∞" : "-∞";
            }
            return value == Math.rint(value) && Math.abs(value) < 1e15 ? Long.toString((long) value)
                    : Double.toString(value);
        }
    }

    /**
     * Un modificateur posé, et son état.
     *
     * @param modifier le modificateur
     * @param origin d'où il vient : {@code api}, {@code commande} ou {@code sauvegarde}
     * @param active vrai s'il compte dans la valeur
     * @param reason pourquoi il est inactif, {@code null} s'il est actif
     */
    record Applied(Modifier modifier, String origin, boolean active, String reason) {}

    /** {@return les paramètres de l'assembly, dans un ordre fixe} Vide tant que sa definition n'est pas compilée. */
    List<String> names();

    /**
     * {@return vrai si l'assembly a ce paramètre}
     *
     * @param parameter son nom
     */
    boolean has(String parameter);

    /**
     * {@return la valeur de la definition}
     *
     * @param parameter son nom
     * @throws IllegalArgumentException si l'assembly n'a pas ce paramètre
     */
    double base(String parameter);

    /**
     * {@return la valeur effective : la base composée avec les modificateurs actifs}
     *
     * @param parameter son nom
     * @throws IllegalArgumentException si l'assembly n'a pas ce paramètre
     */
    double value(String parameter);

    /**
     * {@return les bornes de la valeur}
     *
     * @param parameter son nom
     * @throws IllegalArgumentException si l'assembly n'a pas ce paramètre
     */
    Range range(String parameter);

    /**
     * {@return l'unité de la valeur, par exemple {@code N·m} ; vide pour un rapport ou un coefficient}
     *
     * @param parameter son nom
     * @throws IllegalArgumentException si l'assembly n'a pas ce paramètre
     */
    String unit(String parameter);

    /**
     * Pose un modificateur, ou remplace celui que sa cause portait déjà sur ce paramètre. La nouvelle valeur agit au
     * pas suivant, véhicule en mouvement.
     *
     * <p>Coût : négligeable ; la valeur part au moteur physique et aux joueurs au tick suivant.
     *
     * @param modifier le modificateur
     * @throws IllegalArgumentException si l'assembly n'a pas ce paramètre, si la cause est dans l'espace de noms
     *     {@code axion}, si l'assembly porte déjà le nombre maximal de modificateurs, ou si la valeur sortirait de ses
     *     bornes ou rendrait inactif un autre modificateur ; rien n'est changé, et le message le nomme
     */
    void modify(Modifier modifier);

    /**
     * Retire tous les modificateurs d'une cause. Réussit toujours ; un modificateur que ce retrait mettrait hors
     * bornes devient inactif.
     *
     * @param cause l'identifiant de la cause
     * @return vrai si elle en portait
     */
    boolean remove(String cause);

    /**
     * Retire le modificateur d'une cause sur un paramètre.
     *
     * @param cause l'identifiant de la cause
     * @param parameter le paramètre
     * @return vrai s'il existait
     */
    boolean remove(String cause, String parameter);

    /** {@return tous les modificateurs posés, dans l'ordre de leur pose, actifs ou non} */
    List<Applied> modifiers();

    /**
     * {@return les modificateurs posés sur un paramètre, dans l'ordre de leur pose, actifs ou non}
     *
     * @param parameter son nom
     */
    List<Applied> modifiers(String parameter);
}
