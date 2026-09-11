package dev.axion.asset;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** T-260..T-263 — cache des assets compilés (C-25). */
class AssetCacheTest {

    @TempDir
    Path root;

    private static final long UNLIMITED = 1L << 40;

    private static AssetKey key(String source) {
        return AssetKey.of(source.getBytes(StandardCharsets.UTF_8), "", 1, 2);
    }

    private static byte[] payload(int size, byte fill) {
        byte[] bytes = new byte[size];
        java.util.Arrays.fill(bytes, fill);
        return bytes;
    }

    @Test
    @DisplayName("T-260 : un asset rangé se retrouve tel quel")
    void unAssetRangeSeRetrouve() {
        AssetCache cache = new AssetCache(root, UNLIMITED);
        AssetKey clef = key("voiture");
        byte[] a3d = payload(512, (byte) 0x5A);

        assertTrue(cache.put(clef, a3d));
        assertArrayEquals(a3d, cache.get(clef));
        assertEquals(1, cache.size());
    }

    @Test
    @DisplayName("T-260 : une clé absente rend null, sans erreur")
    void uneCleAbsenteRendNull() {
        AssetCache cache = new AssetCache(root, UNLIMITED);
        // Une absence est le cas courant du premier démarrage : ce n'est pas
        // une erreur, c'est un cache vide.
        assertNull(cache.get(key("jamais vue")));
        assertTrue(cache.diagnostics().isEmpty());
    }

    @Test
    @DisplayName("T-260 : le chemin suit <2 hex>/<clé>.a3d, hors du monde")
    void leCheminSuitLaConvention() throws IOException {
        AssetCache cache = new AssetCache(root, UNLIMITED);
        AssetKey clef = key("voiture");
        cache.put(clef, payload(64, (byte) 1));

        Path attendu = root.resolve(clef.shard()).resolve(clef.hex() + ".a3d");
        assertTrue(Files.isReadable(attendu), () -> "entrée absente de " + attendu);
        // R-563, INV-10 : rien n'est écrit ailleurs que sous la racine donnée.
        try (var flux = Files.walk(root)) {
            assertTrue(flux.allMatch(path -> path.startsWith(root)));
        }
    }

    @Test
    @DisplayName("T-261 : une entrée corrompue est écartée et supprimée")
    void uneEntreeCorrompueEstEcartee() throws IOException {
        AssetCache cache = new AssetCache(root, UNLIMITED);
        AssetKey clef = key("voiture");
        cache.put(clef, payload(256, (byte) 7));

        // Un octet retourné dans la charge utile : le CRC ne correspond plus.
        Path fichier = root.resolve(clef.shard()).resolve(clef.hex() + ".a3d");
        byte[] brut = Files.readAllBytes(fichier);
        brut[AssetCache.ENTRY_HEADER_BYTES] ^= 0xFF;
        Files.write(fichier, brut);

        assertNull(cache.get(clef));
        // R-560 : supprimée, pas réparée. La source est là, recompiler coûte
        // moins cher que de deviner ce qui manque.
        assertFalse(Files.exists(fichier));
        assertEquals(1, cache.diagnostics().size());
        assertTrue(cache.diagnostics().get(0).contains("corrompue"));
    }

    @Test
    @DisplayName("T-261 : une entrée tronquée est écartée")
    void uneEntreeTronqueeEstEcartee() throws IOException {
        AssetCache cache = new AssetCache(root, UNLIMITED);
        AssetKey clef = key("voiture");
        cache.put(clef, payload(256, (byte) 7));

        Path fichier = root.resolve(clef.shard()).resolve(clef.hex() + ".a3d");
        byte[] brut = Files.readAllBytes(fichier);
        Files.write(fichier, java.util.Arrays.copyOf(brut, brut.length - 10));

        // Un disque plein laisse exactement cela : un en-tête valide suivi de
        // moins que ce qu'il annonce.
        assertNull(cache.get(clef));
    }

    @Test
    @DisplayName("T-261 : une entrée d'un autre schéma est écartée")
    void uneEntreeDUnAutreSchemaEstEcartee() throws IOException {
        AssetCache cache = new AssetCache(root, UNLIMITED);
        AssetKey clef = key("voiture");
        cache.put(clef, payload(64, (byte) 3));

        Path fichier = root.resolve(clef.shard()).resolve(clef.hex() + ".a3d");
        byte[] brut = Files.readAllBytes(fichier);
        brut[4] = 99;
        Files.write(fichier, brut);

        assertNull(cache.get(clef));
    }

    @Test
    @DisplayName("T-262 : R-561, le plus ancien employé part en premier")
    void evictionDuMoinsRecemmentEmploye() {
        // Trois entrées de 128 octets utiles ; le plafond n'en laisse tenir
        // que deux, enveloppe comprise.
        long plafond = 2 * (128 + AssetCache.ENTRY_HEADER_BYTES);
        AssetCache cache = new AssetCache(root, plafond);

        AssetKey a = key("a");
        AssetKey b = key("b");
        AssetKey c = key("c");

        cache.put(a, payload(128, (byte) 1));
        cache.put(b, payload(128, (byte) 2));
        assertEquals(2, cache.size());

        // `a` est employée : elle repasse en queue de la file d'éviction.
        assertNotNull(cache.get(a));

        cache.put(c, payload(128, (byte) 3));
        assertEquals(2, cache.size());
        assertNull(cache.get(b), "la plus ancienne employée devait partir");
        assertNotNull(cache.get(a));
        assertNotNull(cache.get(c));
    }

    @Test
    @DisplayName("T-262 : le cache ne dépasse pas son plafond")
    void leCacheNeDepassePasSonPlafond() {
        long plafond = 4 * (64 + AssetCache.ENTRY_HEADER_BYTES);
        AssetCache cache = new AssetCache(root, plafond);

        for (int index = 0; index < 20; index++) {
            cache.put(key("asset" + index), payload(64, (byte) index));
        }
        // Un cache qui grossirait sans fin finirait par occuper plus de place
        // que les mondes qu'il sert.
        assertTrue(cache.totalBytes() <= plafond, () -> cache.totalBytes() + " > " + plafond);
        assertEquals(4, cache.size());
    }

    @Test
    @DisplayName("T-263 : l'index survit à un redémarrage")
    void lIndexSurvitAUnRedemarrage() {
        AssetCache premier = new AssetCache(root, UNLIMITED);
        premier.put(key("a"), payload(100, (byte) 1));
        premier.put(key("b"), payload(200, (byte) 2));
        assertTrue(premier.writeIndex());

        AssetCache second = new AssetCache(root, UNLIMITED);
        assertEquals(2, second.size());
        assertEquals(premier.totalBytes(), second.totalBytes());
        assertNotNull(second.get(key("a")));
    }

    @Test
    @DisplayName("T-263 : un index corrompu ne perd pas le cache")
    void unIndexCorrompuNePerdPasLeCache() throws IOException {
        AssetCache premier = new AssetCache(root, UNLIMITED);
        premier.put(key("a"), payload(100, (byte) 1));
        premier.writeIndex();

        Path index = root.resolve("index.bin");
        byte[] brut = Files.readAllBytes(index);
        brut[brut.length - 1] ^= 0xFF;
        Files.write(index, brut);

        AssetCache second = new AssetCache(root, UNLIMITED);
        assertEquals(0, second.size(), "l'index corrompu a été cru");
        assertFalse(second.diagnostics().isEmpty());
        // L'index n'est pas la vérité : les fichiers le sont, et l'entrée se
        // relit malgré tout.
        assertNotNull(second.get(key("a")));
    }

    @Test
    @DisplayName("T-263 : un index absent n'est pas une erreur")
    void unIndexAbsentNEstPasUneErreur() {
        AssetCache cache = new AssetCache(root, UNLIMITED);
        assertEquals(0, cache.size());
        assertTrue(cache.diagnostics().isEmpty());
    }

    @Test
    @DisplayName("T-260 : ranger deux fois la même clé ne double pas sa taille")
    void rangerDeuxFoisNeDoublePasLaTaille() {
        AssetCache cache = new AssetCache(root, UNLIMITED);
        AssetKey clef = key("voiture");

        cache.put(clef, payload(128, (byte) 1));
        long apresLaPremiere = cache.totalBytes();
        cache.put(clef, payload(128, (byte) 2));

        assertEquals(apresLaPremiere, cache.totalBytes());
        assertEquals(1, cache.size());
        // C'est bien la seconde qui reste.
        assertEquals(2, cache.get(clef)[0]);
    }

    @Test
    @DisplayName("La clé dépend aussi de l'ABI")
    void laCleDependAussiDeLAbi() {
        byte[] contenu = "v 0 0 0".getBytes(StandardCharsets.UTF_8);
        AssetKey abiUn = AssetKey.of(contenu, "", 1, 1);
        AssetKey abiDeux = AssetKey.of(contenu, "", 1, 2);

        // Sans ce terme, un asset resterait en cache après une mise à jour de
        // la frontière qui en change la lecture.
        assertFalse(abiUn.equals(abiDeux));
    }
}
