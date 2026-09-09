package dev.axion.bootstrap;

import dev.axion.bootstrap.NativeLoadResult.Failed;
import dev.axion.bootstrap.NativeLoadResult.Loaded;
import dev.axion.bootstrap.NativeLoadResult.Reason;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Extraction et chargement de la bibliothèque native (C-03).
 *
 * <p>La chaîne suit celle du cahier des charges :
 *
 * <pre>
 * resource = /natives/&lt;os&gt;-&lt;arch&gt;/&lt;libname&gt;
 * expected = SHA-256 lu depuis resource + ".sha256"
 * target   = &lt;racine&gt;/axion/native/&lt;expected&gt;/&lt;libname&gt;
 * extraction atomique si absent ; System.load(target)
 * </pre>
 *
 * <p>Trois décisions structurent l'implémentation.
 *
 * <p><strong>Le chemin est versionné par l'empreinte</strong>, si bien que deux
 * versions de la bibliothèque cohabitent sans se marcher dessus, et qu'une mise
 * à jour du mod n'a jamais à écraser un fichier peut-être déjà chargé par la JVM
 * — ce qui est impossible sous Windows.
 *
 * <p><strong>Le fichier déjà extrait est revérifié.</strong> Son chemin contient
 * pourtant l'empreinte attendue : ce n'est pas une preuve de son contenu, et
 * l'interdiction 3.13 refuse de faire confiance à une donnée venant du cache.
 *
 * <p><strong>Le chargement est retenté ailleurs avant d'abandonner</strong>
 * (R-421). Un système de fichiers en lecture seule fait échouer l'extraction, un
 * système monté {@code noexec} fait échouer le chargement : les deux causes se
 * traitent de la même façon, en réessayant sous {@code java.io.tmpdir}.
 *
 * <p>Exigences : R-420, R-421.
 */
public final class NativeLoader {

    /** Sous-chemin d'extraction, à partir d'une racine. */
    private static final String EXTRACTION_SUBPATH = "axion/native";

    /**
     * Source des ressources embarquées.
     *
     * <p>Injectable pour que l'extraction se teste sans dépendre du contenu réel
     * du JAR, qui ne porte de bibliothèque native qu'une fois la chaîne de build
     * complète (M0.8).
     */
    @FunctionalInterface
    public interface ResourceSource {
        /**
         * Ouvre une ressource.
         *
         * @param resourcePath chemin absolu de ressource, commençant par « / »
         * @return le flux, ou {@code null} si la ressource n'existe pas
         * @throws IOException si la ressource existe mais ne peut pas être lue
         */
        InputStream open(String resourcePath) throws IOException;
    }

    /**
     * Liaison effective de la bibliothèque à la JVM.
     *
     * <p>Injectable pour la même raison : le repli de R-421 se déclenche sur un
     * échec de chargement, qu'aucun test ne pourrait provoquer autrement.
     */
    @FunctionalInterface
    public interface NativeBinder {
        /**
         * Charge la bibliothèque désignée.
         *
         * @param absolutePath chemin absolu du fichier
         * @throws UnsatisfiedLinkError si la bibliothèque ne peut pas être
         *     chargée — système {@code noexec}, architecture incompatible,
         *     dépendance manquante
         */
        void bind(String absolutePath);
    }

    private NativeLoader() {
        throw new AssertionError("classe utilitaire, non instanciable");
    }

    /**
     * Charge la bibliothèque native pour la plateforme courante.
     *
     * @param gameDir répertoire de jeu, racine d'extraction préférée
     * @return l'issue du chargement, jamais {@code null}
     */
    public static NativeLoadResult load(Path gameDir) {
        Optional<NativePlatform> platform = NativePlatform.detect();
        if (platform.isEmpty()) {
            return new Failed(
                    Reason.PLATFORM_UNSUPPORTED,
                    "plateforme non supportée : " + System.getProperty("os.name")
                            + " / " + System.getProperty("os.arch")
                            + " ; AXION reste inactif et le jeu est jouable");
        }
        return load(
                platform.get(),
                defaultRoots(gameDir),
                NativeLoader.class::getResourceAsStream,
                // R-420 : chemin absolu, jamais System.loadLibrary, qui
                // dépendrait de java.library.path et pourrait charger la
                // bibliothèque d'un autre mod.
                System::load);
    }

    /**
     * Charge la bibliothèque, toutes dépendances explicites.
     *
     * @param platform plateforme visée
     * @param roots racines d'extraction, essayées dans l'ordre
     * @param resources source des ressources embarquées
     * @param binder liaison effective à la JVM
     * @return l'issue du chargement, jamais {@code null}
     */
    public static NativeLoadResult load(
            NativePlatform platform,
            List<Path> roots,
            ResourceSource resources,
            NativeBinder binder) {

        Optional<String> expected = readExpectedChecksum(platform, resources);
        if (expected.isEmpty()) {
            return new Failed(
                    Reason.RESOURCE_MISSING,
                    "empreinte absente du JAR : " + platform.checksumResource());
        }
        String sha256 = expected.get();

        List<String> attempts = new ArrayList<>();
        for (Path root : roots) {
            Path target = root.resolve(EXTRACTION_SUBPATH).resolve(sha256)
                    .resolve(platform.libraryFileName());
            try {
                if (!isAlreadyValid(target, sha256)) {
                    NativeLoadResult extraction = extract(platform, resources, target, sha256);
                    if (extraction instanceof Failed failed) {
                        // Une empreinte invalide vient du JAR lui-même :
                        // réessayer ailleurs donnerait le même résultat.
                        if (failed.reason() == Reason.CHECKSUM_MISMATCH
                                || failed.reason() == Reason.RESOURCE_MISSING) {
                            return failed;
                        }
                        attempts.add(failed.detail());
                        continue;
                    }
                }
                binder.bind(target.toAbsolutePath().toString());
                return new Loaded(target.toAbsolutePath(), sha256);
            } catch (IOException | UnsatisfiedLinkError | SecurityException failure) {
                // Lecture seule, disque plein, montage noexec, politique de
                // sécurité : autant de raisons de tenter l'emplacement suivant.
                attempts.add(target + " : " + failure);
            }
        }

        return new Failed(
                Reason.NO_USABLE_LOCATION,
                "aucun emplacement d'extraction utilisable — " + String.join(" ; ", attempts));
    }

    /**
     * Racines d'extraction essayées, dans l'ordre de préférence (R-421).
     *
     * @param gameDir répertoire de jeu
     * @return le répertoire de jeu, puis le répertoire temporaire du système
     */
    static List<Path> defaultRoots(Path gameDir) {
        List<Path> roots = new ArrayList<>(2);
        roots.add(gameDir);
        String tmp = System.getProperty("java.io.tmpdir");
        if (tmp != null && !tmp.isBlank()) {
            Path fallback = Path.of(tmp);
            if (!fallback.equals(gameDir)) {
                roots.add(fallback);
            }
        }
        return List.copyOf(roots);
    }

    private static Optional<String> readExpectedChecksum(
            NativePlatform platform, ResourceSource resources) {
        try (InputStream stream = resources.open(platform.checksumResource())) {
            if (stream == null) {
                return Optional.empty();
            }
            String content = new String(stream.readAllBytes(), StandardCharsets.UTF_8).trim();
            if (content.isEmpty()) {
                return Optional.empty();
            }
            // Le format usuel des fichiers .sha256 est « <empreinte>  <nom> » ;
            // seule l'empreinte nous intéresse.
            String digest = content.split("\\s+", 2)[0].toLowerCase(java.util.Locale.ROOT);
            return isHexDigest(digest) ? Optional.of(digest) : Optional.empty();
        } catch (IOException failure) {
            return Optional.empty();
        }
    }

    private static boolean isHexDigest(String value) {
        if (value.length() != 64) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAlreadyValid(Path target, String expected) {
        if (!Files.isReadable(target)) {
            return false;
        }
        try {
            // Le chemin porte l'empreinte attendue, ce qui ne dit rien du
            // contenu du fichier : on le vérifie (interdiction 3.13).
            return sha256(Files.readAllBytes(target)).equals(expected);
        } catch (IOException failure) {
            return false;
        }
    }

    private static NativeLoadResult extract(
            NativePlatform platform, ResourceSource resources, Path target, String expected)
            throws IOException {

        byte[] library;
        try (InputStream stream = resources.open(platform.libraryResource())) {
            if (stream == null) {
                return new Failed(
                        Reason.RESOURCE_MISSING,
                        "bibliothèque absente du JAR : " + platform.libraryResource());
            }
            library = stream.readAllBytes();
        }

        String actual = sha256(library);
        if (!actual.equals(expected)) {
            return new Failed(
                    Reason.CHECKSUM_MISMATCH,
                    "empreinte invalide pour " + platform.libraryResource()
                            + " : attendu " + expected + ", obtenu " + actual);
        }

        Files.createDirectories(target.getParent());
        // Écriture dans un fichier temporaire voisin puis déplacement : un
        // autre processus ne peut jamais observer un fichier à moitié écrit,
        // et deux instances du jeu qui démarrent ensemble ne se corrompent pas.
        Path temporary = Files.createTempFile(
                target.getParent(), platform.libraryFileName(), ".partial");
        try {
            Files.write(temporary, library);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        return new Loaded(target.toAbsolutePath(), expected);
    }

    /**
     * {@return l'empreinte SHA-256 du contenu, en hexadécimal minuscule}
     *
     * @param content contenu à empreindre
     */
    static String sha256(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            // SHA-256 fait partie des algorithmes que toute implémentation de
            // la plateforme Java doit fournir.
            throw new IllegalStateException("SHA-256 indisponible", impossible);
        }
    }
}
