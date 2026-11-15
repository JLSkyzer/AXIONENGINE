package dev.axion.attach;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.axion.attach.AttachmentGraph.Link;
import dev.axion.attach.AttachmentGraph.Refusal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-171 D3, R-690, R-691 : le graphe des attaches. */
class AttachmentGraphTest {

    private static final int CHAINE = 8;

    private static UUID a(int n) {
        return new UUID(0, n);
    }

    private static Link lien(int id, int de, int vers) {
        return new Link(id, a(de), "arriere", a(vers), "avant");
    }

    @Test
    @DisplayName("T-892, R-690 : deux assemblies déjà reliées ne s'attachent pas une seconde fois — E-8030")
    void cycleRefuse() {
        AttachmentGraph graphe = new AttachmentGraph();
        assertEquals(Optional.empty(), graphe.add(lien(1, 1, 2), CHAINE));
        assertEquals(Optional.empty(), graphe.add(lien(2, 2, 3), CHAINE));
        // 3 → 1 fermerait la boucle, par d'autres sockets.
        assertEquals(Optional.of(Refusal.CYCLE),
                graphe.add(new Link(3, a(3), "arriere", a(1), "avant_bis"), CHAINE));
        assertEquals(Optional.of(Refusal.CYCLE), graphe.add(new Link(4, a(2), "x", a(1), "y"), CHAINE),
                "une seconde attache entre les deux mêmes");
        assertEquals(2, graphe.all().size());
    }

    @Test
    @DisplayName("T-893, R-691 : une chaîne de huit attaches passe, la neuvième non ; les branches comptent au plus long")
    void chaineBornee() {
        AttachmentGraph graphe = new AttachmentGraph();
        for (int i = 0; i < 8; i++) {
            assertEquals(Optional.empty(), graphe.add(lien(i, i, i + 1), CHAINE), "attache " + i);
        }
        assertEquals(Optional.of(Refusal.CHAIN), graphe.add(lien(8, 8, 9), CHAINE), "neuf de bout en bout");
        assertEquals(Optional.of(Refusal.CHAIN), graphe.add(new Link(9, a(20), "s", a(0), "flanc"), CHAINE),
                "un ajout à l'autre bout aussi");
        // Une branche au milieu ne rallonge pas le plus long chemin : l'excentricité de 4 est 4, plus 1, soit 5.
        assertEquals(Optional.empty(), graphe.add(new Link(10, a(4), "flanc", a(20), "s"), CHAINE));
        // Deux chaînes de quatre réunies par une attache : 4 + 1 + 4 = 9 de bout en bout.
        AttachmentGraph deux = new AttachmentGraph();
        for (int i = 0; i < 4; i++) {
            deux.add(lien(i, i, i + 1), CHAINE);
            deux.add(lien(10 + i, 10 + i, 11 + i), CHAINE);
        }
        assertEquals(Optional.of(Refusal.CHAIN), deux.add(new Link(99, a(4), "fin", a(10), "debut"), CHAINE));
        assertEquals(Optional.empty(), deux.add(new Link(99, a(4), "fin", a(10), "debut"), 9));
    }

    @Test
    @DisplayName("D3 : un socket porte une attache à la fois ; une assembly ne s'attache pas à elle-même")
    void socketsEtSoi() {
        AttachmentGraph graphe = new AttachmentGraph();
        graphe.add(lien(1, 1, 2), CHAINE);
        assertEquals(Optional.of(Refusal.SOCKET_BUSY), graphe.add(lien(2, 1, 3), CHAINE), "arriere de 1 est pris");
        assertEquals(Optional.of(Refusal.SOCKET_BUSY), graphe.add(new Link(2, a(3), "x", a(2), "avant"), CHAINE));
        assertEquals(Optional.of(Refusal.SELF), graphe.add(new Link(3, a(5), "x", a(5), "y"), CHAINE));
        assertEquals(Optional.of(Refusal.DUPLICATE), graphe.add(new Link(1, a(7), "x", a(8), "y"), CHAINE));
    }

    @Test
    @DisplayName("D3 : le monde n'est pas un nœud — un ensemble s'ancre plusieurs fois")
    void ancrages() {
        AttachmentGraph graphe = new AttachmentGraph();
        graphe.add(lien(1, 1, 2), CHAINE);
        assertEquals(Optional.empty(), graphe.add(new Link(2, a(1), "treuil", null, null), CHAINE));
        assertEquals(Optional.empty(), graphe.add(new Link(3, a(2), "treuil", null, null), CHAINE));
        assertTrue(graphe.get(2).orElseThrow().anchored());
        assertEquals(Set.of(a(1), a(2)), graphe.ensemble(a(1)), "l'ensemble ne contient pas le monde");
    }

    @Test
    @DisplayName("D7 : l'ensemble est la composante ; une assembly qui part emporte ses attaches")
    void ensembleEtRetrait() {
        AttachmentGraph graphe = new AttachmentGraph();
        graphe.add(lien(1, 1, 2), CHAINE);
        graphe.add(lien(2, 2, 3), CHAINE);
        graphe.add(lien(3, 7, 8), CHAINE);
        assertEquals(Set.of(a(1), a(2), a(3)), graphe.ensemble(a(3)));
        assertEquals(Set.of(a(9)), graphe.ensemble(a(9)), "seule");
        assertEquals(List.of(1, 2), graphe.removeAll(a(2)).stream().map(Link::id).toList());
        assertEquals(Set.of(a(1)), graphe.ensemble(a(1)));
        assertEquals(List.of(3), graphe.all().stream().map(Link::id).toList());
        assertTrue(graphe.remove(42).isEmpty());
        assertEquals("avant", graphe.get(3).orElseThrow().socketOf(a(8)));
    }
}
