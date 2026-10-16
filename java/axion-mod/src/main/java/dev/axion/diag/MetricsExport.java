package dev.axion.diag;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * L'export des métriques dans un fichier (R-502) : {@code <gameDir>/axion/<préfixe><horodatage>.json}.
 *
 * <p>Un seul chemin d'écriture pour les deux commandes qui exportent : {@code /axion metrics
 * export}, exécutée par le serveur, et {@code /axion metrics export client}, exécutée par le
 * client (ADR-133 D1, ADR-144). Sur un client relié à un serveur distant, la première ne voit que
 * le serveur ; la seconde écrit ce que mesure le client, rendu compris.
 *
 * <p>Le document part dans un fichier plutôt que dans le chat : il compte plusieurs dizaines de
 * lignes, et R-442 le destine aussi au dump de diagnostic. Le nom porte l'horodatage, si bien que
 * deux exports successifs ne s'écrasent pas — comparer deux instants est précisément ce qu'on veut
 * faire de métriques.
 */
public final class MetricsExport {

    /** Préfixe d'un export du serveur, ou du runtime unique d'une partie solo. */
    public static final String SERVER_PREFIX = "metrics-";

    /** Préfixe d'un export du client : dans un répertoire de jeu partagé, il ne se confond pas. */
    public static final String CLIENT_PREFIX = "metrics-client-";

    /** Horodatage du nom de fichier : trié par ordre alphabétique comme par date. */
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private MetricsExport() {}

    /**
     * Écrit un export.
     *
     * @param json document rendu par {@code AxionRuntime.metricsJson()}
     * @param gameDir répertoire de jeu
     * @param prefix {@link #SERVER_PREFIX} ou {@link #CLIENT_PREFIX}
     * @param when instant de l'export, qui nomme le fichier
     * @return le fichier écrit
     * @throws IOException si le répertoire ou le fichier ne peut pas être écrit
     */
    public static Path write(String json, Path gameDir, String prefix, LocalDateTime when)
            throws IOException {
        Path target = gameDir.resolve("axion").resolve(prefix + when.format(FILE_STAMP) + ".json");
        Files.createDirectories(target.getParent());
        Files.writeString(target, json, StandardCharsets.UTF_8);
        return target;
    }
}
