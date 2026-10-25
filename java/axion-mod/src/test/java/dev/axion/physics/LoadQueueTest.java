package dev.axion.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** R-1730, ADR-153 D4 : la file des créations avance sous budget, et jamais ne s'arrête. */
class LoadQueueTest {

    /** Une horloge que chaque création avance de {@code prep} nanosecondes. */
    private static final class Horloge {
        long maintenant;
    }

    private static LoadQueue<String> file(Horloge horloge, String... elements) {
        LoadQueue<String> file = new LoadQueue<>(() -> horloge.maintenant);
        for (String element : elements) {
            file.offer(element);
        }
        return file;
    }

    @Test
    @DisplayName("R-1730 : la file s'arrête avant que l'élément suivant, à son coût prévu, dépasse le budget")
    void budgetRespecte() {
        Horloge horloge = new Horloge();
        LoadQueue<String> file = file(horloge, "a", "b", "c", "d", "e");
        List<String> crees = new ArrayList<>();
        int n = file.drain(250, element -> {
            horloge.maintenant += 100;
            crees.add(element);
            return true;
        });
        // a (100), b (200) ; c en coûterait 100 de plus, au-delà de 250.
        assertEquals(2, n);
        assertEquals(List.of("a", "b"), crees);
        assertEquals(3, file.size(), "l'excédent attend le tick suivant");
    }

    @Test
    @DisplayName("R-1730 : un élément au moins par tick, même au-delà du budget")
    void jamaisArretee() {
        Horloge horloge = new Horloge();
        LoadQueue<String> file = file(horloge, "a", "b");
        int n = file.drain(10, element -> {
            horloge.maintenant += 1_000;
            return true;
        });
        assertEquals(1, n);
        assertEquals(1, file.size());
        assertEquals(1, file.drain(10, element -> true), "le tick suivant reprend");
        assertEquals(0, file.size());
    }

    @Test
    @DisplayName("ADR-153 D4 : le coût natif d'une création est la soumission mesurée, rapportée à ses créations")
    void coutNatifMesure() {
        Horloge horloge = new Horloge();
        LoadQueue<String> file = file(horloge, "a", "b", "c", "d", "e", "f");
        // Premier tick : préparations nulles, aucune estimation native encore — tout passe.
        assertEquals(6, file.drain(1_000, element -> true));
        // La soumission qui portait ces six créations a pris 1 200 ns : 200 ns chacune.
        file.submitted(1_200);
        for (String element : new String[] {"g", "h", "i", "j", "k", "l"}) {
            file.offer(element);
        }
        // 200 par création sous un budget de 500 : deux passent, la troisième dépasserait.
        assertEquals(2, file.drain(500, element -> true));
        assertEquals(4, file.size());
    }

    @Test
    @DisplayName("ADR-153 D4 : ce qui ne se crée pas ne coûte rien au natif et ne compte pas")
    void echecSansCoutNatif() {
        Horloge horloge = new Horloge();
        LoadQueue<String> file = file(horloge, "a", "b");
        assertEquals(2, file.drain(1_000, element -> true));
        file.submitted(1_000);
        file.offer("retire");
        file.offer("c");
        int n = file.drain(600, element -> !element.equals("retire"));
        // « retire » ne part pas : seul c compte, à 500 de coût natif.
        assertEquals(1, n);
        assertEquals(0, file.size());
    }

    @Test
    @DisplayName("Une entrée déjà en file n'y entre pas deux fois")
    void sansDoublon() {
        LoadQueue<String> file = file(new Horloge(), "a", "a", "b");
        assertEquals(2, file.size());
        file.drain(Long.MAX_VALUE, element -> true);
        file.offer("a");
        assertEquals(1, file.size(), "sortie de la file, elle peut y revenir");
    }
}
