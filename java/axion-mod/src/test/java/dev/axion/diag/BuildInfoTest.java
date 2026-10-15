package dev.axion.diag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** R-2231 — le commit que le build a compilé, pour la provenance des benchmarks en jeu. */
class BuildInfoTest {

    private static String lire(String contenu) throws IOException {
        return BuildInfo.commitOf(new ByteArrayInputStream(contenu.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("R-2231 : le commit se lit ; une ressource absente ou vide le dit inconnu")
    void leCommitSeLit() throws IOException {
        assertEquals("0123abcd-dirty", lire("commit=0123abcd-dirty\n"));
        assertEquals(BuildInfo.UNKNOWN, lire(""));
        assertEquals(BuildInfo.UNKNOWN, lire("commit=  \n"));
        assertEquals(BuildInfo.UNKNOWN, BuildInfo.commitOf(null));
    }

    @Test
    @DisplayName("R-2231 : le build écrit l'empreinte du commit, marquée -dirty si l'arbre suivi était modifié")
    void leBuildEcritLeCommit() {
        String commit = BuildInfo.commit();
        assertTrue(commit.matches("[0-9a-f]{40}(-dirty)?"), () -> "commit lu : " + commit);
    }
}
