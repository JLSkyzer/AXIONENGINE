package dev.axion.net;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Les places des entités qu'une assembly porte, chez le client (ADR-184 D2) : les descripteurs reçus, datés, et la place de
 * chaque entité au temps de rendu, le même que celui du corps qu'elle suit.
 *
 * <ul>
 *   <li>Entre deux descripteurs : la place dans le repère du corps s'interpole, de celui d'avant à celui d'après.</li>
 *   <li>Est portée celle que le descripteur d'après liste : une entité descendue entre les deux ne l'est plus.</li>
 *   <li>Une nouvelle venue, ou un changement de corps, prend la place d'après : rien ne s'interpole d'un repère à
 *       l'autre.</li>
 *   <li>Au-delà du dernier, les places sont tenues ; avant le premier, ce sont les siennes.</li>
 * </ul>
 *
 * <p>Une instance par assembly, sur le thread du client. Logique pure.
 */
public final class CarriedTrack {

    /** Descripteurs gardés, au plus : de quoi couvrir largement le retard d'interpolation. */
    static final int MAX_SAMPLES = 64;

    /**
     * La place d'une entité portée.
     *
     * @param body rang du corps qui la porte
     * @param local place dans le repère de ce corps, en blocs
     * @param tick tick du serveur du descripteur dont elle vient
     */
    public record Place(int body, double[] local, long tick) {}

    private record Sample(long tick, AssemblyState.Carried carried) {

        double millis() {
            return tick * ServerClock.TICK_MILLIS;
        }
    }

    private final ArrayDeque<Sample> samples = new ArrayDeque<>();

    /**
     * Reçoit le descripteur d'un snapshot ; un tick déjà reçu, ou plus ancien, est ignoré.
     *
     * @param serverTick tick du serveur dont il est l'état
     * @param carried les entités portées
     */
    public void push(long serverTick, AssemblyState.Carried carried) {
        if (!samples.isEmpty() && serverTick <= samples.peekLast().tick()) {
            return;
        }
        samples.addLast(new Sample(serverTick, carried));
        while (samples.size() > MAX_SAMPLES) {
            samples.removeFirst();
        }
    }

    /** {@return vrai si aucun descripteur gardé ne liste personne} */
    public boolean empty() {
        for (Sample sample : samples) {
            if (!sample.carried().entities().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Les entités portées à un temps de rendu, et ce qui le précède s'oublie.
     *
     * @param renderMillis temps de rendu, en millisecondes du serveur
     * @return la place de chaque entité portée, par identifiant réseau ; vide avant tout descripteur
     */
    public Map<Integer, Place> at(double renderMillis) {
        while (samples.size() > 1) {
            Iterator<Sample> it = samples.iterator();
            it.next();
            if (it.next().millis() <= renderMillis) {
                samples.removeFirst();
            } else {
                break;
            }
        }
        if (samples.isEmpty()) {
            return Map.of();
        }
        Iterator<Sample> it = samples.iterator();
        Sample before = it.next();
        Sample after = renderMillis > before.millis() && it.hasNext() ? it.next() : before;
        double part = after == before ? 0.0 : (renderMillis - before.millis()) / (after.millis() - before.millis());
        Map<Integer, Place> places = new LinkedHashMap<>();
        for (AssemblyState.CarriedEntity one : after.carried().entities()) {
            double[] local = one.local();
            AssemblyState.CarriedEntity from = after == before ? null : find(before.carried(), one.entity());
            if (from != null && from.body() == one.body()) {
                double[] start = from.local();
                for (int axis = 0; axis < 3; axis++) {
                    local[axis] = start[axis] + part * (local[axis] - start[axis]);
                }
            }
            places.put(one.entity(), new Place(one.body(), local, after.tick()));
        }
        return places;
    }

    private static AssemblyState.CarriedEntity find(AssemblyState.Carried carried, int entity) {
        for (AssemblyState.CarriedEntity one : carried.entities()) {
            if (one.entity() == entity) {
                return one;
            }
        }
        return null;
    }

    /** {@return le nombre de descripteurs gardés} */
    int size() {
        return samples.size();
    }
}
