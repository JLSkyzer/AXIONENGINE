package dev.axion.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.axion.world.WorldTilePlanner.SectionKey;
import dev.axion.world.WorldTilePlanner.TilePlan;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Épingle l'ordonnancement des tuiles du monde (C-38, fiche 5.30, étapes 4-5). */
class WorldTilePlannerTest {

    private static final List<SectionKey> AT_ORIGIN = List.of(new SectionKey(0L, 0, 0, 0));

    @Test
    void laReconstructionEstAmortieAuBudgetParTick() {
        // Rayon 1 → 3×3×3 = 27 sections désirées ; budget 8 par tick.
        WorldTilePlanner planner = new WorldTilePlanner(1, 8);

        TilePlan t1 = planner.plan(AT_ORIGIN);
        assertEquals(8, t1.toBuild().size(), "8 construites au premier tick");
        assertEquals(0, t1.toRemove().size());
        assertEquals(19, planner.pendingCount(), "19 restent en file");

        planner.plan(AT_ORIGIN); // 16
        planner.plan(AT_ORIGIN); // 24
        TilePlan t4 = planner.plan(AT_ORIGIN); // 27
        assertEquals(3, t4.toBuild().size(), "les 3 dernières");
        assertEquals(27, planner.loadedCount());
        assertEquals(0, planner.pendingCount());

        // Régime établi : plus rien à faire.
        TilePlan t5 = planner.plan(AT_ORIGIN);
        assertEquals(0, t5.toBuild().size());
        assertEquals(0, t5.toRemove().size());
    }

    @Test
    void lesTuilesLointainesSontLiberees() {
        WorldTilePlanner planner = new WorldTilePlanner(1, 64); // budget large : tout en un tick
        planner.plan(AT_ORIGIN);
        assertEquals(27, planner.loadedCount());

        // L'assembly saute loin : les 27 sections de l'origine sortent du voisinage.
        TilePlan moved = planner.plan(List.of(new SectionKey(0L, 100, 0, 0)));
        assertEquals(27, moved.toRemove().size(), "les 27 sections d'origine sont libérées");
        assertEquals(27, moved.toBuild().size(), "les 27 sections autour de la nouvelle position");
    }

    @Test
    void uneSectionInvalideeEstReconstruite() {
        WorldTilePlanner planner = new WorldTilePlanner(0, 8); // 1 section désirée
        assertEquals(1, planner.plan(AT_ORIGIN).toBuild().size());
        assertEquals(0, planner.plan(AT_ORIGIN).toBuild().size(), "déjà chargée");

        planner.invalidate(new SectionKey(0L, 0, 0, 0));
        assertEquals(1, planner.plan(AT_ORIGIN).toBuild().size(), "reconstruite après invalidation");
    }

    @Test
    void uneInvalidationHorsPortEstElaguee() {
        WorldTilePlanner planner = new WorldTilePlanner(0, 8);
        planner.plan(AT_ORIGIN);
        planner.invalidate(new SectionKey(0L, 999, 0, 0)); // loin de l'assembly
        assertEquals(0, planner.plan(AT_ORIGIN).toBuild().size(), "rien à bâtir hors portée");
    }

    @Test
    void lOrdreDeConstructionEstDeterministe() {
        WorldTilePlanner a = new WorldTilePlanner(1, 8);
        WorldTilePlanner b = new WorldTilePlanner(1, 8);
        assertEquals(a.plan(AT_ORIGIN).toBuild(), b.plan(AT_ORIGIN).toBuild());
        assertEquals(a.plan(AT_ORIGIN).toBuild(), b.plan(AT_ORIGIN).toBuild());
    }

    @Test
    void deuxAssembliesProchesPartagentLeurVoisinage() {
        WorldTilePlanner planner = new WorldTilePlanner(1, 512);
        // Deux assemblies adjacentes : l'union des voisinages, sans double comptage.
        TilePlan plan =
                planner.plan(List.of(new SectionKey(0L, 0, 0, 0), new SectionKey(0L, 1, 0, 0)));
        // Union de deux cubes 3×3×3 décalés de 1 en x : 4×3×3 = 36 sections distinctes.
        assertEquals(36, plan.toBuild().size());
        assertTrue(plan.toRemove().isEmpty());
    }

    @Test
    void laSectionAbritantUneAssemblyEstBatieEnPremier() {
        // Rayon 1 (27 désirées), budget 4. Dans l'ordre déterministe (dim, x, y, z), la
        // section centrale (0,0,0) est 14ᵉ : sans priorité, elle ne serait pas dans le
        // premier lot, et un corps créé à ce tick tomberait au travers avant que son sol
        // soit bâti. Elle doit être construite en tête.
        WorldTilePlanner planner = new WorldTilePlanner(1, 4);
        SectionKey centre = new SectionKey(0L, 0, 0, 0);

        List<SectionKey> premierLot = planner.plan(List.of(centre)).toBuild();

        assertEquals(4, premierLot.size(), "budget respecté");
        assertEquals(centre, premierLot.get(0), "la section de l'assembly est bâtie en tête");
    }
}
