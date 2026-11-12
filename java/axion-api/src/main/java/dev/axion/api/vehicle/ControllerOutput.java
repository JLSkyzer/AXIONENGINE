package dev.axion.api.vehicle;

import dev.axion.api.Experimental;
import dev.axion.api.assembly.Modifier;

/**
 * Ce qu'un contrôleur écrit pour un tick (ADR-167 D9) : valable pendant son appel seulement. Ce qu'il n'écrit pas ne
 * vaut pas : un modificateur ou un couple d'un tick ne reste pas au suivant.
 */
@Experimental
public interface ControllerOutput {

    /**
     * Pose, pour ce tick, un modificateur au nom du contrôleur. Il se compose avec les autres causes, comme un
     * modificateur de l'API ; s'il ferait sortir le paramètre de ses bornes, il est écarté pour ce tick et le journal le
     * dit, à débit limité. En reposer un sur le même paramètre remplace le précédent.
     *
     * @param parameter le paramètre, celui d'AXION ou celui d'un addon
     * @param operation l'opération
     * @param amount la quantité, finie
     * @throws IllegalArgumentException si le véhicule n'a pas ce paramètre, ou si la quantité n'est pas finie
     */
    void modify(String parameter, Modifier.Operation operation, double amount);

    /**
     * Pose, pour ce tick, le couple moteur d'une roue (R-1082). Il s'ajoute à celui du motopropulseur ; sans
     * {@code powertrain}, c'est tout le couple de la roue. Les couples de plusieurs contrôleurs sur une même roue
     * s'additionnent.
     *
     * @param wheel le rang de la roue, dans l'ordre de la definition
     * @param torque le couple, en N·m, positif vers l'avant ; fini, et dans ce qu'un {@code float} porte
     * @throws IndexOutOfBoundsException si le véhicule n'a pas cette roue
     * @throws IllegalArgumentException si le couple n'est pas fini ou dépasse ce qu'un {@code float} porte
     */
    void wheelTorque(int wheel, double torque);

    /**
     * Écrit une variable du véhicule, dans ses bornes : c'est là que vit l'état d'un contrôleur. Une valeur que la
     * variable refuse, ou que ce qu'elle règle refuse, est écartée et dite au journal, à débit limité.
     *
     * @param name son nom dans la definition
     * @param value la valeur
     * @throws IllegalArgumentException si la definition ne la déclare pas
     */
    void variable(String name, double value);
}
