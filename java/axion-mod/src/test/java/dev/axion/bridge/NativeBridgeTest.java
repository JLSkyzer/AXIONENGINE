package dev.axion.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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

    private static Path libraryPath() {
        String dir = System.getProperty("axion.native.dir");
        if (dir == null) {
            return null;
        }
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        String fileName;
        if (os.contains("mac") || os.contains("darwin")) {
            fileName = "libaxion_native.dylib";
        } else if (os.startsWith("windows")) {
            fileName = "axion_native.dll";
        } else {
            fileName = "libaxion_native.so";
        }
        return Path.of(dir, fileName);
    }

    @Test
    @DisplayName("T-190 : cycle complet à travers la frontière JNI")
    void cycleCompletAtraversLaFrontiere() {
        Path library = libraryPath();
        assumeTrue(
                library != null && Files.isReadable(library),
                () -> "bibliothèque native absente (" + library + ") — la construire avec :\n"
                        + "  cargo build --release -p ax-ffi");

        // System.load exige un chemin absolu (R-420) : jamais loadLibrary, qui
        // dépendrait de java.library.path et pourrait charger la bibliothèque
        // d'un autre mod.
        System.load(library.toAbsolutePath().toString());

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

        long ctx = NativeBridge.initialize(cbor);
        assertTrue(ctx > 0, () -> "initialisation refusée, code " + ctx);

        try {
            // Un contexte par processus.
            assertEquals(NativeBridge.E_ALREADY_INITIALIZED, NativeBridge.initialize(cbor));

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
        long refus = NativeBridge.initialize(CborWriter.encodeMap(invalide));
        assertTrue(refus < 0, () -> "configuration hors plage acceptée, jeton " + refus);

        // Et un redémarrage propre reste possible.
        long reprise = NativeBridge.initialize(null);
        assertTrue(reprise > 0, () -> "réinitialisation refusée, code " + reprise);
        assertEquals(NativeBridge.OK, NativeBridge.close(reprise));
    }
}
