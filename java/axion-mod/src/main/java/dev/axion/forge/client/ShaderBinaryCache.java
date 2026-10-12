package dev.axion.forge.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.axion.render.ShaderBinaryFile;
import dev.axion.render.ShaderCacheKey;
import dev.axion.render.ShaderVariant;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL41;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Le cache binaire des programmes du backend natif (R-760, ADR-127 §4) : sous
 * {@code <gameDir>/axion/cache/shaders/}, un fichier par programme, nommé par sa clé
 * ({@link ShaderCacheKey} : sources, définitions, chaîne du pilote), au format de
 * {@link ShaderBinaryFile}.
 *
 * <p>Offert seulement si le pilote rend des binaires — OpenGL 4.1 ou
 * {@code GL_ARB_get_program_binary}, et au moins un format. Un fichier illisible, ou un binaire que
 * le pilote refuse, est oublié : le programme est recompilé, et son fichier remplacé. Les fichiers
 * s'écrivent sur un thread à eux — rien d'OpenGL n'y passe (INV-12) —, par un fichier temporaire
 * renommé : un fichier à moitié écrit n'est jamais lu. Une variante à la demande s'y lit aussi
 * ({@link #read}) : lire un fichier tout juste écrit a pris jusqu'à 7,6 ms au render thread sur la
 * machine de Killian (banc du 2026-10-12, T-904), là où le binaire passé au pilote en prend moins
 * d'une. Lectures et écritures partagent ce thread, dans l'ordre : une lecture suit l'écriture du
 * même fichier.
 *
 * <p>Render thread seul, hors la lecture et l'écriture des fichiers.
 */
final class ShaderBinaryCache {

    private static final Logger LOGGER = LoggerFactory.getLogger("axion");

    private static final String SUFFIX = ".bin";

    /** Lecture et écriture des fichiers : dédiées, et démon, pour ne retenir ni le rendu ni la fermeture. */
    private static final Executor WRITER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "AXION shader cache");
        thread.setDaemon(true);
        return thread;
    });

    /** Répertoire du cache ; {@code null} : le pilote ne rend pas de binaires. */
    private final Path directory;

    private final String vendor;
    private final String renderer;
    private final String version;
    private int hits;
    private int misses;

    private ShaderBinaryCache(Path directory, String vendor, String renderer, String version) {
        this.directory = directory;
        this.vendor = vendor;
        this.renderer = renderer;
        this.version = version;
    }

    /**
     * {@return le cache, actif si le pilote rend des binaires}
     *
     * @param directory {@code <gameDir>/axion/cache/shaders/}
     */
    static ShaderBinaryCache open(Path directory) {
        RenderSystem.assertOnRenderThread();
        GLCapabilities capabilities = GL.getCapabilities();
        boolean binaries = (capabilities.OpenGL41 || capabilities.GL_ARB_get_program_binary)
                && GL11.glGetInteger(GL41.GL_NUM_PROGRAM_BINARY_FORMATS) > 0;
        return new ShaderBinaryCache(
                binaries ? directory : null,
                String.valueOf(GL11.glGetString(GL11.GL_VENDOR)),
                String.valueOf(GL11.glGetString(GL11.GL_RENDERER)),
                String.valueOf(GL11.glGetString(GL11.GL_VERSION)));
    }

    /** {@return vrai si le pilote rend des binaires : le cache sert} */
    boolean enabled() {
        return directory != null;
    }

    /**
     * {@return la clé d'un programme sous ce pilote}
     *
     * @param program nom du programme
     * @param variant ses définitions
     * @param vertexSource source des sommets
     * @param fragmentSource source des fragments, sans les définitions
     */
    String key(String program, ShaderVariant variant, String vertexSource, String fragmentSource) {
        return ShaderCacheKey.of(program, variant, vertexSource, fragmentSource, vendor, renderer, version);
    }

    /**
     * {@return le programme relu du cache et lié par le pilote, ou 0 : absent, illisible ou refusé}
     *
     * @param key sa clé
     */
    int load(String key) {
        RenderSystem.assertOnRenderThread();
        if (!enabled()) {
            return 0;
        }
        return link(readFile(key));
    }

    /**
     * {@return le binaire d'un programme, lu et décodé hors du render thread ; vide : absent ou
     * illisible} À passer au pilote par {@link #link}, sur le render thread.
     *
     * @param key sa clé
     */
    CompletableFuture<Optional<ShaderBinaryFile.Binary>> read(String key) {
        if (!enabled()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return CompletableFuture.supplyAsync(() -> readFile(key), WRITER);
    }

    /** {@return le binaire d'un programme, lu et décodé ; vide : absent ou illisible} Sur tout thread. */
    private Optional<ShaderBinaryFile.Binary> readFile(String key) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(directory.resolve(key + SUFFIX));
        } catch (NoSuchFileException absent) {
            return Optional.empty();
        } catch (IOException unreadable) {
            LOGGER.warn("AXION : cache des shaders illisible ({}) : {}", key, unreadable.getMessage());
            return Optional.empty();
        }
        return ShaderBinaryFile.decode(bytes);
    }

    /**
     * {@return le programme lié par le pilote depuis son binaire, ou 0 : binaire absent, illisible ou
     * refusé} Le compte des relectures et des absences se tient ici.
     *
     * @param binary le binaire lu, ou vide
     */
    int link(Optional<ShaderBinaryFile.Binary> binary) {
        RenderSystem.assertOnRenderThread();
        if (binary.isEmpty()) {
            misses++;
            return 0;
        }
        int program = GL20.glCreateProgram();
        ByteBuffer direct = MemoryUtil.memAlloc(binary.get().bytes().length);
        try {
            direct.put(binary.get().bytes()).flip();
            GL41.glProgramBinary(program, binary.get().format(), direct);
        } finally {
            MemoryUtil.memFree(direct);
        }
        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            // Refusé par le pilote : recompilé, puis remplacé (R-760).
            GL20.glDeleteProgram(program);
            misses++;
            return 0;
        }
        hits++;
        return program;
    }

    /**
     * Avant la liaison d'un programme à garder : le pilote doit pouvoir en rendre le binaire.
     *
     * @param program le programme, pas encore lié
     */
    void prepare(int program) {
        if (enabled()) {
            GL41.glProgramParameteri(program, GL41.GL_PROGRAM_BINARY_RETRIEVABLE_HINT, GL11.GL_TRUE);
        }
    }

    /**
     * Garde le binaire d'un programme lié ; son fichier s'écrit hors du render thread.
     *
     * @param key sa clé
     * @param program le programme lié
     */
    void store(String key, int program) {
        RenderSystem.assertOnRenderThread();
        if (!enabled()) {
            return;
        }
        int length = GL20.glGetProgrami(program, GL41.GL_PROGRAM_BINARY_LENGTH);
        if (length <= 0) {
            return;
        }
        byte[] bytes;
        int format;
        ByteBuffer direct = MemoryUtil.memAlloc(length);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer written = stack.mallocInt(1);
            IntBuffer formats = stack.mallocInt(1);
            GL41.glGetProgramBinary(program, written, formats, direct);
            bytes = new byte[written.get(0)];
            direct.get(bytes);
            format = formats.get(0);
        } finally {
            MemoryUtil.memFree(direct);
        }
        byte[] file = ShaderBinaryFile.encode(new ShaderBinaryFile.Binary(format, bytes));
        Path target = directory.resolve(key + SUFFIX);
        WRITER.execute(() -> write(target, file));
    }

    /** {@return les programmes relus du cache depuis l'ouverture} */
    int hits() {
        return hits;
    }

    /** {@return les programmes cherchés au cache et recompilés : absents, illisibles ou refusés} */
    int misses() {
        return misses;
    }

    private static void write(Path target, byte[] file) {
        try {
            Files.createDirectories(target.getParent());
            Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
            Files.write(temporary, file);
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException failure) {
            LOGGER.warn("AXION : cache des shaders non écrit ({}) : {}", target.getFileName(), failure.getMessage());
        }
    }
}
