package dev.axion.forge;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.world.entity.Entity;

/**
 * Le support de chaque entité posée sur une assembly (ADR-178 D2, CAP-14) : l'assembly, le corps sur lequel elle repose, et
 * sa place dans le repère de ce corps. Il naît quand la collision arrête une entité vers le bas sur un corps, il meurt quand
 * elle quitte le sol, se pose sur un bloc ou s'éloigne : une entité n'a qu'un support à la fois. Il meurt aussi quand son assembly
 * est reliée à une autre compilation de sa definition (ADR-175 D5) : le rang de son corps n'y vaut plus rien.
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
        /** La compilation de la definition où le support est né : le rang du corps ne vaut que là. */
        private final Object definition;
        private double[] local;
        private double[] previousLocal;
        private int lag = -1;

        private Support(AxionEntity assembly, int body) {
            this.assembly = assembly;
            this.body = body;
            definition = assembly.carryDefinition();
        }

        /** {@return vrai si l'assembly est au monde et tient encore la compilation où le support est né} */
        boolean current() {
            return !assembly.isRemoved() && assembly.carryDefinition() == definition;
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
        boolean client = entity.level().isClientSide();
        Map<Entity, Support> side = of(client);
        Support before;
        synchronized (side) {
            before = side.get(entity);
            if (before != null && before.assembly == assembly && before.body == body && before.current()) {
                return;
            }
            side.put(entity, new Support(assembly, body));
        }
        // ADR-178 : les écouteurs du serveur apprennent ce qui monte et descend ; un corps change, pas l'assembly.
        if (!client && (before == null || before.assembly != assembly)) {
            if (before != null) {
                tell(before.assembly, entity, false);
            }
            tell(assembly, entity, true);
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
        boolean client = entity.level().isClientSide();
        Map<Entity, Support> side = of(client);
        if (side.isEmpty()) {
            return;
        }
        Support before = side.remove(entity);
        if (!client && before != null) {
            tell(before.assembly, entity, false);
        }
    }

    /**
     * {@return les entités que cette assembly porte au serveur}
     *
     * @param assembly l'assembly
     */
    static java.util.List<Entity> carriedBy(AxionEntity assembly) {
        java.util.List<Entity> carried = new java.util.ArrayList<>();
        for (Map.Entry<Entity, Support> entry : serverEntries()) {
            if (entry.getValue().assembly == assembly && entry.getValue().current() && !entry.getKey().isRemoved()) {
                carried.add(entry.getKey());
            }
        }
        return carried;
    }

    private static void tell(AxionEntity assembly, Entity entity, boolean carried) {
        if (assembly.isRemoved()) {
            return;
        }
        var listeners = ContentListeners.carriers();
        if (listeners.isEmpty()) {
            return;
        }
        dev.axion.api.assembly.Assembly view = ForgeAxionApi.view(assembly);
        ContentListeners.notify(listeners, listener -> {
            if (carried) {
                listener.carried(view, entity);
            } else {
                listener.dropped(view, entity);
            }
        }, carried ? "la montée sur une assembly" : "la descente d'une assembly");
    }

    /**
     * {@return le support de cette entité, {@code null} si elle n'en a pas, ou si son assembly n'est plus au monde ou a changé de
     * definition}
     *
     * @param entity l'entité
     */
    public static Support of(Entity entity) {
        Map<Entity, Support> side = of(entity.level().isClientSide());
        if (side.isEmpty()) {
            return null;
        }
        Support held = side.get(entity);
        if (held != null && (!held.current() || held.assembly.level() != entity.level())) {
            clear(entity);
            return null;
        }
        return held;
    }
}
