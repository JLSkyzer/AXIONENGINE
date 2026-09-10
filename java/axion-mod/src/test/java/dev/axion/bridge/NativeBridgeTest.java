package dev.axion.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.axion.bootstrap.NativeLoadResult;
import dev.axion.bootstrap.NativeLoader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * T-190, T-191 — la frontière JNI, exercée sur la vraie bibliothèque native.
 *
 * <p>Cette classe ne contient <strong>qu'un seul</strong> test, et c'est
 * délibéré : la session native est un état global au processus, et la
 * bibliothèque reste chargée pour toute la JVM de test. Deux tests qui
 * l'initialiseraient en parallèle mesureraient les effets l'un de l'autre.
 *
 * <p>C'est le seul test qui prouve que l'enregistrement par
 * {@code RegisterNatives} fonctionne : les signatures JNI sont des chaînes que
 * le compilateur ne vérifie pas, et une faute de frappe ne se voit qu'ici.
 */
class NativeBridgeTest {

    /**
     * Repertoire d'extraction, jamais nettoye.
     *
     * <p>Une bibliotheque chargee par {@code System.load} reste verrouillee par
     * la JVM jusqu'a sa fin : sous Windows, son fichier ne peut plus etre
     * supprime, et JUnit echouerait en tentant de vider le repertoire. C'est
     * aussi la raison pour laquelle le chemin d'extraction est versionne par
     * empreinte plutot que reecrit.
     */
    @TempDir(cleanup = CleanupMode.NEVER)
    Path gameDir;

    @Test
    @DisplayName("T-190 : cycle complet, du chargeur natif a la frontiere JNI")
    void cycleCompletAtraversLaFrontiere() {
        // La bibliotheque est chargee par le vrai chargeur (C-03), depuis les
        // ressources que la chaine de build y a placees (M0.8). Le test couvre
        // donc l'extraction, la verification SHA-256 et le chargement, en plus
        // de la frontiere elle-meme : c'est le chemin qu'empruntera le jeu.
        NativeLoadResult loaded = NativeLoader.load(gameDir);
        assumeTrue(
                loaded instanceof NativeLoadResult.Loaded,
                () -> "bibliotheque native absente des ressources — la produire avec : "
                        + "./gradlew :axion-mod:packageNatives");

        NativeLoadResult.Loaded ok = (NativeLoadResult.Loaded) loaded;
        // Le chemin d'extraction porte l'empreinte verifiee (R-420).
        assertTrue(ok.path().toString().contains(ok.sha256()), ok.path().toString());
        assertTrue(ok.path().startsWith(gameDir), "extrait hors du repertoire de jeu");

        // R-260 : la version d'ABI se demande avant toute autre chose. Si
        // RegisterNatives avait échoué, cet appel lèverait déjà
        // UnsatisfiedLinkError.
        assertEquals(NativeBridge.EXPECTED_ABI_VERSION, NativeBridge.nativeAbiVersion());
        assertTrue(NativeBridge.isAbiCompatible());

        // Aucun appel n'aboutit tant qu'aucun contexte n'existe.
        assertNull(NativeBridge.acquire(0L, BufferKinds.SIM_IN, 16));

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("sim.max_substeps", 6L);
        config.put("physics.gravity", -12.5);
        config.put("general.enabled", true);
        config.put("deformation.quality", "high");
        byte[] cbor = CborWriter.encodeMap(config);

        long ctx = NativeBridge.initialize(cbor, NativeBridge.SIDE_SERVER);
        assertTrue(ctx > 0, () -> "initialisation refusée, code " + ctx);

        try {
            // Un contexte par processus.
            assertEquals(NativeBridge.E_ALREADY_INITIALIZED, NativeBridge.initialize(cbor, NativeBridge.SIDE_SERVER));

            ByteBuffer buffer = NativeBridge.acquire(ctx, BufferKinds.SIM_OUT, 128);
            assertNotNull(buffer, "acquisition refusée");
            assertTrue(buffer.isDirect(), "le tampon devrait être direct");
            assertEquals(ByteOrder.LITTLE_ENDIAN, buffer.order(), "ordre des octets non imposé");
            assertTrue(buffer.capacity() >= 128 + BufferKinds.HEADER_BYTES);

            // L'en-tête est déjà posé par le natif : Java y lit le magic, le
            // kind et la génération sans second appel.
            assertEquals(BufferKinds.MAGIC, buffer.getInt(0), "magic absent de l'en-tête");
            assertEquals(BufferKinds.SIM_OUT, buffer.getInt(4), "kind incorrect");
            int generation = buffer.getInt(8);
            assertTrue(generation > 0, "génération nulle");

            // Écriture d'une charge utile, comme le fera le moteur.
            buffer.putLong(16, 8L); // payload_len
            buffer.putInt(24, 2); // element_count
            for (int index = 0; index < 8; index++) {
                buffer.put(BufferKinds.HEADER_BYTES + index, (byte) (index + 1));
            }
            assertEquals(8L, buffer.getLong(16));
            assertEquals(1, buffer.get(BufferKinds.HEADER_BYTES));

            // Ré-acquérir la même taille rend la même vue : rien n'est
            // réalloué, la génération ne bouge pas.
            ByteBuffer again = NativeBridge.acquire(ctx, BufferKinds.SIM_OUT, 64);
            assertNotNull(again);
            assertEquals(generation, again.getInt(8), "génération changée sans raison");

            // La libération exige la génération courante (R-270).
            assertEquals(
                    NativeBridge.E_INVALID_BUFFER,
                    NativeBridge.release(ctx, BufferKinds.SIM_OUT, generation ^ 0xFF));
            assertEquals(NativeBridge.OK, NativeBridge.release(ctx, BufferKinds.SIM_OUT, generation));

            // Un kind inconnu est refusé, jamais utilisé comme indice.
            assertNull(NativeBridge.acquire(ctx, 999, 16));

            // Le message d'erreur traverse en UTF-8, sans zéro terminal.
            String message = NativeBridge.lastErrorMessage(ctx);
            assertNotNull(message);
            assertEquals(
                    message,
                    new String(message.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8),
                    "message d'erreur non UTF-8");
        } finally {
            assertEquals(NativeBridge.OK, NativeBridge.close(ctx));
        }

        // Après l'arrêt, le jeton ne désigne plus rien.
        assertEquals(NativeBridge.E_INVALID_HANDLE, NativeBridge.close(ctx));
        assertNull(NativeBridge.acquire(ctx, BufferKinds.SIM_IN, 16));

        // Une configuration invalide est refusée par le natif, qui revalide ce
        // que Java lui envoie (interdiction 3.13).
        Map<String, Object> invalide = new LinkedHashMap<>();
        invalide.put("sim.max_substeps", 99L);
        long refus = NativeBridge.initialize(
                CborWriter.encodeMap(invalide), NativeBridge.SIDE_SERVER);
        assertTrue(refus < 0, () -> "configuration hors plage acceptée, jeton " + refus);
        // Le jeton nul porte la cause du refus : sans elle, Java n'aurait
        // qu'un code au moment où il en a le plus besoin.
        String cause = NativeBridge.lastErrorMessage(0L);
        assertTrue(cause.contains("sim.max_substeps"), () -> "cause muette : " + cause);

        // Un côté inconnu est refusé plutôt que deviné : en supposer un
        // donnerait un pool mal dimensionné sans que rien ne le signale.
        long cote = NativeBridge.initialize(null, 42);
        assertTrue(cote < 0, () -> "côté inconnu accepté, jeton " + cote);

        // Et un redémarrage propre reste possible.
        long reprise = NativeBridge.initialize(null, NativeBridge.SIDE_SERVER);
        assertTrue(reprise > 0, () -> "réinitialisation refusée, code " + reprise);

        // R-502 : l'export des métriques traverse la frontière et porte les
        // métriques de budget qu'INV-19 exige.
        String metriques = NativeBridge.metricsJson(reprise);
        assertTrue(metriques.startsWith("{"), metriques);
        assertTrue(metriques.contains("axion.budget.sim_ns_per_tick.consumed"), metriques);
        assertTrue(metriques.contains("axion.jobs.workers"), metriques);

        assertEquals(NativeBridge.OK, NativeBridge.close(reprise));
    }
}
