package dev.axion.render;

import java.util.concurrent.TimeUnit;

/**
 * Les paliers de la tenue du budget GPU (R-750, T-484), et leurs transitions (R-1880).
 *
 * <p>R-750 ordonne les leviers : décharger les assets non visibles, le moins récemment vu
 * d'abord, puis forcer le LOD, puis réduire la qualité de déformation. Le LOD forcé (ADR-154,
 * décision de Killian) tient en deux paliers : chacun descend d'un niveau de détail de plus et
 * retire un mip de plus aux textures d'AXION — un LOD ne coûte que des indices (ADR-107), ce sont
 * les textures qui rendent de la mémoire. La qualité de déformation vient avec la M6 ; au-delà du
 * second palier, un dépassement se tient à {@link Level#UNRESOLVED}, et le dit.
 *
 * <p>Le §19.12 veut chaque palier journalisé et réversible avec hystérésis, celle de la machine de
 * dégradation (SM-02, §25.6) : la remontée se fait d'un palier à la fois, au plus une toutes les
 * 10 s, après 30 s sous le seuil. La descente, elle, est immédiate : un budget de mémoire est
 * dépassé à la frame près, sans fenêtre de p95 à attendre (R-1870 vise les budgets de durée). Seule
 * la descente d'un palier de LOD forcé au suivant attend 10 s : le temps que les textures se
 * rechargent à leur nouvelle taille, sans quoi chaque frame de rechargement compterait encore
 * l'ancienne.
 *
 * <p>Fil de rendu seul.
 */
public final class GpuDegradation {

    /** Durée sous le seuil avant une remontée (SM-02, §25.6). */
    static final long CALM_NANOS = TimeUnit.SECONDS.toNanos(30);

    /** Écart minimal entre deux remontées (SM-02, §25.6). */
    static final long STEP_NANOS = TimeUnit.SECONDS.toNanos(10);

    /** Palier de la tenue du budget GPU. */
    public enum Level {
        /** Le budget tient sans rien décharger. */
        NOMINAL,
        /** Premier levier de R-750 : des assets non visibles sont déchargés pour le tenir. */
        UNLOADING,
        /** Deuxième levier : un niveau de détail de plus, et un mip de texture retiré. */
        FORCED_LOD_1,
        /** Deuxième levier, renforcé : deux niveaux de plus, deux mips retirés. */
        FORCED_LOD_2,
        /** Les leviers jusqu'au LOD forcé ne suffisent pas ; la qualité de déformation vient en M6. */
        UNRESOLVED;

        /** {@return le décalage de niveau de détail que le palier impose} */
        public int lodBias() {
            return switch (this) {
                case FORCED_LOD_1 -> 1;
                case FORCED_LOD_2, UNRESOLVED -> 2;
                default -> 0;
            };
        }

        /** {@return les mips les plus fins que le palier retire aux textures d'AXION} */
        public int mipSkip() {
            return lodBias();
        }
    }

    /**
     * Un changement de palier, avec sa cause (R-1880).
     *
     * @param from palier quitté
     * @param to palier atteint
     * @param bytes octets GPU tenus à la frame qui a décidé
     * @param budgetBytes {@code budgets.gpu_mem_bytes}
     * @param evicted assets déchargés à cette frame
     */
    public record Transition(Level from, Level to, long bytes, long budgetBytes, int evicted) {

        /** {@return vrai si la transition dégrade} */
        public boolean degrades() {
            return to.ordinal() > from.ordinal();
        }

        /** {@return la transition dite pour le journal, cause comprise} */
        public String describe() {
            String cause = bytes + " octets tenus pour un budget de " + budgetBytes
                    + " (budgets.gpu_mem_bytes)";
            return switch (to) {
                case FORCED_LOD_1, FORCED_LOD_2 -> degrades()
                        ? "budget GPU dépassé par des assets visibles — palier " + from + " → " + to
                                + " : LOD forcé de " + to.lodBias() + " niveau(x), " + to.mipSkip()
                                + " mip(s) de texture retiré(s), " + cause
                        : "budget GPU tenu depuis 30 s — palier " + from + " → " + to + ", " + cause;
                case UNLOADING -> degrades()
                        ? "budget GPU dépassé — palier " + from + " → " + to
                                + " : déchargement des assets non visibles, " + evicted
                                + " asset(s) déchargé(s), " + cause
                        : "budget GPU tenu depuis 30 s — palier " + from + " → " + to + ", " + cause;
                case UNRESOLVED -> "budget GPU dépassé malgré le LOD forcé — palier " + from
                        + " → " + to + ", " + cause + " ; le dernier levier de R-750, la qualité de"
                        + " déformation, arrive avec la M6";
                case NOMINAL -> "budget GPU tenu depuis 30 s — palier " + from + " → " + to + ", " + cause;
            };
        }
    }

    private final long budgetBytes;
    private Level level = Level.NOMINAL;
    /** Instant depuis lequel chaque frame tient le budget ; négatif pendant un dépassement. */
    private long calmSince = -1;
    /** Instant de la dernière descente à un palier de LOD forcé. */
    private long lastForced;

    /** Instant de la dernière remontée ; absent avant la première. */
    private long lastStepUp;
    private boolean steppedUp;

    /**
     * Crée l'automate au palier nominal.
     *
     * @param budgetBytes {@code budgets.gpu_mem_bytes}
     */
    public GpuDegradation(long budgetBytes) {
        this.budgetBytes = budgetBytes;
    }

    /** {@return le palier courant} */
    public Level level() {
        return level;
    }

    /**
     * Observe ce que la tenue du budget a fait à une frame.
     *
     * @param frame résultat de {@link MeshCache#enforceBudget}
     * @param nowNanos horloge monotone, en nanosecondes
     * @return le changement de palier que la frame provoque, ou {@code null}
     */
    public Transition observe(MeshCache.Residency frame, long nowNanos) {
        if (frame.exceeded()) {
            calmSince = -1;
            if (frame.bytes() <= budgetBytes) {
                // Résorbé par les déchargements. Un dépassement n'est jamais une raison de remonter.
                return Level.UNLOADING.ordinal() > level.ordinal() ? move(Level.UNLOADING, frame) : null;
            }
            // Les assets en trop sont visibles : le levier suivant, le LOD forcé, palier par palier.
            if (level.ordinal() < Level.FORCED_LOD_1.ordinal()) {
                lastForced = nowNanos;
                return move(Level.FORCED_LOD_1, frame);
            }
            if (level == Level.UNRESOLVED || nowNanos - lastForced < STEP_NANOS) {
                return null;
            }
            lastForced = nowNanos;
            return move(Level.values()[level.ordinal() + 1], frame);
        }
        if (calmSince < 0) {
            calmSince = nowNanos;
        }
        boolean calm = nowNanos - calmSince >= CALM_NANOS;
        boolean spaced = !steppedUp || nowNanos - lastStepUp >= STEP_NANOS;
        if (level == Level.NOMINAL || !calm || !spaced) {
            return null;
        }
        lastStepUp = nowNanos;
        steppedUp = true;
        return move(Level.values()[level.ordinal() - 1], frame);
    }

    private Transition move(Level target, MeshCache.Residency frame) {
        Transition transition = new Transition(level, target, frame.bytes(), budgetBytes, frame.evicted());
        level = target;
        return transition;
    }
}
