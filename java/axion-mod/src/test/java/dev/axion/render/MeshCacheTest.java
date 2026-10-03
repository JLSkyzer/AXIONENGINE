package dev.axion.render;

import static dev.axion.render.TestMaterials.EMBEDDED;
import static dev.axion.render.TestMaterials.material;
import static dev.axion.render.TestMaterials.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.axion.asset.AssetKey;
import dev.axion.asset.AssetLoader;
import dev.axion.asset.AssetRegistry.Published;
import dev.axion.asset.GeometryTransfer;
import dev.axion.asset.MaterialTransfer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Cycle de vie des assets client, de leurs handles natifs et de leurs textures (ADR-119,
 * ADR-122 §7, R-321, R-322, R-751, R-752).
 *
 * <p>Les deux exécuteurs sont manuels : chaque test décide quand le thread de fond et le fil de
 * rendu tournent, ce qui rend déterministes les entrelacements qui comptent — libération pendant
 * qu'un chargement attend, nouveau contenu pendant qu'un autre se charge, texture préparée qu'un
 * téléversement n'attend plus.
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
        private final List<Integer> textureReads = new ArrayList<>();
        private final Map<Integer, byte[]> pngs = new HashMap<>();
        private int nextIndex = 1;
        private int failCode;
        private int materialsCode;
        private MaterialTransfer materials = MaterialTransfer.empty();
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

        @Override
        public FetchedMaterials materials(int index, int generation) {
            return materialsCode != 0 ? FetchedMaterials.failed(materialsCode) : new FetchedMaterials(0, materials);
        }

        @Override
        public FetchedTexture texture(int index, int generation, int texture) {
            textureReads.add(texture);
            byte[] png = pngs.get(texture);
            return png == null ? FetchedTexture.failed(-2002) : new FetchedTexture(0, png);
        }
    }

    /** Pipeline simulé : il ne décode rien, il compte. */
    private static final class FakePipeline implements TexturePipeline {
        private final List<Request> requests = new ArrayList<>();
        private final List<String> uploaded = new ArrayList<>();
        private final List<String> released = new ArrayList<>();
        private final List<String> discarded = new ArrayList<>();
        private final Set<Integer> refused = new HashSet<>();
        private boolean blur;
        private boolean mipmap;
        private boolean failUploads;

        @Override
        public Prepared prepare(Request request) throws TextureRefusal {
            requests.add(request);
            if (refused.contains(request.key().rank())) {
                throw new TextureRefusal("E-3004 : pas un PNG (R-532)");
            }
            return new Staged(new TextureBinding(request.location(), blur, mipmap));
        }

        @Override
        public void upload(Prepared prepared) {
            if (failUploads) {
                throw new IllegalStateException("contexte graphique perdu");
            }
            uploaded.add(prepared.binding().location());
        }

        @Override
        public void release(String location) {
            released.add(location);
        }

        @Override
        public void discard(Prepared prepared) {
            discarded.add(prepared.binding().location());
        }

        private record Staged(TextureBinding binding) implements Prepared {
            @Override
            public String note() {
                return null;
            }
        }
    }

    private final Manual executor = new Manual();
    private final Manual render = new Manual();
    private final FakeLoader loader = new FakeLoader();
    private final FakePipeline textures = new FakePipeline();
    private final List<String> diagnostics = new ArrayList<>();
    private final MeshCache cache = new MeshCache(loader, textures, executor, render, diagnostics::add);

    private static Published published(String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        return new Published(7L, AssetKey.of(bytes, "", 1, 2), bytes);
    }

    /** Fait tourner le thread de fond et le fil de rendu jusqu'à ce que plus rien n'attende. */
    private void settle() {
        while (!executor.queue.isEmpty() || !render.queue.isEmpty()) {
            executor.runAll();
            render.runAll();
        }
    }

    /** Un matériau opaque texturé d'un PNG embarqué. */
    private void oneEmbeddedTexture() {
        loader.materials = table(List.of(material().albedo(0)), EMBEDDED);
        loader.pngs.put(0, new byte[] {1, 2, 3});
    }

    @Test
    @DisplayName("Un maillage se charge en arrière-plan, puis sert sans rechargement")
    void unMaillageSeChargeEnArrierePlan() {
        Published source = published("cube");
        assertNull(cache.get(PATH, source), "rendu bloquant : le maillage est déjà là");
        assertNull(cache.get(PATH, source), "chargement demandé deux fois");
        assertTrue(loader.loads.isEmpty(), "chargé sur le thread de rendu");

        settle();
        RenderAsset asset = cache.get(PATH, source);
        assertNotNull(asset);
        assertSame(asset, cache.get(PATH, source));
        assertEquals(List.of(7L), loader.loads);
        assertEquals(1, cache.liveHandles());
    }

    @Test
    @DisplayName("Même clé, même maillage : une republication ne recharge rien")
    void uneRepublicationDeMemeCleNeRechargeRien() {
        Published premiere = published("cube");
        cache.get(PATH, premiere);
        settle();
        RenderAsset asset = cache.get(PATH, premiere);

        // Un rechargement de ressources republie le même contenu, sous un autre objet.
        assertSame(asset, cache.get(PATH, published("cube")));
        assertEquals(1, loader.loads.size());
    }

    @Test
    @DisplayName("Un nouveau contenu remplace l'ancien, dont le handle est rendu")
    void unNouveauContenuRendLAncienHandle() {
        cache.get(PATH, published("cube"));
        settle();

        Published modifie = published("cube modifié");
        assertNull(cache.get(PATH, modifie), "ancien maillage servi pour un nouveau contenu");
        settle();
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
        settle();

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
        settle();

        assertNull(cache.get(PATH, source));
        assertNull(cache.get(PATH, source));
        settle();
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
        settle();

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
        settle();
        assertEquals(1, cache.liveHandles());

        cache.releaseAll();
        assertEquals(0, cache.liveHandles());
        assertEquals(List.of(1), loader.unloads);

        // Le monde suivant redemande, et recharge.
        assertNull(cache.get(PATH, source));
        settle();
        assertNotNull(cache.get(PATH, source));
        assertEquals(2, loader.loads.size());
    }

    @Test
    @DisplayName("Un chargement demandé avant une libération n'a pas lieu")
    void unChargementDemandeAvantUneLiberationNALieuPas() {
        Published source = published("cube");
        cache.get(PATH, source);
        cache.releaseAll();
        settle();

        assertTrue(loader.loads.isEmpty(), "chargé après la libération qui l'annulait");
        assertEquals(0, cache.liveHandles());
        // La demande abandonnée n'a rien laissé derrière elle : la suivante aboutit.
        assertNull(cache.get(PATH, source));
        settle();
        assertNotNull(cache.get(PATH, source));
    }

    @Test
    @DisplayName("R-322 : après la fermeture, rien ne se charge plus et rien ne reste vivant")
    void apresLaFermetureRienNeSeCharge() {
        Published source = published("cube");
        cache.get(PATH, source);
        settle();
        cache.get(PATH, published("autre contenu"));

        cache.close();
        settle();
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

    @Test
    @DisplayName("Une texture se prépare en arrière-plan et se téléverse sur le fil de rendu")
    void uneTextureSeTeleverseSurLeFilDeRendu() {
        oneEmbeddedTexture();
        Published source = published("cube texturé");
        cache.get(PATH, source);

        executor.runAll();
        assertEquals(1, textures.requests.size(), "préparée en arrière-plan");
        assertTrue(textures.uploaded.isEmpty(), "téléversée hors du fil de rendu");
        assertNull(cache.get(PATH, source), "asset servi avant ses textures");

        render.runAll();
        RenderAsset asset = cache.get(PATH, source);
        assertNotNull(asset);
        String location = textures.requests.get(0).location();
        assertEquals(List.of(location), textures.uploaded);
        assertEquals(location, asset.texture(TextureKey.plain(0)).location());
        assertEquals(1, cache.liveTextures());
        assertTrue(diagnostics.isEmpty(), () -> diagnostics.toString());
    }

    @Test
    @DisplayName("R-751 : la sortie d'un monde libère aussi les textures, sur le fil de rendu")
    void laSortieDUnMondeLibereLesTextures() {
        oneEmbeddedTexture();
        cache.get(PATH, published("cube texturé"));
        settle();
        String location = textures.uploaded.get(0);

        cache.releaseAll();
        assertEquals(List.of(location), textures.released);
        assertEquals(0, cache.liveTextures());
        assertEquals(0, cache.liveHandles());
    }

    @Test
    @DisplayName("Fermé depuis un autre thread, le cache confie la libération des textures au fil de rendu")
    void laFermetureLibereLesTexturesSurLeFilDeRendu() {
        oneEmbeddedTexture();
        cache.get(PATH, published("cube texturé"));
        settle();

        cache.close();
        assertTrue(textures.released.isEmpty(), "libérée hors du fil de rendu");
        render.runAll();
        assertEquals(textures.uploaded, textures.released);
    }

    @Test
    @DisplayName("Un nouveau contenu libère les textures de l'ancien, la frame en cours finie")
    void unNouveauContenuLibereLesTexturesDeLAncien() {
        oneEmbeddedTexture();
        cache.get(PATH, published("v1"));
        settle();
        String ancienne = textures.uploaded.get(0);

        cache.get(PATH, published("v2"));
        // La frame qui découvre le nouveau contenu peut encore dessiner l'ancien asset.
        assertTrue(textures.released.isEmpty(), "libérée pendant la frame qui peut encore la lier");
        render.runAll();
        assertEquals(List.of(ancienne), textures.released);
        settle();
        assertEquals(2, textures.uploaded.size());
        assertTrue(!textures.uploaded.get(1).equals(ancienne), "deux chargements, un même nom");
    }

    @Test
    @DisplayName("Un fil de rendu qui refuse : les textures de l'ancien contenu se libèrent sur place")
    void unFilDeRenduQuiRefuseLibereSurPlace() {
        oneEmbeddedTexture();
        cache.get(PATH, published("v1"));
        settle();
        String ancienne = textures.uploaded.get(0);

        render.refuse = true;
        cache.get(PATH, published("v2"));
        assertEquals(List.of(ancienne), textures.released);
    }

    @Test
    @DisplayName("Un chargement supplanté abandonne ses textures préparées, sans les téléverser")
    void unChargementSupplanteAbandonneSesTextures() {
        oneEmbeddedTexture();
        cache.get(PATH, published("v1"));
        executor.runAll();
        String v1 = textures.requests.get(0).location();

        cache.get(PATH, published("v2"));
        settle();
        assertEquals(List.of(v1), textures.discarded);
        assertTrue(!textures.uploaded.contains(v1), "texture d'un contenu supplanté téléversée");
        assertEquals(List.of(1), loader.unloads);
        assertEquals(1, cache.liveTextures());
    }

    @Test
    @DisplayName("Une libération pendant la préparation abandonne les textures et rend le handle une fois")
    void uneLiberationPendantLaPreparationAbandonneLesTextures() {
        oneEmbeddedTexture();
        cache.get(PATH, published("cube texturé"));
        executor.runAll();

        cache.releaseAll();
        settle();
        assertEquals(1, textures.discarded.size());
        assertTrue(textures.uploaded.isEmpty());
        assertEquals(List.of(1), loader.unloads, "handle rendu deux fois, ou jamais");
        assertEquals(0, cache.liveHandles());
    }

    @Test
    @DisplayName("Une texture refusée est remplacée par la texture neutre, et rapportée une fois")
    void uneTextureRefuseeEstRemplaceeParLaNeutre() {
        oneEmbeddedTexture();
        textures.refused.add(0);
        Published source = published("cube texturé");
        cache.get(PATH, source);
        settle();

        RenderAsset asset = cache.get(PATH, source);
        assertNotNull(asset, "une texture refusée a bloqué l'asset");
        assertNull(asset.texture(TextureKey.plain(0)), "texture refusée servie");
        assertEquals(1, diagnostics.size(), () -> diagnostics.toString());
        assertTrue(diagnostics.get(0).contains("E-3004") && diagnostics.get(0).contains("texture neutre"));
    }

    @Test
    @DisplayName("Une ressource se résout contre le répertoire du modèle ; un chemin refusé est rapporté")
    void uneRessourceSeResoutEtUnCheminRefuseEstRapporte() {
        loader.materials = table(List.of(material().albedo(0), material().albedo(1)), "tex/a.png", "../b.png");
        cache.get(PATH, published("cube"));
        settle();

        assertEquals(1, textures.requests.size(), () -> textures.requests.toString());
        TexturePipeline.Request request = textures.requests.get(0);
        assertEquals("axion:axion/models/test/tex/a.png", request.resource());
        assertNull(request.embedded());
        assertEquals(1, diagnostics.size(), () -> diagnostics.toString());
        assertTrue(diagnostics.get(0).contains("E-3002"));
    }

    @Test
    @DisplayName("Deux variantes d'une même image : ses octets sont lus une fois, chacune au seuil voulu")
    void deuxVariantesDUneMemeImage() {
        loader.materials = table(List.of(material().albedo(0), material().albedo(0).cutout(0.5f)), EMBEDDED);
        loader.pngs.put(0, new byte[] {1});
        cache.get(PATH, published("grille"));
        settle();

        assertEquals(List.of(0), loader.textureReads, "octets embarqués lus deux fois");
        assertEquals(List.of(TextureKey.plain(0), TextureKey.cutout(0, 0.5f)),
                textures.requests.stream().map(TexturePipeline.Request::key).toList());
        assertEquals(2, cache.liveTextures());
    }

    @Test
    @DisplayName("Une table illisible laisse l'asset s'afficher, avec le matériau par défaut")
    void uneTableIllisibleDonneLeMateriauParDefaut() {
        loader.materialsCode = -2002;
        Published source = published("cube");
        cache.get(PATH, source);
        settle();

        RenderAsset asset = cache.get(PATH, source);
        assertNotNull(asset);
        assertTrue(asset.materials().materials().isEmpty());
        assertEquals(1, diagnostics.size(), () -> diagnostics.toString());
        assertTrue(diagnostics.get(0).contains("matériau par défaut"));
    }

    @Test
    @DisplayName("Chaque mesh prend son matériau, et sa texture liée au filtrage du téléversement")
    void chaqueMeshPrendSonMateriauEtSaTextureLiee() {
        loader.transfer = TestGeometry.bytes(TestGeometry.white(0, 0), TestGeometry.white(1, 0));
        loader.materials = table(List.of(material().albedo(0), material().doubleSided()), EMBEDDED);
        loader.pngs.put(0, new byte[] {1});
        textures.blur = true;
        textures.mipmap = true;
        Published source = published("deux meshes");
        cache.get(PATH, source);
        settle();

        RenderAsset asset = cache.get(PATH, source);
        assertNotNull(asset);
        assertSame(loader.materials.materials().get(0), asset.look(0).material());
        assertSame(loader.materials.materials().get(1), asset.look(1).material());
        TextureBinding albedo = asset.albedo(0);
        assertEquals(textures.uploaded.get(0), albedo.location());
        assertTrue(albedo.blur() && albedo.mipmap(), "filtrage du téléversement perdu");
        assertFalse(asset.look(0).doubleSided());
        assertNull(asset.albedo(1), "un slot vide prend la texture neutre");
        assertTrue(asset.look(1).doubleSided());
        assertTrue(diagnostics.isEmpty(), () -> diagnostics.toString());
    }

    @Test
    @DisplayName("Des meshes sans matériau dans la table : matériau par défaut, signalé une fois")
    void desMeshesSansMateriauSontSignalesUneFois() {
        loader.transfer = TestGeometry.bytes(TestGeometry.white(4, 0), TestGeometry.white(5, 0));
        loader.materials = table(List.of(material()));
        Published source = published("deux meshes");
        cache.get(PATH, source);
        settle();

        RenderAsset asset = cache.get(PATH, source);
        assertNotNull(asset, "un matériau absent a bloqué l'asset");
        assertEquals(SurfacePass.OPAQUE, asset.look(0).pass());
        assertEquals(1, diagnostics.size(), () -> diagnostics.toString());
        assertTrue(diagnostics.get(0).startsWith(PATH) && diagnostics.get(0).contains("matériau par défaut"));
    }

    @Test
    @DisplayName("Une table illisible est signalée une fois, pas une seconde par ses meshes")
    void uneTableIllisibleNEstSignaleeQuUneFois() {
        loader.transfer = TestGeometry.bytes(TestGeometry.white(0, 0), TestGeometry.white(1, 0));
        loader.materialsCode = -2002;
        Published source = published("deux meshes");
        cache.get(PATH, source);
        settle();

        RenderAsset asset = cache.get(PATH, source);
        assertNotNull(asset);
        assertEquals(SurfacePass.OPAQUE, asset.look(1).pass());
        assertEquals(1, diagnostics.size(), () -> diagnostics.toString());
        assertTrue(diagnostics.get(0).contains("-2002"));
    }

    @Test
    @DisplayName("Une texture perdue au téléversement laisse la texture neutre à son mesh")
    void uneTexturePerdueAuTeleversementLaisseLaNeutre() {
        loader.transfer = TestGeometry.bytes(TestGeometry.white(0, 0));
        oneEmbeddedTexture();
        textures.failUploads = true;
        Published source = published("cube texturé");
        cache.get(PATH, source);
        settle();

        RenderAsset asset = cache.get(PATH, source);
        assertNotNull(asset);
        assertNull(asset.albedo(0));
        assertEquals(1, textures.discarded.size());
        assertEquals(0, cache.liveTextures());
        assertEquals(1, diagnostics.size(), () -> diagnostics.toString());
        assertTrue(diagnostics.get(0).contains("téléversement impossible"));
    }

    @Test
    @DisplayName("Un mesh qui désigne hors de ses sommets rend son handle et laisse la boîte de repli")
    void uneApparenceImpossibleRendLeHandle() {
        byte[] transfer = TestGeometry.bytes(TestGeometry.white(0, 0));
        // vertexCount du premier MeshDesc, juste après vertexOffset : 99 sommets, pour 3.
        ByteBuffer.wrap(transfer).order(ByteOrder.LITTLE_ENDIAN).putInt(16 + 4, 99);
        loader.transfer = transfer;
        Published source = published("cube");
        cache.get(PATH, source);
        settle();

        assertNull(cache.get(PATH, source));
        assertEquals(List.of(1), loader.unloads);
        assertEquals(0, cache.liveHandles());
        assertEquals(1, diagnostics.size(), () -> diagnostics.toString());
        assertTrue(diagnostics.get(0).contains("apparence impossible"));
    }
}
