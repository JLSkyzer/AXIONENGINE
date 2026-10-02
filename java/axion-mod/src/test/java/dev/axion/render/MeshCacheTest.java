package dev.axion.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.axion.asset.AssetKey;
import dev.axion.asset.AssetLoader;
import dev.axion.asset.AssetRegistry.Published;
import dev.axion.asset.GeometryTransfer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Cycle de vie des maillages client et de leurs handles natifs (ADR-119, R-321, R-322).
 *
 * <p>L'exécuteur est manuel : chaque test décide quand le thread de fond tourne, ce qui rend
 * déterministes les entrelacements qui comptent — libération pendant qu'un chargement attend,
 * nouveau contenu pendant qu'un autre se charge.
 */
class MeshCacheTest {

    private static final String PATH = "axion:axion/models/test/cube.gltf";

    /** Exécuteur manuel. */
    private static final class Manual implements Executor {
        private final Deque<Runnable> queue = new ArrayDeque<>();
        private boolean refuse;

        @Override
        public void execute(Runnable task) {
            if (refuse) {
                throw new RejectedExecutionException("arrêté");
            }
            queue.add(task);
        }

        void runAll() {
            while (!queue.isEmpty()) {
                queue.poll().run();
            }
        }
    }

    /** Chargeur simulé : chaque chargement réussi crée un handle, que le test peut compter. */
    private static final class FakeLoader implements AssetLoader {
        private final List<Long> loads = new ArrayList<>();
        private final List<Integer> unloads = new ArrayList<>();
        private int nextIndex = 1;
        private int failCode;
        private byte[] transfer = new byte[GeometryTransfer.HEADER_BYTES];

        @Override
        public Loaded load(long assetId, byte[] a3d) {
            loads.add(assetId);
            if (failCode != 0) {
                return Loaded.failed(failCode);
            }
            return new Loaded(0, nextIndex++, 1, transfer);
        }

        @Override
        public int unload(int index, int generation) {
            unloads.add(index);
            return 0;
        }
    }

    private final Manual executor = new Manual();
    private final FakeLoader loader = new FakeLoader();
    private final List<String> diagnostics = new ArrayList<>();
    private final MeshCache cache = new MeshCache(loader, executor, diagnostics::add);

    private static Published published(String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        return new Published(7L, AssetKey.of(bytes, "", 1, 2), bytes);
    }

    @Test
    @DisplayName("Un maillage se charge en arrière-plan, puis sert sans rechargement")
    void unMaillageSeChargeEnArrierePlan() {
        Published source = published("cube");
        assertNull(cache.get(PATH, source), "rendu bloquant : le maillage est déjà là");
        assertNull(cache.get(PATH, source), "chargement demandé deux fois");
        assertTrue(loader.loads.isEmpty(), "chargé sur le thread de rendu");

        executor.runAll();
        GeometryTransfer mesh = cache.get(PATH, source);
        assertNotNull(mesh);
        assertSame(mesh, cache.get(PATH, source));
        assertEquals(List.of(7L), loader.loads);
        assertEquals(1, cache.liveHandles());
    }

    @Test
    @DisplayName("Même clé, même maillage : une republication ne recharge rien")
    void uneRepublicationDeMemeCleNeRechargeRien() {
        Published premiere = published("cube");
        cache.get(PATH, premiere);
        executor.runAll();
        GeometryTransfer mesh = cache.get(PATH, premiere);

        // Un rechargement de ressources republie le même contenu, sous un autre objet.
        assertSame(mesh, cache.get(PATH, published("cube")));
        assertEquals(1, loader.loads.size());
    }

    @Test
    @DisplayName("Un nouveau contenu remplace l'ancien, dont le handle est rendu")
    void unNouveauContenuRendLAncienHandle() {
        cache.get(PATH, published("cube"));
        executor.runAll();

        Published modifie = published("cube modifié");
        assertNull(cache.get(PATH, modifie), "ancien maillage servi pour un nouveau contenu");
        executor.runAll();
        assertNotNull(cache.get(PATH, modifie));
        assertEquals(List.of(1), loader.unloads, "l'ancien handle n'a pas été rendu");
        assertEquals(1, cache.liveHandles());
    }

    @Test
    @DisplayName("Un chargement supplanté pendant qu'il attend rend son handle")
    void unChargementSupplanteRendSonHandle() {
        cache.get(PATH, published("v1"));
        Published v2 = published("v2");
        cache.get(PATH, v2);
        executor.runAll();

        // Les deux chargements ont eu lieu ; seul le second est gardé.
        assertEquals(2, loader.loads.size());
        assertEquals(List.of(1), loader.unloads);
        assertEquals(1, cache.liveHandles());
        assertNotNull(cache.get(PATH, v2));
    }

    @Test
    @DisplayName("Un échec est rapporté une fois et n'est pas retenté pour le même contenu")
    void unEchecEstRapporteUneFois() {
        loader.failCode = -3007;
        Published source = published("cassé");
        cache.get(PATH, source);
        executor.runAll();

        assertNull(cache.get(PATH, source));
        assertNull(cache.get(PATH, source));
        executor.runAll();
        assertEquals(1, loader.loads.size(), "échec retenté à chaque frame");
        assertEquals(1, diagnostics.size(), () -> diagnostics.toString());
        assertTrue(diagnostics.get(0).contains("-3007"));
        assertEquals(0, cache.liveHandles());
    }

    @Test
    @DisplayName("Une géométrie illisible rend le handle qu'elle a coûté")
    void uneGeometrieIllisibleRendSonHandle() {
        loader.transfer = new byte[GeometryTransfer.HEADER_BYTES + 1];
        cache.get(PATH, published("cube"));
        executor.runAll();

        assertNull(cache.get(PATH, published("cube")));
        assertEquals(List.of(1), loader.unloads);
        assertEquals(0, cache.liveHandles());
        assertEquals(1, diagnostics.size());
    }

    @Test
    @DisplayName("R-321 : la sortie d'un monde rend tout, et le cache resservira")
    void laSortieDUnMondeRendTout() {
        Published source = published("cube");
        cache.get(PATH, source);
        executor.runAll();
        assertEquals(1, cache.liveHandles());

        cache.releaseAll();
        assertEquals(0, cache.liveHandles());
        assertEquals(List.of(1), loader.unloads);

        // Le monde suivant redemande, et recharge.
        assertNull(cache.get(PATH, source));
        executor.runAll();
        assertNotNull(cache.get(PATH, source));
        assertEquals(2, loader.loads.size());
    }

    @Test
    @DisplayName("Un chargement demandé avant une libération n'a pas lieu")
    void unChargementDemandeAvantUneLiberationNALieuPas() {
        Published source = published("cube");
        cache.get(PATH, source);
        cache.releaseAll();
        executor.runAll();

        assertTrue(loader.loads.isEmpty(), "chargé après la libération qui l'annulait");
        assertEquals(0, cache.liveHandles());
        // La demande abandonnée n'a rien laissé derrière elle : la suivante aboutit.
        assertNull(cache.get(PATH, source));
        executor.runAll();
        assertNotNull(cache.get(PATH, source));
    }

    @Test
    @DisplayName("R-322 : après la fermeture, rien ne se charge plus et rien ne reste vivant")
    void apresLaFermetureRienNeSeCharge() {
        Published source = published("cube");
        cache.get(PATH, source);
        executor.runAll();
        cache.get(PATH, published("autre contenu"));

        cache.close();
        executor.runAll();
        assertEquals(0, cache.liveHandles());
        assertEquals(1, loader.loads.size(), "chargé après la fermeture");
        assertNull(cache.get(PATH, source));
        assertTrue(executor.queue.isEmpty(), "chargement demandé après la fermeture");
    }

    @Test
    @DisplayName("Un exécuteur arrêté donne un échec rapporté, pas une attente sans fin")
    void unExecuteurArreteDonneUnEchec() {
        executor.refuse = true;
        Published source = published("cube");
        assertNull(cache.get(PATH, source));
        assertNull(cache.get(PATH, source));

        assertEquals(1, diagnostics.size(), () -> diagnostics.toString());
        assertTrue(loader.loads.isEmpty());
    }
}
