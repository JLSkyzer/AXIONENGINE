package dev.axion.persist;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Le budget du journal sur le thread autoritatif (R-1744), contre une horloge factice. */
class JournalBudgetTest {

    /** Horloge factice : chaque soumission coûte {@code COUT} ns. */
    private long maintenant;

    private static final long COUT = 30;

    private final List<String> soumises = new ArrayList<>();

    private void soumettre(String sauvegarde) {
        maintenant += COUT;
        soumises.add(sauvegarde);
    }

    private static UUID uuid(int n) {
        return new UUID(9, n);
    }

    @Test
    @DisplayName("R-1744 : une sauvegarde part tant que son coût prévu tient dans le budget ; au-delà, elle est différée")
    void auDelaDuBudgetDifferee() {
        JournalBudget<String> budget = new JournalBudget<>(100, () -> maintenant);
        for (int n = 0; n < 6; n++) {
            budget.save(uuid(n), "s" + n, this::soumettre);
        }
        // 30, 60, 90 : une quatrième, à 30 de plus, ferait 120 — elle et les suivantes attendent.
        assertEquals(List.of("s0", "s1", "s2"), soumises);
        assertEquals(3, budget.deferred());
    }

    @Test
    @DisplayName("R-1744 : en fin de tick, les différées partent dans ce qui reste du budget, une au moins")
    void finDeTick() {
        JournalBudget<String> budget = new JournalBudget<>(100, () -> maintenant);
        for (int n = 0; n < 10; n++) {
            budget.save(uuid(n), "s" + n, this::soumettre);
        }
        assertEquals(7, budget.deferred());
        assertEquals(120, budget.endTick(this::soumettre), "le budget épuisé, une part quand même");
        assertEquals(6, budget.deferred());
        assertEquals(90, budget.endTick(this::soumettre), "un tick neuf : trois partent, sans dépasser le budget");
        assertEquals(3, budget.deferred());
        assertEquals(90, budget.endTick(this::soumettre));
        assertEquals(0, budget.deferred());
        assertEquals(List.of("s0", "s1", "s2", "s3", "s4", "s5", "s6", "s7", "s8", "s9"), soumises, "dans l'ordre");
    }

    @Test
    @DisplayName("ADR-153 D6 : une sauvegarde plus récente remplace la différée ; une suppression l'abandonne")
    void remplacementEtSuppression() {
        JournalBudget<String> budget = new JournalBudget<>(10, () -> maintenant);
        budget.save(uuid(0), "a", this::soumettre); // épuise le budget
        budget.save(uuid(1), "b-ancienne", this::soumettre);
        budget.save(uuid(2), "c", this::soumettre);
        budget.save(uuid(1), "b-recente", this::soumettre);
        List<String> suppressions = new ArrayList<>();
        budget.cancel(uuid(2), () -> suppressions.add("c"));
        assertEquals(1, budget.deferred());
        assertEquals(List.of("b-recente"), budget.drainAll());
        assertEquals(List.of("c"), suppressions);
        assertEquals(0, budget.deferred());
    }

    @Test
    @DisplayName("R-1744 : tout le temps pris est compté, et le compte repart à chaque tick")
    void temps() {
        JournalBudget<String> budget = new JournalBudget<>(1_000, () -> maintenant);
        budget.save(uuid(0), "a", this::soumettre);
        budget.cancel(uuid(1), () -> maintenant += 7);
        assertEquals(37, budget.endTick(this::soumettre));
        assertEquals(0, budget.endTick(this::soumettre));
    }

    @Test
    @DisplayName("Un budget nul n'a pas de plafond : rien n'est différé")
    void sansPlafond() {
        JournalBudget<String> budget = new JournalBudget<>(0, () -> maintenant);
        for (int n = 0; n < 50; n++) {
            budget.save(uuid(n), "s" + n, this::soumettre);
        }
        assertEquals(0, budget.deferred());
        assertEquals(50, soumises.size());
    }
}
