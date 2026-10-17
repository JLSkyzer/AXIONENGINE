package dev.axion.debug;

import dev.axion.bridge.NativeBridge;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Overlays de debug allumés, côté client (C-67, R-800, R-2280).
 *
 * <p>Chaque overlay s'allume et s'éteint indépendamment. Éteint, il ne coûte qu'un test de
 * drapeau : c'est le masque lu ici qui décide si le natif est seulement appelé.
 */
public final class DebugOverlays {

    /**
     * Overlays implémentés, avec leur nom de commande et leur bit dans le masque — le rang de
     * l'overlay dans la liste du §31.4 (ADR-121). Seuls ceux-ci sont proposés à la commande.
     */
    public enum Overlay {
        /** Les colliders des corps, tournés avec eux (ADR-121). Rang 0. */
        COLLIDERS("colliders", NativeBridge.OVERLAY_COLLIDERS),
        /** L'AABB monde de chaque collider (amendement A1 d'ADR-121). Rang 1. */
        AABB("aabb", NativeBridge.OVERLAY_AABB),
        /** Les axes de chaque corps, à son origine (A1). Rang 2. */
        BODIES("bodies", NativeBridge.OVERLAY_BODIES),
        /** Les points de contact du dernier pas (A1). Rang 3. */
        CONTACTS("contacts", NativeBridge.OVERLAY_CONTACTS),
        /** La normale de chaque point de contact (A1). Rang 4. */
        NORMALS("normals", NativeBridge.OVERLAY_NORMALS),
        /** Les tuiles de collision du monde (C-38, A1). Rang 30. */
        WORLD_TILES("world_tiles", NativeBridge.OVERLAY_WORLD_TILES),
        /** Le centre de masse de chaque corps (A1). Rang 31. */
        CENTER_OF_MASS("center_of_mass", NativeBridge.OVERLAY_CENTER_OF_MASS);

        private final String commandName;
        private final long bit;

        Overlay(String commandName, long bit) {
            this.commandName = commandName;
            this.bit = bit;
        }

        /** {@return le nom de l'overlay dans {@code /axion debug <overlay> on|off}} */
        public String commandName() {
            return commandName;
        }

        /** {@return le bit de l'overlay dans le masque passé au natif} */
        public long bit() {
            return bit;
        }

        /** {@return le rang de l'overlay au §31.4, celui qu'une plage de géométrie porte} */
        public int rank() {
            return Long.numberOfTrailingZeros(bit);
        }

        /**
         * {@return l'overlay d'un rang du §31.4, ou {@code null} s'il n'est pas implémenté}
         *
         * @param rank rang porté par une plage de géométrie
         */
        public static Overlay ofRank(int rank) {
            for (Overlay overlay : values()) {
                if (overlay.rank() == rank) {
                    return overlay;
                }
            }
            return null;
        }
    }

    private final AtomicLong mask = new AtomicLong();

    /**
     * Allume ou éteint un overlay.
     *
     * @param overlay overlay visé
     * @param on vrai pour l'allumer
     */
    public void set(Overlay overlay, boolean on) {
        mask.updateAndGet(current -> on ? current | overlay.bit() : current & ~overlay.bit());
    }

    /**
     * {@return vrai si l'overlay est allumé}
     *
     * @param overlay overlay visé
     */
    public boolean isOn(Overlay overlay) {
        return (mask.get() & overlay.bit()) != 0;
    }

    /** {@return le masque des overlays allumés, tel que le natif l'attend} */
    public long mask() {
        return mask.get();
    }
}
