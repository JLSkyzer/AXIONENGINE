package dev.axion.net;

import java.util.ArrayDeque;

/**
 * Le temps du serveur, tel que le client l'estime d'après l'arrivée de ses snapshots (21.7).
 *
 * <p>Un snapshot porte le tick du serveur dont il donne l'état ; un tick dure 50 ms. L'écart entre ce temps
 * et l'heure d'arrivée locale mêle le décalage des horloges et le retard du réseau : le retard ne fait que
 * l'abaisser. La cible retient donc l'écart le plus grand — l'arrivée la moins retardée — sur une fenêtre
 * glissante de {@link #WINDOW_NANOS}, qui suit un serveur dont le tick dérive.
 *
 * <p>Cette cible saute quand l'arrivée retenue sort de la fenêtre, ou qu'une moins retardée survient ; le
 * temps de rendu en hériterait, et l'affichage avec. L'estimation la rejoint donc en glissant : elle avance à
 * 1 ± {@link #SLEW} fois le temps local, et ne saute qu'au-delà de {@link #RESET_MILLIS} — un serveur figé,
 * un réseau qui change, où glisser durerait des secondes.
 */
public final class ServerClock {

    /** Durée d'un tick du serveur, en millisecondes. */
    public static final double TICK_MILLIS = 50.0;

    /** Fenêtre des arrivées retenues, en nanosecondes. */
    static final long WINDOW_NANOS = 2_000_000_000L;

    /** Part du temps local écoulé dont l'estimation peut se décaler pour rejoindre la cible. */
    static final double SLEW = 0.05;

    /** Au-delà de cet écart à la cible, l'estimation saute plutôt que de glisser, en millisecondes. */
    static final double RESET_MILLIS = 250.0;

    private record Arrival(long nanos, double offsetMillis) {}

    /** Écarts décroissants de l'avant à l'arrière : l'avant est le plus grand de la fenêtre. */
    private final ArrayDeque<Arrival> arrivals = new ArrayDeque<>();

    /** Écart estimé, qui glisse vers la cible ; {@code NaN} avant la première estimation. */
    private double offsetMillis = Double.NaN;
    private long lastNanos;

    /**
     * Note l'arrivée d'un snapshot.
     *
     * @param serverTick tick du serveur qu'il porte
     * @param arrivalNanos heure locale de son arrivée, en nanosecondes
     */
    public void onSnapshot(long serverTick, long arrivalNanos) {
        double offset = serverTick * TICK_MILLIS - arrivalNanos / 1e6;
        // Une arrivée moins retardée que les plus récentes les rend inutiles : elles ne seraient jamais le
        // maximum avant de sortir de la fenêtre.
        while (!arrivals.isEmpty() && arrivals.peekLast().offsetMillis() <= offset) {
            arrivals.removeLast();
        }
        arrivals.addLast(new Arrival(arrivalNanos, offset));
        while (arrivals.size() > 1 && arrivalNanos - arrivals.peekFirst().nanos() > WINDOW_NANOS) {
            arrivals.removeFirst();
        }
    }

    /** {@return vrai si au moins un snapshot est arrivé} */
    public boolean known() {
        return !arrivals.isEmpty();
    }

    /**
     * Le temps du serveur estimé, qui glisse vers la cible depuis l'appel précédent. Deux appels à la même
     * heure rendent la même valeur.
     *
     * @param nowNanos heure locale, en nanosecondes, croissante d'un appel à l'autre
     * @return le temps du serveur estimé à cette heure, en millisecondes ; {@code NaN} sans snapshot
     */
    public double serverMillis(long nowNanos) {
        Arrival best = arrivals.peekFirst();
        if (best == null) {
            return Double.NaN;
        }
        double target = best.offsetMillis();
        if (Double.isNaN(offsetMillis) || Math.abs(target - offsetMillis) > RESET_MILLIS) {
            offsetMillis = target;
        } else if (nowNanos > lastNanos) {
            double step = SLEW * (nowNanos - lastNanos) / 1e6;
            offsetMillis += Math.max(-step, Math.min(step, target - offsetMillis));
        }
        lastNanos = Math.max(lastNanos, nowNanos);
        return nowNanos / 1e6 + offsetMillis;
    }

    /**
     * @param nowNanos heure locale, en nanosecondes
     * @return la cible à cette heure, sans glissement, en millisecondes ; {@code NaN} sans snapshot
     */
    double targetMillis(long nowNanos) {
        Arrival best = arrivals.peekFirst();
        return best == null ? Double.NaN : nowNanos / 1e6 + best.offsetMillis();
    }
}
