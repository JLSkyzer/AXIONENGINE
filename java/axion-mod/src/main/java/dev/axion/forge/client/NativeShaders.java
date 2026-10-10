package dev.axion.forge.client;

import com.mojang.blaze3d.shaders.ProgramManager;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.axion.AxionMod;
import dev.axion.render.ShaderVariant;
import dev.axion.render.ShaderVariants;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.KHRParallelShaderCompile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Programmes du backend natif (C-63, ADR-127 §4) : sources lues dans le JAR, sous
 * {@code assets/axion/shaders/} (R-760), compilées et liées sur le render thread (INV-12).
 *
 * <p><b>Au démarrage du backend</b>, la variante de base de la surface — la variante de secours — et
 * le programme de la passe d'émission, d'un bloc. <b>À la demande</b>, les variantes de R-762
 * ({@link ShaderVariants}) : une variante demandée entre en compilation sans que la frame l'attende
 * — toutes à la fois quand le pilote compile en parallèle ({@code GL_KHR_parallel_shader_compile}, état
 * relu à chaque frame), une par frame sinon — et la base dessine à sa place jusqu'à ce qu'elle soit
 * prête ; au-delà de {@code render.max_shader_variants}, la base la remplace pour de bon, et c'est dit
 * une fois.
 *
 * <p><b>Cache binaire</b> ({@link ShaderBinaryCache}, R-760) : un programme dont le binaire est au
 * cache, sous ce pilote, en est relu sans compilation ; un programme compilé y est gardé.
 *
 * <p><b>Échecs</b> : au démarrage, un échec — sources absentes, compilation, liaison — lève
 * {@link ShaderFailure} avec le journal du pilote ; à la demande, il est retenu et
 * {@link #failure()} le rend. Le backend le rapporte en {@code E-4001}, et la sélection retombe sur
 * vanilla (R-761). {@code -Daxion.debug.shader_fail=true}, ou le banc de rendu, provoque l'un ou
 * l'autre, pour le vérifier (T-479, T-511).
 *
 * <p>Les attributs ont leur emplacement dans les sources ({@code layout(location = …)}) : ni
 * {@code glBindAttribLocation}, ni dépendance à l'ordre de liaison.
 */
final class NativeShaders implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger("axion");

    /**
     * Vrai : la compilation du démarrage échoue exprès (T-479) — {@code -Daxion.debug.shader_fail=true},
     * ou le banc de rendu par {@link #forceFailure}. Render thread seul.
     */
    private static boolean forcedFailure = Boolean.getBoolean("axion.debug.shader_fail");

    /** Vrai : les variantes compilées à la demande échouent exprès (R-761). Render thread seul. */
    private static boolean forcedVariantFailure;

    /** Unités de texture des échantillonneurs : albedo ou émission sur la 0, lightmap sur la 2. */
    static final int ALBEDO_UNIT = 0;

    static final int LIGHTMAP_UNIT = 2;

    private static final String SURFACE = "surface";
    private static final String EMISSION = "émission";

    private static final ResourceLocation SURFACE_VERTEX = location("axion_surface.vsh");
    private static final ResourceLocation SURFACE_FRAGMENT = location("axion_surface.fsh");
    private static final ResourceLocation EMISSION_FRAGMENT = location("axion_emission.fsh");

    /** Échec de compilation ou de liaison : {@code E-4001} (R-761). */
    static final class ShaderFailure extends Exception {
        private static final long serialVersionUID = 1L;

        ShaderFailure(String message) {
            super(message);
        }
    }

    /** Un programme lié, et l'emplacement de ses uniformes, lus une fois. */
    static final class Program {
        final int id;
        private final Map<String, Integer> uniforms = new HashMap<>();

        private Program(int id) {
            this.id = id;
        }

        /** {@return l'emplacement d'un uniforme, ou -1 s'il n'existe pas — ou que le pilote l'a ôté} */
        int uniform(String name) {
            return uniforms.computeIfAbsent(name, key -> GL20.glGetUniformLocation(id, key));
        }
    }

    /**
     * Ce que le banc de rendu relève des programmes (T-512, T-905).
     *
     * @param cacheHits programmes relus du cache binaire
     * @param cacheMisses programmes cherchés au cache, absents, illisibles ou refusés
     * @param compiled programmes compilés depuis leurs sources
     * @param onDemand variantes mises en service à la demande, du cache ou compilées
     * @param pending variantes en attente ou en cours de compilation
     * @param refused variantes au-delà de la borne
     */
    record Stats(int cacheHits, int cacheMisses, int compiled, int onDemand, int pending, int refused) {}

    /** Une variante en cours de compilation : ses objets, dont l'état n'a pas encore été lu. */
    private record Compilation(ShaderVariant variant, String key, int vertex, int fragment, int program) {}

    private final String vertexSource;
    private final String surfaceSource;
    private final ShaderBinaryCache cache;
    private final boolean parallel;
    private final ShaderVariants<Program> surfaces;
    private final Program emission;
    private final List<Compilation> compiling = new ArrayList<>();
    private final Set<ShaderVariant> refusedReported = new HashSet<>();
    private ShaderFailure failure;
    private int compiled;
    private int onDemand;

    private NativeShaders(
            String vertexSource,
            String surfaceSource,
            ShaderBinaryCache cache,
            boolean parallel,
            Program base,
            Program emission,
            int maxVariants,
            int compiled) {
        this.vertexSource = vertexSource;
        this.surfaceSource = surfaceSource;
        this.cache = cache;
        this.parallel = parallel;
        this.surfaces = new ShaderVariants<>(base, maxVariants);
        this.emission = emission;
        this.compiled = compiled;
    }

    /**
     * Lit les sources, puis compile — ou relit du cache — la variante de base de la surface et le
     * programme d'émission.
     *
     * @param resources ressources du client
     * @param cacheDirectory {@code <gameDir>/axion/cache/shaders/}
     * @param maxVariants {@code render.max_shader_variants}
     * @return les programmes
     * @throws ShaderFailure si l'un d'eux ne se compile ou ne se lie pas
     */
    static NativeShaders compile(ResourceManager resources, Path cacheDirectory, int maxVariants)
            throws ShaderFailure {
        RenderSystem.assertOnRenderThread();
        if (forcedFailure) {
            throw new ShaderFailure("échec provoqué (axion.debug.shader_fail, ou banc de rendu)");
        }
        String vertex = read(resources, SURFACE_VERTEX);
        String surface = read(resources, SURFACE_FRAGMENT);
        String emissive = read(resources, EMISSION_FRAGMENT);
        ShaderBinaryCache cache = ShaderBinaryCache.open(cacheDirectory);
        GLCapabilities capabilities = GL.getCapabilities();
        boolean parallel = capabilities.GL_KHR_parallel_shader_compile || capabilities.GL_ARB_parallel_shader_compile;
        int[] compiled = {0};
        Program base = loadOrLink(cache, SURFACE, ShaderVariant.BASE, vertex, surface, compiled);
        Program emission;
        try {
            emission = loadOrLink(cache, EMISSION, ShaderVariant.BASE, vertex, emissive, compiled);
        } catch (ShaderFailure failure) {
            delete(base);
            throw failure;
        }
        return new NativeShaders(vertex, surface, cache, parallel, base, emission, maxVariants, compiled[0]);
    }

    /**
     * Fait échouer, ou non, les compilations du démarrage qui suivent (T-479).
     *
     * @param failing vrai pour qu'elles échouent
     */
    static void forceFailure(boolean failing) {
        RenderSystem.assertOnRenderThread();
        forcedFailure = failing;
    }

    /**
     * Fait échouer, ou non, les variantes compilées à la demande qui suivent (R-761).
     *
     * @param failing vrai pour qu'elles échouent
     */
    static void forceVariantFailure(boolean failing) {
        RenderSystem.assertOnRenderThread();
        forcedVariantFailure = failing;
    }

    /**
     * {@return le programme de surface qui dessine une variante à cette frame : le sien s'il est prêt,
     * la base sinon} Une variante demandée pour la première fois est mise en compilation.
     *
     * @param variant la variante voulue
     */
    Program surface(ShaderVariant variant) {
        Program program = surfaces.program(variant);
        if (surfaces.state(variant) == ShaderVariants.State.REFUSED && refusedReported.add(variant)) {
            LOGGER.warn("AXION : variante {} des shaders non compilée, render.max_shader_variants ({}) atteint ;"
                    + " la variante de base la remplace (R-762)", variant.key(), surfaces.maxVariants());
        }
        return program;
    }

    /** {@return le programme de la passe d'émission} */
    Program emission() {
        return emission;
    }

    /**
     * Avance les variantes à la demande, une fois par frame, avant tout dessin : celles dont la
     * compilation est finie sont reprises, puis celles qui attendent sont lancées.
     */
    void poll() {
        RenderSystem.assertOnRenderThread();
        Iterator<Compilation> running = compiling.iterator();
        while (running.hasNext()) {
            Compilation compilation = running.next();
            if (finished(compilation)) {
                running.remove();
                adopt(compilation);
            }
        }
        while (parallel || compiling.isEmpty()) {
            ShaderVariant next = surfaces.nextToCompile();
            if (next == null) {
                break;
            }
            start(next);
            if (!parallel) {
                break;
            }
        }
    }

    /** {@return l'échec d'une variante compilée à la demande, ou {@code null} : aucun (R-761)} */
    ShaderFailure failure() {
        return failure;
    }

    /** {@return où en est une variante ; {@code null} pour une variante jamais demandée} */
    ShaderVariants.State state(ShaderVariant variant) {
        return surfaces.state(variant);
    }

    /** {@return ce que le banc de rendu relève des programmes} */
    Stats stats() {
        return new Stats(cache.hits(), cache.misses(), compiled, onDemand,
                surfaces.inState(ShaderVariants.State.PENDING).size(),
                surfaces.inState(ShaderVariants.State.REFUSED).size());
    }

    @Override
    public void close() {
        RenderSystem.assertOnRenderThread();
        for (Compilation compilation : compiling) {
            discard(compilation);
        }
        compiling.clear();
        for (Program program : surfaces.programs()) {
            delete(program);
        }
        delete(emission);
    }

    /** Met une variante en service : relue du cache sans attendre, sinon mise en compilation. */
    private void start(ShaderVariant variant) {
        if (forcedVariantFailure) {
            fail(variant, "échec provoqué (banc de rendu)");
            return;
        }
        String key = cache.key(SURFACE, variant, vertexSource, surfaceSource);
        int cached = cache.load(key);
        if (cached != 0) {
            surfaces.ready(variant, adopted(cached));
            onDemand++;
            return;
        }
        String fragment = variant.apply(surfaceSource);
        int vertex = shader(GL20.GL_VERTEX_SHADER, vertexSource);
        int fragmentShader = shader(GL20.GL_FRAGMENT_SHADER, fragment);
        int program = GL20.glCreateProgram();
        GL20.glAttachShader(program, vertex);
        GL20.glAttachShader(program, fragmentShader);
        cache.prepare(program);
        GL20.glLinkProgram(program);
        compiling.add(new Compilation(variant, key, vertex, fragmentShader, program));
    }

    /**
     * {@return vrai si la compilation d'une variante est finie} Sans compilation parallèle, elle l'est
     * à la frame qui suit son lancement : lire son état peut attendre le pilote, une variante par
     * frame au plus.
     */
    private boolean finished(Compilation compilation) {
        return !parallel
                || GL20.glGetProgrami(compilation.program(), KHRParallelShaderCompile.GL_COMPLETION_STATUS_KHR)
                        == GL11.GL_TRUE;
    }

    /** Reprend une variante compilée : en service si elle se lie, perdue sinon (R-761). */
    private void adopt(Compilation compilation) {
        String name = SURFACE + " " + compilation.variant().key();
        String error = null;
        if (GL20.glGetShaderi(compilation.vertex(), GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            error = "compilation de « " + name + ", sommets » : " + GL20.glGetShaderInfoLog(compilation.vertex()).strip();
        } else if (GL20.glGetShaderi(compilation.fragment(), GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            error = "compilation de « " + name + ", fragments » : "
                    + GL20.glGetShaderInfoLog(compilation.fragment()).strip();
        } else if (GL20.glGetProgrami(compilation.program(), GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            error = "liaison du programme « " + name + " » : " + GL20.glGetProgramInfoLog(compilation.program()).strip();
        }
        if (error != null) {
            discard(compilation);
            fail(compilation.variant(), error);
            return;
        }
        GL20.glDetachShader(compilation.program(), compilation.vertex());
        GL20.glDetachShader(compilation.program(), compilation.fragment());
        GL20.glDeleteShader(compilation.vertex());
        GL20.glDeleteShader(compilation.fragment());
        cache.store(compilation.key(), compilation.program());
        surfaces.ready(compilation.variant(), adopted(compilation.program()));
        compiled++;
        onDemand++;
    }

    /** Une variante est perdue : la base la remplace, et le backend devra basculer (R-761). */
    private void fail(ShaderVariant variant, String message) {
        surfaces.failed(variant);
        if (failure == null) {
            failure = new ShaderFailure("variante " + variant.key() + " : " + message);
        }
    }

    /** Détruit les objets d'une compilation abandonnée ou ratée. */
    private static void discard(Compilation compilation) {
        GL20.glDeleteProgram(compilation.program());
        GL20.glDeleteShader(compilation.vertex());
        GL20.glDeleteShader(compilation.fragment());
    }

    /** {@return un programme relu du cache, ou compilé et lié d'un bloc, puis gardé au cache} */
    private static Program loadOrLink(
            ShaderBinaryCache cache, String name, ShaderVariant variant, String vertex, String fragment, int[] compiled)
            throws ShaderFailure {
        String key = cache.key(name, variant, vertex, fragment);
        int cached = cache.load(key);
        if (cached != 0) {
            return adopted(cached);
        }
        Program linked = link(cache, name, vertex, variant.apply(fragment));
        cache.store(key, linked.id);
        compiled[0]++;
        return linked;
    }

    /** {@return un programme lié, compté, ses échantillonneurs posés} */
    private static Program adopted(int id) {
        Program program = new Program(id);
        NativeGlObjects.created();
        // Les échantillonneurs ne changent jamais d'unité : posés une fois, à l'adoption.
        ProgramManager.glUseProgram(id);
        setSampler(program, "u_albedo", ALBEDO_UNIT);
        setSampler(program, "u_emission", ALBEDO_UNIT);
        setSampler(program, "u_lightmap", LIGHTMAP_UNIT);
        ProgramManager.glUseProgram(0);
        return program;
    }

    /** Détruit un programme adopté, compté parmi les objets du backend natif. */
    private static void delete(Program program) {
        GL20.glDeleteProgram(program.id);
        NativeGlObjects.deleted();
    }

    /**
     * {@return l'emplacement d'une source} Le constructeur est marqué déprécié mais existe dans toute
     * la branche Forge 47, comme l'explique la texture neutre de {@link VanillaConsumerBackend}.
     */
    private static ResourceLocation location(String file) {
        return new ResourceLocation(AxionMod.MODID, "shaders/" + file);
    }

    private static String read(ResourceManager resources, ResourceLocation location) throws ShaderFailure {
        Resource resource = resources.getResource(location)
                .orElseThrow(() -> new ShaderFailure("sources introuvables : " + location));
        try (InputStream in = resource.open()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new ShaderFailure("sources illisibles : " + location + " (" + failure.getMessage() + ")");
        }
    }

    /** Compile et lie un programme d'un bloc, en attendant le pilote : le démarrage du backend. */
    private static Program link(ShaderBinaryCache cache, String name, String vertexSource, String fragmentSource)
            throws ShaderFailure {
        int vertex = compileStage(GL20.GL_VERTEX_SHADER, name + ", sommets", vertexSource);
        int fragment;
        try {
            fragment = compileStage(GL20.GL_FRAGMENT_SHADER, name + ", fragments", fragmentSource);
        } catch (ShaderFailure failure) {
            GL20.glDeleteShader(vertex);
            throw failure;
        }
        int program = GL20.glCreateProgram();
        GL20.glAttachShader(program, vertex);
        GL20.glAttachShader(program, fragment);
        cache.prepare(program);
        GL20.glLinkProgram(program);
        GL20.glDetachShader(program, vertex);
        GL20.glDetachShader(program, fragment);
        GL20.glDeleteShader(vertex);
        GL20.glDeleteShader(fragment);
        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetProgramInfoLog(program);
            GL20.glDeleteProgram(program);
            throw new ShaderFailure("liaison du programme « " + name + " » : " + log.strip());
        }
        return adopted(program);
    }

    private static void setSampler(Program program, String name, int unit) {
        int location = program.uniform(name);
        if (location >= 0) {
            GL20.glUniform1i(location, unit);
        }
    }

    /** {@return un shader dont la compilation est lancée, son état pas encore lu} */
    private static int shader(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        return shader;
    }

    private static int compileStage(int type, String name, String source) throws ShaderFailure {
        int shader = shader(type, source);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(shader);
            GL20.glDeleteShader(shader);
            throw new ShaderFailure("compilation de « " + name + " » : " + log.strip());
        }
        return shader;
    }
}
