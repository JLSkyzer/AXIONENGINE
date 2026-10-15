package dev.axion.forge;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.axion.asset.AssetCompiler;
import dev.axion.asset.AssetRegistry;
import dev.axion.asset.AssetState;
import dev.axion.asset.SiblingFiles;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** C-20 étape 1 et ADR-135 — ce qu'un pack donne à lire, et ce qu'il ne peut pas donner. */
class ResourceAssetSourceTest {

    private static final String VOITURE = "axion:axion/models/voiture.glb";
    private static final String OPTIONS = "axion:axion/models/voiture.axion.json";
    private static final String BUFFER = "axion:axion/models/voiture.bin";

    /** Borne des voisins des essais : assez pour tout ce qu'ils lisent. */
    private static final long BORNE = 1024;

    /** Un gestionnaire de ressources qui ne sert que ce qu'on lui donne. */
    private static ResourceManager manager(Map<String, IoSupplier<InputStream>> resources) {
        Map<ResourceLocation, Resource> all = new LinkedHashMap<>();
        resources.forEach((location, supplier) ->
                all.put(ResourceLocation.tryParse(location), new Resource(null, supplier)));
        return new ResourceManager() {
            @Override
            public Set<String> getNamespaces() {
                return Set.of("axion");
            }

            @Override
            public List<Resource> getResourceStack(ResourceLocation location) {
                return List.of();
            }

            @Override
            public Map<ResourceLocation, Resource> listResources(
                    String prefix, Predicate<ResourceLocation> filter) {
                Map<ResourceLocation, Resource> found = new LinkedHashMap<>();
                all.forEach((location, resource) -> {
                    if (location.getPath().startsWith(prefix + "/") && filter.test(location)) {
                        found.put(location, resource);
                    }
                });
                return found;
            }

            @Override
            public Map<ResourceLocation, List<Resource>> listResourceStacks(
                    String prefix, Predicate<ResourceLocation> filter) {
                return Map.of();
            }

            @Override
            public Stream<PackResources> listPacks() {
                return Stream.empty();
            }

            @Override
            public Optional<Resource> getResource(ResourceLocation location) {
                return Optional.ofNullable(all.get(location));
            }
        };
    }

    private static IoSupplier<InputStream> text(String content) {
        return () -> new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    private static IoSupplier<InputStream> unreadable() {
        return () -> {
            throw new IOException("pack retiré pendant la lecture");
        };
    }

    @Test
    @DisplayName("ADR-135, ADR-139 : un modèle, ses options et ses voisins possibles sont énumérés ;"
            + " ni les images, ni ce qui est hors des modèles")
    void lesOptionsEtLesVoisinsSontEnumeresAvecLeurSource() throws IOException {
        Map<String, IoSupplier<InputStream>> resources = new LinkedHashMap<>();
        resources.put(VOITURE, text("glTF"));
        resources.put(OPTIONS, text("{ \"schema\": 1 }"));
        resources.put(BUFFER, text("octets"));
        resources.put("axion:axion/models/notes.txt", text("bonjour"));
        resources.put("axion:axion/models/textures/carrosserie.png", text("PNG"));
        resources.put("axion:axion/models/textures/carrosserie.ktx2", text("KTX"));
        resources.put("axion:axion/definitions/voiture.json", text("{}"));

        ResourceAssetSource source =
                new ResourceAssetSource(manager(resources), ResourceAssetSource.MODELS, BORNE);
        // Trié par chemin : deux démarrages du même pack lisent dans le même ordre.
        // Un buffer glTF ou un .mtl porte le nom que l'auteur lui donne : tout ce qui
        // n'est pas une image est gardé, le compilateur n'en lisant aucune.
        assertEquals(
                List.of("axion:axion/models/notes.txt", OPTIONS, BUFFER, VOITURE), source.list());
        assertArrayEquals("octets".getBytes(StandardCharsets.UTF_8), source.read(BUFFER));
        assertArrayEquals(
                "{ \"schema\": 1 }".getBytes(StandardCharsets.UTF_8), source.read(OPTIONS));
        assertTrue(source.failures().isEmpty());
    }

    @Test
    @DisplayName("Une ressource illisible reste énumérée, et sa lecture rend l'échec")
    void uneRessourceIllisibleResteEnumeree() {
        Map<String, IoSupplier<InputStream>> resources = new LinkedHashMap<>();
        resources.put(VOITURE, text("glTF"));
        resources.put(OPTIONS, unreadable());

        ResourceAssetSource source =
                new ResourceAssetSource(manager(resources), ResourceAssetSource.MODELS, BORNE);
        assertEquals(List.of(OPTIONS, VOITURE), source.list());
        IOException echec = assertThrows(IOException.class, () -> source.read(OPTIONS));
        assertTrue(echec.getMessage().contains("pack retiré"), echec.getMessage());
        assertEquals(1, source.failures().size());
    }

    @Test
    @DisplayName("ADR-135 : des options illisibles font échouer leur source, sans repli sur les défauts")
    void desOptionsIllisiblesFontEchouerLeurSource() {
        Map<String, IoSupplier<InputStream>> resources = new LinkedHashMap<>();
        resources.put(VOITURE, text("glTF"));
        resources.put(OPTIONS, unreadable());
        int[] soumissions = {0};
        AssetCompiler compiler = new AssetCompiler() {
            @Override
            public int submit(
                    long assetId, int format, byte[] source, byte[] options, SiblingFiles siblings) {
                soumissions[0]++;
                return 1;
            }

            @Override
            public CompileStatus poll(int jobId) {
                return CompileStatus.compiled(new byte[1]);
            }
        };

        AssetRegistry registry = new AssetRegistry(
                new ResourceAssetSource(manager(resources), ResourceAssetSource.MODELS, BORNE),
                compiler,
                1,
                () -> 0L);
        registry.discover();
        registry.pump(0);
        assertEquals(AssetState.FAILED, registry.entry(VOITURE).state());
        assertEquals(0, soumissions[0], "compilée avec les défauts");
    }

    @Test
    @DisplayName("ADR-139 : un voisin énuméré avec les modèles parvient au compilateur qui le demande")
    void unVoisinEnumereParvientAuCompilateur() {
        Map<String, IoSupplier<InputStream>> resources = new LinkedHashMap<>();
        resources.put("axion:axion/models/cube/cube.obj", text("mtllib cube.mtl"));
        resources.put("axion:axion/models/cube/cube.mtl", text("newmtl rouge"));
        resources.put("axion:axion/models/cube/cube.png", text("PNG"));
        Map<String, byte[]> lus = new LinkedHashMap<>();
        AssetCompiler compiler = new AssetCompiler() {
            @Override
            public int submit(
                    long assetId, int format, byte[] source, byte[] options, SiblingFiles siblings) {
                lus.put("cube.mtl", siblings.read("cube.mtl"));
                lus.put("cube.png", siblings.read("cube.png"));
                return 1;
            }

            @Override
            public CompileStatus poll(int jobId) {
                return CompileStatus.compiled(new byte[1]);
            }
        };

        AssetRegistry registry = new AssetRegistry(
                new ResourceAssetSource(manager(resources), ResourceAssetSource.MODELS, BORNE),
                compiler,
                1,
                () -> 0L);
        registry.discover();
        registry.pump(0);
        assertArrayEquals("newmtl rouge".getBytes(StandardCharsets.UTF_8), lus.get("cube.mtl"));
        assertNull(lus.get("cube.png"), "une image n'est pas énumérée");
    }

    @Test
    @DisplayName("ADR-139 : un voisin au-delà d'assets.max_source_bytes reste énuméré, non lu ; une source, si")
    void unVoisinAuDelaDeLaBorneNEstPasLu() throws IOException {
        Map<String, IoSupplier<InputStream>> resources = new LinkedHashMap<>();
        resources.put(VOITURE, text("glTF-trop-long"));
        resources.put(OPTIONS, text("{ \"schema\": 1 }"));
        resources.put(BUFFER, text("12345"));
        resources.put("axion:axion/models/juste.bin", text("1234"));

        ResourceAssetSource source =
                new ResourceAssetSource(manager(resources), ResourceAssetSource.MODELS, 4);
        assertEquals(4, source.size());
        assertArrayEquals("1234".getBytes(StandardCharsets.UTF_8), source.read("axion:axion/models/juste.bin"));
        IOException trop = assertThrows(IOException.class, () -> source.read(BUFFER));
        assertTrue(trop.getMessage().contains("assets.max_source_bytes"), trop.getMessage());
        assertEquals(1, source.failures().size(), "signalé, pas tu");
        // La source et ses options ne sont pas des voisins : le compilateur juge la taille
        // d'une source lui-même (R-533, E-3005), avec le code que la CLI donne.
        assertArrayEquals("glTF-trop-long".getBytes(StandardCharsets.UTF_8), source.read(VOITURE));
        assertArrayEquals("{ \"schema\": 1 }".getBytes(StandardCharsets.UTF_8), source.read(OPTIONS));
    }
}
