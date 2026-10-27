package dev.axion.persist;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * La restauration depuis le journal latéral (cas 3 de 22.5.3, R-1745, R-1748, ADR-153 D6).
 *
 * <p>Les assemblies que le journal garde vivantes attendent ici. À chaque tick, quelques-unes sont
 * examinées, à tour de rôle :
 *
 * <ul>
 *   <li>une entité de cet UUID existe, dans n'importe quelle dimension : son NBT fait foi, l'entrée est
 *       oubliée sans rien créer (R-1745, R-1748) ;
 *   <li>les entités du chunk enregistré sont chargées et aucune n'a cet UUID : l'entité a disparu sans
 *       suppression explicite — vanilla l'a perdue —, l'assembly est recréée, puis oubliée ;
 *   <li>sinon, l'entrée attend son tour suivant.
 * </ul>
 *
 * <p>Une assembly vue dans le monde ou supprimée explicitement pendant la session est oubliée
 * ({@link #forget}), y compris avant que le journal ait fini d'être lu. Logique pure, sans Forge ; sur le
 * thread autoritatif seulement.
 */
public final class RestorePlanner {

    /** Ce que la restauration demande au monde. */
    public interface World {

        /** {@return vrai si une entité de cet UUID est dans le monde, quelle que soit sa dimension} */
        boolean exists(UUID uuid);

        /**
         * {@return vrai si les entités du chunk de cette position sont chargées} Faux pour une dimension
         * inconnue : l'entrée attend.
         */
        boolean entitiesLoaded(String dimension, double x, double z);

        /** Recrée l'assembly ; {@return vrai si elle est entrée dans le monde} */
        boolean restore(JournalRecord.Put put);
    }

    /** Le résultat d'un examen : recréée, ou refusée par le monde. */
    public record Outcome(JournalRecord.Put put, boolean restored) {}

    private final Map<UUID, JournalRecord.Put> pending = new HashMap<>();
    private final ArrayDeque<UUID> turn = new ArrayDeque<>();

    /** Oubliées avant que le journal soit lu ; {@code null} une fois qu'il l'est. */
    private Set<UUID> forgottenEarly = new HashSet<>();

    /** Reçoit les assemblies que le journal a lues, moins celles déjà oubliées pendant la session. */
    public void offer(List<JournalRecord.Put> live) {
        for (JournalRecord.Put put : live) {
            if (forgottenEarly != null && forgottenEarly.contains(put.uuid())) {
                continue;
            }
            if (pending.put(put.uuid(), put) == null) {
                turn.addLast(put.uuid());
            }
        }
        forgottenEarly = null;
    }

    /** Oublie une assembly : vue dans le monde, ou supprimée explicitement. */
    public void forget(UUID uuid) {
        if (forgottenEarly != null) {
            forgottenEarly.add(uuid);
        }
        pending.remove(uuid);
    }

    /** {@return le nombre d'assemblies qui attendent} */
    public int pending() {
        return pending.size();
    }

    /**
     * Examine au plus {@code budget} assemblies.
     *
     * @return celles qui ont été recréées ou refusées par le monde, dans l'ordre de leur examen
     */
    public List<Outcome> tick(World world, int budget) {
        List<Outcome> outcomes = new ArrayList<>();
        int examined = 0;
        while (examined < budget && !turn.isEmpty()) {
            UUID uuid = turn.pollFirst();
            JournalRecord.Put put = pending.get(uuid);
            if (put == null) {
                continue; // oubliée entre-temps : ne coûte pas d'examen
            }
            examined++;
            if (world.exists(uuid)) {
                pending.remove(uuid);
            } else if (world.entitiesLoaded(put.dimension(), put.x(), put.z())) {
                pending.remove(uuid);
                outcomes.add(new Outcome(put, world.restore(put)));
            } else {
                turn.addLast(uuid);
            }
        }
        return outcomes;
    }
}
