package dev.axion.render;

import java.util.concurrent.TimeUnit;

/**
 * Les paliers de la tenue du budget GPU (R-750, T-484), et leurs transitions (R-1880).
 *
 * <p>R-750 ordonne les leviers : décharger les assets non visibles, le moins récemment vu
 * d'abord, puis forcer le LOD, puis réduire la qualité de déformation. Seul le premier existe en
 * M3 ; les deux suivants viennent avec C-64 (M4) et la déformation (M6). Tant qu'ils manquent, un
 * dépassement que les déchargements ne résorbent pas — les assets en trop sont tous visibles — se
 * tient au palier {@link Level#UNRESOLVED}, et le dit.
 *
 * <p>Le §19.12 veut chaque palier journalisé et réversible avec hystérésis, celle de la machine de
 * dégradation (SM-02, §25.6) : la remontée se fait d'un palier à la fois, au plus une toutes les
 * 10 s, après 30 s sous le seuil. La descente, elle, est immédiate : un budget de mémoire est
 * dépassé à la frame près, sans fenêtre de p95 à attendre (R-1870 vise les budgets de durée).
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
        /** Les déchargements ne suffisent pas ; les leviers suivants de R-750 manquent encore. */
        UNRESOLVED
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
                case UNLOADING -> degrades()
                        ? "budget GPU dépassé — palier " + from + " → " + to
                                + " : déchargement des assets non visibles, " + evicted
                                + " asset(s) déchargé(s), " + cause
                        : "budget GPU tenu depuis 30 s — palier " + from + " → " + to + ", " + cause;
                case UNRESOLVED -> "budget GPU dépassé par des assets tous visibles — palier " + from
                        + " → " + to + ", " + cause + " ; les leviers suivants de R-750 — LOD forcé,"
                        + " qualité de déformation — arrivent avec C-64 et la M6";
                case NOMINAL -> "budget GPU tenu depuis 30 s — palier " + from + " → " + to + ", " + cause;
            };
        }
    }

    private final long budgetBytes;
    private Level level = Level.NOMINAL;
    /** Instant depuis lequel chaque frame tient le budget ; négatif pendant un dépassement. */
    private long calmSince = -1;
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
            Level target = frame.bytes() <= budgetBytes ? Level.UNLOADING : Level.UNRESOLVED;
            // Un dépassement n'est jamais une raison de remonter : le palier ne fait que descendre.
            return target.ordinal() > level.ordinal() ? move(target, frame) : null;
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
