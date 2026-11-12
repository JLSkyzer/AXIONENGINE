package dev.axion.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.axion.api.assembly.Modifier;
import dev.axion.api.assembly.Modifier.Operation;
import dev.axion.api.assembly.Parameters;
import dev.axion.definition.Tuning;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-167 D3 : la composition des causes, leurs bornes, leur retrait et leur reprise. */
class AssemblyParametersTest {

    private static final Parameters.Range TOUT = new Parameters.Range(
            Double.NEGATIVE_INFINITY, false, Double.POSITIVE_INFINITY, false);

    /** Un couple de base 260 N·m, positif, réglable de 200 à 420 ; une masse de 1200 kg dans le domaine de DM-08. */
    private static Tuning tuning() {
        return new Tuning(List.of(
                new Tuning.Parameter(Parameters.MASS, "kg", 1200, new Parameters.Range(0.001, true, 1e6, true), TOUT),
                new Tuning.Parameter(Parameters.ENGINE_TORQUE, "N·m", 260,
                        new Parameters.Range(0, true, Double.POSITIVE_INFINITY, false),
                        new Parameters.Range(200, true, 420, true))));
    }

    private static Modifier couple(String cause, Operation operation, double amount) {
        return new Modifier(cause, Parameters.ENGINE_TORQUE, operation, amount);
    }

    @Test
    @DisplayName("D3 : vecteurs d'or de la composition, (base + Σ add) × (1 + Σ multiply_base) × Π (1 + multiply_total)")
    void vecteursDOr() {
        List<Modifier> causes = List.of(
                couple("a:un", Operation.ADD, 10.5),
                couple("b:deux", Operation.ADD, -3.25),
                couple("c:trois", Operation.MULTIPLY_BASE, 0.1),
                couple("d:quatre", Operation.MULTIPLY_BASE, 0.15),
                couple("e:cinq", Operation.MULTIPLY_TOTAL, 0.2),
                couple("f:six", Operation.MULTIPLY_TOTAL, -0.1));
        // (260 + 10.5 - 3.25) × 1.25 × 1.2 × 0.9 : la valeur de référence, calculée une fois et figée.
        double attendu = (260.0 + 10.5 + -3.25) * (1.0 + (0.1 + 0.15)) * (1.0 * (1.0 + 0.2) * (1.0 + -0.1));
        assertEquals(360.7875, attendu);
        assertEquals(Double.doubleToLongBits(attendu),
                Double.doubleToLongBits(AssemblyParameters.compose(260, causes)));
        List<Modifier> melange = new ArrayList<>(causes);
        Collections.reverse(melange);
        assertEquals(Double.doubleToLongBits(attendu),
                Double.doubleToLongBits(AssemblyParameters.compose(260, melange)),
                "l'ordre des identifiants de cause, pas celui de la liste");
        assertEquals(260.0, AssemblyParameters.compose(260, List.of()));
    }

    @Test
    @DisplayName("D3 : poser, remplacer par la même cause, retirer par cause ; les autres paramètres n'en voient rien")
    void poserEtRetirer() {
        AssemblyParameters parametres = new AssemblyParameters(tuning());
        int couple = parametres.tuning().indexOf(Parameters.ENGINE_TORQUE);
        parametres.modify(couple("upgrades:stage1", Operation.MULTIPLY_BASE, 0.25), AssemblyParameters.API);
        assertEquals(325, parametres.value(couple));
        parametres.modify(couple("upgrades:stage1", Operation.MULTIPLY_BASE, 0.5), AssemblyParameters.API);
        assertEquals(390, parametres.value(couple), "la même cause remplace son modificateur");
        assertEquals(1, parametres.modifiers().size());
        parametres.modify(new Modifier("upgrades:stage1", Parameters.MASS, Operation.ADD, -80),
                AssemblyParameters.COMMAND);
        assertEquals(1120, parametres.value(parametres.tuning().indexOf(Parameters.MASS)));
        assertTrue(parametres.tuned());
        long version = parametres.version();
        assertTrue(parametres.remove("upgrades:stage1"));
        assertTrue(parametres.version() > version);
        assertEquals(260, parametres.value(couple));
        assertFalse(parametres.tuned());
        assertFalse(parametres.remove("upgrades:stage1"));
    }

    @Test
    @DisplayName("D2 : hors bornes, refusé et nommé, rien n'est changé ; une cause du moteur ou inconnue aussi")
    void refus() {
        AssemblyParameters parametres = new AssemblyParameters(tuning());
        parametres.modify(couple("upgrades:stage1", Operation.MULTIPLY_BASE, 0.5), AssemblyParameters.API);
        long version = parametres.version();
        IllegalArgumentException hors = assertThrows(IllegalArgumentException.class,
                () -> parametres.modify(couple("turbo:boost", Operation.ADD, 100), AssemblyParameters.API));
        assertTrue(hors.getMessage().contains("engine.torque") && hors.getMessage().contains("420"),
                hors.getMessage());
        assertEquals(390, parametres.value(parametres.tuning().indexOf(Parameters.ENGINE_TORQUE)));
        assertEquals(version, parametres.version());
        assertEquals(1, parametres.modifiers().size());
        assertThrows(IllegalArgumentException.class,
                () -> parametres.modify(couple("axion:damage", Operation.MULTIPLY_TOTAL, -1), AssemblyParameters.API));
        assertThrows(IllegalArgumentException.class, () -> parametres.modify(
                new Modifier("upgrades:x", Parameters.TIRES_GRIP, Operation.ADD, 1), AssemblyParameters.API));
        assertThrows(IllegalArgumentException.class, () -> new Modifier("sans-espace", "mass", Operation.ADD, 1));
        assertThrows(IllegalArgumentException.class, () -> new Modifier("a:b", "mass", Operation.ADD, Double.NaN));
    }

    @Test
    @DisplayName("D3 : un modificateur qui rendrait inactif un actif est refusé ; un retrait réussit et en désactive")
    void retraitQuiDesactive() {
        AssemblyParameters parametres = new AssemblyParameters(tuning());
        int couple = parametres.tuning().indexOf(Parameters.ENGINE_TORQUE);
        // 260 - 50 = 210, puis × 1.9 = 399 : deux causes qui ne tiennent dans [200, 420] qu'ensemble.
        parametres.modify(couple("allegement:moteur", Operation.ADD, -50), AssemblyParameters.API);
        parametres.modify(couple("turbo:boost", Operation.MULTIPLY_BASE, 0.9), AssemblyParameters.API);
        assertEquals(399, parametres.value(couple), 1e-9);
        IllegalArgumentException remplace = assertThrows(IllegalArgumentException.class,
                () -> parametres.modify(couple("allegement:moteur", Operation.ADD, 0), AssemblyParameters.API));
        assertTrue(remplace.getMessage().contains("turbo:boost"), remplace.getMessage());
        assertEquals(399, parametres.value(couple), 1e-9, "rien n'a changé");

        assertTrue(parametres.remove("allegement:moteur"));
        Parameters.Applied turbo = parametres.applied().get(0);
        assertFalse(turbo.active(), "260 × 1.9 = 494 sort de la plage : le turbo reste posé, inactif");
        assertTrue(turbo.reason().contains("plage de la definition"), turbo.reason());
        assertEquals(260, parametres.value(couple));
        parametres.modify(couple("allegement:moteur", Operation.ADD, -50), AssemblyParameters.API);
        assertTrue(parametres.applied().get(0).active(), "le rejeu le fait rentrer dans ses bornes");
        assertEquals(399, parametres.value(couple), 1e-9);
    }

    @Test
    @DisplayName("CAP-38 : la reprise d'une liste garde inactif, et nomme, ce que la definition refuse désormais")
    void reprise() {
        AssemblyParameters parametres = new AssemblyParameters(tuning());
        List<String> lignes = parametres.restore(List.of(
                couple("turbo:boost", Operation.MULTIPLY_BASE, 0.25),
                new Modifier("garage:rapport", Parameters.gear(6), Operation.ADD, 0.1),
                couple("turbo:hors", Operation.ADD, 500),
                couple("axion:damage", Operation.MULTIPLY_TOTAL, -1)), AssemblyParameters.SAVE);
        assertEquals(325, parametres.value(parametres.tuning().indexOf(Parameters.ENGINE_TORQUE)));
        assertEquals(3, parametres.modifiers().size(), "la cause du moteur est écartée, les autres restent posées");
        assertEquals(3, lignes.size(), String.join("\n", lignes));
        assertTrue(lignes.stream().anyMatch(ligne -> ligne.contains("transmission.gear.6")
                && ligne.contains("absent de la definition")));
        assertTrue(lignes.stream().anyMatch(ligne -> ligne.contains("turbo:hors")));
        assertTrue(lignes.stream().anyMatch(ligne -> ligne.contains("axion:damage")));
    }

    @Test
    @DisplayName("D3 : au plus 64 modificateurs posés ; le suivant est refusé et nommé")
    void plafond() {
        AssemblyParameters parametres = new AssemblyParameters(tuning());
        for (int i = 0; i < AssemblyParameters.MAX_MODIFIERS; i++) {
            parametres.modify(new Modifier("mod:c" + i, Parameters.MASS, Operation.ADD, 1), AssemblyParameters.API);
        }
        IllegalArgumentException refus = assertThrows(IllegalArgumentException.class, () -> parametres.modify(
                new Modifier("mod:trop", Parameters.MASS, Operation.ADD, 1), AssemblyParameters.API));
        assertTrue(refus.getMessage().contains("64"), refus.getMessage());
        parametres.modify(new Modifier("mod:c0", Parameters.MASS, Operation.ADD, 2), AssemblyParameters.API);
        assertEquals(1200 + 63 + 2, parametres.value(parametres.tuning().indexOf(Parameters.MASS)), 1e-9,
                "remplacer reste possible au plafond");
    }

    @Test
    @DisplayName("D8 : deux tables qui reprennent la même liste ont les mêmes actifs et les mêmes valeurs, au bit près")
    void miroir() {
        AssemblyParameters serveur = new AssemblyParameters(tuning());
        serveur.modify(couple("b:deux", Operation.MULTIPLY_TOTAL, 0.07), AssemblyParameters.API);
        serveur.modify(couple("a:un", Operation.ADD, 3.3), AssemblyParameters.API);
        serveur.modify(new Modifier("a:un", Parameters.MASS, Operation.MULTIPLY_BASE, -0.15), AssemblyParameters.API);
        AssemblyParameters client = new AssemblyParameters(tuning());
        client.restore(serveur.modifiers(), AssemblyParameters.SAVE);
        for (int i = 0; i < serveur.tuning().size(); i++) {
            assertEquals(Double.doubleToLongBits(serveur.value(i)), Double.doubleToLongBits(client.value(i)));
        }
    }
}
