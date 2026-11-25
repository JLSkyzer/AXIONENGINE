package dev.axion.forge;

import com.mojang.logging.LogUtils;
import dev.axion.api.assembly.InteractionListener;
import dev.axion.api.assembly.SeatListener;
import dev.axion.content.ContentRegistry.ListenerEntry;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;

/**
 * Les écouteurs des sièges et des interactions que les mods ont enregistrés (ADR-174 D1), et leur isolement (R-1770) :
 * appelés sur le thread autoritatif, dans l'ordre ; le premier refus l'emporte ; une exception est journalisée en
 * nommant le mod et ne refuse rien ; {@code content.listener_strikes} exceptions de suite désactivent l'écouteur pour la
 * session.
 */
final class ContentListeners {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static volatile List<ListenerEntry<SeatListener>> seats = List.of();
    private static volatile List<ListenerEntry<InteractionListener>> interactions = List.of();
    private static volatile List<ListenerEntry<dev.axion.api.assembly.ContentEventListener>> events = List.of();
    private static final Map<Object, Integer> strikes = new HashMap<>();
    private static final Set<Object> disabled = new HashSet<>();
    private static volatile int maxStrikes = 20;

    private ContentListeners() {}

    /**
     * Publie les écouteurs enregistrés ; les fautes repartent de zéro.
     *
     * @param seatListeners les écouteurs des sièges, dans l'ordre
     * @param interactionListeners les écouteurs des interactions, dans l'ordre
     */
    static synchronized void publish(List<ListenerEntry<SeatListener>> seatListeners,
            List<ListenerEntry<InteractionListener>> interactionListeners) {
        seats = List.copyOf(seatListeners);
        interactions = List.copyOf(interactionListeners);
        strikes.clear();
        disabled.clear();
    }

    private static volatile List<ListenerEntry<dev.axion.api.assembly.CarryListener>> carriers = List.of();

    /**
     * Publie les écouteurs de ce qui est porté (ADR-178).
     *
     * @param carryListeners les écouteurs, dans l'ordre
     */
    static synchronized void publishCarry(List<ListenerEntry<dev.axion.api.assembly.CarryListener>> carryListeners) {
        carriers = List.copyOf(carryListeners);
    }

    /** {@return les écouteurs de ce qui est porté actifs} */
    static List<ListenerEntry<dev.axion.api.assembly.CarryListener>> carriers() {
        return carriers;
    }

    /**
     * Publie les écouteurs des événements de contenu (ADR-174 D2).
     *
     * @param eventListeners les écouteurs, dans l'ordre
     */
    static synchronized void publishEvents(List<ListenerEntry<dev.axion.api.assembly.ContentEventListener>> eventListeners) {
        events = List.copyOf(eventListeners);
    }

    /** {@return les écouteurs des événements de contenu} */
    static List<ListenerEntry<dev.axion.api.assembly.ContentEventListener>> events() {
        return events;
    }

    /**
     * Prévient chaque écouteur d'un fait arrivé chez le client, sur son thread principal (ADR-174 D2).
     *
     * @param entries les écouteurs
     * @param notice ce qu'on leur dit
     * @param what ce qui a eu lieu, pour le journal
     * @param <T> leur type
     */
    static <T> void notifyClient(List<ListenerEntry<T>> entries, java.util.function.Consumer<T> notice, String what) {
        AuthoritativeThread.requireClient();
        for (ListenerEntry<T> entry : entries) {
            if (disabled.contains(entry.listener())) {
                continue;
            }
            try {
                notice.accept(entry.listener());
                strikes.remove(entry.listener());
            } catch (RuntimeException | LinkageError failure) {
                fault(entry, what, failure);
            }
        }
    }

    /**
     * Règle le nombre d'exceptions de suite qui désactivent un écouteur.
     *
     * @param strikesBeforeDisable {@code content.listener_strikes}
     */
    static void limit(int strikesBeforeDisable) {
        maxStrikes = Math.max(1, strikesBeforeDisable);
    }

    /** {@return les écouteurs des sièges actifs} */
    static List<ListenerEntry<SeatListener>> seats() {
        return seats;
    }

    /** {@return les écouteurs des interactions actifs} */
    static List<ListenerEntry<InteractionListener>> interactions() {
        return interactions;
    }

    /**
     * {@return la raison du premier refus, ou {@code null} si tous laissent faire}
     *
     * @param entries les écouteurs
     * @param question ce qu'on demande à chacun : {@code null} pour laisser faire, une clé de traduction sinon
     * @param what ce qui est jugé, pour le journal
     * @param <T> leur type
     */
    static <T> Refusal firstRefusal(List<ListenerEntry<T>> entries, Function<T, String> question, String what) {
        AuthoritativeThread.require();
        for (ListenerEntry<T> entry : entries) {
            if (disabled.contains(entry.listener())) {
                continue;
            }
            try {
                String reason = question.apply(entry.listener());
                strikes.remove(entry.listener());
                if (reason != null) {
                    return new Refusal(entry.modId(), reason);
                }
            } catch (RuntimeException | LinkageError failure) {
                fault(entry, what, failure);
            }
        }
        return null;
    }

    /**
     * Prévient chaque écouteur d'un fait accompli.
     *
     * @param entries les écouteurs
     * @param notice ce qu'on leur dit
     * @param what ce qui a eu lieu, pour le journal
     * @param <T> leur type
     */
    static <T> void notify(List<ListenerEntry<T>> entries, java.util.function.Consumer<T> notice, String what) {
        AuthoritativeThread.require();
        for (ListenerEntry<T> entry : entries) {
            if (disabled.contains(entry.listener())) {
                continue;
            }
            try {
                notice.accept(entry.listener());
                strikes.remove(entry.listener());
            } catch (RuntimeException | LinkageError failure) {
                fault(entry, what, failure);
            }
        }
    }

    /**
     * Appelle une action ou une interaction d'un mod (ADR-174 D6), isolée comme un écouteur : une exception rend
     * {@code fallback}, et {@code content.listener_strikes} de suite la désactivent pour la session ({@code E-1012}).
     *
     * @param handler ce qui est appelé
     * @param modId le mod qui le livre
     * @param what ce qui est fait, pour le journal
     * @param body l'appel
     * @param fallback ce que vaut un appel désactivé ou en faute
     * @param <R> ce que l'appel rend
     * @return ce que rend l'appel, ou {@code fallback}
     */
    static <R> R call(Object handler, String modId, String what, java.util.function.Supplier<R> body, R fallback) {
        AuthoritativeThread.require();
        if (disabled.contains(handler)) {
            return fallback;
        }
        try {
            R result = body.get();
            strikes.remove(handler);
            return result;
        } catch (RuntimeException | LinkageError failure) {
            fault(new ListenerEntry<>(modId, handler), what, failure);
            return fallback;
        }
    }

    /** {@return vrai si cet écouteur a été désactivé pour la session} */
    static boolean disabled(Object listener) {
        return disabled.contains(listener);
    }

    private static <T> void fault(ListenerEntry<T> entry, String what, Throwable failure) {
        int count = strikes.merge(entry.listener(), 1, Integer::sum);
        if (count >= maxStrikes) {
            disabled.add(entry.listener());
            LOGGER.error("AXION : E-1012 l'écouteur {} de {} est désactivé pour la session : {} exception(s) de suite,"
                    + " la dernière sur {}", entry.listener().getClass().getName(), entry.modId(), count, what, failure);
        } else {
            LOGGER.error("AXION : l'écouteur {} de {} a levé sur {} ; rien n'est refusé (faute {} sur {})",
                    entry.listener().getClass().getName(), entry.modId(), what, count, maxStrikes, failure);
        }
    }

    /**
     * Un refus : le mod qui l'a dit, et sa raison.
     *
     * @param modId le mod
     * @param reason la clé de traduction de la raison
     */
    record Refusal(String modId, String reason) {}
}
