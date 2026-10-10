package dev.axion.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T-500 — arènes de 16 Mio : sous-allocation, fusion, mesh hors arène (fiche 5.49, ADR-127 §3). */
class ArenaAllocatorTest {

    private static final long MIO = 1L << 20;

    /** Des sommets de 48 octets, des indices de 4 : les tailles d'un vrai mesh. */
    private static long sommets(long nombre) {
        return nombre * ArenaAllocator.VERTEX_BYTES;
    }

    private static long indices(long nombre) {
        return nombre * ArenaAllocator.INDEX_BYTES;
    }

    @Test
    @DisplayName("Deux meshes partagent une arène : sommets et indices se suivent dans chaque tampon")
    void deuxMeshesPartagentUneArene() {
        ArenaAllocator arenes = new ArenaAllocator(16 * MIO);
        ArenaAllocator.Allocation a = arenes.allocate(sommets(100), indices(300));
        ArenaAllocator.Allocation b = arenes.allocate(sommets(50), indices(60));

        assertEquals(a.arena(), b.arena());
        assertEquals(0, a.vertexOffset());
        assertEquals(0, a.indexOffset());
        assertEquals(sommets(100), b.vertexOffset());
        assertEquals(indices(300), b.indexOffset());
        assertEquals(1, arenes.arenaCount());
        assertEquals(2 * 16 * MIO, arenes.gpuBytes(), "une paire de tampons de 16 Mio");
    }

    @Test
    @DisplayName("Le décalage d'un mesh tombe sur un sommet entier : base de glDrawElementsBaseVertex")
    void leDecalageDesSommetsTombeSurUnSommetEntier() {
        ArenaAllocator arenes = new ArenaAllocator(16 * MIO);
        arenes.allocate(sommets(7), indices(9));
        ArenaAllocator.Allocation b = arenes.allocate(sommets(3), indices(3));
        assertEquals(0, b.vertexOffset() % ArenaAllocator.VERTEX_BYTES);
        assertEquals(7, b.baseVertex());
        assertEquals(0, b.indexOffset() % ArenaAllocator.INDEX_BYTES);
    }

    @Test
    @DisplayName("Une taille qui n'est pas un nombre entier de sommets ou d'indices est refusée")
    void uneTailleBancaleEstRefusee() {
        ArenaAllocator arenes = new ArenaAllocator(16 * MIO);
        assertThrows(IllegalArgumentException.class, () -> arenes.allocate(47, indices(3)));
        assertThrows(IllegalArgumentException.class, () -> arenes.allocate(sommets(3), 6));
        assertThrows(IllegalArgumentException.class, () -> arenes.allocate(0, indices(3)));
    }

    @Test
    @DisplayName("Une place rendue sert au mesh suivant qui y tient : la première place libre")
    void unePlaceRendueSertAuSuivant() {
        ArenaAllocator arenes = new ArenaAllocator(16 * MIO);
        arenes.allocate(sommets(100), indices(100));
        ArenaAllocator.Allocation b = arenes.allocate(sommets(50), indices(50));
        arenes.allocate(sommets(100), indices(100));

        arenes.free(b);
        ArenaAllocator.Allocation d = arenes.allocate(sommets(40), indices(40));
        assertEquals(b.vertexOffset(), d.vertexOffset(), "la place de b, la première qui convient");
        assertEquals(b.indexOffset(), d.indexOffset());
    }

    @Test
    @DisplayName("Deux places libres voisines fusionnent : un mesh plus grand que chacune y tient")
    void lesPlacesVoisinesFusionnent() {
        ArenaAllocator arenes = new ArenaAllocator(sommets(300));
        ArenaAllocator.Allocation a = arenes.allocate(sommets(100), indices(10));
        ArenaAllocator.Allocation b = arenes.allocate(sommets(100), indices(10));
        arenes.allocate(sommets(100), indices(10));

        arenes.free(b);
        arenes.free(a);
        ArenaAllocator.Allocation d = arenes.allocate(sommets(200), indices(20));
        assertEquals(a.arena(), d.arena(), "les deux places fusionnées suffisent, pas d'arène neuve");
        assertEquals(0, d.vertexOffset());
        assertEquals(1, arenes.arenaCount());
    }

    @Test
    @DisplayName("Une arène pleine en ouvre une autre")
    void uneArenePleineEnOuvreUneAutre() {
        ArenaAllocator arenes = new ArenaAllocator(16 * MIO);
        ArenaAllocator.Allocation a = arenes.allocate(sommets(200_000), indices(10));
        ArenaAllocator.Allocation b = arenes.allocate(sommets(200_000), indices(10));
        assertNotEquals(a.arena(), b.arena(), "deux fois 9,2 Mio ne tiennent pas dans 16");
        assertEquals(2, arenes.arenaCount());
    }

    @Test
    @DisplayName("Des indices qui débordent ouvrent aussi une arène, même si les sommets tiennent")
    void desIndicesQuiDebordentOuvrentUneArene() {
        ArenaAllocator arenes = new ArenaAllocator(16 * MIO);
        ArenaAllocator.Allocation a = arenes.allocate(sommets(10), 10 * MIO);
        ArenaAllocator.Allocation b = arenes.allocate(sommets(10), 10 * MIO);
        assertNotEquals(a.arena(), b.arena());
    }

    @Test
    @DisplayName("Un mesh plus grand qu'une arène reçoit une paire à sa taille, comptée")
    void unMeshHorsAreneRecoitUnePaireASaTaille() {
        ArenaAllocator arenes = new ArenaAllocator(16 * MIO);
        long grands = sommets(500_000); // 22,9 Mio
        ArenaAllocator.Allocation a = arenes.allocate(grands, indices(1_000));

        ArenaAllocator.Arena arene = arenes.arena(a.arena());
        assertTrue(arene.dedicated());
        assertEquals(grands, arene.vertexCapacity());
        assertEquals(indices(1_000), arene.indexCapacity());
        assertEquals(grands + indices(1_000), arenes.gpuBytes());
        // Elle ne reçoit personne d'autre.
        ArenaAllocator.Allocation b = arenes.allocate(sommets(1), indices(3));
        assertNotEquals(a.arena(), b.arena());
    }

    @Test
    @DisplayName("Une arène vidée est rendue : elle ne compte plus, et le collage GL détruit ses tampons")
    void uneAreneVideeEstRendue() {
        ArenaAllocator arenes = new ArenaAllocator(16 * MIO);
        ArenaAllocator.Allocation a = arenes.allocate(sommets(10), indices(30));
        ArenaAllocator.Allocation b = arenes.allocate(sommets(10), indices(30));

        assertEquals(Optional.empty(), arenes.free(a), "b l'occupe encore");
        Optional<ArenaAllocator.Arena> videe = arenes.free(b);
        assertTrue(videe.isPresent());
        assertEquals(b.arena(), videe.get().id());
        assertEquals(0, arenes.arenaCount());
        assertEquals(0, arenes.gpuBytes());
    }

    @Test
    @DisplayName("Une place rendue deux fois, ou étrangère, est refusée")
    void unePlaceRendueDeuxFoisEstRefusee() {
        ArenaAllocator arenes = new ArenaAllocator(16 * MIO);
        ArenaAllocator.Allocation a = arenes.allocate(sommets(10), indices(30));
        arenes.allocate(sommets(10), indices(30));
        arenes.free(a);
        assertThrows(IllegalStateException.class, () -> arenes.free(a));
        assertThrows(IllegalStateException.class,
                () -> arenes.free(new ArenaAllocator.Allocation(99, 0, sommets(1), 0, indices(3))));
    }

    @Test
    @DisplayName("Tout rendre d'un coup — un rechargement de ressources, R-752 — vide toutes les arènes")
    void toutRendreVideToutesLesArenes() {
        ArenaAllocator arenes = new ArenaAllocator(16 * MIO);
        arenes.allocate(sommets(200_000), indices(10));
        arenes.allocate(sommets(200_000), indices(10));
        arenes.allocate(sommets(500_000), indices(10));

        List<ArenaAllocator.Arena> rendues = arenes.clear();
        assertEquals(3, rendues.size());
        assertEquals(0, arenes.arenaCount());
        assertEquals(0, arenes.gpuBytes());
        // Les numéros ne resservent pas : un tampon détruit ne se confond pas avec un neuf.
        ArenaAllocator.Allocation neuve = arenes.allocate(sommets(1), indices(3));
        assertFalse(rendues.stream().anyMatch(arene -> arene.id() == neuve.arena()));
    }
}
