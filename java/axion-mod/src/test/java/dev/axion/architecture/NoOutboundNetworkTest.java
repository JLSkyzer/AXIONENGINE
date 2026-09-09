package dev.axion.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-022 — R-440 : AXION n'ouvre jamais de connexion réseau sortante.
 *
 * <p>Le mod ne transmet aucune télémétrie, ne vérifie aucune mise à jour, ne
 * contacte aucun service. C'est une promesse faite à l'utilisateur, et une
 * promesse ne se vérifie pas à la relecture : il suffit qu'une dépendance ou une
 * commodité l'introduise un jour pour qu'elle soit rompue sans que personne ne
 * le remarque.
 *
 * <p>La recherche porte sur les deux côtés de la frontière : le natif pourrait
 * ouvrir une socket aussi facilement que Java.
 */
class NoOutboundNetworkTest {

    /**
     * Formes qui ouvrent effectivement une connexion.
     *
     * <p>Ne figurent pas ici les types qui manipulent une adresse sans rien
     * ouvrir — {@code URI} par exemple : les inclure produirait des faux
     * positifs, et un test qu'on apprend à ignorer ne protège plus de rien.
     */
    private static final List<String> JAVA_PATTERNS = List.of(
            "new Socket(",
            "new DatagramSocket(",
            "new ServerSocket(",
            "openConnection(",
            "openStream(",
            "HttpClient",
            "URLConnection",
            "SocketChannel",
            "InetAddress.getByName");

    /** Équivalents côté natif, y compris les crates clients HTTP usuels. */
    private static final List<String> RUST_PATTERNS = List.of(
            "TcpStream",
            "TcpListener",
            "UdpSocket",
            "reqwest",
            "ureq",
            "hyper::",
            "curl::");

    private static List<Path> sourcesUnder(String property, String extension) throws IOException {
        String dir = System.getProperty(property);
        assertTrue(dir != null && !dir.isBlank(), property + " non fourni par le build");
        Path root = Path.of(dir);
        assertTrue(Files.isDirectory(root), () -> "répertoire introuvable : " + root);
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(extension))
                    .toList();
        }
    }

    private static List<String> scan(List<Path> sources, List<String> patterns, Path root)
            throws IOException {
        List<String> hits = new ArrayList<>();
        for (Path source : sources) {
            List<String> lines = Files.readAllLines(source, StandardCharsets.UTF_8);
            for (int index = 0; index < lines.size(); index++) {
                String line = lines.get(index);
                // Un commentaire qui parle de réseau n'ouvre rien : seul le
                // code compte, et la ligne est ignorée si elle commence par un
                // marqueur de commentaire.
                String stripped = line.stripLeading();
                if (stripped.startsWith("//") || stripped.startsWith("*")
                        || stripped.startsWith("/*") || stripped.startsWith("#")) {
                    continue;
                }
                for (String pattern : patterns) {
                    if (line.contains(pattern)) {
                        hits.add(root.relativize(source) + ":" + (index + 1) + " — " + pattern);
                    }
                }
            }
        }
        return hits;
    }

    @Test
    @DisplayName("T-022 : aucune ouverture de connexion côté Java")
    void aucuneConnexionSortanteEnJava() throws IOException {
        Path root = Path.of(System.getProperty("axion.source.dir"));
        List<Path> sources = sourcesUnder("axion.source.dir", ".java");
        assertTrue(sources.size() > 5, "trop peu de sources inspectées");

        List<String> hits = scan(sources, JAVA_PATTERNS, root);
        assertTrue(
                hits.isEmpty(),
                () -> "R-440 violé — AXION n'ouvre jamais de connexion sortante :\n  "
                        + String.join("\n  ", hits));
    }

    @Test
    @DisplayName("T-022 : aucune ouverture de connexion côté natif")
    void aucuneConnexionSortanteEnRust() throws IOException {
        Path root = Path.of(System.getProperty("axion.crates.dir"));
        List<Path> sources = sourcesUnder("axion.crates.dir", ".rs");
        assertTrue(sources.size() > 3, "trop peu de sources Rust inspectées");

        List<String> hits = scan(sources, RUST_PATTERNS, root);
        assertTrue(
                hits.isEmpty(),
                () -> "R-440 violé côté natif :\n  " + String.join("\n  ", hits));
    }

    @Test
    @DisplayName("T-022 : aucune dépendance réseau déclarée dans le workspace")
    void aucuneDependanceReseau() throws IOException {
        // Une dépendance suffit à rendre la promesse fausse, même si le code
        // ne l'appelle pas encore.
        Path lock = Path.of(System.getProperty("axion.crates.dir")).getParent()
                .resolve("Cargo.lock");
        assertTrue(Files.isReadable(lock), () -> "Cargo.lock introuvable : " + lock);

        String content = Files.readString(lock, StandardCharsets.UTF_8)
                .toLowerCase(Locale.ROOT);
        for (String forbidden : List.of("reqwest", "ureq", "hyper", "tokio", "curl")) {
            assertTrue(
                    !content.contains("name = \"" + forbidden + "\""),
                    "dépendance réseau dans le workspace : " + forbidden);
        }
    }
}
