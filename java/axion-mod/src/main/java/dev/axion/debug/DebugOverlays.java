package dev.axion.debug;

import dev.axion.bridge.NativeBridge;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Overlays de debug allumés, côté client (C-67, R-800, R-2280).
 *
 * <p>Chaque overlay s'allume et s'éteint indépendamment. Éteint, il ne coûte qu'un test de
 * drapeau : c'est le masque lu ici qui décide si le natif est seulement appelé.
 *
 * <p>Trois sources de géométrie ({@link Source}) : la physique de la session, une fois par tick ; le
 * contexte de rendu, à chaque frame, pour l'animation (ADR-155, tranche 6b) ; Java seul.
 */
public final class DebugOverlays {

    /**
     * Overlays implémentés, avec leur nom de commande et leur bit dans le masque — le rang de
     * l'overlay dans la liste du §31.4 (ADR-121). Seuls ceux-ci sont proposés à la commande.
     */
    public enum Overlay {
        /** Les colliders des corps, tournés avec eux (ADR-121). Rang 0. */
        COLLIDERS("colliders", NativeBridge.OVERLAY_COLLIDERS, Source.PHYSICS),
        /** L'AABB monde de chaque collider (amendement A1 d'ADR-121). Rang 1. */
        AABB("aabb", NativeBridge.OVERLAY_AABB, Source.PHYSICS),
        /** Les axes de chaque corps, à son origine (A1). Rang 2. */
        BODIES("bodies", NativeBridge.OVERLAY_BODIES, Source.PHYSICS),
        /** Les points de contact du dernier pas (A1). Rang 3. */
        CONTACTS("contacts", NativeBridge.OVERLAY_CONTACTS, Source.PHYSICS),
        /** La normale de chaque point de contact (A1). Rang 4. */
        NORMALS("normals", NativeBridge.OVERLAY_NORMALS, Source.PHYSICS),
        /** Chaque joint, d'un ancrage à l'autre, avec son axe (ADR-151 D7). Rang 5. */
        JOINTS("joints", NativeBridge.OVERLAY_JOINTS, Source.PHYSICS),
        /** La plage permise de l'axe libre de chaque joint limité, et sa position (ADR-151 D7). Rang 6. */
        LIMITS("limits", NativeBridge.OVERLAY_LIMITS, Source.PHYSICS),
        /** Chaque os d'une instance animée, de son os parent à lui (ADR-155, tranche 6b). Rang 10. */
        BONES("bones", NativeBridge.OVERLAY_BONES, Source.RENDER),
        /** Le repère de chaque node d'une instance animée (ADR-155, tranche 6b). Rang 11. */
        NODES("nodes", NativeBridge.OVERLAY_NODES, Source.RENDER),
        /** L'origine de chaque node animé, autour de laquelle il tourne (ADR-155, tranche 6b). Rang 12. */
        PIVOTS("pivots", NativeBridge.OVERLAY_PIVOTS, Source.RENDER),
        /** Les tuiles de collision du monde (C-38, A1). Rang 30. */
        WORLD_TILES("world_tiles", NativeBridge.OVERLAY_WORLD_TILES, Source.PHYSICS),
        /** Le centre de masse de chaque corps (A1). Rang 31. */
        CENTER_OF_MASS("center_of_mass", NativeBridge.OVERLAY_CENTER_OF_MASS, Source.PHYSICS),
        /**
         * La pose interpolée et le dernier état reçu de chaque assembly (C-51, ADR-146 §1). Rang 32. En Java
         * seul : sa géométrie vient de l'interpolation du client, et son bit ne part pas au natif, qui refuse
         * un overlay qu'il ne connaît pas ({@code abi.rs}). La divergence de champ du §31.4 vient avec la
         * déformation, en M6.
         */
        NETWORK("network", 1L << 32, Source.JAVA),
        /**
         * L'emprise de chaque assembly retenue par le culling, colorée par son niveau de détail (C-64,
         * ADR-146 §1, ADR-154). Rang 24. En Java seul : le niveau vient du résultat de la frame.
         */
        LOD("lod", 1L << 24, Source.JAVA),
        /**
         * L'emprise de chaque assembly candidate au culling : retenue en vert, écartée en rouge (C-64,
         * ADR-146 §1, ADR-154). Rang 25. En Java seul, comme {@link #LOD}.
         */
        CULLING("culling", 1L << 25, Source.JAVA);

        private final String commandName;
        private final long bit;
        private final Source source;

        Overlay(String commandName, long bit, Source source) {
            this.commandName = commandName;
            this.bit = bit;
            this.source = source;
        }

        /** {@return d'où vient sa géométrie, et dans quel masque part son bit} */
        public Source source() {
            return source;
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

    /** D'où vient la géométrie d'un overlay. */
    public enum Source {
        /** La physique de la session, une fois par tick : {@code axion_debug_fill} (ADR-121). */
        PHYSICS,
        /** Le contexte de rendu, à chaque frame : {@code axion_render_debug} (ADR-155, tranche 6b). */
        RENDER,
        /** Java seul : le bit ne part jamais au natif, qui refuse un overlay qu'il ne trace pas. */
        JAVA
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

    /** {@return le masque des overlays allumés, natifs ou non : la passe DEBUG s'ouvre s'il n'est pas nul} */
    public long mask() {
        return mask.get();
    }

    /** {@return le masque des overlays de la physique allumés, tel que {@code axion_debug_fill} l'attend} */
    public long nativeMask() {
        return mask.get() & PHYSICS_BITS;
    }

    /** {@return le masque des overlays de l'animation allumés, tel que {@code axion_render_debug} l'attend} */
    public long renderMask() {
        return mask.get() & RENDER_BITS;
    }

    /** Les bits des overlays dont la géométrie vient de la physique, puis du contexte de rendu. */
    private static final long PHYSICS_BITS = bitsOf(Source.PHYSICS);
    private static final long RENDER_BITS = bitsOf(Source.RENDER);

    private static long bitsOf(Source source) {
        long bits = 0;
        for (Overlay overlay : Overlay.values()) {
            if (overlay.source() == source) {
                bits |= overlay.bit();
            }
        }
        return bits;
    }
}
