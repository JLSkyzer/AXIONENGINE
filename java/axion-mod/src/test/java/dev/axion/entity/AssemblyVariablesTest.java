package dev.axion.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import dev.axion.definition.Seating;
import dev.axion.definition.Variables;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-163 D2 : les variables d'une assembly et les modes des commandes liées, sur la séquence des entrées. */
class AssemblyVariablesTest {

    private static final Variables DECLAREES = Variables.of(JsonParser.parseString("""
            { "variables": {
                "gyrophare": { "type": "bool", "persist": true },
                "klaxon": { "type": "bool" },
                "phares": { "type": "int", "default": 0, "min": 0, "max": 2, "persist": true },
                "bip": { "type": "bool" } } }
            """).getAsJsonObject());

    private static final int GYROPHARE = 0;
    private static final int KLAXON = 1;
    private static final int PHARES = 2;
    private static final int BIP = 3;

    private static final List<Seating.Binding> COMMANDES = List.of(
            new Seating.Binding(1, "gyrophare", GYROPHARE, Seating.Mode.TOGGLE, new double[] {0, 1}),
            new Seating.Binding(2, "klaxon", KLAXON, Seating.Mode.HOLD, new double[] {0, 1}),
            new Seating.Binding(3, "phares", PHARES, Seating.Mode.CYCLE, new double[] {0, 1, 2}),
            new Seating.Binding(4, "bip", BIP, Seating.Mode.PRESS, new double[] {0, 1}));

    private static int bits(int... commandes) {
        int mask = 0;
        for (int commande : commandes) {
            mask |= 1 << (commande - 1);
        }
        return mask;
    }

    @Test
    @DisplayName("toggle bascule au front montant seulement, tenue ou non")
    void toggle() {
        AssemblyVariables variables = new AssemblyVariables(DECLAREES);
        variables.input(0, COMMANDES, bits(1), "conducteur");
        assertEquals(1.0, variables.get(GYROPHARE));
        variables.input(0, COMMANDES, bits(1), "conducteur");
        assertEquals(1.0, variables.get(GYROPHARE), "tenue : pas de nouveau front");
        variables.input(0, COMMANDES, 0, "conducteur");
        assertEquals(1.0, variables.get(GYROPHARE), "relâchée : rien");
        variables.input(0, COMMANDES, bits(1), "conducteur");
        assertEquals(0.0, variables.get(GYROPHARE), "second front : éteint");
        assertEquals("conducteur (commande 1)", variables.writer(GYROPHARE));
    }

    @Test
    @DisplayName("cycle parcourt sa liste en boucle, et repart de la première si la valeur courante n'y est pas")
    void cycle() {
        AssemblyVariables variables = new AssemblyVariables(DECLAREES);
        double[] vus = new double[4];
        for (int i = 0; i < 4; i++) {
            variables.input(0, COMMANDES, bits(3), "c");
            vus[i] = variables.get(PHARES);
            variables.input(0, COMMANDES, 0, "c");
        }
        assertEquals(List.of(1.0, 2.0, 0.0, 1.0), List.of(vus[0], vus[1], vus[2], vus[3]));
        List<Seating.Binding> autre = List.of(new Seating.Binding(3, "phares", PHARES, Seating.Mode.CYCLE,
                new double[] {2, 1}));
        AssemblyVariables hors = new AssemblyVariables(DECLAREES);
        hors.input(0, autre, bits(3), "c");
        assertEquals(2.0, hors.get(PHARES), "0 n'est pas dans [2, 1] : la première");
    }

    @Test
    @DisplayName("hold vaut sa seconde valeur tenue, la première relâchée, au silence comme à la descente")
    void hold() {
        AssemblyVariables variables = new AssemblyVariables(DECLAREES);
        variables.input(0, COMMANDES, bits(2), "c");
        assertEquals(1.0, variables.get(KLAXON));
        variables.input(0, COMMANDES, 0, "c");
        assertEquals(0.0, variables.get(KLAXON));
        variables.input(0, COMMANDES, bits(2, 1), "c");
        assertEquals(1.0, variables.get(KLAXON));
        variables.release(0, COMMANDES, "silence");
        assertEquals(0.0, variables.get(KLAXON), "entrées tues : relâchée");
        assertEquals(1.0, variables.get(GYROPHARE), "un toggle ne revient pas au relâchement");
        long avant = variables.version();
        variables.release(0, COMMANDES, "silence");
        assertEquals(avant, variables.version(), "rien de tenu : rien ne change");
    }

    @Test
    @DisplayName("press vaut sa seconde valeur le tick qui suit l'entrée, puis revient")
    void press() {
        AssemblyVariables variables = new AssemblyVariables(DECLAREES);
        variables.input(0, COMMANDES, bits(4), "c");
        assertEquals(1.0, variables.get(BIP), "posée dès l'entrée");
        variables.tick();
        assertEquals(1.0, variables.get(BIP), "le tick qui suit la voit");
        variables.tick();
        assertEquals(0.0, variables.get(BIP), "puis elle revient");
        variables.input(0, COMMANDES, bits(4), "c");
        variables.tick();
        variables.tick();
        assertEquals(0.0, variables.get(BIP), "tenue, un seul front : un seul tick");
    }

    @Test
    @DisplayName("deux sièges ont chacun leurs fronts")
    void deuxSieges() {
        AssemblyVariables variables = new AssemblyVariables(DECLAREES);
        variables.input(0, COMMANDES, bits(1), "conducteur");
        variables.input(1, COMMANDES, bits(1), "passager");
        assertEquals(0.0, variables.get(GYROPHARE), "deux fronts, deux bascules ; la dernière l'emporte");
        assertEquals("passager (commande 1)", variables.writer(GYROPHARE));
    }

    @Test
    @DisplayName("une valeur hors de la variable est refusée et nommée, jamais tronquée")
    void refus() {
        AssemblyVariables variables = new AssemblyVariables(DECLAREES);
        IllegalArgumentException hors = assertThrows(IllegalArgumentException.class,
                () -> variables.set(PHARES, 3, "API"));
        assertTrue(hors.getMessage().contains("phares"), hors.getMessage());
        assertEquals(0.0, variables.get(PHARES), "inchangée");
        assertThrows(IllegalArgumentException.class, () -> variables.set(GYROPHARE, 0.5, "API"));
        assertThrows(IllegalArgumentException.class, () -> variables.set(PHARES, Double.NaN, "API"));
        variables.set(PHARES, 2, "API");
        assertEquals(2.0, variables.get(PHARES));
        assertEquals("API", variables.writer(PHARES));
        assertEquals(AssemblyVariables.DEFAULT, variables.writer(KLAXON));
    }

    @Test
    @DisplayName("seules les variables persist se sauvegardent ; une valeur relue que la variable refuse reste au défaut")
    void sauvegarde() {
        AssemblyVariables variables = new AssemblyVariables(DECLAREES);
        variables.set(GYROPHARE, 1, "API");
        variables.set(KLAXON, 1, "API");
        variables.set(PHARES, 2, "API");
        Map<String, Double> sauvee = variables.persisted();
        assertEquals(Map.of("gyrophare", 1.0, "phares", 2.0), sauvee);

        AssemblyVariables relue = new AssemblyVariables(DECLAREES);
        assertEquals(List.of(), relue.restore(sauvee));
        assertEquals(1.0, relue.get(GYROPHARE));
        assertEquals(2.0, relue.get(PHARES));
        assertEquals(AssemblyVariables.SAVE, relue.writer(PHARES));

        Map<String, Double> changee = new LinkedHashMap<>();
        changee.put("phares", 5.0);
        changee.put("klaxon", 1.0);
        changee.put("disparue", 3.0);
        AssemblyVariables autre = new AssemblyVariables(DECLAREES);
        List<String> refus = autre.restore(changee);
        assertEquals(1, refus.size(), refus.toString());
        assertTrue(refus.get(0).contains("phares"), refus.get(0));
        assertEquals(0.0, autre.get(PHARES), "au défaut");
        assertEquals(0.0, autre.get(KLAXON), "non persist : ignorée");
    }

    @Test
    @DisplayName("la version ne change que si une valeur change")
    void version() {
        AssemblyVariables variables = new AssemblyVariables(DECLAREES);
        long debut = variables.version();
        variables.set(PHARES, 0, "API");
        assertEquals(debut, variables.version());
        variables.set(PHARES, 1, "API");
        assertEquals(debut + 1, variables.version());
    }
}
