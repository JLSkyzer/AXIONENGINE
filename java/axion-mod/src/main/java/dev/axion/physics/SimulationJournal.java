package dev.axion.physics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntFunction;

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

    private final List<Entry> pending = new ArrayList<>();
    /** Tick de la dernière ligne de chaque fait, tant qu'elle compte pour le débit. */
    private final Map<Fact, Long> reported = new HashMap<>();
    private IntFunction<String> describer = SimulationJournal::anonymous;
    private long lastPurge;
    private int level;
    private long lastP95Ns = -1L;

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
