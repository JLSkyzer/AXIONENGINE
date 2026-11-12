package dev.axion.diag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.axion.api.assembly.Modifier;
import dev.axion.api.assembly.Parameters;
import dev.axion.definition.Tuning;
import dev.axion.entity.AssemblyParameters;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-167 D10 : {@code /axion params} — la valeur, la base, et chaque modificateur avec son origine et son état. */
class ParametersReportTest {

    private static AssemblyParameters table() {
        Parameters.Range tout = new Parameters.Range(Double.NEGATIVE_INFINITY, false, Double.POSITIVE_INFINITY, false);
        return new AssemblyParameters(new Tuning(List.of(
                new Tuning.Parameter(Parameters.MASS, "kg", 1200, new Parameters.Range(0.001, true, 1e6, true), tout),
                new Tuning.Parameter(Parameters.ENGINE_TORQUE, "N·m", 260,
                        new Parameters.Range(0, true, Double.POSITIVE_INFINITY, false),
                        new Parameters.Range(200, true, 420, true)))));
    }

    @Test
    @DisplayName("D10 : la base n'apparaît que réglée ; chaque modificateur sous son paramètre, l'inactif avec sa raison")
    void rapport() {
        AssemblyParameters table = table();
        table.modify(new Modifier("allegement:moteur", Parameters.ENGINE_TORQUE, Modifier.Operation.ADD, -50),
                AssemblyParameters.API);
        table.modify(new Modifier("turbo:boost", Parameters.ENGINE_TORQUE, Modifier.Operation.MULTIPLY_BASE, 0.9),
                AssemblyParameters.COMMAND);
        table.remove("allegement:moteur");
        List<String> lignes = ParametersReport.lines("mymod:buggy", table, null);
        assertEquals("AXION ENGINE — mymod:buggy : 2 paramètre(s), 1 modificateur(s)", lignes.get(0));
        assertEquals("  mass = 1200 kg", lignes.get(1));
        assertEquals("  engine.torque = 260 N·m", lignes.get(2));
        assertTrue(lignes.get(3).startsWith("    inactif — turbo:boost multiply_base 0.9 (commande) : ")
                && lignes.get(3).contains("[200, 420]"), lignes.get(3));
        List<String> seul = ParametersReport.lines("mymod:buggy", table, Parameters.ENGINE_TORQUE);
        assertEquals("  engine.torque = 260 N·m ; bornes [200, 420]", seul.get(1));
        assertEquals(3, seul.size());
    }
}
