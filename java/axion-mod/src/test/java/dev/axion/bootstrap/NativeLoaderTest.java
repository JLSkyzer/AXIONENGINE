package dev.axion.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.axion.bootstrap.NativeLoadResult.Failed;
import dev.axion.bootstrap.NativeLoadResult.Loaded;
import dev.axion.bootstrap.NativeLoadResult.Reason;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** T-121..T-124 — extraction, vérification et chargement de la bibliothèque native (C-03). */
class NativeLoaderTest {

    @TempDir
    Path gameDir;

    @TempDir
    Path fallbackDir;

    private static final NativePlatform PLATFORM =
            NativePlatform.of("Linux", "amd64").orElseThrow();

    private static final byte[] LIBRARY = "contenu de la bibliothèque native"
            .getBytes(StandardCharsets.UTF_8);

    /** Source de ressources en mémoire, qui compte ce qui est réellement lu. */
    private static final class FakeResources implements NativeLoader.ResourceSource {
        private final Map<String, byte[]> entries = new HashMap<>();
        private final List<String> opened = new ArrayList<>();

        FakeResources put(String path, byte[] content) {
            entries.put(path, content);
            return this;
        }

        FakeResources put(String path, String content) {
            return put(path, content.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public InputStream open(String resourcePath) {
            opened.add(resourcePath);
            byte[] content = entries.get(resourcePath);
            return content == null ? null : new ByteArrayInputStream(content);
        }

        long timesOpened(String path) {
            return opened.stream().filter(path::equals).count();
        }
    }

    /** Liaison simulée : enregistre les chemins, et peut refuser certains d'entre eux. */
    private static final class FakeBinder implements NativeLoader.NativeBinder {
        private final List<String> bound = new ArrayList<>();
        private Path refusedPrefix;

        FakeBinder refusing(Path prefix) {
            this.refusedPrefix = prefix;
            return this;
        }

        @Override
        public void bind(String absolutePath) {
            if (refusedPrefix != null && absolutePath.startsWith(refusedPrefix.toString())) {
                // Ce que produit un montage noexec : l'écriture réussit, le
                // chargement échoue.
                throw new UnsatisfiedLinkError("montage noexec simulé : " + absolutePath);
            }
            bound.add(absolutePath);
        }
    }

    private static FakeResources resourcesWith(byte[] library) {
        return new FakeResources()
                .put(PLATFORM.libraryResource(), library)
                // Format usuel d'un fichier .sha256 : empreinte, deux espaces,
                // nom du fichier.
                .put(
                        PLATFORM.checksumResource(),
                        NativeLoader.sha256(library) + "  " + PLATFORM.libraryFileName());
    }

    private Path expectedTarget(Path root, byte[] library) {
        return root.resolve("axion/native")
                .resolve(NativeLoader.sha256(library))
                .resolve(PLATFORM.libraryFileName());
    }

    @Test
    @DisplayName("T-121 : extraction nominale, chemin versionné par empreinte, chargement absolu")
    void extractionNominale() throws IOException {
        FakeResources resources = resourcesWith(LIBRARY);
        FakeBinder binder = new FakeBinder();

        NativeLoadResult result =
                NativeLoader.load(PLATFORM, List.of(gameDir), resources, binder);

        Loaded loaded = assertInstanceOf(Loaded.class, result);
        assertEquals(NativeLoader.sha256(LIBRARY), loaded.sha256());
        assertEquals(expectedTarget(gameDir, LIBRARY), loaded.path());

        // Le fichier est écrit, complet, et son chemin contient l'empreinte :
        // deux versions de la bibliothèque peuvent cohabiter.
        assertTrue(Files.exists(loaded.path()));
        assertEquals(NativeLoader.sha256(LIBRARY), NativeLoader.sha256(Files.readAllBytes(loaded.path())));
        assertTrue(loaded.path().toString().contains(NativeLoader.sha256(LIBRARY)));

        // R-420 : le chargement se fait sur un chemin absolu.
        assertEquals(1, binder.bound.size());
        assertEquals(loaded.path().toString(), binder.bound.get(0));
        assertTrue(Path.of(binder.bound.get(0)).isAbsolute());

        // Aucun fichier temporaire ne subsiste après une extraction réussie.
        try (var files = Files.list(loaded.path().getParent())) {
            assertEquals(1, files.count(), "un fichier partiel a été laissé derrière");
        }
    }

    @Test
    @DisplayName("T-122 : une empreinte invalide refuse le chargement avec E-1003")
    void empreinteInvalideRefusee() {
        // L'empreinte annoncée ne correspond pas au contenu : le JAR est
        // corrompu.
        FakeResources resources = new FakeResources()
                .put(PLATFORM.libraryResource(), LIBRARY)
                .put(PLATFORM.checksumResource(), "0".repeat(64));
        FakeBinder binder = new FakeBinder();

        NativeLoadResult result =
                NativeLoader.load(PLATFORM, List.of(gameDir, fallbackDir), resources, binder);

        Failed failed = assertInstanceOf(Failed.class, result);
        assertEquals(Reason.CHECKSUM_MISMATCH, failed.reason());
        assertEquals(-1003, failed.code());
        assertTrue(binder.bound.isEmpty(), "rien ne doit être chargé après un refus d'empreinte");
        // Réessayer ailleurs ne servirait à rien : la corruption est dans le JAR.
        assertFalse(Files.exists(expectedTarget(fallbackDir, LIBRARY)));
    }

    @Test
    @DisplayName("T-123 : un fichier déjà extrait mais altéré est détecté et remplacé")
    void fichierExtraitAltereEstRemplace() throws IOException {
        FakeResources resources = resourcesWith(LIBRARY);
        Path target = expectedTarget(gameDir, LIBRARY);
        Files.createDirectories(target.getParent());
        // Le chemin porte la bonne empreinte, le contenu non : c'est
        // exactement ce qu'un cache corrompu produit.
        Files.write(target, "contenu altéré".getBytes(StandardCharsets.UTF_8));
        assertNotEquals(
                NativeLoader.sha256(LIBRARY), NativeLoader.sha256(Files.readAllBytes(target)));

        FakeBinder binder = new FakeBinder();
        NativeLoadResult result =
                NativeLoader.load(PLATFORM, List.of(gameDir), resources, binder);

        assertInstanceOf(Loaded.class, result);
        assertEquals(
                NativeLoader.sha256(LIBRARY),
                NativeLoader.sha256(Files.readAllBytes(target)),
                "le fichier altéré aurait dû être réécrit");
        assertEquals(1, binder.bound.size());
    }

    @Test
    @DisplayName("T-123 : un fichier déjà extrait et valide est réutilisé sans relire le JAR")
    void fichierExtraitValideEstReutilise() throws IOException {
        FakeResources resources = resourcesWith(LIBRARY);
        Path target = expectedTarget(gameDir, LIBRARY);
        Files.createDirectories(target.getParent());
        Files.write(target, LIBRARY);

        NativeLoadResult result =
                NativeLoader.load(PLATFORM, List.of(gameDir), resources, new FakeBinder());

        assertInstanceOf(Loaded.class, result);
        // L'empreinte attendue est toujours lue ; la bibliothèque, elle, n'a
        // pas à être extraite de nouveau à chaque démarrage.
        assertEquals(1, resources.timesOpened(PLATFORM.checksumResource()));
        assertEquals(0, resources.timesOpened(PLATFORM.libraryResource()));
    }

    @Test
    @DisplayName("T-124 : un emplacement qui refuse le chargement fait basculer sur le suivant")
    void repliSurLeSecondEmplacement() {
        FakeResources resources = resourcesWith(LIBRARY);
        // Le premier emplacement écrit sans problème mais refuse de charger :
        // c'est le comportement d'un montage noexec (R-421).
        FakeBinder binder = new FakeBinder().refusing(gameDir);

        NativeLoadResult result =
                NativeLoader.load(PLATFORM, List.of(gameDir, fallbackDir), resources, binder);

        Loaded loaded = assertInstanceOf(Loaded.class, result);
        assertEquals(expectedTarget(fallbackDir, LIBRARY), loaded.path());
        assertEquals(1, binder.bound.size());
        assertTrue(loaded.path().startsWith(fallbackDir));
    }

    @Test
    @DisplayName("T-124 : si aucun emplacement ne convient, l'échec est propre et explicite")
    void aucunEmplacementUtilisable() {
        FakeResources resources = resourcesWith(LIBRARY);
        FakeBinder binder = new FakeBinder().refusing(Path.of(""));

        NativeLoadResult result =
                NativeLoader.load(PLATFORM, List.of(gameDir, fallbackDir), resources, binder);

        Failed failed = assertInstanceOf(Failed.class, result);
        assertEquals(Reason.NO_USABLE_LOCATION, failed.reason());
        // Le message doit nommer les emplacements essayés, sinon il n'aide
        // personne à diagnostiquer.
        assertTrue(failed.detail().contains(gameDir.toString()), failed.detail());
        assertTrue(failed.detail().contains(fallbackDir.toString()), failed.detail());
        assertTrue(binder.bound.isEmpty());
    }

    @Test
    @DisplayName("Un JAR sans bibliothèque native échoue proprement, sans rien charger")
    void bibliothequeAbsenteDuJar() {
        // Cas d'un JAR marqué « partial » : l'empreinte est là, pas le binaire.
        FakeResources resources = new FakeResources()
                .put(PLATFORM.checksumResource(), NativeLoader.sha256(LIBRARY) + "  lib");
        FakeBinder binder = new FakeBinder();

        NativeLoadResult result =
                NativeLoader.load(PLATFORM, List.of(gameDir), resources, binder);

        Failed failed = assertInstanceOf(Failed.class, result);
        assertEquals(Reason.RESOURCE_MISSING, failed.reason());
        assertEquals(0, failed.code(), "aucun code n'est attribué à ce cas par l'annexe A.1");
        assertTrue(binder.bound.isEmpty());
    }

    @Test
    @DisplayName("Une empreinte absente ou mal formée est traitée comme une ressource manquante")
    void empreinteAbsenteOuMalFormee() {
        for (FakeResources resources : List.of(
                new FakeResources().put(PLATFORM.libraryResource(), LIBRARY),
                resourcesWith(LIBRARY).put(PLATFORM.checksumResource(), "   "),
                resourcesWith(LIBRARY).put(PLATFORM.checksumResource(), "pas-une-empreinte"),
                // Bonne longueur, mais pas de l'hexadécimal.
                resourcesWith(LIBRARY).put(PLATFORM.checksumResource(), "z".repeat(64)))) {

            NativeLoadResult result =
                    NativeLoader.load(PLATFORM, List.of(gameDir), resources, new FakeBinder());

            Failed failed = assertInstanceOf(Failed.class, result);
            assertEquals(Reason.RESOURCE_MISSING, failed.reason());
        }
    }

    @Test
    @DisplayName("R-421 : le répertoire temporaire est le second emplacement essayé")
    void repliParDefautSurTmpdir() {
        List<Path> roots = NativeLoader.defaultRoots(gameDir);

        assertEquals(gameDir, roots.get(0), "le répertoire de jeu reste préféré");
        assertEquals(2, roots.size());
        assertEquals(Path.of(System.getProperty("java.io.tmpdir")), roots.get(1));
    }

    @Test
    @DisplayName("M0.8 : la bibliothèque livrée dans les ressources a une empreinte valide")
    void bibliothequeLivreeDansLesRessources() {
        NativePlatform platform = NativePlatform.detect().orElseThrow();

        // Le vrai classpath, mais une liaison simulée : charger réellement
        // verrouillerait le fichier, et le répertoire temporaire ne pourrait
        // plus être supprimé. Le chargement réel est exercé une seule fois,
        // par NativeBridgeTest.
        FakeBinder binder = new FakeBinder();
        NativeLoadResult result = NativeLoader.load(
                platform, List.of(gameDir), NativeLoader.class::getResourceAsStream, binder);

        if (result instanceof Failed failed) {
            // Sans chaîne Rust, la ressource est absente : c'est un échec
            // propre et attendu, jamais une exception.
            assertEquals(Reason.RESOURCE_MISSING, failed.reason(), failed.detail());
            assertFalse(failed.detail().isBlank(), "un échec doit toujours s'expliquer");
            return;
        }

        // Chaîne de build passée : l'empreinte du fichier livré correspond à
        // celle que le JAR annonce, sans quoi le chargement serait refusé.
        Loaded ok = (Loaded) result;
        assertEquals(1, binder.bound.size());
        assertTrue(ok.path().toString().contains(ok.sha256()), ok.path().toString());
    }
}
