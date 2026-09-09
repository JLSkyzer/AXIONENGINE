package dev.axion.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** T-120 — table des plateformes supportées et chemins de ressources (C-03, 34.2). */
class NativePlatformTest {

    @ParameterizedTest
    @CsvSource({
        // Les cinq plateformes de la table 34.2, avec les valeurs que la JVM
        // rapporte réellement pour os.name et os.arch.
        "Windows 11,       amd64,   windows-x86_64, axion_native.dll",
        "Windows Server 2022, x86_64, windows-x86_64, axion_native.dll",
        "Linux,            amd64,   linux-x86_64,   libaxion_native.so",
        "Linux,            aarch64, linux-aarch64,  libaxion_native.so",
        "Mac OS X,         x86_64,  macos-x86_64,   libaxion_native.dylib",
        "Mac OS X,         aarch64, macos-aarch64,  libaxion_native.dylib",
        "Darwin,           arm64,   macos-aarch64,  libaxion_native.dylib",
    })
    @DisplayName("T-120 : chaque plateforme supportée donne son identifiant et son fichier")
    void plateformesSupportees(String osName, String osArch, String id, String fileName) {
        NativePlatform platform = NativePlatform.of(osName, osArch).orElseThrow();

        assertEquals(id, platform.id());
        assertEquals(fileName, platform.libraryFileName());
        assertEquals("/natives/" + id + "/" + fileName, platform.libraryResource());
        assertEquals(platform.libraryResource() + ".sha256", platform.checksumResource());
    }

    @ParameterizedTest
    @CsvSource({
        // Architectures et systèmes hors table : le mod doit le dire, pas
        // tenter un chargement voué à l'échec.
        "Linux,      x86,     32 bits",
        "Linux,      riscv64, architecture hors table",
        "SunOS,      amd64,   système hors table",
        "FreeBSD,    amd64,   système hors table",
        // Windows sur ARM : aucun binaire n'est produit pour cette cible.
        "Windows 11, aarch64, pas de binaire Windows ARM",
    })
    @DisplayName("T-120 : une plateforme hors table est refusée explicitement")
    void plateformesNonSupportees(String osName, String osArch, String motif) {
        assertTrue(
                NativePlatform.of(osName, osArch).isEmpty(),
                () -> osName + " / " + osArch + " devrait être refusée (" + motif + ")");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    @DisplayName("Des propriétés système vides ne font pas planter la détection")
    void proprietesVides(String value) {
        assertTrue(NativePlatform.of(value, value).isEmpty());
    }

    @Test
    @DisplayName("La détection courante fonctionne sur la machine qui exécute les tests")
    void detectionCourante() {
        Optional<NativePlatform> platform = NativePlatform.detect();

        // La CI n'exécute les tests que sur des plateformes supportées ; si
        // celle-ci ne l'était pas, le message doit le dire clairement plutôt
        // que de laisser un échec obscur.
        assertTrue(
                platform.isPresent(),
                () -> "plateforme de test non supportée : "
                        + System.getProperty("os.name") + " / " + System.getProperty("os.arch"));
        assertTrue(platform.get().libraryFileName().contains(NativePlatform.LIBRARY_NAME));
    }

    @Test
    @DisplayName("R-420 : le nom de bibliothèque est propre au projet")
    void nomDeBibliothequeUnique() {
        // Un nom générique entrerait en confusion avec la bibliothèque native
        // d'un autre mod ; celui-ci est cité tel quel par le cahier des charges.
        assertEquals("axion_native", NativePlatform.LIBRARY_NAME);
    }
}
