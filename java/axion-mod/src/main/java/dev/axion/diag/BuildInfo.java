package dev.axion.diag;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * Ce que le build a dit de lui-même : le commit compilé, pour la provenance des résultats de
 * benchmark (R-2231).
 *
 * <p>La tâche Gradle {@code generateBuildInfo} écrit {@value #RESOURCE} : l'empreinte du commit,
 * suivie de {@code -dirty} si des fichiers suivis étaient modifiés. Sans ce fichier — un
 * lancement qui ne passe pas par Gradle —, le commit est {@value #UNKNOWN} : une absence se
 * marque, elle ne se remplace pas.
 */
public final class BuildInfo {

    /** Ressource écrite par le build. */
    public static final String RESOURCE = "/axion-build.properties";

    /** Valeur rendue quand le build n'a rien dit. */
    public static final String UNKNOWN = "inconnu";

    private static final String COMMIT = load();

    private BuildInfo() {}

    /** {@return le commit compilé, éventuellement suivi de {@code -dirty}, ou {@value #UNKNOWN}} */
    public static String commit() {
        return COMMIT;
    }

    private static String load() {
        try (InputStream in = BuildInfo.class.getResourceAsStream(RESOURCE)) {
            return commitOf(in);
        } catch (IOException unreadable) {
            return UNKNOWN;
        }
    }

    /**
     * {@return le commit que porte une ressource de build, ou {@value #UNKNOWN}}
     *
     * @param in contenu de la ressource, ou {@code null} si elle est absente
     * @throws IOException si la ressource est illisible
     */
    static String commitOf(InputStream in) throws IOException {
        if (in == null) {
            return UNKNOWN;
        }
        Properties properties = new Properties();
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        String commit = properties.getProperty("commit", "").trim();
        return commit.isEmpty() ? UNKNOWN : commit;
    }
}
