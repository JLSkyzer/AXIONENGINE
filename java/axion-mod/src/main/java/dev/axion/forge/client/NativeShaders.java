package dev.axion.forge.client;

import com.mojang.blaze3d.shaders.ProgramManager;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.axion.AxionMod;
import dev.axion.render.ShaderVariant;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

/**
 * Programmes du backend natif (C-63, ADR-127 §4) : sources lues dans le JAR, sous
 * {@code assets/axion/shaders/} (R-760), compilées et liées sur le render thread (INV-12) ; la
 * variante {@code CUTOUT} de R-762 pour la surface, et le programme de la passe d'émission.
 *
 * <p>Un échec — sources absentes, compilation, liaison — lève {@link ShaderFailure} avec le journal
 * du pilote : le backend le rapporte en {@code E-4001}, et la sélection retombe sur vanilla (R-761).
 * {@code -Daxion.debug.shader_fail=true}, ou le banc de rendu, provoque cet échec, pour le vérifier
 * (T-479).
 *
 * <p>Les attributs ont leur emplacement dans les sources ({@code layout(location = …)}) : ni
 * {@code glBindAttribLocation}, ni dépendance à l'ordre de liaison.
 */
final class NativeShaders implements AutoCloseable {

    /**
     * Vrai : la compilation échoue exprès (T-479) — {@code -Daxion.debug.shader_fail=true}, ou le banc
     * de rendu par {@link #forceFailure}. Render thread seul.
     */
    private static boolean forcedFailure = Boolean.getBoolean("axion.debug.shader_fail");

    /** Unités de texture des échantillonneurs : albedo ou émission sur la 0, lightmap sur la 2. */
    static final int ALBEDO_UNIT = 0;

    static final int LIGHTMAP_UNIT = 2;

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

    private final Program surface;
    private final Program cutout;
    private final Program emission;

    private NativeShaders(Program surface, Program cutout, Program emission) {
        this.surface = surface;
        this.cutout = cutout;
        this.emission = emission;
    }

    /**
     * Lit, compile et lie les programmes du backend natif.
     *
     * @param resources ressources du client
     * @return les programmes
     * @throws ShaderFailure si l'un d'eux ne se compile ou ne se lie pas
     */
    static NativeShaders compile(ResourceManager resources) throws ShaderFailure {
        RenderSystem.assertOnRenderThread();
        if (forcedFailure) {
            throw new ShaderFailure("échec provoqué (axion.debug.shader_fail, ou banc de rendu)");
        }
        String vertex = read(resources, SURFACE_VERTEX);
        String fragment = read(resources, SURFACE_FRAGMENT);
        String emissive = read(resources, EMISSION_FRAGMENT);
        Program surface = link("surface", vertex, fragment);
        Program cutout;
        try {
            ShaderVariant variant = ShaderVariant.of(ShaderVariant.Define.CUTOUT);
            cutout = link("surface " + variant.key(), vertex, variant.apply(fragment));
        } catch (ShaderFailure failure) {
            GL20.glDeleteProgram(surface.id);
            throw failure;
        }
        Program emission;
        try {
            emission = link("émission", vertex, emissive);
        } catch (ShaderFailure failure) {
            GL20.glDeleteProgram(surface.id);
            GL20.glDeleteProgram(cutout.id);
            throw failure;
        }
        return new NativeShaders(surface, cutout, emission);
    }

    /**
     * Fait échouer, ou non, les compilations suivantes (T-479).
     *
     * @param failing vrai pour qu'elles échouent
     */
    static void forceFailure(boolean failing) {
        RenderSystem.assertOnRenderThread();
        forcedFailure = failing;
    }

    /** {@return le programme des surfaces opaques et translucides} */
    Program surface() {
        return surface;
    }

    /** {@return le programme des surfaces découpées} */
    Program cutout() {
        return cutout;
    }

    /** {@return le programme de la passe d'émission} */
    Program emission() {
        return emission;
    }

    @Override
    public void close() {
        RenderSystem.assertOnRenderThread();
        GL20.glDeleteProgram(surface.id);
        GL20.glDeleteProgram(cutout.id);
        GL20.glDeleteProgram(emission.id);
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

    private static Program link(String name, String vertexSource, String fragmentSource) throws ShaderFailure {
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
        Program linked = new Program(program);
        // Les échantillonneurs ne changent jamais d'unité : posés une fois, à la liaison.
        ProgramManager.glUseProgram(program);
        setSampler(linked, "u_albedo", ALBEDO_UNIT);
        setSampler(linked, "u_emission", ALBEDO_UNIT);
        setSampler(linked, "u_lightmap", LIGHTMAP_UNIT);
        ProgramManager.glUseProgram(0);
        return linked;
    }

    private static void setSampler(Program program, String name, int unit) {
        int location = program.uniform(name);
        if (location >= 0) {
            GL20.glUniform1i(location, unit);
        }
    }

    private static int compileStage(int type, String name, String source) throws ShaderFailure {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(shader);
            GL20.glDeleteShader(shader);
            throw new ShaderFailure("compilation de « " + name + " » : " + log.strip());
        }
        return shader;
    }
}
