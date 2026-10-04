package dev.axion.physics;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.axion.physics.EntityProxySelector.Assembly;
import dev.axion.physics.EntityProxySelector.Box;
import dev.axion.physics.EntityProxySelector.Candidate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** T-304, part Java : la règle du rayon d'influence (R-614, ADR-123 §7). */
class EntityProxySelectorTest {

    private static final Box CUBE = new Box(0, 0, 0, 1, 1, 1);

    /** Une entité de 1 × 2 × 1 dont le flanc gauche est en {@code x}. */
    private static Candidate entite(int id, double x, double vitesse, boolean vivante) {
        return new Candidate(id, new Box(x, 0, 0, x + 1, 2, 1), new double[] {vitesse, 0, 0}, vivante);
    }

    @Test
    void laMargeVautUnBlocAuMoinsPuisDeuxTicksDeVitesse() {
        assertEquals(1.0, EntityProxySelector.margin(0, 0));
        assertEquals(1.0, EntityProxySelector.margin(4, 4), "0,8 bloc : la marge minimale l'emporte");
        assertEquals(2.0, EntityProxySelector.margin(10, 10), 1e-12);
    }

    @Test
    void uneEntiteEstVueSiElleEstDansLeRayonDInfluence() {
        List<SimCommandStream.EntityProxy> proxies = EntityProxySelector.select(
                List.of(new Assembly(CUBE, 0)),
                List.of(
                        entite(1, 1.5, 0, true), // à 0,5 bloc : vue
                        entite(2, 3.0, 0, true), // à 2 blocs, immobile : hors de portée
                        entite(3, 3.0, 30, false))); // à 2 blocs mais à 30 m/s : marge de 3 blocs

        assertEquals(List.of(1, 3), proxies.stream().map(SimCommandStream.EntityProxy::entity).toList());
    }

    @Test
    void laVitesseDeLAssemblyElargitAussiLeRayon() {
        Candidate loin = entite(4, 3.0, 0, true);
        assertTrue(EntityProxySelector.select(List.of(new Assembly(CUBE, 0)), List.of(loin)).isEmpty());
        assertEquals(1, EntityProxySelector.select(List.of(new Assembly(CUBE, 25)), List.of(loin)).size());
    }

    @Test
    void uneEntiteProcheDeDeuxAssembliesNestDeclareeQuUneFois() {
        Assembly gauche = new Assembly(CUBE, 0);
        Assembly droite = new Assembly(new Box(3, 0, 0, 4, 1, 1), 0);
        List<SimCommandStream.EntityProxy> proxies =
                EntityProxySelector.select(List.of(gauche, droite), List.of(entite(5, 1.5, 0, true)));
        assertEquals(1, proxies.size());
    }

    @Test
    void leProxyPorteLeCentreLesDemiDimensionsLaVitesseEtLaForme() {
        SimCommandStream.EntityProxy vivant = EntityProxySelector.select(
                        List.of(new Assembly(CUBE, 0)), List.of(entite(7, 1.5, 2.5, true)))
                .get(0);
        assertEquals(7, vivant.entity());
        assertArrayEquals(new double[] {2.0, 1.0, 0.5}, vivant.center(), 1e-12);
        assertArrayEquals(new float[] {0.5f, 1.0f, 0.5f}, vivant.halfExtents());
        assertArrayEquals(new float[] {2.5f, 0f, 0f}, vivant.velocity());
        assertEquals(SimCommandStream.PROXY_CAPSULE, vivant.shape());

        SimCommandStream.EntityProxy objet = EntityProxySelector.select(
                        List.of(new Assembly(CUBE, 0)), List.of(entite(8, 1.5, 0, false)))
                .get(0);
        assertEquals(SimCommandStream.PROXY_BOX, objet.shape());
    }
}
