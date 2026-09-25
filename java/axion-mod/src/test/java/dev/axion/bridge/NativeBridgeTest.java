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
                    + "\"meshes\":[{\"name\":\"boite\",\"primitives\":[{\"attributes\":"
                    + "{\"POSITION\":0},\"indices\":1}]}],"
                    + "\"accessors\":[{\"bufferView\":0,\"componentType\":5126,\"count\":3,"
                    + "\"type\":\"VEC3\",\"min\":[0.0,0.0,0.0],\"max\":[1.0,1.0,1.0]},"
                    + "{\"bufferView\":1,\"componentType\":5123,\"count\":3,\"type\":\"SCALAR\"}],"
                    + "\"bufferViews\":[{\"buffer\":0,\"byteOffset\":0,\"byteLength\":36},"
                    + "{\"buffer\":0,\"byteOffset\":36,\"byteLength\":6}],"
                    + "\"buffers\":[{\"byteLength\":42,\"uri\":\"data:application/octet-stream;base64,"
                    + "AAAAAAAAAAAAAAAAAACAPwAAAAAAAAAAAAAAAAAAgD8AAIA/AAABAAIA\"}]}";

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

        // T-210 : une compilation d'asset traverse la frontière, sur la vraie
        // bibliothèque. C'est le chemin complet de C-20 : écrire la source,
        // lancer, sonder, relire.
        dev.axion.asset.NativeAssetCompiler compilateur =
                new dev.axion.asset.NativeAssetCompiler(reprise);
        byte[] source = String.join(
                        "\n", "v 0 0 0", "v 1 0 0", "v 0 1 0", "f 1 2 3", "")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int job = compilateur.submit(0x4242L, dev.axion.asset.SourceFormats.OBJ, source);
        assertTrue(job > 0, () -> "compilation refusée, code " + job);

        dev.axion.asset.AssetCompiler.CompileStatus etat = null;
        for (int essai = 0; essai < 100_000; essai++) {
            etat = compilateur.poll(job);
            if (etat.state() != dev.axion.asset.AssetState.COMPILING) {
                break;
            }
            Thread.onSpinWait();
        }
        assertEquals(
                dev.axion.asset.AssetState.COMPILED,
                etat.state(),
                () -> "compilation échouée");
        assertTrue(etat.size() > 0, "asset compilé vide");

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

        // R-502 : l'export des métriques traverse la frontière et porte les
        // métriques de budget qu'INV-19 exige.
        String metriques = NativeBridge.metricsJson(reprise);
        assertTrue(metriques.startsWith("{"), metriques);
        assertTrue(metriques.contains("axion.budget.sim_ns_per_tick.consumed"), metriques);
        assertTrue(metriques.contains("axion.jobs.workers"), metriques);

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

        // T-310/T-311 : CREATE_ASSEMBLY de bout en bout (C-32, Option A, ADR-115).
        // Un glTF à node collider est compilé, Java localise sa section PHYS et
        // l'envoie dans une commande CREATE_ASSEMBLY ; le natif crée un corps
        // dynamique qui, ticks suivants, tombe sous la gravité. C'est le chemin
        // complet colliders compilés → corps qui bouge en jeu.
        byte[] gltf = GLTF_COLLIDER.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int jobCollider = compilateur.submit(0x4343L, dev.axion.asset.SourceFormats.GLTF, gltf);
        assertTrue(jobCollider > 0, () -> "compilation du glTF collider refusée, code " + jobCollider);

        dev.axion.asset.AssetCompiler.CompileStatus etatCollider = null;
        for (int essai = 0; essai < 100_000; essai++) {
            etatCollider = compilateur.poll(jobCollider);
            if (etatCollider.state() != dev.axion.asset.AssetState.COMPILING) {
                break;
            }
            Thread.onSpinWait();
        }
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
                        dev.axion.physics.SimCommandStream.BODY_DYNAMIC,
                        phys);
        dev.axion.physics.CollectResult cree = simulation.tick(3L, spawn, 0L);
        assertTrue(cree.ok(), () -> "collect après CREATE_ASSEMBLY refusé, code " + cree.code());
        assertEquals(1, cree.stateCount(), "un corps créé depuis la section PHYS");
        dev.axion.physics.BodyState corps = cree.bodies().get(0);
        assertEquals(1, corps.handleIndex(), "le corps est routé sur son handle");
        assertEquals(1, corps.handleGeneration());
        double yDepart = corps.position()[1];

        // Ticks à vide : le corps tombe sous la gravité par défaut (−9,81 m/s²).
        double yCourant = yDepart;
        for (int t = 0; t < 30; t++) {
            dev.axion.physics.CollectResult pas =
                    simulation.tick(4L + t, new dev.axion.physics.SimCommandStream(), 0L);
            assertTrue(pas.ok(), () -> "collect refusé pendant la chute, code " + pas.code());
            assertEquals(1, pas.stateCount(), "le corps persiste d'un tick à l'autre");
            yCourant = pas.bodies().get(0).position()[1];
        }
        double yFinal = yCourant;
        assertTrue(
                yFinal < yDepart - 0.1,
                () -> "le corps dynamique doit tomber : y " + yDepart + " -> " + yFinal);

        // Annuler après un cycle clos est inoffensif.
        assertEquals(NativeBridge.OK, simulation.cancel());

        // Les tampons du cycle ont été relâchés à chaque tick : la fermeture ne
        // signale aucune fuite (R-322).
        assertEquals(NativeBridge.OK, NativeBridge.close(reprise));
    }
}
