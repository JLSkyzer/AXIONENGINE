package dev.axion.forge;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.axion.asset.AssetCompiler;
import dev.axion.asset.AssetRegistry;
import dev.axion.asset.AssetState;
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
    @DisplayName("ADR-135 : un modèle et son .axion.json sont énumérés ensemble, le reste non")
    void lesOptionsSontEnumereesAvecLeurSource() throws IOException {
        Map<String, IoSupplier<InputStream>> resources = new LinkedHashMap<>();
        resources.put(VOITURE, text("glTF"));
        resources.put(OPTIONS, text("{ \"schema\": 1 }"));
        resources.put("axion:axion/models/notes.txt", text("bonjour"));
        resources.put("axion:axion/definitions/voiture.json", text("{}"));

        ResourceAssetSource source =
                new ResourceAssetSource(manager(resources), ResourceAssetSource.MODELS);
        // Trié par chemin : deux démarrages du même pack lisent dans le même ordre.
        assertEquals(List.of(OPTIONS, VOITURE), source.list());
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
                new ResourceAssetSource(manager(resources), ResourceAssetSource.MODELS);
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
            public int submit(long assetId, int format, byte[] source, byte[] options) {
                soumissions[0]++;
                return 1;
            }

            @Override
            public CompileStatus poll(int jobId) {
                return CompileStatus.compiled(new byte[1]);
            }
        };

        AssetRegistry registry = new AssetRegistry(
                new ResourceAssetSource(manager(resources), ResourceAssetSource.MODELS),
                compiler,
                1,
                () -> 0L);
        registry.discover();
        registry.pump(0);
        assertEquals(AssetState.FAILED, registry.entry(VOITURE).state());
        assertEquals(0, soumissions[0], "compilée avec les défauts");
    }
}
