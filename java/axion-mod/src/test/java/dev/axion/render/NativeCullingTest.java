package dev.axion.render;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.axion.asset.AssetCompiler;
import dev.axion.asset.AssetLoader;
import dev.axion.asset.AssetState;
import dev.axion.asset.NativeAssetCompiler;
import dev.axion.asset.NativeAssetLoader;
import dev.axion.asset.SiblingFiles;
import dev.axion.asset.SourceFormats;
import dev.axion.bootstrap.NativeLoadResult;
import dev.axion.bootstrap.NativeLoader;
import dev.axion.bridge.BufferKinds;
import dev.axion.bridge.NativeBridge;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/** IF-05 côté Java (ADR-154) : les octets de la frontière, puis une frame culée par le natif. */
class NativeCullingTest {

    /** Un cube unité, de (0, 0, 0) à (1, 1, 1). */
    private static final String CUBE = String.join("\n",
            "v 0 0 0", "v 1 0 0", "v 1 1 0", "v 0 1 0", "v 0 0 1", "v 1 0 1", "v 1 1 1", "v 0 1 1",
            "f 1 2 3", "f 1 3 4", "f 5 7 6", "f 5 8 7", "f 1 5 6", "f 1 6 2",
            "f 4 3 7", "f 4 7 8", "f 1 4 8", "f 1 8 5", "f 2 6 7", "f 2 7 3", "");

    private static final long ASSET_ID = 0x0A51_0C0B_E000_1154L;

    private static final float[] IDENTITY = {0f, 0f, 0f, 1f};

    /** Répertoire d'extraction, jamais nettoyé : la bibliothèque chargée y reste verrouillée. */
    @TempDir(cleanup = CleanupMode.NEVER)
    Path gameDir;

    /** Projection en perspective d'OpenGL, 70° de champ vertical, comme celle de Minecraft. */
    private static float[] perspective() {
        float f = (float) (1.0 / Math.tan(Math.toRadians(70) / 2));
        float near = 0.05f;
        float far = 1024f;
        float[] m = new float[16];
        m[0] = f / (16f / 9f);
        m[5] = f;
        m[10] = (far + near) / (near - far);
        m[11] = -1f;
        m[14] = 2f * far * near / (near - far);
        return m;
    }

    @Test
    @DisplayName("ADR-154 D3 : caméra et candidat à la disposition du natif")
    void disposition() {
        float[] projection = perspective();
        byte[] camera = RenderPrepCodec.camera(projection, new float[] {0.1f, 0.2f, 0.3f, 0.9f}, 1920, 1080,
                96f, -1, 2, 7);
        assertEquals(RenderPrepCodec.CAMERA_BYTES, camera.length);
        ByteBuffer c = ByteBuffer.wrap(camera).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(projection[14], c.getFloat(14 * 4));
        assertEquals(0.9f, c.getFloat(76));
        assertEquals(1920, c.getInt(80));
        assertEquals(1080, c.getInt(84));
        assertEquals(96f, c.getFloat(88));
        assertEquals(-1, c.getInt(92));
        assertEquals(2, c.getInt(96));
        assertEquals(7, c.getInt(100), "le nombre de candidats, au premier mot réservé");
        assertEquals(0, c.getInt(104));

        ByteBuffer candidate = ByteBuffer.allocate(RenderPrepCodec.CANDIDATE_BYTES + 8).order(ByteOrder.LITTLE_ENDIAN);
        RenderPrepCodec.putCandidate(candidate, 8, 11, 3, 5, RenderPrepCodec.NO_CULL, 1f, 2f, -3f, 1.5f,
                new float[] {0f, 0.6f, 0f, 0.8f});
        assertArrayEquals(new int[] {11, 3, 5, 1}, new int[] {
            candidate.getInt(8), candidate.getInt(12), candidate.getInt(16), candidate.getInt(20)});
        assertEquals(-3f, candidate.getFloat(8 + 24));
        assertEquals(1.5f, candidate.getFloat(8 + 28));
        assertEquals(0.8f, candidate.getFloat(8 + 44));
    }

    @Test
    @DisplayName("IF-05 à travers JNI : un cube chargé, ses candidats culés par le natif")
    void uneFrameCuleeParLeNatif() {
        NativeLoadResult loaded = NativeLoader.load(gameDir);
        assumeTrue(loaded instanceof NativeLoadResult.Loaded,
                () -> "bibliotheque native absente — la produire avec : ./gradlew :axion-mod:packageNatives");
        long ctx = NativeBridge.initialize(new byte[0], NativeBridge.SIDE_CLIENT);
        assertTrue(ctx > 0, () -> "initialisation refusée, code " + ctx);
        try {
            byte[] a3d = compile(ctx);
            NativeAssetLoader loader = new NativeAssetLoader(ctx);
            AssetLoader.Loaded asset = loader.load(ASSET_ID, a3d);
            assertTrue(asset.ok(), () -> "chargement refusé, code " + asset.code());

            // Devant, derrière, d'un handle périmé, et derrière mais NO_CULL.
            float[][] positions = {{0f, 0f, -10f}, {0f, 0f, 10f}, {0f, 0f, -6f}, {0f, 0f, 10f}};
            int[] generations = {asset.generation(), asset.generation(), asset.generation() + 1, asset.generation()};
            int[] flags = {0, 0, 0, RenderPrepCodec.NO_CULL};
            byte[] camera = RenderPrepCodec.camera(perspective(), IDENTITY, 1920, 1080, 128f, 0, 0, 4);
            NativeCulling culling = new NativeCulling(ctx);
            NativeCulling.Outcome outcome = culling.cull(camera, 4, (buffer, offset, rank) ->
                    RenderPrepCodec.putCandidate(buffer, offset, 100 + rank, asset.index(), generations[rank],
                            flags[rank], positions[rank][0], positions[rank][1], positions[rank][2], 1f, IDENTITY),
                    1_000_000_000L);
            assertEquals(NativeBridge.OK, outcome.code());
            assertArrayEquals(new int[] {0, 3}, outcome.retained());
            assertEquals(0, outcome.flags());
            assertEquals(24, outcome.triangles(), "deux cubes de douze triangles");

            // Une caméra qui annonce plus de candidats que le tampon n'en tient est refusée.
            byte[] menteuse = RenderPrepCodec.camera(perspective(), IDENTITY, 1920, 1080, 128f, 0, 0, 1_000_000);
            assertEquals(NativeBridge.E_INVALID_BUFFER, culling.cull(menteuse, 1, (buffer, offset, rank) ->
                    RenderPrepCodec.putCandidate(buffer, offset, 1, asset.index(), asset.generation(), 0,
                            0f, 0f, -10f, 1f, IDENTITY), 1_000_000_000L).code());

            assertEquals(NativeBridge.OK, loader.unload(asset.index(), asset.generation()));
        } finally {
            // Tampons relâchés et frames rendues à chaque appel : l'arrêt ne signale aucune fuite.
            assertEquals(NativeBridge.OK, NativeBridge.close(ctx));
        }
    }

    /** Compile le cube par la vraie bibliothèque et relâche les tampons d'asset. */
    private static byte[] compile(long ctx) {
        NativeAssetCompiler compiler = new NativeAssetCompiler(ctx);
        int job = compiler.submit(ASSET_ID, SourceFormats.OBJ, CUBE.getBytes(StandardCharsets.UTF_8), new byte[0],
                SiblingFiles.NONE);
        assertTrue(job > 0, () -> "compilation refusée, code " + job);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        AssetCompiler.CompileStatus status = compiler.poll(job);
        while (status.state() == AssetState.COMPILING && System.nanoTime() - deadline < 0) {
            Thread.onSpinWait();
            status = compiler.poll(job);
        }
        assertEquals(AssetState.COMPILED, status.state(), "compilation échouée");
        for (int kind : new int[] {BufferKinds.ASSET_IN, BufferKinds.ASSET_OUT}) {
            ByteBuffer buffer = NativeBridge.acquire(ctx, kind, 0);
            assertEquals(NativeBridge.OK, NativeBridge.release(ctx, kind, buffer.getInt(8)));
        }
        return status.payload();
    }
}
