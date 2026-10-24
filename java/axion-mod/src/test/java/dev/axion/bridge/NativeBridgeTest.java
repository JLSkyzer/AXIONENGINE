package dev.axion.bridge;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.axion.bootstrap.NativeLoadResult;
import dev.axion.bootstrap.NativeLoader;
import dev.axion.net.DetProfile;
import dev.axion.net.MalformedPacketException;
import dev.axion.net.NativeNetCompression;
import dev.axion.net.NetCompression;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
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

    /** Le maillage commun des glTF de test : un triangle dont la boîte couvre [0, 1]³. */
    private static final String MAILLAGE_BOITE =
            "\"meshes\":[{\"name\":\"boite\",\"primitives\":[{\"attributes\":"
                    + "{\"POSITION\":0},\"indices\":1}]}],"
                    + "\"accessors\":[{\"bufferView\":0,\"componentType\":5126,\"count\":3,"
                    + "\"type\":\"VEC3\",\"min\":[0.0,0.0,0.0],\"max\":[1.0,1.0,1.0]},"
                    + "{\"bufferView\":1,\"componentType\":5123,\"count\":3,\"type\":\"SCALAR\"}],"
                    + "\"bufferViews\":[{\"buffer\":0,\"byteOffset\":0,\"byteLength\":36},"
                    + "{\"buffer\":0,\"byteOffset\":36,\"byteLength\":6}],"
                    + "\"buffers\":[{\"byteLength\":42,\"uri\":\"data:application/octet-stream;base64,"
                    + "AAAAAAAAAAAAAAAAAACAPwAAAAAAAAAAAAAAAAAAgD8AAIA/AAABAAIA\"}]}";

    /**
     * glTF minimal dont l'unique node porte {@code role=collider},
     * {@code shape=auto_box} : trois sommets couvrant l'AABB [0,0,0]–[1,1,1], donc
     * une boîte valide. Compilé au runtime, il produit une section {@code PHYS}
     * (vérifié aussi côté Rust, {@code compile.rs}). Sert à prouver CREATE_ASSEMBLY
     * de bout en bout (C-32, Option A, ADR-115).
     */
    private static final String GLTF_COLLIDER =
            "{\"asset\":{\"version\":\"2.0\"},\"scene\":0,\"scenes\":[{\"nodes\":[0]}],"
                    + "\"nodes\":[{\"name\":\"collideur\",\"mesh\":0,\"extras\":{\"axion\":"
                    + "{\"role\":\"collider\",\"shape\":\"auto_box\"}}}],"
                    + MAILLAGE_BOITE;

    /**
     * Deux nodes colliders côte à côte, {@code cadre} et {@code porte} (2 blocs plus loin) : une
     * definition en fait deux corps qu'un joint relie (ADR-151).
     */
    private static final String GLTF_DEUX_CORPS =
            "{\"asset\":{\"version\":\"2.0\"},\"scene\":0,\"scenes\":[{\"nodes\":[0,1]}],"
                    + "\"nodes\":[{\"name\":\"cadre\",\"mesh\":0,\"extras\":{\"axion\":"
                    + "{\"role\":\"collider\",\"shape\":\"auto_box\"}}},"
                    + "{\"name\":\"porte\",\"mesh\":0,\"translation\":[2.0,0.0,0.0],\"extras\":{\"axion\":"
                    + "{\"role\":\"collider\",\"shape\":\"auto_box\"}}}],"
                    + MAILLAGE_BOITE;

    /**
     * Une chaîne tournée et mise à l'échelle : {@code socle} (un bloc en y, un quart de tour autour de z,
     * échelle 2) porte son collider et {@code bras} (un bloc en x, un quart de tour autour de y), qui porte
     * le sien. Une definition en fait deux corps, et le repère du bras dépend de toute la chaîne (ADR-151 D9).
     */
    private static final String GLTF_BRAS =
            "{\"asset\":{\"version\":\"2.0\"},\"scene\":0,\"scenes\":[{\"nodes\":[0]}],"
                    + "\"nodes\":[{\"name\":\"socle\",\"translation\":[0.0,1.0,0.0],"
                    + "\"rotation\":[0.0,0.0,0.70710677,0.70710677],\"scale\":[2.0,2.0,2.0],\"children\":[1,2]},"
                    + "{\"name\":\"socle_col\",\"mesh\":0,\"extras\":{\"axion\":"
                    + "{\"role\":\"collider\",\"shape\":\"auto_box\"}}},"
                    + "{\"name\":\"bras\",\"translation\":[1.0,0.0,0.0],"
                    + "\"rotation\":[0.0,0.70710677,0.0,0.70710677],\"children\":[3]},"
                    + "{\"name\":\"bras_col\",\"mesh\":0,\"extras\":{\"axion\":"
                    + "{\"role\":\"collider\",\"shape\":\"auto_box\"}}}],"
                    + MAILLAGE_BOITE;

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

    /** Lit la génération courante d'un tampon, dans son en-tête. */
    private static int lireGeneration(long ctx, int kind) {
        java.nio.ByteBuffer buffer = NativeBridge.acquire(ctx, kind, 0);
        assertNotNull(buffer, "tampon absent");
        return buffer.getInt(8);
    }

    /**
     * En-tête PNG d'une image de 2×2 — signature et {@code IHDR} : ce que le natif contrôle,
     * sans rien décoder (R-532).
     */
    private static byte[] png2x2() {
        ByteBuffer png = ByteBuffer.allocate(33).order(ByteOrder.BIG_ENDIAN);
        png.put(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
        png.putInt(13).put("IHDR".getBytes(StandardCharsets.US_ASCII)).putInt(2).putInt(2);
        // Profondeur, couleur, compression, filtre, entrelacement, puis un CRC que personne
        // ne vérifie ici.
        png.put(new byte[] {8, 6, 0, 0, 0}).putInt(0);
        return png.array();
    }

    /**
     * glTF d'un triangle à UV et à matériau texturé (ADR-122) : albedo du PNG que porte la
     * {@code bufferView} 3, filtré au plus proche ; émissive d'une ressource.
     */
    private static byte[] gltfTexture(byte[] png) {
        ByteBuffer bin = ByteBuffer.allocate(68 + png.length).order(ByteOrder.LITTLE_ENDIAN);
        // Positions, puis UV.
        for (float value : new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 1}) {
            bin.putFloat(value);
        }
        bin.putShort((short) 0).putShort((short) 1).putShort((short) 2);
        bin.position(68);
        bin.put(png);
        String json = "{\"asset\":{\"version\":\"2.0\"},\"scene\":0,\"scenes\":[{\"nodes\":[0]}],"
                + "\"nodes\":[{\"name\":\"panneau\",\"mesh\":0}],"
                + "\"meshes\":[{\"name\":\"panneau\",\"primitives\":[{\"attributes\":"
                + "{\"POSITION\":0,\"TEXCOORD_0\":1},\"indices\":2,\"material\":0}]}],"
                + "\"materials\":[{\"name\":\"peint\",\"pbrMetallicRoughness\":"
                + "{\"baseColorTexture\":{\"index\":0}},\"emissiveFactor\":[1.0,1.0,1.0],"
                + "\"emissiveTexture\":{\"index\":1}}],"
                + "\"textures\":[{\"source\":0,\"sampler\":0},{\"source\":1}],"
                + "\"samplers\":[{\"magFilter\":9728}],"
                + "\"images\":[{\"bufferView\":3,\"mimeType\":\"image/png\"},"
                + "{\"uri\":\"tex/lueur.png\"}],"
                + "\"accessors\":[{\"bufferView\":0,\"componentType\":5126,\"count\":3,"
                + "\"type\":\"VEC3\",\"min\":[0.0,0.0,0.0],\"max\":[1.0,1.0,0.0]},"
                + "{\"bufferView\":1,\"componentType\":5126,\"count\":3,\"type\":\"VEC2\"},"
                + "{\"bufferView\":2,\"componentType\":5123,\"count\":3,\"type\":\"SCALAR\"}],"
                + "\"bufferViews\":[{\"buffer\":0,\"byteOffset\":0,\"byteLength\":36},"
                + "{\"buffer\":0,\"byteOffset\":36,\"byteLength\":24},"
                + "{\"buffer\":0,\"byteOffset\":60,\"byteLength\":6},"
                + "{\"buffer\":0,\"byteOffset\":68,\"byteLength\":" + png.length + "}],"
                + "\"buffers\":[{\"byteLength\":" + bin.capacity()
                + ",\"uri\":\"data:application/octet-stream;base64,"
                + Base64.getEncoder().encodeToString(bin.array()) + "\"}]}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    /** Un triangle glTF qui cite dans {@code extensionsUsed} une extension non prise en charge (R-530). */
    private static byte[] gltfAvecExtension() {
        ByteBuffer bin = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        for (float valeur : new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0}) {
            bin.putFloat(valeur);
        }
        bin.putShort((short) 0).putShort((short) 1).putShort((short) 2).putShort((short) 0);
        String json = "{\"asset\":{\"version\":\"2.0\"},\"extensionsUsed\":[\"KHR_materials_variants\"],"
                + "\"scene\":0,\"scenes\":[{\"nodes\":[0]}],\"nodes\":[{\"mesh\":0}],"
                + "\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":0},\"indices\":1}]}],"
                + "\"accessors\":[{\"bufferView\":0,\"componentType\":5126,\"count\":3,\"type\":\"VEC3\","
                + "\"min\":[0,0,0],\"max\":[1,1,0]},"
                + "{\"bufferView\":1,\"componentType\":5123,\"count\":3,\"type\":\"SCALAR\"}],"
                + "\"bufferViews\":[{\"buffer\":0,\"byteLength\":36},"
                + "{\"buffer\":0,\"byteOffset\":36,\"byteLength\":6}],"
                + "\"buffers\":[{\"byteLength\":44,\"uri\":\"data:application/octet-stream;base64,"
                + Base64.getEncoder().encodeToString(bin.array()) + "\"}]}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    /** Compile une source par la vraie bibliothèque, attend le résultat et relâche les tampons. */
    private static byte[] compiler(long ctx, long assetId, int format, byte[] source) {
        return compiler(ctx, assetId, format, source, new byte[0]);
    }

    /** Idem, avec le texte d'un {@code .axion.json} voisin (ADR-135). */
    private static byte[] compiler(long ctx, long assetId, int format, byte[] source, byte[] options) {
        return compiler(ctx, assetId, format, source, options, dev.axion.asset.SiblingFiles.NONE);
    }

    /** Idem, avec les fichiers voisins que la source peut demander (ADR-139). */
    private static byte[] compiler(
            long ctx,
            long assetId,
            int format,
            byte[] source,
            byte[] options,
            dev.axion.asset.SiblingFiles siblings) {
        dev.axion.asset.NativeAssetCompiler compilateur = new dev.axion.asset.NativeAssetCompiler(ctx);
        int job = compilateur.submit(assetId, format, source, options, siblings);
        assertTrue(job > 0, () -> "compilation refusée, code " + job);
        dev.axion.asset.AssetCompiler.CompileStatus etat = attendre(compilateur, job);
        assertEquals(dev.axion.asset.AssetState.COMPILED, etat.state(), "compilation échouée");
        assertEquals(
                NativeBridge.OK,
                NativeBridge.release(ctx, BufferKinds.ASSET_IN, lireGeneration(ctx, BufferKinds.ASSET_IN)));
        assertEquals(
                NativeBridge.OK,
                NativeBridge.release(ctx, BufferKinds.ASSET_OUT, lireGeneration(ctx, BufferKinds.ASSET_OUT)));
        return etat.payload();
    }

    /**
     * Sonde une compilation jusqu'à ce qu'elle quitte {@code COMPILING}, ou jusqu'à une échéance
     * large : elle tourne sur le pool natif, et un nombre fixe de sondages ne borne pas un temps —
     * le test natif {@code t210} l'a montré sur un runner macOS de la CI, où cent mille sondages
     * ont tenu moins de 50 ms.
     */
    private static dev.axion.asset.AssetCompiler.CompileStatus attendre(
            dev.axion.asset.NativeAssetCompiler compilateur, int job) {
        long echeance = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        dev.axion.asset.AssetCompiler.CompileStatus etat = compilateur.poll(job);
        while (etat.state() == dev.axion.asset.AssetState.COMPILING && System.nanoTime() - echeance < 0) {
            Thread.onSpinWait();
            etat = compilateur.poll(job);
        }
        return etat;
    }

    /** Un joueur près du corps de l'essai de simulation : sans lui, R-612 l'endormirait. */
    private static final double[][] JOUEUR_PRES_DU_CORPS = {{0.0, 100.0, 0.0}};

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

            // ADR-150 D4 : l'empreinte déterministe, propriété du binaire, stable d'un appel à l'autre.
            DetProfile profil = DetProfile.ofThisBinary();
            assertEquals(1, profil.kernelVersion(), "DET_KERNEL_VERSION");
            assertNotEquals(0L, profil.profile(), "empreinte nulle");
            assertEquals(profil, DetProfile.ofThisBinary(), "empreinte instable");

            // ADR-150 D5 : un registre compressé par le zstd du natif se relit à l'identique ; une
            // trame plus grande que la borne, ou qui n'en est pas une, est refusée (R-1691). Chaque
            // appel relâche NET_OUT : la fermeture ci-dessous le vérifie (E-2003 sinon).
            NetCompression zstd = new NativeNetCompression(ctx);
            byte[] registre = "{\"schema\":1,\"asset\":\"axion:models/test/ballon.glb\"}\n"
                    .repeat(2000).getBytes(StandardCharsets.UTF_8);
            byte[] trame = zstd.compress(registre);
            assertTrue(trame.length < registre.length, "la trame ne compresse pas");
            assertThrows(MalformedPacketException.class, () -> zstd.decompress(trame, registre.length - 1));
            assertThrows(MalformedPacketException.class,
                    () -> zstd.decompress("pas une trame".getBytes(StandardCharsets.UTF_8), 1024));
            // Les succès en dernier : un appel qui ne relâcherait pas NET_OUT ferait échouer la
            // fermeture du contexte (E-2003), alors qu'un refus relâche le tampon de lui-même.
            try {
                assertArrayEquals(new byte[0], zstd.decompress(zstd.compress(new byte[0]), 0));
                assertArrayEquals(registre, zstd.decompress(trame, registre.length));
            } catch (MalformedPacketException error) {
                throw new AssertionError("trame valide refusée", error);
            }
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

        // T-210 : une compilation d'asset traverse la frontière, sur la vraie
        // bibliothèque. C'est le chemin complet de C-20 : écrire la source,
        // lancer, sonder, relire.
        dev.axion.asset.NativeAssetCompiler compilateur =
                new dev.axion.asset.NativeAssetCompiler(reprise);
        byte[] source = String.join(
                        "\n", "v 0 0 0", "v 1 0 0", "v 0 1 0", "f 1 2 3", "")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int job = compilateur.submit(
                0x4242L, dev.axion.asset.SourceFormats.OBJ, source, new byte[0],
                dev.axion.asset.SiblingFiles.NONE);
        assertTrue(job > 0, () -> "compilation refusée, code " + job);

        dev.axion.asset.AssetCompiler.CompileStatus etat = attendre(compilateur, job);
        assertEquals(
                dev.axion.asset.AssetState.COMPILED,
                etat.state(),
                () -> "compilation échouée");
        assertTrue(etat.size() > 0, "asset compilé vide");
        // ADR-148 : un triangle sans rien à redire n'a aucune ligne.
        assertEquals(java.util.List.of(), etat.lines(), "lignes d'une compilation sans avertissement");

        // Le résultat n'est rendu qu'une fois : le redemander vaut mieux qu'une
        // seconde lecture d'un tampon qui a pu changer.
        assertEquals(
                dev.axion.asset.AssetState.FAILED,
                compilateur.poll(job).state(),
                "un travail repris devrait être oublié");

        assertEquals(
                NativeBridge.OK,
                NativeBridge.release(reprise, BufferKinds.ASSET_IN, lireGeneration(reprise, BufferKinds.ASSET_IN)));
        assertEquals(
                NativeBridge.OK,
                NativeBridge.release(reprise, BufferKinds.ASSET_OUT, lireGeneration(reprise, BufferKinds.ASSET_OUT)));

        // ADR-135 : le texte du `.axion.json` voisin traverse la vraie JNI en chaîne
        // CBOR et la compilation l'applique ; une option refusée se lit au sondage,
        // avec le code que la CLI donne au même fichier.
        byte[] defauts = compiler(reprise, 0x4235L, dev.axion.asset.SourceFormats.OBJ, source);
        byte[] agrandi = compiler(
                reprise,
                0x4235L,
                dev.axion.asset.SourceFormats.OBJ,
                source,
                "{ \"schema\": 1, \"scale\": 2.0 }".getBytes(StandardCharsets.UTF_8));
        assertFalse(java.util.Arrays.equals(defauts, agrandi), "options sans effet sur l'asset");
        int jobRefuse = compilateur.submit(
                0x4235L,
                dev.axion.asset.SourceFormats.OBJ,
                source,
                "{ \"schema\": 1, \"scal\": 2.0 }".getBytes(StandardCharsets.UTF_8),
                dev.axion.asset.SiblingFiles.NONE);
        assertTrue(jobRefuse > 0, () -> "soumission refusée, code " + jobRefuse);
        dev.axion.asset.AssetCompiler.CompileStatus refusee = attendre(compilateur, jobRefuse);
        assertEquals(dev.axion.asset.AssetState.FAILED, refusee.state(), "option inconnue acceptée");
        assertEquals(-7001, refusee.error(), "E-7001 : option inconnue");
        // ADR-148 D3 : le refus porte son motif, pas seulement son code.
        assertEquals(1, refusee.lines().size(), refusee.lines()::toString);
        assertTrue(refusee.lines().get(0).startsWith("options : "), refusee.lines()::toString);

        // ADR-148 : les avertissements d'une compilation traversent la vraie JNI, après l'A3D.
        int jobAverti = compilateur.submit(
                0x4248L,
                dev.axion.asset.SourceFormats.GLTF,
                gltfAvecExtension(),
                new byte[0],
                dev.axion.asset.SiblingFiles.NONE);
        assertTrue(jobAverti > 0, () -> "soumission refusée, code " + jobAverti);
        dev.axion.asset.AssetCompiler.CompileStatus averti = attendre(compilateur, jobAverti);
        assertEquals(dev.axion.asset.AssetState.COMPILED, averti.state(), "compilation échouée");
        assertTrue(averti.size() > 0, "l'A3D se lit avant les lignes");
        assertEquals(
                java.util.List.of("extension ignorée : KHR_materials_variants"), averti.lines());

        // ADR-139, R-830 : un OBJ qui référence son `.mtl`, à travers la vraie JNI. Le premier
        // passage demande le fichier, NativeAssetCompiler le cherche dans les voisins et relance
        // la source avec lui ; le registre ne voit qu'un travail qui continue. Sans le fichier,
        // la compilation fait ce que fait la CLI qui ne le trouve pas : un autre asset.
        byte[] avecMtl = String.join(
                        "\n", "mtllib ./sub//cube.mtl", "usemtl rouge", "v 0 0 0", "v 1 0 0", "v 0 1 0",
                        "f 1 2 3", "")
                .getBytes(StandardCharsets.UTF_8);
        byte[] mtl = String.join("\n", "newmtl rouge", "Kd 0.9 0.1 0.1", "")
                .getBytes(StandardCharsets.UTF_8);
        java.util.List<String> demandes = new java.util.ArrayList<>();
        byte[] fourni = compiler(reprise, 0x4236L, dev.axion.asset.SourceFormats.OBJ, avecMtl, new byte[0],
                name -> {
                    demandes.add(name);
                    return name.equals("sub/cube.mtl") ? mtl : null;
                });
        assertEquals(java.util.List.of("sub/cube.mtl"), demandes, "un nom, sous sa forme normale");
        byte[] sansMtl = compiler(reprise, 0x4236L, dev.axion.asset.SourceFormats.OBJ, avecMtl, new byte[0],
                dev.axion.asset.SiblingFiles.NONE);
        assertFalse(java.util.Arrays.equals(fourni, sansMtl), "le .mtl fourni sans effet sur l'asset");

        // ADR-140 : une compilation abandonnée par la vraie JNI quitte le natif — ce qui prouve
        // aussi l'enregistrement de `assetCancel (JI)I`. La sonder ensuite rend E_INVALID_HANDLE,
        // comme un second abandon ou un identifiant jamais émis.
        int jobAbandonne = compilateur.submit(
                0x4237L, dev.axion.asset.SourceFormats.OBJ, source, new byte[0],
                dev.axion.asset.SiblingFiles.NONE);
        assertTrue(jobAbandonne > 0, () -> "soumission refusée, code " + jobAbandonne);
        compilateur.abandon(jobAbandonne);
        assertEquals(
                NativeBridge.E_INVALID_HANDLE,
                NativeBridge.cancelAsset(reprise, jobAbandonne),
                "le natif garde le travail abandonné");
        dev.axion.asset.AssetCompiler.CompileStatus apresAbandon = compilateur.poll(jobAbandonne);
        assertEquals(dev.axion.asset.AssetState.FAILED, apresAbandon.state());
        assertEquals(NativeBridge.E_INVALID_HANDLE, apresAbandon.error(), "sondage après abandon");
        assertEquals(NativeBridge.E_INVALID_HANDLE, NativeBridge.cancelAsset(reprise, 0));

        // ADR-119 : l'asset compilé se charge dans le natif, sa géométrie revient par
        // ASSET_OUT, puis son handle se rend — à travers la vraie JNI, ce qui prouve
        // aussi l'enregistrement des trois méthodes. Le triangle OBJ a un node
        // visible : il se dessine une fois.
        byte[] triangle = etat.payload();
        dev.axion.asset.NativeAssetLoader chargeur = new dev.axion.asset.NativeAssetLoader(reprise);
        dev.axion.asset.AssetLoader.Loaded charge = chargeur.load(0x4242L, triangle);
        assertTrue(
                charge.ok(),
                () -> "chargement refusé, code " + charge.code() + " : "
                        + NativeBridge.lastErrorMessage(reprise));
        dev.axion.asset.GeometryTransfer geometrie =
                dev.axion.asset.GeometryTransfer.parse(charge.transfer());
        assertEquals(1, geometrie.draws().size(), "un node visible, un dessin");
        dev.axion.asset.GeometryTransfer.Mesh dessine =
                geometrie.meshes().get(geometrie.draws().get(0).mesh());
        assertEquals(3, dessine.indexCount(), "un triangle");
        assertEquals(0, dessine.lod(), "niveau de détail 0");
        assertTrue(geometrie.vertexCount() >= 3);

        // ADR-122 §6 : la table des matériaux revient par ASSET_OUT, au schéma 1. Le triangle
        // OBJ, sans .mtl, porte le matériau par défaut des options de compilation, sans texture.
        dev.axion.asset.AssetLoader.FetchedMaterials materiaux =
                chargeur.materials(charge.index(), charge.generation());
        assertTrue(materiaux.ok(), () -> "table des matériaux refusée, code " + materiaux.code());
        assertEquals(1, materiaux.transfer().materials().size(), "le matériau par défaut");
        assertTrue(materiaux.transfer().textures().isEmpty(), "aucune texture");
        assertEquals(
                NativeBridge.E_INVALID_BUFFER,
                chargeur.texture(charge.index(), charge.generation(), 0).code(),
                "aucune texture à remettre");

        // Les octets d'un autre asset que celui annoncé sont refusés (E-2002) ; rien
        // n'est rangé, et les tampons sont relâchés quand même.
        assertEquals(NativeBridge.E_INVALID_BUFFER, chargeur.load(0x9999L, triangle).code());

        // Rendu, puis périmé (R-110) : la fermeture finale ne verra aucun handle.
        assertEquals(NativeBridge.OK, chargeur.unload(charge.index(), charge.generation()));
        assertEquals(
                NativeBridge.E_INVALID_HANDLE,
                chargeur.unload(charge.index(), charge.generation()));

        // ADR-122 : un glTF au matériau texturé, compilé puis chargé par la vraie JNI — ce qui
        // prouve l'enregistrement d'assetMaterials et d'assetTexture. La table nomme ses deux
        // textures ; le PNG embarqué revient seul, octet pour octet ; la ressource n'a que
        // son chemin, que Java résoudra contre le répertoire du modèle.
        byte[] png = png2x2();
        byte[] panneau = compiler(
                reprise, 0x4444L, dev.axion.asset.SourceFormats.GLTF, gltfTexture(png));
        dev.axion.asset.AssetLoader.Loaded chargePanneau = chargeur.load(0x4444L, panneau);
        assertTrue(chargePanneau.ok(), () -> "chargement refusé, code " + chargePanneau.code());
        dev.axion.asset.AssetLoader.FetchedMaterials table =
                chargeur.materials(chargePanneau.index(), chargePanneau.generation());
        assertTrue(table.ok(), () -> "table des matériaux refusée, code " + table.code());
        assertEquals(1, table.transfer().materials().size());
        assertEquals(2, table.transfer().textures().size());
        dev.axion.asset.MaterialTransfer.Material peint = table.transfer().materials().get(0);
        dev.axion.asset.MaterialTransfer.Texture albedo =
                table.transfer().textures().get(peint.albedoTexture());
        assertTrue(albedo.embedded());
        assertEquals(2, albedo.width());
        assertEquals(2, albedo.height());
        assertEquals(png.length, albedo.size());
        assertEquals(1, albedo.sampler() & 3, "filtrage au plus proche");
        dev.axion.asset.MaterialTransfer.Texture lueur =
                table.transfer().textures().get(peint.emissiveTexture());
        assertEquals("tex/lueur.png", lueur.path());

        dev.axion.asset.AssetLoader.FetchedTexture octets =
                chargeur.texture(chargePanneau.index(), chargePanneau.generation(), peint.albedoTexture());
        assertTrue(octets.ok(), () -> "texture refusée, code " + octets.code());
        assertArrayEquals(png, octets.png());
        assertEquals(
                NativeBridge.E_INVALID_BUFFER,
                chargeur.texture(chargePanneau.index(), chargePanneau.generation(), peint.emissiveTexture()).code(),
                "une ressource ne voyage pas par le natif");
        assertEquals(
                NativeBridge.E_INVALID_BUFFER,
                chargeur.texture(chargePanneau.index(), chargePanneau.generation(), -1).code(),
                "un rang négatif ne désigne aucune texture");

        assertEquals(NativeBridge.OK, chargeur.unload(chargePanneau.index(), chargePanneau.generation()));
        assertEquals(
                NativeBridge.E_INVALID_HANDLE,
                chargeur.materials(chargePanneau.index(), chargePanneau.generation()).code(),
                "handle périmé");
        assertEquals(
                NativeBridge.E_INVALID_HANDLE,
                chargeur.texture(chargePanneau.index(), chargePanneau.generation(), peint.albedoTexture()).code(),
                "handle périmé");

        // R-502 : l'export des métriques traverse la frontière et porte les
        // métriques de budget qu'INV-19 exige.
        String metriques = NativeBridge.metricsJson(reprise);
        assertTrue(metriques.startsWith("{"), metriques);
        assertTrue(metriques.contains("axion.budget.sim_ns_per_tick.consumed"), metriques);
        assertTrue(metriques.contains("axion.jobs.workers"), metriques);
        // ADR-123 §9 : les jauges de dégradation que Java relève sont dans l'export réel, au
        // palier NORMAL tant qu'aucune fenêtre n'a pesé.
        dev.axion.physics.DegradationGauges jauges = dev.axion.physics.DegradationGauges.parse(metriques);
        assertNotNull(jauges, metriques);
        assertEquals(0, jauges.level(), "palier NORMAL");

        // T-190 (suite) : le cycle de simulation IF-03 traverse la frontière via
        // NativeSimulation, sur la vraie bibliothèque. C'est le chemin qu'empruntera
        // le thread autoritatif à chaque tick.
        dev.axion.physics.NativeSimulation simulation =
                new dev.axion.physics.NativeSimulation(reprise);

        // Un tick à vide : aucune commande, aucun corps encore créé. Le cycle
        // avance et récolte un état vide.
        dev.axion.physics.CollectResult vide =
                simulation.tick(1L, new dev.axion.physics.SimCommandStream(), 0L);
        assertTrue(vide.ok(), () -> "collect à vide refusé, code " + vide.code());
        assertEquals(0, vide.stateCount(), "aucun corps");
        assertEquals(0, vide.eventCount(), "aucun événement");
        assertTrue(vide.bodies().isEmpty());
        assertTrue(vide.events().isEmpty());

        // Un tick portant une commande SET_DIMENSION_ENV : le flux SimIn est écrit,
        // soumis et appliqué (le natif crée la dimension). Toujours aucun corps,
        // mais tout le chemin submit→collect avec commande est traversé.
        dev.axion.physics.SimCommandStream commandes =
                new dev.axion.physics.SimCommandStream()
                        .setDimensionEnv(
                                0L,
                                new float[] {0.0f, -9.81f, 0.0f},
                                new float[] {0.0f, 0.0f, 0.0f},
                                0.0f,
                                0.0f,
                                false);
        dev.axion.physics.CollectResult apresCommande = simulation.tick(2L, commandes, 0L);
        assertTrue(apresCommande.ok(), () -> "collect après commande refusé, code " + apresCommande.code());
        assertEquals(0, apresCommande.stateCount(), "aucun corps créé par SET_DIMENSION_ENV");
        // C-15 : le pas de ce monde est décomposé dans l'export réel, sous les noms que lit le
        // journal des ticks lents.
        dev.axion.physics.StepBreakdown pasDecompose =
                dev.axion.physics.StepBreakdown.parse(NativeBridge.metricsJson(reprise));
        assertNotNull(pasDecompose, "le pas décomposé est dans l'export");
        assertTrue(pasDecompose.stepNs() > 0, "temps mural du pas mesuré");

        // T-310/T-311 : CREATE_ASSEMBLY de bout en bout (C-32, Option A, ADR-115).
        // Un glTF à node collider est compilé, Java localise sa section PHYS et
        // l'envoie dans une commande CREATE_ASSEMBLY ; le natif crée un corps
        // dynamique qui, ticks suivants, tombe sous la gravité. C'est le chemin
        // complet colliders compilés → corps qui bouge en jeu.
        byte[] gltf = GLTF_COLLIDER.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int jobCollider = compilateur.submit(
                0x4343L, dev.axion.asset.SourceFormats.GLTF, gltf, new byte[0],
                dev.axion.asset.SiblingFiles.NONE);
        assertTrue(jobCollider > 0, () -> "compilation du glTF collider refusée, code " + jobCollider);

        dev.axion.asset.AssetCompiler.CompileStatus etatCollider = attendre(compilateur, jobCollider);
        assertEquals(
                dev.axion.asset.AssetState.COMPILED,
                etatCollider.state(),
                () -> "compilation du glTF collider échouée");
        byte[] a3d = etatCollider.payload();

        // Les tampons de compilation sont relâchés (R-322), avant d'ouvrir un cycle.
        assertEquals(
                NativeBridge.OK,
                NativeBridge.release(reprise, BufferKinds.ASSET_IN, lireGeneration(reprise, BufferKinds.ASSET_IN)));
        assertEquals(
                NativeBridge.OK,
                NativeBridge.release(reprise, BufferKinds.ASSET_OUT, lireGeneration(reprise, BufferKinds.ASSET_OUT)));

        // Java détient l'A3D et en extrait la section PHYS (chemin de spawn réel).
        byte[] phys = dev.axion.asset.A3dSections.section(a3d, "PHYS");
        assertNotNull(phys, "section PHYS absente de l'A3D compilé");

        // Spawn d'un corps dynamique haut dans la dimension 0.
        dev.axion.physics.SimCommandStream spawn = new dev.axion.physics.SimCommandStream()
                .createAssembly(
                        1,
                        1,
                        0L,
                        new double[] {0.0, 100.0, 0.0},
                        new float[] {0.0f, 0.0f, 0.0f, 1.0f},
                        dev.axion.physics.BodyDescription.undeclared(
                                dev.axion.physics.SimCommandStream.BODY_DYNAMIC),
                        phys)
                // ADR-123 : comme en jeu, un joueur près du corps le garde éveillé (R-612).
                .setObservers(0L, JOUEUR_PRES_DU_CORPS);
        dev.axion.physics.CollectResult cree = simulation.tick(3L, spawn, 0L);
        assertTrue(cree.ok(), () -> "collect après CREATE_ASSEMBLY refusé, code " + cree.code());
        assertEquals(1, cree.stateCount(), "un corps créé depuis la section PHYS");
        dev.axion.physics.BodyState corps = cree.bodies().get(0);
        assertEquals(1, corps.handleIndex(), "le corps est routé sur son handle");
        assertEquals(1, corps.handleGeneration());
        double yDepart = corps.position()[1];

        // ADR-120 : l'emprise suit l'état dans SIM_OUT (schéma 1). Le collider auto_box
        // du triangle couvre [0, 1]³ dans l'asset ; le spawn est sans rotation, d'où une
        // emprise de (0, 0, 0) à (1, 1, 1) relative à la position du corps.
        assertEquals(1, cree.bounds().size(), "une emprise par état");
        assertArrayEquals(new float[] {0.0f, 0.0f, 0.0f}, cree.bounds().get(0).min(), 1.0e-5f);
        assertArrayEquals(new float[] {1.0f, 1.0f, 1.0f}, cree.bounds().get(0).max(), 1.0e-5f);
        ByteBuffer simOut = NativeBridge.acquire(reprise, BufferKinds.SIM_OUT, 0);
        assertNotNull(simOut);
        assertEquals(
                BufferKinds.SIM_OUT_SCHEMA,
                simOut.getInt(12),
                "l'en-tête de SIM_OUT annonce le schéma du registre");
        assertEquals(NativeBridge.OK, NativeBridge.release(reprise, BufferKinds.SIM_OUT, simOut.getInt(8)));

        // ADR-121 : la géométrie de l'overlay `colliders`, par la vraie JNI. Le collider
        // auto_box du triangle couvre [0, 1]³ en repère du corps : 12 arêtes dont toutes
        // les coordonnées valent 0 ou 1, quelle que soit la pose du corps.
        dev.axion.debug.NativeDebugLoader debug = new dev.axion.debug.NativeDebugLoader(reprise);
        dev.axion.debug.NativeDebugLoader.Fetched trace =
                debug.fetch(NativeBridge.OVERLAY_COLLIDERS, 0L, 0.0, 100.0, 0.0, 1024);
        assertTrue(trace.ok(), () -> "géométrie de debug refusée, code " + trace.code());
        assertEquals(1, trace.geometry().ranges().size(), "un corps d'assembly tracé");
        java.util.List<dev.axion.debug.DebugGeometry.Range> duCorps = trace.geometry().bodyRangesOf(1, 1);
        assertEquals(1, duCorps.size(), "tracé sous le handle du corps");
        dev.axion.debug.DebugGeometry.Range trace1 = duCorps.get(0);
        assertEquals(dev.axion.debug.DebugGeometry.FRAME_BODY, trace1.frame());
        assertEquals(12, trace1.segmentCount());
        for (float coordonnee : trace1.segments()) {
            assertTrue(
                    Math.abs(coordonnee) < 1.0e-5f || Math.abs(coordonnee - 1.0f) < 1.0e-5f,
                    () -> "coordonnée hors des coins du cube unité : " + coordonnee);
        }
        // A1 d'ADR-121 : l'AABB du même corps, en repère d'origine, à part des plages du corps.
        dev.axion.debug.NativeDebugLoader.Fetched boite =
                debug.fetch(NativeBridge.OVERLAY_AABB, 0L, 0.0, 100.0, 0.0, 1024);
        assertTrue(boite.ok(), () -> "AABB refusée, code " + boite.code());
        assertEquals(1, boite.geometry().originRanges().size());
        assertTrue(boite.geometry().bodyRangesOf(1, 1).isEmpty());
        // Un overlay que cette version ne trace pas (bones, rang 7) est refusé net.
        assertEquals(
                NativeBridge.E_INVALID_BUFFER,
                debug.fetch(1L << 7, 0L, 0.0, 100.0, 0.0, 1024).code());

        // Ticks sans autre commande que le joueur, déclaré chaque tick comme en jeu : le corps
        // tombe sous la gravité par défaut (−9,81 m/s²). Sans joueur, il dormirait (R-612).
        // T-012 (INV-04) : ces ticks sont le régime établi d'un serveur — un corps, le joueur déclaré
        // à chaque tick —, et le cycle de simulation y est tout le trafic natif du tick : assets
        // compilés, overlays éteints, métriques lues aux seuls ticks lents. Chacun traverse la
        // frontière moins de 32 fois.
        long piresTraversees = 0;
        double yCourant = yDepart;
        for (int t = 0; t < 30; t++) {
            long traverseesAvant = NativeBridge.crossings();
            dev.axion.physics.CollectResult pas = simulation.tick(
                    4L + t, new dev.axion.physics.SimCommandStream().setObservers(0L, JOUEUR_PRES_DU_CORPS), 0L);
            piresTraversees = Math.max(piresTraversees, NativeBridge.crossings() - traverseesAvant);
            assertTrue(pas.ok(), () -> "collect refusé pendant la chute, code " + pas.code());
            assertEquals(1, pas.stateCount(), "le corps persiste d'un tick à l'autre");
            yCourant = pas.bodies().get(0).position()[1];
        }
        double yFinal = yCourant;
        assertTrue(
                yFinal < yDepart - 0.1,
                () -> "le corps dynamique doit tomber : y " + yDepart + " -> " + yFinal);
        // Chute libre sans couple : l'emprise, relative à la position, n'a pas bougé.
        dev.axion.physics.CollectResult apresChute = simulation.tick(
                40L, new dev.axion.physics.SimCommandStream().setObservers(0L, JOUEUR_PRES_DU_CORPS), 0L);
        assertArrayEquals(new float[] {1.0f, 1.0f, 1.0f}, apresChute.bounds().get(0).max(), 1.0e-5f);

        // ADR-123 §2 et §5 : les joueurs et les entités écrits par Java sont lus par le natif —
        // un flux mal formé serait refusé en entier. Une entité-boîte posée sous le corps qui
        // tombe l'arrête, et le contact la nomme : l'assembly en a, l'entité en b, génération
        // 0, bit CONTACT_OTHER_ENTITY.
        double ySousLeCorps = apresChute.bodies().get(0).position()[1] - 2.0;
        dev.axion.physics.SimCommandStream.EntityProxy dalle = new dev.axion.physics.SimCommandStream.EntityProxy(
                77,
                new double[] {0.5, ySousLeCorps, 0.5},
                new float[] {2.0f, 0.5f, 2.0f},
                new float[] {0.0f, 0.0f, 0.0f},
                dev.axion.physics.SimCommandStream.PROXY_BOX);
        // Le choc lui-même remonte en CONTACT_IMPULSE : au sous-pas du début du contact, ou au
        // suivant si ce début n'est encore qu'un contact prédit. On garde les événements de son
        // tick et la vitesse de chute du tick d'avant.
        dev.axion.physics.PhysicsEvent contact = null;
        java.util.List<dev.axion.physics.PhysicsEvent> evenementsDuChoc = null;
        double vitesseAvantChoc = 0.0;
        for (int t = 0; t < 40 && evenementsDuChoc == null; t++) {
            dev.axion.physics.SimCommandStream entites = new dev.axion.physics.SimCommandStream()
                    .setObservers(0L, new double[][] {{0.0, ySousLeCorps, 0.0}})
                    .setEntityProxies(0L, java.util.List.of(dalle));
            long traverseesAvant = NativeBridge.crossings();
            dev.axion.physics.CollectResult pas = simulation.tick(41L + t, entites, 0L);
            piresTraversees = Math.max(piresTraversees, NativeBridge.crossings() - traverseesAvant);
            assertTrue(pas.ok(), () -> "collect avec joueurs et entités refusé, code " + pas.code());
            for (dev.axion.physics.PhysicsEvent evenement : pas.events()) {
                if (contact == null
                        && evenement.kind() == dev.axion.physics.PhysicsEventCodes.Kind.CONTACT_START
                        && (evenement.data() & dev.axion.physics.PhysicsEventCodes.Data.CONTACT_OTHER_ENTITY) != 0) {
                    contact = evenement;
                }
            }
            if (pas.events().stream().anyMatch(dev.axion.physics.EntityImpacts::concernsEntity)) {
                evenementsDuChoc = pas.events();
            } else {
                vitesseAvantChoc = -pas.bodies().stream()
                        .filter(corpsChute -> corpsChute.handleIndex() == 1)
                        .findFirst()
                        .orElseThrow()
                        .linearVelocity()[1];
            }
        }
        assertNotNull(contact, "le corps qui tombe heurte l'entité");
        assertEquals(1, contact.assemblyAIndex(), "l'assembly en a");
        assertEquals(77, contact.assemblyBIndex(), "l'entité en b");
        assertEquals(0, contact.assemblyBGeneration(), "génération 0 : pas une assembly");

        // R-1010 et ADR-123 §7 sur les vrais événements du natif : le choc pousse l'entité de
        // l'assembly vers elle — vers le bas —, et sa vitesse d'approche est celle de la chute.
        assertNotNull(evenementsDuChoc, "le choc remonte en CONTACT_IMPULSE");
        java.util.List<dev.axion.physics.EntityImpacts.Impact> impacts =
                dev.axion.physics.EntityImpacts.gather(evenementsDuChoc);
        assertEquals(1, impacts.size(), "une entité touchée");
        dev.axion.physics.EntityImpacts.Impact impact = impacts.get(0);
        assertEquals(77, impact.entity());
        assertEquals(1, impact.assemblyIndex());
        assertEquals(1, impact.assemblyGeneration());
        double[] poussee = impact.velocityChange(dev.axion.physics.EntityImpacts.massOf(4.0 * 1.0 * 4.0));
        assertTrue(poussee[1] < 0.0, () -> "poussée vers le bas attendue : " + java.util.Arrays.toString(poussee));
        assertTrue(Math.abs(poussee[0]) <= 1.0e-3 * -poussee[1] && Math.abs(poussee[2]) <= 1.0e-3 * -poussee[1],
                () -> "face contre face, la poussée est verticale : " + java.util.Arrays.toString(poussee));
        double approche = impact.approachSpeed();
        double avant = vitesseAvantChoc;
        // Mesurée avant la résolution du sous-pas du choc : la chute du tick d'avant, plus au plus
        // un tick de gravité (0,49 m/s).
        assertTrue(avant > 5.0, () -> "le corps tombe vite au contact : " + avant + " m/s");
        assertTrue(approche > avant - 0.05 && approche < avant + 0.6,
                () -> "vitesse d'approche " + approche + " m/s, chute " + avant + " m/s");
        assertTrue(dev.axion.physics.EntityImpacts.fallDamage(approche) > 0.0, "un tel choc blesse");

        // T-012 : le pire tick établi, chute comme choc (états et événements), sous la borne d'INV-04.
        long pire = piresTraversees;
        assertTrue(pire > 0, "le compteur de traversées avance");
        assertTrue(pire < 32, () -> "INV-04 : " + pire + " traversées FFI sur un tick établi, 32 au plus");

        // ADR-151 D7 : une charnière par le chemin du jeu — glTF compilé, definition lue par
        // AssemblyBodies, CREATE_ASSEMBLY avec ses joints —, puis son état dans SIM_OUT et les deux
        // commandes du grippage et de la rupture, par la vraie JNI. Dans une autre dimension, loin
        // du premier corps.
        byte[] a3dCharniere = compiler(
                reprise, 0x4345L, dev.axion.asset.SourceFormats.GLTF,
                GLTF_DEUX_CORPS.getBytes(StandardCharsets.UTF_8));
        byte[] nodesCharniere = dev.axion.asset.A3dSections.section(a3dCharniere, "NODE");
        byte[] physCharniere = dev.axion.asset.A3dSections.section(a3dCharniere, "PHYS");
        assertNotNull(nodesCharniere, "section NODE absente de l'A3D compilé");
        assertNotNull(physCharniere, "section PHYS absente de l'A3D compilé");
        com.google.gson.JsonObject definitionCharniere = com.google.gson.JsonParser.parseString(
                        "{\"bodies\":[{\"name\":\"cadre\",\"root_node\":\"cadre\"}],"
                                + "\"joints\":[{\"type\":\"revolute\",\"a\":\"cadre\",\"b_node\":\"porte\","
                                + "\"axis\":[0,1,0],\"limits\":[-1.0,1.0],"
                                + "\"jam\":{\"curve\":[[1.0,0.0]],\"friction\":5.0}}]}")
                .getAsJsonObject();
        dev.axion.physics.AssemblyBodies.Layout charniere = dev.axion.physics.AssemblyBodies.of(
                definitionCharniere,
                nodesCharniere,
                dev.axion.physics.BodyDescription.undeclared(dev.axion.physics.SimCommandStream.BODY_DYNAMIC),
                true);
        assertNotNull(charniere, "la definition se résout sur les nodes de l'asset");
        assertEquals(2, charniere.bodies().size(), "le cadre déclaré, la porte implicite");
        assertEquals(1, charniere.joints().size());
        double[][] joueurPresDeLaCharniere = {{0.0, 100.0, 0.0}};
        dev.axion.physics.CollectResult relie = simulation.tick(
                200L,
                new dev.axion.physics.SimCommandStream()
                        .createAssembly(
                                2,
                                1,
                                5L,
                                new double[] {0.0, 100.0, 0.0},
                                new float[] {0.0f, 0.0f, 0.0f, 1.0f},
                                charniere.bodies(),
                                charniere.joints(),
                                charniere.jamPoints(),
                                charniere.assemblyMass(),
                                charniere.selfCollision(),
                                nodesCharniere,
                                physCharniere)
                        .setObservers(5L, joueurPresDeLaCharniere),
                0L);
        assertTrue(relie.ok(), () -> "collect après la charnière refusé, code " + relie.code());
        assertEquals(
                2,
                relie.bodies().stream().filter(etatCorps -> etatCorps.handleIndex() == 2).count(),
                "deux corps sous le handle de la charnière");
        assertEquals(1, relie.joints().size(), "un état par joint d'assembly éveillée");
        dev.axion.physics.JointState libre = relie.joints().get(0);
        assertEquals(2, libre.handleIndex());
        assertEquals(1, libre.handleGeneration());
        assertEquals(0, libre.index(), "rang du joint dans la definition");
        assertEquals(0, libre.flags(), "ni rompu ni grippé");
        assertEquals(1.0f, libre.fraction(), 0.0f, "toute sa plage");

        // JAM_JOINT : la courbe va de (0, 1) à (1, 0) ; un demi-bloc de désalignement laisse la
        // moitié de la plage, et l'entrée dans l'état grippé part en événement.
        dev.axion.physics.CollectResult grippe = simulation.tick(
                201L,
                new dev.axion.physics.SimCommandStream()
                        .jamJoint(2, 1, 0, 0.5f)
                        .setObservers(5L, joueurPresDeLaCharniere),
                0L);
        assertTrue(grippe.ok(), () -> "collect après JAM_JOINT refusé, code " + grippe.code());
        assertTrue(grippe.joints().get(0).jammed(), "grippé");
        assertEquals(0.5f, grippe.joints().get(0).fraction(), 1.0e-6f);
        assertTrue(
                grippe.events().stream().anyMatch(evenement -> evenement.kind()
                                == dev.axion.physics.PhysicsEventCodes.Kind.JOINT_JAMMED
                        && evenement.assemblyAIndex() == 2
                        && evenement.data() == 0),
                () -> "JOINT_JAMMED attendu : " + grippe.events());

        // Un désalignement nul rend toute la plage (R-632).
        dev.axion.physics.CollectResult rendu = simulation.tick(
                202L,
                new dev.axion.physics.SimCommandStream()
                        .jamJoint(2, 1, 0, 0.0f)
                        .setObservers(5L, joueurPresDeLaCharniere),
                0L);
        assertEquals(0, rendu.joints().get(0).flags(), "plus grippé");
        assertEquals(1.0f, rendu.joints().get(0).fraction(), 0.0f);

        // BREAK_JOINT : rompu, rapporté tant que l'assembly est éveillée, et l'événement part.
        dev.axion.physics.CollectResult rompu = simulation.tick(
                203L,
                new dev.axion.physics.SimCommandStream()
                        .breakJoint(2, 1, 0)
                        .setObservers(5L, joueurPresDeLaCharniere),
                0L);
        assertTrue(rompu.ok(), () -> "collect après BREAK_JOINT refusé, code " + rompu.code());
        assertEquals(1, rompu.joints().size(), "un joint rompu reste rapporté");
        assertTrue(rompu.joints().get(0).broken(), "rompu");
        assertTrue(
                rompu.events().stream().anyMatch(evenement -> evenement.kind()
                                == dev.axion.physics.PhysicsEventCodes.Kind.JOINT_BROKEN
                        && evenement.data() == 0),
                () -> "JOINT_BROKEN attendu : " + rompu.events());

        // ADR-151 D7 : les overlays joints et limits, par la vraie JNI. La charnière rompue reste tracée, ses
        // ancrages marqués rompus, et n'a plus de plage.
        dev.axion.debug.NativeDebugLoader.Fetched joints = debug.fetch(
                NativeBridge.OVERLAY_JOINTS | NativeBridge.OVERLAY_LIMITS, 5L, 0.0, 100.0, 0.0, 1024);
        assertTrue(joints.ok(), () -> "overlays des joints refusés, code " + joints.code());
        java.util.List<dev.axion.debug.DebugGeometry.Range> charnieres = joints.geometry().originRanges().stream()
                .filter(plage -> plage.handleIndex() == 2)
                .toList();
        assertEquals(1, charnieres.size(), () -> "une plage, joints seul : " + charnieres);
        dev.axion.debug.DebugGeometry.Range plageJoint = charnieres.get(0);
        assertEquals(dev.axion.debug.DebugOverlays.Overlay.JOINTS.rank(), plageJoint.overlay());
        assertEquals(dev.axion.debug.DebugGeometry.FRAME_ORIGIN, plageJoint.frame());
        assertTrue((plageJoint.flags() & dev.axion.debug.DebugGeometry.JOINT_BROKEN) != 0, "marquée rompue");
        assertEquals(5, plageJoint.segmentCount(), "la croix de l'ancrage, le lien des ancrages, l'axe");

        // ADR-151 D9 : le repère d'un corps secondaire, tel que BodySkeleton le calcule pour le dessin, est
        // celui où le natif pose ce corps. Sans gravité, rien ne le bouge : à sa création, sa pose monde est
        // la pose de spawn composée avec ce repère, sur une chaîne tournée et mise à l'échelle.
        byte[] a3dBras = compiler(
                reprise, 0x4346L, dev.axion.asset.SourceFormats.GLTF, GLTF_BRAS.getBytes(StandardCharsets.UTF_8));
        byte[] nodesBras = dev.axion.asset.A3dSections.section(a3dBras, "NODE");
        byte[] physBras = dev.axion.asset.A3dSections.section(a3dBras, "PHYS");
        dev.axion.physics.AssemblyBodies.Layout bras = dev.axion.physics.AssemblyBodies.of(
                com.google.gson.JsonParser.parseString(
                                "{\"bodies\":[{\"name\":\"socle\",\"root_node\":\"socle\"},"
                                        + "{\"name\":\"bras\",\"root_node\":\"bras\"}]}")
                        .getAsJsonObject(),
                nodesBras,
                dev.axion.physics.BodyDescription.undeclared(dev.axion.physics.SimCommandStream.BODY_DYNAMIC),
                true);
        assertNotNull(bras, "les deux corps se résolvent sur les nodes de l'asset");
        int noeudBras = bras.bodies().get(1).rootNode();
        dev.axion.physics.CollectResult pose = simulation.tick(
                300L,
                new dev.axion.physics.SimCommandStream()
                        .setDimensionEnv(6L, new float[3], new float[3], 0f, 0f, false)
                        .createAssembly(3, 1, 6L, new double[] {0.0, 100.0, 0.0},
                                new float[] {0.0f, 0.0f, 0.0f, 1.0f}, bras.bodies(), bras.joints(),
                                bras.jamPoints(), bras.assemblyMass(), false, nodesBras, physBras)
                        .setObservers(6L, new double[][] {{0.0, 100.0, 0.0}}),
                0L);
        dev.axion.physics.BodyState etatBras = pose.bodies().stream()
                .filter(etatCorps -> etatCorps.handleIndex() == 3 && etatCorps.node() == noeudBras)
                .findFirst()
                .orElseThrow(() -> new AssertionError("le bras n'est pas rapporté : " + pose.bodies()));
        dev.axion.render.BodySkeleton squelette =
                dev.axion.render.BodySkeleton.of(nodesBras, bras.rootNode(), new int[] {noeudBras});
        assertNotNull(squelette, "la section NODE compilée se lit");
        float[] repere = squelette.frame(1);
        assertArrayEquals(new double[] {repere[0], 100.0 + repere[1], repere[2]}, etatBras.position(), 1e-4,
                "position du bras : celle du repère");
        assertArrayEquals(new double[] {0.0, 103.0, 0.0}, etatBras.position(), 1e-4,
                "(0, 1, 0) + quart de tour en z de (1, 0, 0) × 2, au-dessus du spawn");
        float[] q = etatBras.rotation();
        double alignement = Math.abs(q[0] * repere[3] + q[1] * repere[4] + q[2] * repere[5] + q[3] * repere[6]);
        assertEquals(1.0, alignement, 1e-5, () -> "rotation du bras " + java.util.Arrays.toString(q)
                + ", repère " + java.util.Arrays.toString(repere));

        // Annuler après un cycle clos est inoffensif.
        assertEquals(NativeBridge.OK, simulation.cancel());

        // Les tampons du cycle ont été relâchés à chaque tick : la fermeture ne
        // signale aucune fuite (R-322).
        assertEquals(NativeBridge.OK, NativeBridge.close(reprise));
    }
}
