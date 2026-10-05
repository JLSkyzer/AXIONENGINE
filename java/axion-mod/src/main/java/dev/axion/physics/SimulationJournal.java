package dev.axion.physics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * Journal des faits de simulation (FM-20, R-180, FM-21, FM-22, R-1880, R-281) : ce que le natif
 * a corrigé, ce que le gouverneur a dégradé, ce que le cycle a refusé — dit, jamais tu, sans
 * inonder.
 *
 * <ul>
 *   <li><b>Faits par assembly</b> : {@code RECOVERED} ({@code E-2030}, FM-20) et {@code CLAMPED}
 *       (vitesse bornée, sommeil de budget, empilement ; ADR-123 §11). Au plus une ligne par
 *       assembly, par fait et par minute de jeu (ADR-123 §8) ; au-delà de
 *       {@value #MAX_FACTS_PER_TICK} lignes dans un même tick, le reste est compté sur une ligne
 *       de synthèse — une rafale, quand la dégradation endort cent corps d'un coup, ne noie pas
 *       le journal au pire moment.
 *   <li><b>Paliers de dégradation</b> (SM-02, R-1880) : chaque changement de palier, avec sa
 *       cause — métrique, valeur, budget.
 *   <li><b>Ticks lents</b> (C-15) : un cycle de simulation au-delà du budget, avec le pas qui
 *       l'explique. Le premier est dit aussitôt ; les suivants sont comptés selon ce qu'a fait
 *       leur pas, et le pire détaillé, une minute de jeu plus tard, juste avant le changement de
 *       palier qu'ils expliquent, ou à l'arrêt du serveur.
 *   <li><b>Notes</b> du cycle : collect refusé, cycle rétabli (R-281).
 * </ul>
 *
 * <p>Le journal ne journalise pas lui-même : il accumule des lignes, que la couche Forge vide
 * après chaque tick ({@link #drain()}), ce qui le laisse testable. Les durées se comptent en
 * ticks de jeu, l'horloge simulée du serveur : déterministe, comme le débit du journal natif
 * (R-180). Utilisé sur le thread du serveur seulement.
 */
public final class SimulationJournal {

    /** Période du débit des faits par assembly : une minute de jeu, en ticks. */
    public static final long PERIOD_TICKS = 20L * 60L;

    /** Lignes de faits par assembly au plus dans un même tick ; le reste est compté. */
    public static final int MAX_FACTS_PER_TICK = 8;

    /** Noms des paliers de SM-02 (§25.6), par rang : celui de {@code axion.sim.degradation_level}. */
    private static final String[] LEVELS = {"NORMAL", "DEGRADED_1", "DEGRADED_2", "DEGRADED_3"};

    /**
     * Une ligne du journal.
     *
     * @param warning vrai pour ce qui demande l'attention de l'administrateur
     * @param text texte de la ligne
     */
    public record Entry(boolean warning, String text) {}

    /** Un fait d'une assembly, tel que le débit le compte. */
    private record Fact(int index, int generation, int kind, int data) {}

    /** Ce que le pas d'un tick lent a fait de son temps : ce qui explique le tick. */
    private enum SlowKind {
        /** Pas physique dans son budget : le temps du cycle est passé ailleurs. */
        OUTSIDE,
        /** Pas au-delà du budget, passé surtout à attendre sans calculer. */
        WAITING,
        /** Pas au-delà du budget, passé surtout à calculer. */
        COMPUTING,
        /** Pas au-delà du budget, dont le temps CPU n'a pas été mesuré. */
        UNMEASURED,
        /** Pas illisible dans l'export des métriques. */
        UNREADABLE;

        static SlowKind of(StepBreakdown step, long budgetNs) {
            if (step == null) {
                return UNREADABLE;
            }
            if (step.stepNs() <= budgetNs) {
                return OUTSIDE;
            }
            if (!step.cpuMeasured()) {
                return UNMEASURED;
            }
            return step.cpuNs() * 2 < step.stepNs() ? WAITING : COMPUTING;
        }

        /** {@return vrai si le pas a dépassé le budget : ce que voit le gouverneur FM-21} */
        boolean overBudget() {
            return this == WAITING || this == COMPUTING || this == UNMEASURED;
        }
    }

    /** Un tick lent tel que le journal le détaille : son numéro, son cycle, son pas, son genre. */
    private record SlowTick(long tick, long cycleNs, StepBreakdown step, SlowKind kind) {

        /**
         * {@return vrai si ce tick explique mieux un dépassement que {@code other}} D'abord un pas
         * au-delà du budget, le plus long ; à défaut, le cycle le plus long.
         */
        boolean worseThan(SlowTick other) {
            if (kind.overBudget() != other.kind.overBudget()) {
                return kind.overBudget();
            }
            return kind.overBudget() ? step.stepNs() > other.step.stepNs() : cycleNs > other.cycleNs;
        }
    }

    private final List<Entry> pending = new ArrayList<>();
    /** Tick de la dernière ligne de chaque fait, tant qu'elle compte pour le débit. */
    private final Map<Fact, Long> reported = new HashMap<>();
    private IntFunction<String> describer = SimulationJournal::anonymous;
    private long lastPurge;
    private int level;
    private long lastP95Ns = -1L;
    /** Tick de la dernière ligne de ticks lents, -1 avant la première. */
    private long lastSlowLine = -1L;
    /** Ticks lents pas encore dits : leur nombre par genre, le premier d'entre eux, le pire. */
    private final int[] slowCounts = new int[SlowKind.values().length];
    private int slowPending;
    private long slowSince;
    private SlowTick slowWorst;
    /** Dernier cycle relevé, tick et budget : ce que rapporte une ligne dite à l'arrêt. */
    private long lastCycleTick;
    private long lastBudgetNs;

    /**
     * Pose ce qui nomme une assembly dans le journal — sa definition, sa position —, fourni par
     * la couche Forge : un groupe nominal, « l'assembly … ». {@code null} revient à son seul
     * index de handle.
     *
     * @param describer index de handle → description
     */
    public void setDescriber(IntFunction<String> describer) {
        this.describer = describer == null ? SimulationJournal::anonymous : describer;
    }

    /**
     * Consigne les faits par assembly d'un tick : {@code RECOVERED} et {@code CLAMPED}.
     *
     * @param tick numéro du tick
     * @param events événements du tick, dans l'ordre du natif
     */
    public void recordEvents(long tick, List<PhysicsEvent> events) {
        purge(tick);
        int written = 0;
        int counted = 0;
        for (PhysicsEvent event : events) {
            int kind = event.kind();
            if (kind != PhysicsEventCodes.Kind.RECOVERED && kind != PhysicsEventCodes.Kind.CLAMPED) {
                continue;
            }
            Fact fact = new Fact(
                    event.assemblyAIndex(), event.assemblyAGeneration(), kind, event.data());
            Long last = reported.get(fact);
            if (last != null && tick >= last && tick - last < PERIOD_TICKS) {
                continue;
            }
            reported.put(fact, tick);
            if (written < MAX_FACTS_PER_TICK) {
                pending.add(entryOf(event));
                written++;
            } else {
                counted++;
            }
        }
        if (counted > 0) {
            pending.add(new Entry(true, counted + " autre(s) fait(s) de simulation au tick " + tick
                    + ", non détaillé(s) : le journal en garde " + MAX_FACTS_PER_TICK
                    + " par tick (/axion metrics pour les totaux)"));
        }
    }

    /**
     * Relève le palier de dégradation publié par le natif ; un changement de palier est
     * journalisé avec sa cause (R-1880).
     *
     * @param tick numéro du tick
     * @param newLevel rang du palier ({@code axion.sim.degradation_level})
     * @param p95Ns p95 de la dernière fenêtre ({@code axion.sim.p95_ns}), en ns
     * @param budgetNs budget de la simulation ({@code budgets.sim_ns_per_tick}), en ns
     */
    public void recordDegradation(long tick, int newLevel, long p95Ns, long budgetNs) {
        lastP95Ns = p95Ns;
        if (newLevel == level) {
            return;
        }
        if (slowPending > 0) {
            // Les ticks lents qui expliquent ce changement, juste avant lui.
            flushSlowTicks(tick, budgetNs);
        }
        boolean descent = newLevel > level;
        String cause = descent
                ? "p95 du tick " + millis(p95Ns) + " au-dessus du budget de " + millis(budgetNs)
                        + " trois fenêtres de suite"
                : "p95 du tick " + millis(p95Ns) + " sous 60 % du budget de " + millis(budgetNs)
                        + " depuis 30 s";
        pending.add(new Entry(descent, "simulation : palier " + levelName(level) + " → "
                + levelName(newLevel) + " au tick " + tick + " (" + (descent ? "descente" : "remontée")
                + ", FM-21) — " + cause + " (axion.sim.p95_ns, budgets.sim_ns_per_tick)"));
        level = newLevel;
    }

    /**
     * Relève la durée du cycle natif d'un tick — {@code submit} puis {@code collect} — et dit les
     * ticks lents, au-delà du budget de la simulation, avec ce qui les explique : le pas physique
     * décomposé et, s'il a été mesuré, son temps CPU (C-15).
     *
     * <p>Le premier tick lent est dit aussitôt. Les suivants sont comptés selon ce qu'a fait leur
     * pas — dans son budget, ou au-delà, et alors surtout en attente, surtout en calcul ou sans
     * temps CPU mesuré —, et le pire d'entre eux détaillé : une minute de jeu après la ligne
     * précédente, juste avant le changement de palier qu'ils expliquent
     * ({@link #recordDegradation}), ou à l'arrêt ({@link #flushPendingSlowTicks}). Le pas se lit
     * à chaque tick lent, pour le classer ; aux ticks nominaux, rien n'est lu.
     *
     * @param tick numéro du tick
     * @param cycleNs durée du cycle natif, en ns
     * @param budgetNs budget de la simulation ({@code budgets.sim_ns_per_tick}), en ns
     * @param step lit le pas décomposé dans l'export des métriques ; rend {@code null} s'il est
     *     illisible
     */
    public void recordCycle(long tick, long cycleNs, long budgetNs, Supplier<StepBreakdown> step) {
        if (tick < lastSlowLine) {
            // Un nouveau serveur, dont les ticks repartent de zéro : rien de l'ancien ne compte.
            lastSlowLine = -1L;
            clearSlowTicks();
        }
        lastCycleTick = tick;
        lastBudgetNs = budgetNs;
        if (cycleNs > budgetNs) {
            StepBreakdown read = step.get();
            SlowTick slow = new SlowTick(tick, cycleNs, read, SlowKind.of(read, budgetNs));
            if (slowPending == 0 && (lastSlowLine < 0 || tick - lastSlowLine >= PERIOD_TICKS)) {
                pending.add(new Entry(false, "simulation : tick lent au tick " + tick + " — "
                        + describeSlow(slow, budgetNs)));
                lastSlowLine = tick;
            } else {
                if (slowPending == 0) {
                    slowSince = tick;
                }
                slowPending++;
                slowCounts[slow.kind().ordinal()]++;
                if (slowWorst == null || slow.worseThan(slowWorst)) {
                    slowWorst = slow;
                }
            }
        }
        if (slowPending > 0 && tick - lastSlowLine >= PERIOD_TICKS) {
            flushSlowTicks(tick, budgetNs);
        }
    }

    /**
     * Dit les ticks lents encore comptés, à l'arrêt d'un serveur : la ligne qui les rapporte
     * attendait la minute suivante ou un changement de palier, et ils seraient tus sans elle.
     */
    public void flushPendingSlowTicks() {
        if (slowPending > 0) {
            flushSlowTicks(lastCycleTick, lastBudgetNs);
        }
    }

    /** Dit les ticks lents comptés depuis la ligne précédente, par genre, en détaillant le pire. */
    private void flushSlowTicks(long tick, long budgetNs) {
        List<String> parts = new ArrayList<>();
        int over = count(SlowKind.WAITING) + count(SlowKind.COMPUTING) + count(SlowKind.UNMEASURED);
        if (over > 0) {
            List<String> how = new ArrayList<>();
            describeCount(how, SlowKind.WAITING, "surtout en attente");
            describeCount(how, SlowKind.COMPUTING, "surtout en calcul");
            describeCount(how, SlowKind.UNMEASURED, "sans temps CPU mesuré");
            parts.add("pas physique au-delà du budget pour " + over + " (" + String.join(", ", how) + ")");
        }
        if (count(SlowKind.OUTSIDE) > 0) {
            parts.add("pas physique dans son budget pour " + count(SlowKind.OUTSIDE));
        }
        if (count(SlowKind.UNREADABLE) > 0) {
            parts.add("pas illisible pour " + count(SlowKind.UNREADABLE));
        }
        pending.add(new Entry(false, "simulation : " + slowPending + " autre(s) tick(s) lent(s)"
                + " depuis le tick " + slowSince + " — " + String.join(" ; ", parts)
                + " ; le pire, au tick " + slowWorst.tick() + " — " + describeSlow(slowWorst, budgetNs)));
        lastSlowLine = tick;
        clearSlowTicks();
    }

    private int count(SlowKind kind) {
        return slowCounts[kind.ordinal()];
    }

    private void describeCount(List<String> how, SlowKind kind, String what) {
        if (count(kind) > 0) {
            how.add(count(kind) + " " + what);
        }
    }

    private void clearSlowTicks() {
        slowPending = 0;
        slowWorst = null;
        Arrays.fill(slowCounts, 0);
    }

    /**
     * Ce qui explique un tick lent : le cycle, le pas physique, et ce que le pas a fait de son
     * temps — calculé, ou attendu sans calculer, préempté par le reste du jeu.
     */
    private static String describeSlow(SlowTick slow, long budgetNs) {
        StringBuilder text = new StringBuilder("cycle de simulation ")
                .append(millis(slow.cycleNs()))
                .append(" pour un budget de ")
                .append(millis(budgetNs));
        StepBreakdown step = slow.step();
        if (slow.kind() == SlowKind.UNREADABLE) {
            return text.append(" ; pas physique illisible dans l'export des métriques").toString();
        }
        text.append(" ; pas physique ").append(millis(step.stepNs()));
        switch (slow.kind()) {
            case OUTSIDE -> text.append(", dans son budget : le temps est passé ailleurs dans le cycle");
            case UNMEASURED ->
                    text.append(", temps CPU non mesuré : sa surveillance s'ouvre à ce dépassement");
            case WAITING -> text.append(" dont ")
                    .append(millis(step.cpuNs()))
                    .append(" de calcul : le thread a surtout attendu");
            case COMPUTING -> text.append(" dont ")
                    .append(millis(step.cpuNs()))
                    .append(" de calcul : le pas a surtout calculé");
            // Rendu plus haut : un pas illisible n'a ni durée ni temps CPU à dire.
            case UNREADABLE -> { }
        }
        return text.append(" (intégration ")
                .append(millis(step.integrationNs()))
                .append(", contacts ")
                .append(millis(step.contactsNs()))
                .append(", ")
                .append(step.colliders())
                .append(" colliders)")
                .toString();
    }

    /**
     * Ajoute une note du cycle de simulation.
     *
     * @param warning vrai pour ce qui demande l'attention
     * @param text texte de la note
     */
    public void note(boolean warning, String text) {
        pending.add(new Entry(warning, text));
    }

    /** {@return les lignes accumulées depuis le dernier appel, puis les oublie} */
    public List<Entry> drain() {
        if (pending.isEmpty()) {
            return List.of();
        }
        List<Entry> lines = List.copyOf(pending);
        pending.clear();
        return lines;
    }

    /** {@return le rang du dernier palier relevé, 0 ({@code NORMAL}) avant tout relevé} */
    public int degradationLevel() {
        return level;
    }

    /** {@return le p95 du dernier relevé, en ns, ou -1 avant tout relevé} */
    public long lastP95Ns() {
        return lastP95Ns;
    }

    /**
     * {@return le nom d'un palier de SM-02}
     *
     * @param rank rang du palier, celui de {@code axion.sim.degradation_level}
     */
    public static String levelName(int rank) {
        return rank >= 0 && rank < LEVELS.length ? LEVELS[rank] : "palier " + rank;
    }

    /**
     * {@return un palier et, hors du nominal, le p95 qui l'explique : ce que
     * {@code /axion status} affiche (R-1880)}
     *
     * @param rank rang du palier
     * @param p95Ns p95 du dernier relevé, en ns (-1 sans relevé)
     * @param budgetNs budget de la simulation, en ns
     */
    public static String describe(int rank, long p95Ns, long budgetNs) {
        if (rank == 0 || p95Ns < 0) {
            return levelName(rank);
        }
        return levelName(rank) + " — p95 du tick " + millis(p95Ns) + " pour un budget de "
                + millis(budgetNs);
    }

    private Entry entryOf(PhysicsEvent event) {
        String assembly = describer.apply(event.assemblyAIndex());
        int data = event.data();
        if (event.kind() == PhysicsEventCodes.Kind.RECOVERED) {
            return data == PhysicsEventCodes.Data.RECOVERED_INVALID_STATE
                    ? new Entry(true, "E-2030 : état non fini de " + assembly + " — corps restauré"
                            + " à son dernier état valide et endormi ; signaler le scénario")
                    : new Entry(true, assembly + " restaurée à un état valide (code " + data + ")");
        }
        return switch (data) {
            case PhysicsEventCodes.Data.CLAMPED_VELOCITY ->
                    new Entry(false, "vitesse de " + assembly + " bornée (R-180)");
            case PhysicsEventCodes.Data.CLAMPED_BUDGET ->
                    new Entry(false, assembly + " endormie par la dégradation du budget de"
                            + " simulation (FM-21)");
            case PhysicsEventCodes.Data.CLAMPED_STACKING ->
                    new Entry(false, assembly + " agitée sur place dans un empilement : amortie,"
                            + " puis endormie si elle ne se calme pas (FM-22)");
            default -> new Entry(false, assembly + " : grandeur bornée (code " + data + ")");
        };
    }

    /**
     * Oublie, une fois par période, les faits dont la dernière ligne ne compte plus pour le
     * débit : la table ne garde que la dernière minute. Un tick antérieur au dernier relevé
     * signale un nouveau serveur, dont les ticks repartent de zéro : tout est oublié.
     */
    private void purge(long tick) {
        if (tick >= lastPurge && tick - lastPurge < PERIOD_TICKS) {
            return;
        }
        lastPurge = tick;
        reported.values().removeIf(last -> tick < last || tick - last >= PERIOD_TICKS);
    }

    private static String anonymous(int index) {
        return "l'assembly #" + index;
    }

    private static String millis(long nanos) {
        return nanos < 0 ? "inconnu" : String.format(Locale.ROOT, "%.2f ms", nanos / 1_000_000.0);
    }
}
