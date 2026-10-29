package dev.axion.lifecycle;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Enveloppe d'un hook d'événement (C-01, FM-02).
 *
 * <p>Chaque hook branché sur un événement de la plateforme passe par ici. Trois
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
 *
 * <p><strong>Un échec se voit au journal.</strong> Un échec absorbé saute tout ce
 * que le hook devait faire — un tick de simulation entier pour le hook
 * {@code tick}. Il est signalé, exception comprise ({@link #drainReports}), au
 * plus une fois par fenêtre de {@link #REPORT_WINDOW} appels du hook ; ceux qui
 * suivent dans la fenêtre sont comptés, et leur nombre part avec le signalement
 * suivant. La désactivation est signalée quand elle survient. La couche de la
 * plateforme vide ces signalements et les écrit.
 */
public final class HookGuard {

    /** Nombre d'échecs consécutifs au-delà duquel le hook est désactivé. */
    public static final int MAX_CONSECUTIVE_FAILURES = 5;

    /** Code d'erreur du hook désactivé ({@code E-1010}). */
    public static final int E_HOOK_DISABLED = -1010;

    /** Incidents gardés pour {@link #failures} : les plus récents. */
    public static final int MAX_KEPT_FAILURES = 16;

    /**
     * Appels du hook entre deux échecs signalés un à un : une minute d'un hook
     * appelé à chaque tick.
     */
    public static final long REPORT_WINDOW = 1200;

    /**
     * Un échec, ou la désactivation, à écrire au journal.
     *
     * @param hook nom du hook
     * @param message ce qui s'est passé, échecs tus depuis le précédent compris
     * @param failure l'exception, pour sa pile ; {@code null} pour un simple compte
     * @param disabling vrai pour la désactivation (E-1010)
     */
    public record Report(String hook, String message, Throwable failure, boolean disabling) {}

    private final String name;
    private final Deque<String> failures = new ArrayDeque<>();
    private final List<Report> reports = new ArrayList<>();
    private int consecutiveFailures;
    private long invocations;
    private boolean disabled;
    /** L'appel du dernier échec signalé ; aucun d'abord. */
    private long lastReportedAt = Long.MIN_VALUE;
    /** Échecs tus depuis le dernier signalé. */
    private long untold;

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
            if (untold != 0) {
                tellUntoldIfDue();
            }
            return true;
        } catch (Throwable failure) {
            // `Throwable` et non `Exception` : une `UnsatisfiedLinkError` ou un
            // `NoClassDefFoundError` venant du natif remonteraient sinon
            // jusqu'à la distribution de l'événement.
            recordFailure(failure);
            return false;
        }
    }

    private synchronized void recordFailure(Throwable failure) {
        consecutiveFailures++;
        keep(name + " : " + failure);
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            // La désactivation part avec l'échec qui la cause, et le compte de ceux tus avant lui.
            disabled = true;
            String line = "désactivé après " + MAX_CONSECUTIVE_FAILURES + " échecs consécutifs (E-1010)";
            keep(name + " " + line);
            report(line, failure, true);
        } else if (lastReportedAt == Long.MIN_VALUE || invocations - lastReportedAt >= REPORT_WINDOW) {
            report("a échoué", failure, false);
        } else {
            untold++;
        }
    }

    private void report(String message, Throwable failure, boolean disabling) {
        String told = untold == 0 ? "" : " (et " + untold + " autre(s) échec(s) tu(s) depuis le précédent signalé)";
        reports.add(new Report(name, message + told, failure, disabling));
        untold = 0;
        lastReportedAt = invocations;
    }

    /** La fenêtre passée, dit le nombre d'échecs tus, même sans échec de plus. */
    private synchronized void tellUntoldIfDue() {
        if (untold != 0 && invocations - lastReportedAt >= REPORT_WINDOW) {
            reports.add(new Report(name, untold + " échec(s) tu(s) dans les " + REPORT_WINDOW
                    + " appels qui ont suivi le dernier signalé", null, false));
            untold = 0;
            lastReportedAt = invocations;
        }
    }

    private void keep(String line) {
        failures.addLast(line);
        while (failures.size() > MAX_KEPT_FAILURES) {
            failures.removeFirst();
        }
    }

    /**
     * {@return les signalements faits depuis le dernier appel, à écrire au journal}
     * Chacun n'est rendu qu'une fois.
     */
    public synchronized List<Report> drainReports() {
        List<Report> drained = List.copyOf(reports);
        reports.clear();
        return drained;
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
     * {@return les {@link #MAX_KEPT_FAILURES} derniers incidents, dans l'ordre}
     *
     * <p>Alimente le journal et {@code /axion status} : un hook silencieux
     * parce que désactivé doit rester visible quelque part.
     */
    public synchronized List<String> failures() {
        return List.copyOf(failures);
    }
}
