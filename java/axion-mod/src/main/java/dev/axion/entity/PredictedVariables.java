package dev.axion.entity;

import dev.axion.definition.Seating;
import dev.axion.definition.Variables;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Les variables du véhicule que le client prédit (ADR-168 D6, CAP-01) : les commandes que le conducteur bascule les
 * changent à l'instant, par les règles du serveur ({@link AssemblyVariables}), et la correction les remet d'accord.
 *
 * <ul>
 *   <li>Chaque pas prédit applique l'entrée du siège puis le début de tick, dans l'ordre du serveur — ses entrées, puis
 *       ses variables —, et garde l'état complet atteint, au rang de l'entrée.</li>
 *   <li>Une correction dit les valeurs du serveur après l'entrée qu'elle acquitte : elles s'accordent, ou non, avec
 *       l'état gardé pour cette entrée ({@link #agrees}).</li>
 *   <li>Au rejeu, l'état gardé pour l'entrée acquittée revient — les commandes tenues et les {@code press} en cours sont
 *       ceux du client —, recalé sur les valeurs du serveur ; les pas rejoués rebasculent à partir de là.</li>
 * </ul>
 *
 * <p>Logique pure, sans Forge. Thread du client seul.
 */
public final class PredictedVariables {

    /** Les états gardés : de quoi couvrir l'historique de la boucle, 64 entrées, et sa marge. */
    static final int KEPT = 128;

    /** Ce qui écrit au client, pour le diagnostic. */
    public static final String DRIVER = "conducteur prédit";

    /** Ce qui écrit les valeurs du serveur. */
    public static final String SERVER = "serveur";

    private final AssemblyVariables variables;
    private final Map<Long, AssemblyVariables.State> states = new LinkedHashMap<>(KEPT, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, AssemblyVariables.State> eldest) {
            return size() > KEPT;
        }
    };
    private double[] serverValues = new double[0];
    private long serverMask;

    /**
     * @param declared les variables que la definition déclare
     * @param values les valeurs reçues du serveur, une par variable déclarée
     * @param mask celles qu'il a envoyées, un bit par rang
     */
    public PredictedVariables(Variables declared, double[] values, long mask) {
        variables = new AssemblyVariables(declared);
        variables.rebase(values, mask, SERVER);
    }

    /**
     * Un pas prédit, neuf ou rejoué : l'entrée du siège, puis le début de tick.
     *
     * @param sequence la séquence de l'entrée
     * @param seat le rang du siège d'où elle part
     * @param bindings les commandes que ce siège lie
     * @param mask les commandes tenues, un bit par commande
     * @return les écritures refusées, à journaliser ; vide d'ordinaire
     */
    public List<String> step(long sequence, int seat, List<Seating.Binding> bindings, int mask) {
        List<String> refusals = bindings.isEmpty() ? List.of() : variables.input(seat, bindings, mask, DRIVER);
        variables.tick();
        states.remove(sequence);
        states.put(sequence, variables.state());
        return refusals;
    }

    /**
     * Les valeurs du serveur, celles de la correction en cours : après l'entrée qu'elle acquitte.
     *
     * @param values une par variable déclarée, 0 pour une absente
     * @param mask celles qu'il a envoyées, un bit par rang
     */
    public void correction(double[] values, long mask) {
        serverValues = values.clone();
        serverMask = mask;
    }

    /**
     * {@return vrai si l'état gardé pour cette entrée vaut le serveur ; vrai aussi sans état gardé — la physique juge
     * seule}
     *
     * @param sequence la séquence acquittée
     */
    public boolean agrees(long sequence) {
        AssemblyVariables.State state = states.get(sequence);
        if (state == null) {
            return true;
        }
        AssemblyVariables probe = new AssemblyVariables(variables.declared());
        probe.resume(state);
        return probe.agrees(serverValues, serverMask);
    }

    /**
     * Avant un rejeu : l'état gardé pour l'entrée acquittée revient, s'il l'est encore, puis prend les valeurs du
     * serveur.
     *
     * @param sequence la séquence acquittée, -1 sans entrée appliquée
     */
    public void restore(long sequence) {
        AssemblyVariables.State state = sequence < 0 ? null : states.get(sequence);
        if (state != null) {
            variables.resume(state);
        }
        variables.rebase(serverValues, serverMask, SERVER);
    }

    /** {@return les variables prédites, telles que le dernier pas les a laissées} Les contrôleurs y écrivent (6d-4). */
    public AssemblyVariables variables() {
        return variables;
    }

    /** {@return les valeurs prédites, copie} */
    public double[] values() {
        return variables.values();
    }
}
