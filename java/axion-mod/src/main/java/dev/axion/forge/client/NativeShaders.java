package dev.axion.forge.client;

import com.mojang.blaze3d.shaders.ProgramManager;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.axion.AxionMod;
import dev.axion.render.ShaderBinaryFile;
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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
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
 * ({@link ShaderVariants}) : une variante demandée se met en service sans que la frame l'attende, et
 * la base dessine à sa place jusqu'à ce qu'elle soit prête ; au-delà de
 * {@code render.max_shader_variants}, la base la remplace pour de bon, et c'est dit une fois.
 *
 * <p><b>Sans à-coup (T-904)</b> : son binaire se lit au cache hors du render thread ; puis, une
 * étape OpenGL par frame au plus, toutes variantes confondues — le binaire passé au pilote, ou les
 * sommets compilés, les fragments, la liaison lancée —, et la liaison est reprise quand le pilote l'a
 * finie, état relu à chaque frame quand il compile en parallèle
 * ({@code GL_KHR_parallel_shader_compile}), à la frame suivante sinon. Au banc du 2026-10-12, sur la
 * machine de Killian, lancer d'un bloc les deux compilations et la liaison prenait 1,1 à 1,6 ms au
 * render thread, et lire le fichier du cache jusqu'à 7,6 ms. Le render thread reste le seul à
 * appeler OpenGL (INV-12).
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

    /**
     * Vrai : les variantes à la demande se compilent comme sans compilation parallèle, une par frame,
     * même si le pilote l'offre. Le banc de rendu mesure ainsi ce chemin (T-904). Render thread seul.
     */
    private static boolean forcedSerial;

    /**
     * Unités de texture des échantillonneurs : albedo ou émission sur la 0, carte de normales sur la
     * 1, lightmap sur la 2 — celle de Minecraft —, carte ORM sur la 3.
     */
    static final int ALBEDO_UNIT = 0;

    static final int NORMAL_UNIT = 1;

    static final int LIGHTMAP_UNIT = 2;

    static final int ORM_UNIT = 3;

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

    /** Où en est une variante en route vers son service. */
    private enum Step {
        /** Son binaire se lit au cache, hors du render thread. */
        READ,
        /** Ses sommets sont à compiler. */
        VERTEX,
        /** Ses fragments sont à compiler. */
        FRAGMENT,
        /** Sa liaison est à lancer. */
        LINK,
        /** Sa liaison est lancée : le pilote la finit. */
        LINKING
    }

    /** Une variante en route vers son service : son binaire en lecture, puis ses objets, une étape par frame. */
    private static final class Route {
        final ShaderVariant variant;
        final String key;
        final CompletableFuture<Optional<ShaderBinaryFile.Binary>> reading;
        Step step;
        int vertex;
        int fragment;
        int program;

        Route(ShaderVariant variant, String key, CompletableFuture<Optional<ShaderBinaryFile.Binary>> reading) {
            this.variant = variant;
            this.key = key;
            this.reading = reading;
            this.step = reading == null ? Step.VERTEX : Step.READ;
        }
    }

    private final String vertexSource;
    private final String surfaceSource;
    private final ShaderBinaryCache cache;
    private final boolean parallel;
    private final ShaderVariants<Program> surfaces;
    private final Program emission;
    private final List<Route> routes = new ArrayList<>();
    private final Set<ShaderVariant> refusedReported = new HashSet<>();
    /** Variantes oubliées dont la prochaine mise en service se compile, sans relire le cache (T-904). */
    private final Set<ShaderVariant> bypassCache = new HashSet<>();
    private ShaderFailure failure;
    private int compiled;
    private int onDemand;

    /** Temps passé dans le dernier {@link #poll()}, sur le render thread. */
    private long lastPollNanos;

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
     * Compile, ou non, les variantes à la demande qui suivent comme sans compilation parallèle (T-904).
     *
     * @param serial vrai pour une variante par frame, dont l'état se lit à la frame suivante
     */
    static void forceSerial(boolean serial) {
        RenderSystem.assertOnRenderThread();
        forcedSerial = serial;
    }

    /**
     * Oublie une variante prête et détruit son programme : sa prochaine demande la remet en service.
     * Le banc de rendu mesure ainsi une mise en service à la demande sur une scène établie (T-904).
     *
     * @param variant la variante
     * @param fromCache vrai pour qu'elle soit relue du cache binaire, faux pour qu'elle se compile
     * @return vrai si elle était prête
     */
    boolean forget(ShaderVariant variant, boolean fromCache) {
        RenderSystem.assertOnRenderThread();
        Program program = surfaces.forget(variant);
        if (program == null) {
            return false;
        }
        delete(program);
        if (fromCache) {
            bypassCache.remove(variant);
        } else {
            bypassCache.add(variant);
        }
        return true;
    }

    /** {@return le temps passé dans le dernier {@link #poll()}, en nanosecondes, sur le render thread} */
    long lastPollNanos() {
        return lastPollNanos;
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
     * Avance les variantes à la demande, une fois par frame, avant tout dessin : les liaisons finies
     * sont reprises, les variantes demandées entrent en route — la lecture de leur binaire lancée,
     * sans appel OpenGL —, puis une étape OpenGL au plus est faite, pour la première variante qui en
     * attend une.
     */
    void poll() {
        RenderSystem.assertOnRenderThread();
        long start = System.nanoTime();
        boolean parallel = parallel();
        Iterator<Route> linked = routes.iterator();
        while (linked.hasNext()) {
            Route route = linked.next();
            if (route.step == Step.LINKING && finished(route, parallel)) {
                linked.remove();
                adopt(route);
            }
        }
        for (ShaderVariant next = surfaces.nextToCompile(); next != null; next = surfaces.nextToCompile()) {
            enter(next);
        }
        Iterator<Route> waiting = routes.iterator();
        while (waiting.hasNext()) {
            Route route = waiting.next();
            boolean stepped = advance(route);
            if (route.step == null) {
                waiting.remove();
            }
            if (stepped) {
                break;
            }
        }
        lastPollNanos = System.nanoTime() - start;
    }

    /** {@return vrai si les variantes se compilent en parallèle : le pilote l'offre, et le banc ne l'a pas coupé} */
    private boolean parallel() {
        return parallel && !forcedSerial;
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
        for (Route route : routes) {
            discard(route);
        }
        routes.clear();
        for (Program program : surfaces.programs()) {
            delete(program);
        }
        delete(emission);
    }

    /** Met une variante en route : la lecture de son binaire lancée, ou sa compilation à faire. Aucun appel OpenGL. */
    private void enter(ShaderVariant variant) {
        if (forcedVariantFailure) {
            fail(variant, "échec provoqué (banc de rendu)");
            return;
        }
        String key = cache.key(SURFACE, variant, vertexSource, surfaceSource);
        routes.add(new Route(variant, key, bypassCache.remove(variant) ? null : cache.read(key)));
    }

    /**
     * Avance une variante d'une étape ; une variante prête sort de route ({@code step} nul).
     *
     * @return vrai si l'étape a appelé OpenGL : la frame n'en fait pas d'autre
     */
    private boolean advance(Route route) {
        switch (route.step) {
            case READ -> {
                if (!route.reading.isDone()) {
                    return false;
                }
                Optional<ShaderBinaryFile.Binary> binary;
                try {
                    binary = route.reading.join();
                } catch (RuntimeException unreadable) {
                    binary = Optional.empty();
                }
                int cached = cache.link(binary);
                if (cached != 0) {
                    surfaces.ready(route.variant, adopted(cached));
                    onDemand++;
                    route.step = null;
                    return true;
                }
                route.step = Step.VERTEX;
                return binary.isPresent();
            }
            case VERTEX -> {
                route.vertex = shader(GL20.GL_VERTEX_SHADER, vertexSource);
                route.step = Step.FRAGMENT;
                return true;
            }
            case FRAGMENT -> {
                route.fragment = shader(GL20.GL_FRAGMENT_SHADER, route.variant.apply(surfaceSource));
                route.step = Step.LINK;
                return true;
            }
            case LINK -> {
                route.program = GL20.glCreateProgram();
                GL20.glAttachShader(route.program, route.vertex);
                GL20.glAttachShader(route.program, route.fragment);
                cache.prepare(route.program);
                GL20.glLinkProgram(route.program);
                route.step = Step.LINKING;
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /**
     * {@return vrai si la compilation d'une variante est finie} Sans compilation parallèle, elle l'est
     * à la frame qui suit son lancement : lire son état peut attendre le pilote, une variante par
     * frame au plus.
     */
    private static boolean finished(Route route, boolean parallel) {
        return !parallel
                || GL20.glGetProgrami(route.program, KHRParallelShaderCompile.GL_COMPLETION_STATUS_KHR)
                        == GL11.GL_TRUE;
    }

    /** Reprend une variante compilée : en service si elle se lie, perdue sinon (R-761). */
    private void adopt(Route route) {
        String name = SURFACE + " " + route.variant.key();
        String error = null;
        if (GL20.glGetShaderi(route.vertex, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            error = "compilation de « " + name + ", sommets » : " + GL20.glGetShaderInfoLog(route.vertex).strip();
        } else if (GL20.glGetShaderi(route.fragment, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            error = "compilation de « " + name + ", fragments » : " + GL20.glGetShaderInfoLog(route.fragment).strip();
        } else if (GL20.glGetProgrami(route.program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            error = "liaison du programme « " + name + " » : " + GL20.glGetProgramInfoLog(route.program).strip();
        }
        if (error != null) {
            discard(route);
            fail(route.variant, error);
            return;
        }
        GL20.glDetachShader(route.program, route.vertex);
        GL20.glDetachShader(route.program, route.fragment);
        GL20.glDeleteShader(route.vertex);
        GL20.glDeleteShader(route.fragment);
        cache.store(route.key, route.program);
        surfaces.ready(route.variant, adopted(route.program));
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

    /** Détruit les objets déjà créés d'une variante abandonnée ou ratée. */
    private static void discard(Route route) {
        if (route.program != 0) {
            GL20.glDeleteProgram(route.program);
        }
        if (route.vertex != 0) {
            GL20.glDeleteShader(route.vertex);
        }
        if (route.fragment != 0) {
            GL20.glDeleteShader(route.fragment);
        }
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
        setSampler(program, "u_normal_map", NORMAL_UNIT);
        setSampler(program, "u_orm", ORM_UNIT);
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
