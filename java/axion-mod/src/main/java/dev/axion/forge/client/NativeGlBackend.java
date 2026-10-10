package dev.axion.forge.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import dev.axion.asset.GeometryTransfer;
import dev.axion.asset.MaterialTransfer;
import dev.axion.render.BackendSelection;
import dev.axion.render.DepthOrder;
import dev.axion.render.InstanceLayout;
import dev.axion.render.MeshLook;
import dev.axion.render.RenderAsset;
import dev.axion.render.ShaderVariant;
import dev.axion.render.ShaderVariants;
import dev.axion.render.SurfacePass;
import dev.axion.render.TextureBinding;
import dev.axion.render.TextureRegion;
import dev.axion.render.WorldLight;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Backend NATIVE_GL (C-60, ADR-127) : les assemblies par les tampons et les shaders d'AXION.
 *
 * <p>Chaque mesh posé d'une assembly prête est un {@code glDrawElementsInstancedBaseVertex} depuis son
 * arène (C-62), sous la transformation que porte son bloc d'instance : sa pose de repos (ADR-119),
 * puis l'orientation et la position interpolées du corps, relatives à la caméra et composées en
 * {@code double} (ADR-127 §2). Même géométrie, même pose, mêmes meshes visibles que le backend vanilla
 * (R-1492) ; seul l'éclairage diffère, comme le déclare la matrice de R-1493 : la BRDF du §19.5 sous
 * le soleil ou la lune, une ambiante ciel–sol, la lightmap de Minecraft et son brouillard
 * ({@link WorldLight}).
 *
 * <p>Passes du §19.10 : OPAQUE, CUTOUT (variante {@code CUTOUT} de R-762), puis EMISSIVE, additive,
 * au stage {@code AFTER_ENTITIES} ; TRANSLUCENT au stage {@code AFTER_TRANSLUCENT_BLOCKS}, du plus loin
 * au plus près dans l'ordre du backend vanilla ({@link DepthOrder}), profondeur non écrite.
 *
 * <p>État GL (§19.3) : ce que Minecraft garde en cache — mélange, profondeur, faces, textures liées,
 * programme — ne change que par {@code RenderSystem} et {@code GlStateManager} (R-1500) ; VAO,
 * tampons, attributs et sens des faces avant sont touchés directement (R-1501). Chaque passe finit
 * sur l'état que laissent les {@code RenderType} en se refermant — mélange coupé, test de profondeur
 * coupé, faces arrière cachées —, ses textures déliées comme le fait {@code ShaderInstance.clear},
 * aucun VAO ni programme lié, et le traqueur de {@code BufferUploader} invalidé ; le garde de la passe
 * centrale rend ensuite les liaisons qu'il a trouvées (R-1502).
 *
 * <p>Tant que rien n'est prêt, l'assembly reçoit la boîte de repli du backend vanilla, à l'identique.
 *
 * <p>Render thread seul (R-1504, INV-12).
 */
final class NativeGlBackend implements RenderBackend {

    private static final Logger LOGGER = LoggerFactory.getLogger("axion");

    /** La variante des surfaces découpées (R-762). */
    private static final ShaderVariant CUTOUT = ShaderVariant.of(ShaderVariant.Define.CUTOUT);

    /** Teinte d'une instance : aucune, en M3. */
    private static final float[] NO_TINT = {1.0f, 1.0f, 1.0f, 1.0f};

    private final NativeShaders shaders;
    private final NativeMeshes meshes;

    /** Les boîtes de repli : celles du backend vanilla. */
    private final VanillaConsumerBackend boxes = new VanillaConsumerBackend();

    /** Blocs d'instance d'une passe, avant téléversement ; agrandi par doublement. */
    private ByteBuffer instances = ByteBuffer.allocateDirect(InstanceLayout.BYTES * 64).order(ByteOrder.LITTLE_ENDIAN);

    /** Le VAO lié pendant une passe, pour ne le relier qu'au changement d'arène. */
    private int boundVao;

    /** La texture liée à chaque unité pendant une passe ; {@code null} : aucune encore. */
    private final TextureBinding[] boundTextures = new TextureBinding[4];

    /** Le sens des faces avant pendant une passe. */
    private int frontFace = GL11.GL_CCW;

    private NativeGlBackend(NativeShaders shaders, NativeMeshes meshes) {
        this.shaders = shaders;
        this.meshes = meshes;
    }

    /**
     * {@return ce qui manque au contexte pour le backend natif, ou {@code null} : rien} Le backend
     * demande OpenGL 3.3 (§19.2).
     */
    static String missingCapability() {
        RenderSystem.assertOnRenderThread();
        if (GL.getCapabilities().OpenGL33) {
            return null;
        }
        return "OpenGL 3.3 absent du contexte (" + GL11.glGetString(GL11.GL_VERSION) + ")";
    }

    /**
     * Démarre le backend : ses programmes sont compilés, ses ressources GPU viendront au premier
     * dessin de chaque asset.
     *
     * @param resources ressources du client, où sont ses shaders (R-760)
     * @param budgetBytes {@code budgets.gpu_mem_bytes} (R-750)
     * @param shaderCache {@code <gameDir>/axion/cache/shaders/}, le cache binaire des programmes (R-760)
     * @param maxVariants {@code render.max_shader_variants} (R-762)
     * @return le backend
     * @throws NativeShaders.ShaderFailure si un programme ne se compile ou ne se lie pas ({@code E-4001})
     */
    static NativeGlBackend create(ResourceManager resources, long budgetBytes, Path shaderCache, int maxVariants)
            throws NativeShaders.ShaderFailure {
        RenderSystem.assertOnRenderThread();
        NativeShaders shaders = NativeShaders.compile(resources, shaderCache, maxVariants);
        try {
            return new NativeGlBackend(shaders, new NativeMeshes(budgetBytes));
        } catch (RuntimeException failure) {
            shaders.close();
            throw failure;
        }
    }

    @Override
    public BackendSelection.Kind kind() {
        return BackendSelection.Kind.NATIVE;
    }

    @Override
    public void renderOpaque(Frame frame) {
        meshes.nextFrame();
        shaders.poll();
        List<Part> parts = gather(frame, false);
        if (!parts.isEmpty()) {
            Environment environment = Environment.of(frame);
            int activeTexture = GlStateManager._getActiveTexture();
            try {
                begin(parts, environment);

                RenderSystem.disableBlend();
                RenderSystem.enableDepthTest();
                RenderSystem.depthFunc(GL11.GL_LEQUAL);
                RenderSystem.depthMask(true);
                drawSurfaces(parts, SurfacePass.OPAQUE, ShaderVariant.BASE, environment);
                drawSurfaces(parts, SurfacePass.CUTOUT, CUTOUT, environment);

                // Passe 5 : l'émission s'ajoute à la surface, sans écrire la profondeur.
                RenderSystem.enableBlend();
                RenderSystem.blendFunc(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE);
                RenderSystem.depthMask(false);
                drawEmission(parts, environment);
            } finally {
                finish(activeTexture);
            }
        }
        List<Assembly> pending = new ArrayList<>();
        for (Assembly assembly : frame.assemblies()) {
            if (assembly.asset() == null) {
                pending.add(assembly);
            }
        }
        if (!pending.isEmpty()) {
            boxes.renderOpaque(new Frame(frame.pose(), frame.camera(), frame.partialTick(), frame.buffers(), pending));
        }
    }

    @Override
    public void renderTranslucent(Frame frame) {
        List<Part> parts = gather(frame, true);
        if (parts.isEmpty()) {
            return;
        }
        double[] distances = new double[parts.size()];
        for (int rank = 0; rank < distances.length; rank++) {
            distances[rank] = squaredDistance(parts.get(rank));
        }
        List<Part> sorted = new ArrayList<>(parts.size());
        for (int rank : DepthOrder.farToNear(distances)) {
            sorted.add(parts.get(rank));
        }
        Environment environment = Environment.of(frame);
        int activeTexture = GlStateManager._getActiveTexture();
        try {
            begin(sorted, environment);
            RenderSystem.enableBlend();
            RenderSystem.blendFuncSeparate(
                    GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                    GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
            RenderSystem.enableDepthTest();
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
            RenderSystem.depthMask(false);
            drawSurfaces(sorted, SurfacePass.TRANSLUCENT, ShaderVariant.BASE, environment);
        } finally {
            finish(activeTexture);
        }
    }

    /** {@return l'échec d'une variante compilée à la demande, ou {@code null} : aucun (R-761)} */
    @Override
    public String failure() {
        NativeShaders.ShaderFailure failure = shaders.failure();
        return failure == null ? null : failure.getMessage();
    }

    /** {@return ce que le banc de rendu relève des programmes (T-512, T-905)} */
    NativeShaders.Stats shaderStats() {
        return shaders.stats();
    }

    /** {@return où en est la variante des surfaces découpées ; {@code null} : jamais demandée} */
    ShaderVariants.State cutoutState() {
        return shaders.state(CUTOUT);
    }

    /** Rend tous ses objets GL ; un objet resté vivant est une fuite, journalisée (R-744). */
    @Override
    public void close() {
        meshes.close();
        shaders.close();
        int left = NativeGlObjects.closed();
        if (left != 0) {
            LOGGER.error("AXION : le backend natif laisse {} objet(s) GL en vie à sa fermeture (R-744)", left);
        }
    }

    /**
     * {@return les meshes posés d'une passe : pour la passe 4, les translucides ; sinon tous ceux que
     * dessinent les passes 1, 2 et 5} Chaque asset est préparé — téléversé à son premier dessin — avant
     * qu'aucun VAO ne soit lié pour dessiner.
     */
    private List<Part> gather(Frame frame, boolean translucent) {
        List<Part> parts = new ArrayList<>();
        Vec3 camera = frame.camera();
        for (Assembly assembly : frame.assemblies()) {
            RenderAsset asset = assembly.asset();
            if (asset == null || asset.mesh().vertexCount() == 0) {
                continue;
            }
            AssemblyPlacement at = null;
            NativeMeshes.GpuAsset gpu = null;
            for (GeometryTransfer.Draw draw : asset.mesh().draws()) {
                MeshLook look = asset.look(draw.mesh());
                boolean surface = look.pass() == SurfacePass.TRANSLUCENT;
                boolean wanted = translucent ? surface : !surface || asset.emits(draw.mesh());
                GeometryTransfer.Mesh mesh = asset.mesh().meshes().get(draw.mesh());
                if (!wanted || !look.visible() || mesh.indexCount() == 0) {
                    continue;
                }
                if (at == null) {
                    at = AssemblyPlacement.of(frame.partialTick(), assembly.entity());
                    gpu = meshes.prepare(asset);
                }
                Quaternionf q = at.rotation();
                float[] rows = InstanceLayout.modelRows(
                        at.x() - camera.x, at.y() - camera.y, at.z() - camera.z,
                        q.x(), q.y(), q.z(), q.w(), draw.model());
                parts.add(new Part(asset, gpu, mesh, draw.mesh(), look, rows, at.light()));
            }
        }
        return parts;
    }

    /** {@return la distance au carré, de la caméra au centre d'un mesh posé (R-1580)} */
    private static double squaredDistance(Part part) {
        float[] r = part.rows();
        float[] c = part.look().center();
        double x = r[0] * c[0] + r[1] * c[1] + r[2] * c[2] + r[3];
        double y = r[4] * c[0] + r[5] * c[1] + r[6] * c[2] + r[7];
        double z = r[8] * c[0] + r[9] * c[1] + r[10] * c[2] + r[11];
        return x * x + y * y + z * z;
    }

    /** Ouvre une passe : blocs d'instance téléversés, lightmap liée, unité de l'albedo active. */
    private void begin(List<Part> parts, Environment environment) {
        int bytes = parts.size() * InstanceLayout.BYTES;
        if (instances.capacity() < bytes) {
            instances = ByteBuffer.allocateDirect(Math.max(bytes, instances.capacity() * 2)).order(ByteOrder.LITTLE_ENDIAN);
        }
        instances.clear();
        for (Part part : parts) {
            InstanceLayout.write(instances, part.rows(), NO_TINT, part.light(), 0);
        }
        instances.flip();
        meshes.uploadInstances(instances);

        RenderSystem.activeTexture(GL13.GL_TEXTURE0 + NativeShaders.LIGHTMAP_UNIT);
        RenderSystem.bindTexture(environment.lightmap());
        RenderSystem.activeTexture(GL13.GL_TEXTURE0 + NativeShaders.ALBEDO_UNIT);
        boundVao = 0;
        Arrays.fill(boundTextures, null);
        frontFace = GL11.GL_CCW;
    }

    /**
     * Les surfaces d'une passe, 1, 2 ou 4, par un programme de surface. La variante n'est demandée
     * qu'avec la première surface de la passe : une variante que rien ne dessine ne se compile pas
     * (R-762) ; tant qu'elle n'est pas prête, la base dessine à sa place.
     */
    private void drawSurfaces(List<Part> parts, SurfacePass pass, ShaderVariant variant, Environment environment) {
        NativeShaders.Program program = null;
        for (int instance = 0; instance < parts.size(); instance++) {
            Part part = parts.get(instance);
            if (part.look().pass() != pass) {
                continue;
            }
            if (program == null) {
                program = shaders.surface(variant);
                GlStateManager._glUseProgram(program.id);
                environment.upload(program, true);
            }
            MaterialTransfer.Material material = part.look().material();
            TextureRegion region = part.asset().albedo(part.rank());
            meshUniforms(program, part, region);
            float[] albedo = material.albedoFactor();
            GL20.glUniform4f(program.uniform("u_albedo_factor"), albedo[0], albedo[1], albedo[2], albedo[3]);
            GL20.glUniform1f(program.uniform("u_metallic"), material.metallic());
            GL20.glUniform1f(program.uniform("u_roughness"), material.roughness());
            GL20.glUniform1f(program.uniform("u_alpha_cutoff"), material.alphaCutoff());
            GL20.glUniform1i(program.uniform("u_vertex_color"),
                    (material.flags() & MaterialTransfer.FLAG_VERTEX_COLOR) != 0 ? 1 : 0);
            GL20.glUniform1i(program.uniform("u_fullbright"), part.look().fullbright() ? 1 : 0);
            // ADR-127 §5 : cartes de normales et ORM, chacune sur son unité, sous sa tuile.
            TextureRegion normal = part.asset().normalMap(part.rank());
            GL20.glUniform1i(program.uniform("u_normal_mapped"), normal == null ? 0 : 1);
            if (normal != null) {
                bindTexture(NativeShaders.NORMAL_UNIT, normal.binding());
                regionUniform(program, "u_normal_region", normal);
                GL20.glUniform1f(program.uniform("u_normal_scale"), material.normalScale());
            }
            TextureRegion orm = part.asset().orm(part.rank());
            GL20.glUniform1i(program.uniform("u_orm_mapped"), orm == null ? 0 : 1);
            if (orm != null) {
                bindTexture(NativeShaders.ORM_UNIT, orm.binding());
                regionUniform(program, "u_orm_region", orm);
                GL20.glUniform1f(program.uniform("u_occlusion_strength"), material.occlusionStrength());
            }
            draw(part, instance, region);
        }
    }

    /** Passe 5 : l'émission des meshes qui émettent, leur texture d'émission ou du blanc. */
    private void drawEmission(List<Part> parts, Environment environment) {
        NativeShaders.Program program = shaders.emission();
        boolean used = false;
        for (int instance = 0; instance < parts.size(); instance++) {
            Part part = parts.get(instance);
            if (!part.asset().emits(part.rank())) {
                continue;
            }
            if (!used) {
                GlStateManager._glUseProgram(program.id);
                environment.upload(program, false);
                used = true;
            }
            TextureRegion region = part.asset().emission(part.rank());
            meshUniforms(program, part, region);
            float[] emissive = part.look().material().emissiveFactor();
            GL20.glUniform3f(program.uniform("u_emissive_factor"), emissive[0], emissive[1], emissive[2]);
            // La couleur des sommets ne module pas l'émission (sémantique glTF).
            GL20.glUniform1i(program.uniform("u_vertex_color"), 0);
            draw(part, instance, region);
        }
    }

    /** Uniformes de lecture des coordonnées de texture d'un mesh : sa plage d'UV, puis sa tuile. */
    private static void meshUniforms(NativeShaders.Program program, Part part, TextureRegion region) {
        GL20.glUniform2f(program.uniform("u_uv_range"), part.mesh().uvMin(), part.mesh().uvSpan());
        if (region == null) {
            GL20.glUniform4f(program.uniform("u_uv_region"), 0.0f, 0.0f, 1.0f, 1.0f);
        } else {
            GL20.glUniform4f(program.uniform("u_uv_region"),
                    region.uOffset(), region.vOffset(), region.uScale(), region.vScale());
        }
    }

    /** Dessine un mesh posé, depuis son arène, sous son bloc d'instance. */
    private void draw(Part part, int instance, TextureRegion region) {
        if (part.gpu().vao() != boundVao) {
            GL30.glBindVertexArray(part.gpu().vao());
            boundVao = part.gpu().vao();
        }
        meshes.pointInstance(instance);
        bindTexture(NativeShaders.ALBEDO_UNIT, region == null ? VanillaConsumerBackend.NEUTRAL : region.binding());
        if (part.look().doubleSided()) {
            RenderSystem.disableCull();
        } else {
            RenderSystem.enableCull();
        }
        // Une transformation miroir retourne les faces : la face avant change de sens.
        int front = InstanceLayout.mirrors(part.rows()) ? GL11.GL_CW : GL11.GL_CCW;
        if (front != frontFace) {
            GL11.glFrontFace(front);
            frontFace = front;
        }
        GL32.glDrawElementsInstancedBaseVertex(
                GL11.GL_TRIANGLES,
                part.mesh().indexCount(),
                GL11.GL_UNSIGNED_INT,
                part.gpu().indexByteOffset(part.mesh()),
                1,
                part.gpu().baseVertex(part.mesh()));
    }

    /**
     * Lie une texture à une unité, filtrée comme à son téléversement : Minecraft réapplique le
     * filtrage à chaque liaison, comme le fait un type de rendu.
     */
    private void bindTexture(int unit, TextureBinding binding) {
        if (binding.equals(boundTextures[unit])) {
            return;
        }
        AbstractTexture texture =
                Minecraft.getInstance().getTextureManager().getTexture(VanillaTexturePipeline.location(binding.location()));
        // setFilter lie la texture à l'unité active : celle qu'on vise, d'abord.
        RenderSystem.activeTexture(GL13.GL_TEXTURE0 + unit);
        texture.setFilter(binding.blur(), binding.mipmap());
        RenderSystem.bindTexture(texture.getId());
        boundTextures[unit] = binding;
    }

    /** Pose la tuile d'une texture : décalage, puis échelle, en u et en v. */
    private static void regionUniform(NativeShaders.Program program, String name, TextureRegion region) {
        GL20.glUniform4f(program.uniform(name), region.uOffset(), region.vOffset(), region.uScale(), region.vScale());
    }

    /** Referme une passe sur l'état que laissent les {@code RenderType} de Minecraft. */
    private void finish(int activeTexture) {
        if (frontFace != GL11.GL_CCW) {
            GL11.glFrontFace(GL11.GL_CCW);
            frontFace = GL11.GL_CCW;
        }
        GL30.glBindVertexArray(0);
        boundVao = 0;
        BufferUploader.invalidate();
        GlStateManager._glUseProgram(0);
        for (int unit : new int[] {
            NativeShaders.ORM_UNIT, NativeShaders.LIGHTMAP_UNIT, NativeShaders.NORMAL_UNIT, NativeShaders.ALBEDO_UNIT
        }) {
            RenderSystem.activeTexture(GL13.GL_TEXTURE0 + unit);
            RenderSystem.bindTexture(0);
        }
        RenderSystem.activeTexture(activeTexture);
        Arrays.fill(boundTextures, null);
        RenderSystem.disableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(true);
        RenderSystem.disableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.enableCull();
    }

    /**
     * Un mesh posé.
     *
     * @param asset son asset
     * @param gpu l'asset sur le GPU
     * @param mesh le mesh
     * @param rank son rang dans l'asset
     * @param look son apparence
     * @param rows sa transformation, relative à la caméra : les trois lignes du bloc d'instance
     * @param light lumière empaquetée au centre de son assembly
     */
    private record Part(
            RenderAsset asset,
            NativeMeshes.GpuAsset gpu,
            GeometryTransfer.Mesh mesh,
            int rank,
            MeshLook look,
            float[] rows,
            int light) {}

    /**
     * Ce qui vaut pour toute une passe : la vue, la lumière du monde, le brouillard, la lightmap.
     *
     * @param projection projection de la frame
     * @param view vue : celle de Minecraft, puis la rotation de la caméra
     * @param light soleil ou lune
     * @param sky ciel de l'hémisphère ambiant, en part de la lumière que porte la lightmap
     * @param ground sol de l'hémisphère ambiant, idem
     * @param fogColor couleur du brouillard, gamma, et son opacité
     * @param fogStart début du brouillard
     * @param fogEnd fin du brouillard
     * @param fogShape forme du brouillard : sphère 0, cylindre 1
     * @param lightmap texture de la lightmap de Minecraft
     */
    private record Environment(
            Matrix4f projection,
            Matrix4f view,
            WorldLight.Light light,
            float[] sky,
            float[] ground,
            float[] fogColor,
            float fogStart,
            float fogEnd,
            int fogShape,
            int lightmap) {

        static Environment of(Frame frame) {
            Minecraft minecraft = Minecraft.getInstance();
            ClientLevel level = minecraft.level;
            float partialTick = frame.partialTick();
            Matrix4f view = new Matrix4f(RenderSystem.getModelViewMatrix()).mul(frame.pose().last().pose());
            WorldLight.Light light = WorldLight.direct(
                    level.getTimeOfDay(partialTick),
                    level.getSkyDarken(partialTick),
                    level.getMoonBrightness(),
                    level.getRainLevel(partialTick),
                    level.dimensionType().hasSkyLight());
            Vec3 sky = level.getSkyColor(frame.camera(), partialTick);
            float[] fog = RenderSystem.getShaderFogColor().clone();
            WorldLight.Hemisphere ambient = WorldLight.hemisphere(sky.x, sky.y, sky.z, fog);
            return new Environment(
                    new Matrix4f(RenderSystem.getProjectionMatrix()),
                    view,
                    light,
                    new float[] {ambient.sky(), ambient.sky(), ambient.sky()},
                    new float[] {ambient.ground(), ambient.ground(), ambient.ground()},
                    fog,
                    RenderSystem.getShaderFogStart(),
                    RenderSystem.getShaderFogEnd(),
                    RenderSystem.getShaderFogShape().getIndex(),
                    lightmapTexture(minecraft));
        }

        /**
         * {@return la texture de la lightmap} Minecraft ne la donne qu'en l'installant comme texture
         * de shader : elle l'est, le temps de lire son nom, puis l'état d'avant revient.
         */
        private static int lightmapTexture(Minecraft minecraft) {
            int previous = RenderSystem.getShaderTexture(NativeShaders.LIGHTMAP_UNIT);
            minecraft.gameRenderer.lightTexture().turnOnLightLayer();
            int lightmap = RenderSystem.getShaderTexture(NativeShaders.LIGHTMAP_UNIT);
            RenderSystem.setShaderTexture(NativeShaders.LIGHTMAP_UNIT, previous);
            return lightmap;
        }

        /**
         * Pose les uniformes de la passe sur le programme lié.
         *
         * @param lit vrai pour un programme de surface : lumière et ambiante en plus
         */
        void upload(NativeShaders.Program program, boolean lit) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                FloatBuffer matrix = stack.mallocFloat(16);
                GL20.glUniformMatrix4fv(program.uniform("u_projection"), false, projection.get(matrix));
                GL20.glUniformMatrix4fv(program.uniform("u_view"), false, view.get(matrix));
            }
            GL20.glUniform1f(program.uniform("u_fog_start"), fogStart);
            GL20.glUniform1f(program.uniform("u_fog_end"), fogEnd);
            GL20.glUniform1i(program.uniform("u_fog_shape"), fogShape);
            if (lit) {
                float[] direction = light.direction();
                float[] color = light.color();
                GL20.glUniform3f(program.uniform("u_light_direction"), direction[0], direction[1], direction[2]);
                GL20.glUniform3f(program.uniform("u_light_color"), color[0], color[1], color[2]);
                GL20.glUniform3f(program.uniform("u_sky_color"), sky[0], sky[1], sky[2]);
                GL20.glUniform3f(program.uniform("u_ground_color"), ground[0], ground[1], ground[2]);
                GL20.glUniform4f(program.uniform("u_fog_color"), fogColor[0], fogColor[1], fogColor[2], fogColor[3]);
            }
        }
    }
}
