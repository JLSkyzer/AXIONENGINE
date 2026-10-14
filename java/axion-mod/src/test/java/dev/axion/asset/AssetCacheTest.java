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
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** T-260..T-263 — cache des assets compilés (C-25). */
class AssetCacheTest {

    @TempDir
    Path root;

    private static final long UNLIMITED = 1L << 40;

    private static AssetKey key(String source) {
        return AssetKey.of(source.getBytes(StandardCharsets.UTF_8), new byte[0], 1, 2);
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
        assertFalse(second.diagnostics().isEmpty());
        // L'index corrompu n'est pas cru : l'entrée est connue par son fichier,
        // avec la taille du fichier (R-561).
        assertEquals(1, second.size());
        assertEquals(premier.totalBytes(), second.totalBytes());
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
        AssetKey abiUn = AssetKey.of(contenu, new byte[0], 1, 1);
        AssetKey abiDeux = AssetKey.of(contenu, new byte[0], 1, 2);

        // Sans ce terme, un asset resterait en cache après une mise à jour de
        // la frontière qui en change la lecture.
        assertFalse(abiUn.equals(abiDeux));
    }

    /** Le fichier d'une entrée, tel que la convention de C-25 le range. */
    private Path fichier(AssetKey clef) {
        return root.resolve(clef.shard()).resolve(clef.hex() + ".a3d");
    }

    /** Date un fichier d'entrée, pour fixer l'ordre de leur adoption. */
    private void dater(AssetKey clef, long millis) throws IOException {
        Files.setLastModifiedTime(fichier(clef), FileTime.fromMillis(millis));
    }

    private long fichiersSurLeDisque() throws IOException {
        try (var flux = Files.walk(root)) {
            return flux.filter(path -> path.toString().endsWith(".a3d")).count();
        }
    }

    @Test
    @DisplayName("T-262 : R-561, une entrée rangée depuis le dernier index est comptée, puis évincée")
    void uneEntreeHorsIndexEstCompteeEtEvincee() throws IOException {
        // Aucun writeIndex : c'est un rechargement de ressources dans la même
        // session, ou un arrêt brutal. Les fichiers sont là, l'index ne les cite pas.
        AssetCache premier = new AssetCache(root, UNLIMITED);
        premier.put(key("a"), payload(128, (byte) 1));
        premier.put(key("b"), payload(128, (byte) 2));
        dater(key("a"), 1_000_000L);
        dater(key("b"), 2_000_000L);

        long plafond = 2 * (128 + AssetCache.ENTRY_HEADER_BYTES);
        AssetCache second = new AssetCache(root, plafond);
        assertEquals(2, second.size());
        assertEquals(premier.totalBytes(), second.totalBytes());

        second.put(key("c"), payload(128, (byte) 3));
        assertEquals(2, second.size());
        assertTrue(second.totalBytes() <= plafond, () -> second.totalBytes() + " > " + plafond);
        // La plus ancienne quitte aussi le disque : c'est le disque que R-561 borne.
        assertFalse(Files.exists(fichier(key("a"))), "la plus ancienne devait partir");
        assertEquals(2, fichiersSurLeDisque());
    }

    @Test
    @DisplayName("T-262 : R-561, le plafond tient dès l'ouverture")
    void lePlafondTientDesLOuverture() throws IOException {
        AssetCache premier = new AssetCache(root, UNLIMITED);
        for (int index = 0; index < 6; index++) {
            premier.put(key("asset" + index), payload(64, (byte) index));
            dater(key("asset" + index), 1_000_000L * (index + 1));
        }

        long plafond = 3 * (64 + AssetCache.ENTRY_HEADER_BYTES);
        AssetCache second = new AssetCache(root, plafond);
        assertEquals(3, second.size());
        assertTrue(second.totalBytes() <= plafond, () -> second.totalBytes() + " > " + plafond);
        assertEquals(3, fichiersSurLeDisque());
        // Les plus récentes restent.
        assertNotNull(second.get(key("asset5")));
        assertNull(second.get(key("asset0")));
    }

    @Test
    @DisplayName("T-263 : une entrée de l'index dont le fichier a disparu n'est plus comptée")
    void unFichierDisparuSortDeLIndex() throws IOException {
        AssetCache premier = new AssetCache(root, UNLIMITED);
        premier.put(key("a"), payload(100, (byte) 1));
        premier.put(key("b"), payload(200, (byte) 2));
        assertTrue(premier.writeIndex());
        Files.delete(fichier(key("a")));

        AssetCache second = new AssetCache(root, UNLIMITED);
        assertEquals(1, second.size());
        assertEquals(200 + AssetCache.ENTRY_HEADER_BYTES, second.totalBytes());
    }

    @Test
    @DisplayName("T-263 : un fichier temporaire laissé par un arrêt brutal est nettoyé")
    void unTemporaireOrphelinEstNettoye() throws IOException {
        AssetKey clef = key("a");
        Path temporaire = root.resolve(clef.shard()).resolve(clef.hex() + ".a3d.tmp");
        Files.createDirectories(temporaire.getParent());
        Files.write(temporaire, payload(32, (byte) 9));

        AssetCache cache = new AssetCache(root, UNLIMITED);
        assertFalse(Files.exists(temporaire), "temporaire laissé sur le disque");
        assertEquals(0, cache.size());
    }

    @Test
    @DisplayName("T-262 : une entrée apparue après l'ouverture est comptée quand elle est lue")
    void uneEntreeLueHorsIndexEstComptee() {
        AssetCache lecteur = new AssetCache(root, UNLIMITED);
        AssetCache ecrivain = new AssetCache(root, UNLIMITED);
        ecrivain.put(key("a"), payload(128, (byte) 1));

        assertNotNull(lecteur.get(key("a")));
        assertEquals(1, lecteur.size());
        assertEquals(ecrivain.totalBytes(), lecteur.totalBytes());
    }
}

