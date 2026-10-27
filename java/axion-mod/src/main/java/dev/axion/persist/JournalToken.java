package dev.axion.persist;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Le jeton qui lie un journal latéral à son monde (ADR-153 D6, décision de Killian du 2026-10-27).
 *
 * <p>Le {@code worldId} hache le chemin du niveau et sa graine (R-1743) : un monde supprimé puis recréé au
 * même chemin avec la même graine retrouverait le journal de l'ancien. Le monde porte donc un jeton
 * aléatoire, que le journal recopie dans {@value #FILE}. Au démarrage, un journal au jeton différent est
 * celui d'un autre monde : il est mis de côté — renommé, jamais supprimé (R-1747) — et rien n'en est
 * restauré. Un journal sans jeton, d'avant ce mécanisme, adopte celui du monde.
 */
public final class JournalToken {

    /** Le fichier du jeton, dans le dossier du journal. */
    public static final String FILE = "monde.jeton";

    private JournalToken() {}

    /**
     * Rapproche le journal du jeton du monde, et l'y inscrit s'il n'en a pas.
     *
     * @param dir dossier du journal du monde
     * @param worldToken jeton du monde
     * @param now horodatage du nom d'un journal mis de côté, en millisecondes
     * @param report où dire qu'un journal a été mis de côté
     * @throws IOException si le dossier ne se lit ou ne s'écrit pas
     */
    public static void reconcile(Path dir, String worldToken, long now, Consumer<String> report) throws IOException {
        Path file = dir.resolve(FILE);
        if (Files.isDirectory(dir) && Files.exists(file)) {
            String journalToken = Files.readString(file, StandardCharsets.UTF_8).trim();
            if (!journalToken.equals(worldToken)) {
                Path aside = dir.resolveSibling(dir.getFileName() + ".ecarte-" + now);
                Files.move(dir, aside);
                report.accept("le journal latéral de " + dir.getFileName() + " appartient à un autre monde de même "
                        + "chemin et de même graine (jeton " + journalToken + ", le monde porte " + worldToken
                        + ") : mis de côté sous " + aside.getFileName() + ", rien n'en sera restauré");
            }
        }
        Files.createDirectories(dir);
        if (!Files.exists(file)) {
            Files.writeString(file, worldToken, StandardCharsets.UTF_8);
        }
    }
}
