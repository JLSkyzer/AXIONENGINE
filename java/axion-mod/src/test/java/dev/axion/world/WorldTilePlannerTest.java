package dev.axion.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.axion.world.WorldTilePlanner.Footprint;
import dev.axion.world.WorldTilePlanner.SectionKey;
import dev.axion.world.WorldTilePlanner.TilePlan;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Épingle l'ordonnancement des tuiles du monde (C-38, fiche 5.30, étapes 4-5).
 *
 * <p>Tests d'acceptance (ADR-125) : T-372 — reconstruction amortie, invalidation, ce qu'un corps
 * occupe en tête ; T-373 — libération des tuiles lointaines.
 */
class WorldTilePlannerTest {

    private static final List<Footprint> AT_ORIGIN = abris(new SectionKey(0L, 0, 0, 0));

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
        TilePlan moved = planner.plan(abris(new SectionKey(0L, 100, 0, 0)));
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
                planner.plan(abris(new SectionKey(0L, 0, 0, 0), new SectionKey(0L, 1, 0, 0)));
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

        List<SectionKey> premierLot = planner.plan(abris(centre)).toBuild();

        assertEquals(4, premierLot.size(), "budget respecté");
        assertEquals(centre, premierLot.get(0), "la section de l'assembly est bâtie en tête");
    }

    @Test
    void lesSectionsOuUnCorpsDebordeSontBatiesEnTeteDerriereUneFile() {
        // L'essai du 2026-10-06 : un cube abrité par (−8, 3, −1) déborde sur (−7, 3, −1), au
        // démarrage d'un serveur dont la file est pleine. Seul l'abri passait en tête : la
        // section voisine, sans eau, attendait son tour, la poussée ne portait que sur la moitié
        // −x du cube, et son couple l'a fait tourner à 47 rad/s. Tout ce qu'il occupe sort au
        // premier lot.
        WorldTilePlanner planner = new WorldTilePlanner(3, 8);
        SectionKey ailleurs = new SectionKey(0L, 10, 3, 7);
        planner.plan(abris(ailleurs)); // 343 sections en file, 8 bâties
        SectionKey abri = new SectionKey(0L, -8, 3, -1);
        Footprint cube = new Footprint(abri, new double[] {-112.79, 61.13, -5.71, -111.72, 62.20, -4.64}, 22.4);

        TilePlan plan = planner.plan(List.of(Footprint.at(ailleurs), cube));

        assertTrue(plan.toBuild().contains(new SectionKey(0L, -7, 3, -1)), plan.toBuild().toString());
        assertEquals(abri, plan.toBuild().get(0), "l'abri reste en tête");
        // Deux ticks de course à 22,4 m/s : le cube atteint aussi la couche y = 4 au-dessus.
        assertEquals(4, plan.urgent(), "(−8|−7, 3|4, −1) : quatre sections occupées");
        assertEquals(8, plan.toBuild().size(), "budget respecté");
    }

    @Test
    void unCorpsQuiTombeFaitBatirLaSectionOuIlVaEntrer() {
        // À 22 m/s, un corps parcourt plus d'un bloc par tick : la section sous lui doit être là
        // avant le pas où il y entre, pas à son tour dans la file.
        SectionKey ailleurs = new SectionKey(0L, 10, 3, 7);
        SectionKey abri = new SectionKey(0L, -8, 4, -1);
        SectionKey dessous = new SectionKey(0L, -8, 3, -1);
        double[] boite = {-112.8, 66.5, -5.7, -111.8, 67.5, -4.7};

        WorldTilePlanner auRepos = new WorldTilePlanner(3, 8);
        auRepos.plan(abris(ailleurs));
        List<SectionKey> lotAuRepos = auRepos.plan(List.of(Footprint.at(ailleurs), new Footprint(abri, boite, 0.0))).toBuild();
        assertFalse(lotAuRepos.contains(dessous), "au repos, la section du dessous attend son tour");

        WorldTilePlanner enChute = new WorldTilePlanner(3, 8);
        enChute.plan(abris(ailleurs));
        List<SectionKey> lotEnChute = enChute.plan(List.of(Footprint.at(ailleurs), new Footprint(abri, boite, 22.0))).toBuild();
        assertTrue(lotEnChute.contains(dessous), "en chute, elle passe en tête : " + lotEnChute);
    }

    @Test
    void uneEmpriseAberranteSeReduitASonAbri() {
        // Donnée venue du natif : non finie, elle ne fait rien énumérer ; démesurée, elle reste
        // bornée au voisinage de l'abri.
        SectionKey abri = new SectionKey(0L, 0, 0, 0);
        WorldTilePlanner nonFinie = new WorldTilePlanner(1, 64);
        TilePlan plan = nonFinie.plan(List.of(new Footprint(abri, new double[] {0, 0, 0, Double.POSITIVE_INFINITY, 1, 1}, Double.NaN)));
        assertEquals(1, plan.urgent(), "seul l'abri est urgent");

        WorldTilePlanner demesuree = new WorldTilePlanner(1, 64);
        TilePlan borne = demesuree.plan(List.of(new Footprint(abri, new double[] {-1e6, -1e6, -1e6, 1e6, 1e6, 1e6}, 300.0)));
        assertEquals(27, borne.urgent(), "le voisinage de rayon 1, pas davantage");
        assertEquals(27, demesuree.loadedCount());
    }

    /** {@return des corps réduits à leur section, sans emprise connue} */
    private static List<Footprint> abris(SectionKey... sections) {
        return Stream.of(sections).map(Footprint::at).toList();
    }
}
