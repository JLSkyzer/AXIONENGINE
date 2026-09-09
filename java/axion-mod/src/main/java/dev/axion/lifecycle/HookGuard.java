package dev.axion.lifecycle;

import java.util.ArrayList;
import java.util.List;

/**
 * Enveloppe d'un hook d'événement (C-01, FM-02).
 *
 * <p>Chaque hook branché sur un événement de la plateforme passe par ici. Deux
 * garanties en découlent.
 *
 * <p><strong>Rien ne remonte.</strong> Une exception levée par AXION dans un
 * hook ne doit jamais traverser vers l'appelant : elle interromprait la
 * distribution de l'événement, donc le travail des autres mods et parfois du
 * jeu lui-même. R-400 l'interdit — AXION n'annule aucun événement et ne modifie
 * le résultat d'aucun événement d'un autre mod.
 *
 * <p><strong>Un hook qui échoue sans cesse se tait.</strong> Après cinq échecs
 * <em>consécutifs</em>, il est désactivé et {@code E-1010} journalisé. Un hook
 * appelé vingt fois par seconde et qui échoue à chaque fois noierait le journal
 * et coûterait plus cher que le travail qu'il devait faire. Un seul succès
 * remet le compteur à zéro : c'est l'échec répété qui condamne, pas l'incident.
 */
public final class HookGuard {

    /** Nombre d'échecs consécutifs au-delà duquel le hook est désactivé. */
    public static final int MAX_CONSECUTIVE_FAILURES = 5;

    /** Code d'erreur du hook désactivé ({@code E-1010}). */
    public static final int E_HOOK_DISABLED = -1010;

    private final String name;
    private final List<String> failures = new ArrayList<>();
    private int consecutiveFailures;
    private long invocations;
    private boolean disabled;

    /**
     * Crée un garde pour le hook nommé.
     *
     * @param name nom du hook, tel qu'il apparaîtra au journal
     */
    public HookGuard(String name) {
        this.name = name;
    }

    /**
     * Exécute le hook, en absorbant tout ce qu'il pourrait lever.
     *
     * <p>Ne relance jamais, et ne fait rien si le hook a déjà été désactivé.
     *
     * @param hook travail à exécuter
     * @return {@code true} si le hook s'est exécuté sans incident
     */
    public boolean run(Runnable hook) {
        if (disabled) {
            return false;
        }
        invocations++;
        try {
            hook.run();
            // Un succès efface l'ardoise : seuls les échecs consécutifs
            // condamnent le hook.
            consecutiveFailures = 0;
            return true;
        } catch (Throwable failure) {
            // `Throwable` et non `Exception` : une `UnsatisfiedLinkError` ou un
            // `NoClassDefFoundError` venant du natif remonteraient sinon
            // jusqu'à la distribution de l'événement.
            recordFailure(failure);
            return false;
        }
    }

    private void recordFailure(Throwable failure) {
        consecutiveFailures++;
        failures.add(name + " : " + failure);
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            disabled = true;
            failures.add(name + " désactivé après " + MAX_CONSECUTIVE_FAILURES
                    + " échecs consécutifs (E-1010)");
        }
    }

    /** {@return le nom du hook} */
    public String name() {
        return name;
    }

    /** {@return vrai si le hook a été désactivé après des échecs répétés} */
    public boolean isDisabled() {
        return disabled;
    }

    /** {@return le nombre d'échecs consécutifs en cours} */
    public int consecutiveFailures() {
        return consecutiveFailures;
    }

    /** {@return le nombre de fois où le hook a été appelé} */
    public long invocations() {
        return invocations;
    }

    /**
     * {@return les incidents rencontrés, dans l'ordre}
     *
     * <p>Alimente le journal et {@code /axion status} : un hook silencieux
     * parce que désactivé doit rester visible quelque part.
     */
    public List<String> failures() {
        return List.copyOf(failures);
    }
}
