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
        COLLIDERS("colliders", NativeBridge.OVERLAY_COLLIDERS);

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
