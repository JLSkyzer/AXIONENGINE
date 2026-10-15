package dev.axion.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * R-1751 — l'artefact {@code axion-api} porte la version de l'API, indépendante de celle du mod.
 *
 * <p>Gradle la tire de {@code axion_api_version} ({@code gradle.properties}) ; le code la déclare
 * dans {@link ApiVersion#CURRENT}. Les deux doivent dire la même chose : une API modifiée sans que
 * sa version change ne doit pas pouvoir se publier sous l'ancienne. Hors release, l'artefact porte
 * {@code -SNAPSHOT}.
 */
class ApiArtifactVersionTest {

    @Test
    @DisplayName("R-1751 : la version de l'artefact axion-api est celle d'ApiVersion.CURRENT")
    void laVersionDeLArtefactEstCelleDeLApi() {
        String artifact = System.getProperty("axion.api.artifact.version");
        assertNotNull(artifact, "axion.api.artifact.version non fournie par le build");
        String version = artifact.endsWith("-SNAPSHOT")
                ? artifact.substring(0, artifact.length() - "-SNAPSHOT".length())
                : artifact;
        assertEquals(ApiVersion.CURRENT.toString(), version, "artefact " + artifact);
    }
}
