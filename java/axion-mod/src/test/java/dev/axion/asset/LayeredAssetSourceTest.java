package dev.axion.asset;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Les modèles du client : ses resource packs par-dessus ses mods, fichier par fichier (ADR-150 D3).
 */
class LayeredAssetSourceTest {

    /** Une source en mémoire ; un contenu {@code null} est illisible. */
    private static AssetSource source(Map<String, String> files) {
        return new AssetSource() {
            @Override
            public List<String> list() {
                return List.copyOf(files.keySet());
            }

            @Override
            public byte[] read(String path) throws IOException {
                String content = files.get(path);
                if (content == null) {
                    throw new IOException("illisible : " + path);
                }
                return content.getBytes(StandardCharsets.UTF_8);
            }
        };
    }

    private static Map<String, String> files(String... pairs) {
        Map<String, String> files = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            files.put(pairs[index], pairs[index + 1]);
        }
        return files;
    }

    private static String text(AssetSource source, String path) throws IOException {
        return new String(source.read(path), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("Un fichier du resource pack remplace celui du mod ; le mod comble le reste")
    void lePackLEmporteFichierParFichier() throws IOException {
        AssetSource packs = source(files(
                "axion:axion/models/caisse.gltf", "caisse du pack"));
        AssetSource mods = source(files(
                "axion:axion/models/caisse.gltf", "caisse du mod",
                "axion:axion/models/caisse.bin", "tampon du mod",
                "autre:axion/models/roue.glb", "roue du mod"));

        AssetSource layered = new LayeredAssetSource(packs, mods);

        assertEquals("caisse du pack", text(layered, "axion:axion/models/caisse.gltf"));
        // Le pack ne remplace que ce qu'il fournit : le tampon voisin reste celui du mod, comme un
        // resource pack de Minecraft remplace un fichier, pas un dossier.
        assertEquals("tampon du mod", text(layered, "axion:axion/models/caisse.bin"));
        assertEquals("roue du mod", text(layered, "autre:axion/models/roue.glb"));
    }

    @Test
    @DisplayName("L'énumération est l'union, sans doublon, triée pour un ordre de compilation reproductible")
    void lEnumerationEstLUnionTriee() {
        AssetSource packs = source(files(
                "b:axion/models/z.glb", "x",
                "a:axion/models/m.glb", "x"));
        AssetSource mods = source(files(
                "a:axion/models/m.glb", "y",
                "a:axion/models/a.glb", "y"));

        assertEquals(
                List.of("a:axion/models/a.glb", "a:axion/models/m.glb", "b:axion/models/z.glb"),
                new LayeredAssetSource(packs, mods).list());
    }

    @Test
    @DisplayName("Un fichier illisible du pack reste illisible : le mod ne le remplace pas en silence")
    void unFichierIllisibleDuPackNEstPasRemplaceParLeMod() {
        Map<String, String> packFiles = files();
        packFiles.put("axion:axion/models/caisse.glb", null);
        AssetSource layered = new LayeredAssetSource(
                source(packFiles), source(files("axion:axion/models/caisse.glb", "caisse du mod")));

        assertThrows(IOException.class, () -> layered.read("axion:axion/models/caisse.glb"));
    }

    @Test
    @DisplayName("Sans resource pack, le client compile les modèles de ses mods")
    void sansPackLesModsSuffisent() throws IOException {
        AssetSource layered = new LayeredAssetSource(
                source(files()), source(files("axion:axion/models/ballon.glb", "ballon")));

        assertEquals(List.of("axion:axion/models/ballon.glb"), layered.list());
        assertArrayEquals("ballon".getBytes(StandardCharsets.UTF_8),
                layered.read("axion:axion/models/ballon.glb"));
    }
}
