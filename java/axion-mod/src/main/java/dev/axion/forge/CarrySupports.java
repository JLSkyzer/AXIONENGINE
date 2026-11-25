package dev.axion.forge;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.world.entity.Entity;

/**
 * Le support de chaque entité posée sur une assembly (ADR-178 D2, CAP-14) : l'assembly, le corps sur lequel elle repose, et
 * sa place dans le repère de ce corps. Il naît quand la collision arrête une entité vers le bas sur un corps, il meurt quand
 * elle quitte le sol, se pose sur un bloc ou s'éloigne : une entité n'a qu'un support à la fois.
 *
 * <p>Chaque côté tient le sien : une entité du serveur et celle du client sont deux objets. Ce registre ne déplace personne ;
 * le transport (D3 au client, D4 au serveur) le lit.
 */
public final class CarrySupports {

    /**
     * Un registre par côté : {@code Entity.equals} compare des identifiants, et en solo le joueur du client et celui du serveur
     * portent le même — un seul registre ferait effacer le support de l'un par l'autre.
     */
    private static final Map<Entity, Support> CLIENT = Collections.synchronizedMap(new WeakHashMap<>());

    private static final Map<Entity, Support> SERVER = Collections.synchronizedMap(new WeakHashMap<>());

    private CarrySupports() {}

    private static Map<Entity, Support> of(boolean client) {
        return client ? CLIENT : SERVER;
    }

    /** Le support d'une entité. Les places se calculent après le déplacement : {@code null} tant qu'il ne l'a pas été. */
    public static final class Support {
        private final AxionEntity assembly;
        private final int body;
        private double[] local;
        private double[] previousLocal;
        private int lag = -1;

        private Support(AxionEntity assembly, int body) {
            this.assembly = assembly;
            this.body = body;
        }

        /**
         * {@return au serveur, le retard en ticks de la pose à laquelle le joueur est porté ({@code -1} sans) : celle que son
         * client dessine}
         */
        public int lag() {
            return lag;
        }

        /** {@return l'assembly porteuse} */
        public AxionEntity assembly() {
            return assembly;
        }

        /** {@return le rang du corps porteur} */
        public int body() {
            return body;
        }

        /** {@return la place de l'entité dans le corps à la fin du dernier tick, {@code null} avant la première} */
        public double[] local() {
            return local == null ? null : local.clone();
        }

        /** {@return la place à la fin du tick d'avant, celle d'après au premier} */
        public double[] previousLocal() {
            return previousLocal == null ? local() : previousLocal.clone();
        }

        /**
         * Retient la place de l'entité à la fin d'un tick ; celle d'avant devient la précédente.
         *
         * @param place la place dans le repère du corps
         */
        public void place(double[] place) {
            previousLocal = local == null ? place.clone() : local;
            local = place.clone();
        }
    }

    /**
     * L'entité repose sur ce corps : le même garde ses places, un autre corps en prend de neuves.
     *
     * @param entity l'entité
     * @param assembly l'assembly porteuse
     * @param body le rang du corps
     */
    static void land(Entity entity, AxionEntity assembly, int body) {
        Map<Entity, Support> side = of(entity.level().isClientSide());
        synchronized (side) {
            Support held = side.get(entity);
            if (held == null || held.assembly != assembly || held.body != body) {
                side.put(entity, new Support(assembly, body));
            }
        }
    }

    /**
     * Au serveur : le joueur repose sur ce corps, tel qu'il était il y a {@code lag} ticks.
     *
     * @param entity le joueur
     * @param assembly l'assembly porteuse
     * @param body le rang du corps
     * @param lag le retard en ticks de la pose
     */
    static void landAt(Entity entity, AxionEntity assembly, int body, int lag) {
        land(entity, assembly, body);
        Support held = of(entity.level().isClientSide()).get(entity);
        if (held != null) {
            held.lag = lag;
        }
    }

    /** {@return une copie des supports du serveur, pour les porter} */
    static java.util.List<Map.Entry<Entity, Support>> serverEntries() {
        synchronized (SERVER) {
            return new java.util.ArrayList<>(SERVER.entrySet());
        }
    }

    /**
     * L'entité ne repose plus sur rien d'AXION.
     *
     * @param entity l'entité
     */
    static void clear(Entity entity) {
        Map<Entity, Support> side = of(entity.level().isClientSide());
        if (!side.isEmpty()) {
            side.remove(entity);
        }
    }

    /**
     * {@return le support de cette entité, {@code null} si elle n'en a pas ou si son assembly n'est plus au monde}
     *
     * @param entity l'entité
     */
    public static Support of(Entity entity) {
        Map<Entity, Support> side = of(entity.level().isClientSide());
        if (side.isEmpty()) {
            return null;
        }
        Support held = side.get(entity);
        if (held != null && (held.assembly.isRemoved() || held.assembly.level() != entity.level())) {
            side.remove(entity);
            return null;
        }
        return held;
    }
}
