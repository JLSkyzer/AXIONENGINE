package dev.axion.render;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.axion.asset.AssetCompiler;
import dev.axion.asset.AssetRegistry;
import dev.axion.asset.AssetSource;
import dev.axion.asset.SiblingFiles;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-421 — R-1640 : un modèle absent chez le client donne la boîte de repli et un avertissement,
 * jamais une déconnexion (ADR-150 D3).
 */
class MissingClientAssetsTest {

    private static final String BALLON = "axion:axion/models/test/ballon.glb";
    private static final String CAISSE = "axion:axion/models/test/caisse.glb";

    /** Un registre de client qui connaît les modèles donnés, en cours de compilation. */
    private static AssetRegistry registry(String... models) {
        AssetSource source = new AssetSource() {
            @Override
            public List<String> list() {
                return List.of(models);
            }

            @Override
            public byte[] read(String path) {
                return new byte[] {1, 2, 3};
            }
        };
        AssetCompiler compiler = new AssetCompiler() {
            @Override
            public int submit(long assetId, int format, byte[] bytes, byte[] options, SiblingFiles siblings) {
                return 1;
            }

            @Override
            public CompileStatus poll(int jobId) {
                return CompileStatus.pending();
            }
        };
        AssetRegistry registry = new AssetRegistry(source, compiler, 1, () -> 0L);
        registry.discover();
        return registry;
    }

    @Test
    @DisplayName("T-421 : un modèle absent chez le client est averti une fois, et seulement lui")
    void t421_unModeleAbsentEstAvertiUneFois() {
        MissingClientAssets missing = new MissingClientAssets();
        AssetRegistry registry = registry(BALLON);

        String warning = missing.check(registry, CAISSE);
        assertNotNull(warning, "un modèle absent n'est pas averti");
        assertTrue(warning.contains(CAISSE) && warning.contains("R-1640"), warning);
        // Chaque frame redemande le même modèle : le journal ne le répète pas.
        assertNull(missing.check(registry, CAISSE));
        // Un modèle connu, même en cours de compilation, n'est pas absent.
        assertNull(missing.check(registry, BALLON));
    }

    @Test
    @DisplayName("T-421 : avant le premier rechargement, rien n'est encore absent")
    void t421_sansRegistreRienNEstAbsent() {
        assertNull(new MissingClientAssets().check(null, CAISSE));
    }

    @Test
    @DisplayName("T-421 : après un rechargement qui ne l'apporte toujours pas, le modèle est averti de nouveau")
    void t421_unRechargementRedonneLAvertissement() {
        MissingClientAssets missing = new MissingClientAssets();
        assertNotNull(missing.check(registry(BALLON), CAISSE));
        assertNotNull(missing.check(registry(BALLON), CAISSE), "un registre neuf ne redit pas l'absence");
        assertNull(missing.check(registry(BALLON, CAISSE), CAISSE), "un registre qui l'apporte l'avertit");
    }
}
