package dev.axion.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Corps des assemblies au fil du suivi de Forge 47 : sortie à chaque fin de suivi, entrée à
 * l'ajout seulement (C-40 ↔ C-50, note BDC « Sur Forge 47 une entité entre au monde à son ajout
 * mais en sort à la fin de son suivi »).
 */
class BodyLedgerTest {

    /** Une entité simulée. */
    private record Entity(String name) {}

    /** Ce que le monde dit des entités : retirées, suivies. */
    private static final class World implements BodyLedger.Probe<Entity> {
        final Set<Entity> removed = new HashSet<>();
        final Set<Entity> tracked = new HashSet<>();

        @Override
        public boolean removed(Entity entity) {
            return removed.contains(entity);
        }

        @Override
        public boolean tracked(int index, Entity entity) {
            return tracked.contains(entity);
        }
    }

    private final BodyLedger<Entity> ledger = new BodyLedger<>();
    private final World world = new World();
    private final Entity cube = new Entity("cube");

    @Test
    @DisplayName("Un tronçon caché puis de nouveau visible : l'assembly retrouve un corps, une fois")
    void unTronconRedevenuVisibleRendLeCorps() {
        ledger.attach(7, cube);

        // Fin de suivi sans retrait : corps rendu, assembly mise de côté.
        assertTrue(ledger.detach(7, cube, false), "le corps à rendre n'a pas été signalé");
        assertFalse(ledger.hasBody(7));
        assertEquals(1, ledger.aside());

        // Toujours cachée : rien ne revient.
        assertTrue(ledger.reconcile(world).isEmpty());
        assertEquals(1, ledger.aside());

        // Suivie de nouveau, sans entrée : elle revient, et n'est rendue qu'une fois.
        world.tracked.add(cube);
        List<Map.Entry<Integer, Entity>> returning = ledger.reconcile(world);
        assertEquals(List.of(Map.entry(7, cube)), returning);
        assertEquals(0, ledger.aside());
        ledger.attach(7, cube);
        assertTrue(ledger.reconcile(world).isEmpty(), "rendue deux fois : deux corps");
        assertSame(cube, ledger.entity(7));
    }

    @Test
    @DisplayName("Un tronçon caché puis déchargé : l'assembly est oubliée, sans corps recréé")
    void unTronconDechargeOublieLAssembly() {
        ledger.attach(7, cube);
        ledger.detach(7, cube, false);

        // Déchargée avec son tronçon : retirée, sans nouvelle sortie (le tronçon était caché).
        world.removed.add(cube);
        world.tracked.add(cube);
        assertTrue(ledger.reconcile(world).isEmpty(), "corps recréé pour une entité retirée");
        assertEquals(0, ledger.aside());
    }

    @Test
    @DisplayName("Un retrait du monde rend le corps et oublie l'assembly")
    void unRetraitOublieLAssembly() {
        ledger.attach(7, cube);

        assertTrue(ledger.detach(7, cube, true));
        assertEquals(0, ledger.aside(), "une entité retirée mise de côté");
        assertNull(ledger.entity(7));
    }

    @Test
    @DisplayName("La sortie d'une assembly sans corps ne demande rien")
    void uneSortieSansCorpsNeDemandeRien() {
        assertFalse(ledger.detach(7, cube, false), "retrait demandé pour un corps qui n'existe pas");
        assertEquals(0, ledger.aside());
    }

    @Test
    @DisplayName("Une entrée signalée entre-temps reprend l'assembly mise de côté")
    void uneEntreeReprendLAssembly() {
        ledger.attach(7, cube);
        ledger.detach(7, cube, false);

        ledger.attach(7, cube);
        world.tracked.add(cube);
        assertTrue(ledger.reconcile(world).isEmpty(), "deux corps pour une assembly");
        assertTrue(ledger.hasBody(7));
    }

    @Test
    @DisplayName("Plusieurs retours se rendent par index croissant, quel que soit l'ordre des sorties")
    void lesRetoursSuiventLIndex() {
        Entity a = new Entity("a");
        Entity b = new Entity("b");
        Entity c = new Entity("c");
        ledger.attach(30, a);
        ledger.attach(10, b);
        ledger.attach(20, c);
        ledger.detach(30, a, false);
        ledger.detach(10, b, false);
        ledger.detach(20, c, false);
        world.tracked.addAll(List.of(a, b, c));

        assertEquals(List.of(Map.entry(10, b), Map.entry(20, c), Map.entry(30, a)), ledger.reconcile(world));
    }

    @Test
    @DisplayName("Les corps se visitent par index croissant, sans les assemblies sorties")
    void lesCorpsSeVisitentParIndexCroissant() {
        Entity b = new Entity("b");
        Entity c = new Entity("c");
        ledger.attach(30, cube);
        ledger.attach(10, b);
        ledger.attach(20, c);
        ledger.detach(20, c, false);

        List<Map.Entry<Integer, Entity>> visites = new java.util.ArrayList<>();
        ledger.forEachBody((index, entity) -> visites.add(Map.entry(index, entity)));

        assertEquals(List.of(Map.entry(10, b), Map.entry(30, cube)), visites);
    }

    @Test
    @DisplayName("L'arrêt oublie tout")
    void lArretOublieTout() {
        Entity other = new Entity("autre");
        ledger.attach(7, cube);
        ledger.attach(8, other);
        ledger.detach(8, other, false);

        ledger.clear();
        assertFalse(ledger.hasBody(7));
        assertEquals(0, ledger.aside());
    }
}
