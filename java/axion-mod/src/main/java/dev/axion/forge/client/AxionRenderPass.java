package dev.axion.forge.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.axion.AxionMod;
import dev.axion.asset.AssetRegistry;
import dev.axion.asset.NativeAssetLoader;
import dev.axion.bootstrap.BootstrapOutcome;
import dev.axion.config.AxionConfig;
import dev.axion.config.ConfigLoader;
import dev.axion.config.ConfigSchema.Scope;
import dev.axion.debug.DebugGeometry;
import dev.axion.debug.DebugOverlays;
import dev.axion.definition.Definition;
import dev.axion.forge.AxionEntity;
import dev.axion.forge.RuntimeAccess;
import dev.axion.lifecycle.AxionRuntime;
import dev.axion.diag.JavaMetrics;
import dev.axion.render.BackendSelection;
import dev.axion.render.GpuDegradation;
import dev.axion.render.GpuPassTimings;
import dev.axion.render.MeshCache;
import dev.axion.render.RenderAsset;
import dev.axion.render.RenderCapabilities;
import dev.axion.render.ShaderVariants;
import dev.axion.render.ShaderpackProbe;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Passe de rendu centrale des assemblies (ADR-118, §19.1, §19.10).
 *
 * <p>AXION dessine ses objets <b>en plus</b> du monde vanilla, dans le même framebuffer, sans
 * altérer aucune passe vanilla (§19.1). Une seule passe collecte les assemblies visibles et
 * les confie au backend actif — pas un rendu par entité — pour que culling (C-64) et
 * instancing (C-65) puissent s'y loger. Chaque passe a son stage (R-1570) : OPAQUE, CUTOUT et
 * EMISSIVE à {@code AFTER_ENTITIES}, TRANSLUCENT à {@code AFTER_TRANSLUCENT_BLOCKS}, DEBUG à
 * {@code AFTER_PARTICLES}.
 *
 * <p>Le backend est choisi au chargement de chaque monde client et après chaque rechargement des
 * ressources (R-1490), d'après {@code render.backend} relu à ce moment-là ; celui qu'il remplace
 * rend d'abord ce qu'il tient sur le GPU. Tout se passe sur le render thread, sauf le
 * chargement des assets (ADR-119, ADR-122) : le {@link MeshCache} confie à un thread de fond le
 * maillage, l'apparence des meshes et la préparation des textures, puis téléverse celles-ci sur
 * le render thread ; le backend dessine une boîte de repli tant que rien n'a abouti. Chaque choix
 * de backend journalise, en une entrée, ce qu'il sait faire et ce qu'il ne sait pas faire
 * (R-1493).
 *
 * <p>Les overlays de debug (C-67, ADR-121) forment la passe DEBUG ; leur géométrie est
 * demandée au natif une fois par tick client, et seulement s'ils sont allumés (R-2280). Une
 * erreur les éteint sans arrêter le rendu des assemblies.
 */
@Mod.EventBusSubscriber(modid = AxionMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class AxionRenderPass {

    private static final Logger LOGGER = LoggerFactory.getLogger("axion");

    /**
     * Identifiants des mods de shaders sous Forge : leur présence fait interroger l'état de leur
     * shaderpack, qui seul impose vanilla (fiche 5.48, ADR-129).
     */
    private static final String[] SHADER_MODS = {"oculus", "iris"};

    /** Sonde de l'état du shaderpack ; créée au premier choix de backend. Render thread seul. */
    private static ShaderpackProbe shaderpack;

    /** Shaderpack en usage au dernier choix de backend : un changement le fait refaire (R-1490). */
    private static boolean shaderpackAtSelection;

    /** Vrai une fois dit qu'un mod de shaders est là sans que l'état de son pack se lise. */
    private static boolean shaderpackUnreadableLogged;

    /** Backend actif ; {@code null} tant qu'aucun monde n'a été chargé. Render thread seul. */
    private static RenderBackend backend;

    /** Choix de backend depuis le lancement. Render thread seul. */
    private static int backendStarts;

    /**
     * Vrai après qu'un shader compilé à la demande a été refusé (R-761) : le natif n'est plus retenté
     * avant le prochain rechargement des ressources ou le prochain monde. Render thread seul.
     */
    private static boolean nativeRefused;

    /** Vrai après une erreur de rendu : la passe se tait plutôt que d'échouer à chaque frame. */
    private static boolean failed;

    /**
     * Thread de fond des chargements de maillage (décision 2 d'ADR-119) : dédié, pour qu'un
     * décodage ne prenne jamais la place d'un travail de Minecraft, et démon, pour ne pas
     * retenir la fermeture du processus.
     */
    private static final Executor LOADER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "AXION mesh loader");
        thread.setDaemon(true);
        return thread;
    });

    /** Maillages chargés ; créé au premier besoin. Render thread seul. */
    private static MeshCache meshes;

    /** Overlays de debug allumés : la commande client les modifie, la passe les lit. */
    private static final DebugOverlays OVERLAYS = new DebugOverlays();

    /** Géométrie et dessin des overlays de debug. Thread client seul. */
    private static final DebugOverlayRenderer DEBUG = new DebugOverlayRenderer(OVERLAYS);

    /** Faux : les passes d'AXION ne dessinent rien. Seul le banc de rendu le coupe (ADR-126). */
    private static boolean passesEnabled = true;

    /** Relevé de la dernière passe opaque. Render thread seul. */
    private static FrameStats lastFrame = new FrameStats(0, 0);

    /** Ce que la tenue du budget GPU a fait à la dernière frame (R-750, ADR-133). Render thread. */
    private static MeshCache.Residency lastResidency = new MeshCache.Residency(0, 0, false);

    /**
     * Paliers de la tenue du budget GPU (R-750, T-484), créés avec le cache ; chaque transition
     * est journalisée avec sa cause (§19.12, R-1880). Render thread.
     */
    private static GpuDegradation gpuDegradation;

    // INV-19, ADR-133 D1 : les budgets du rendu se mesurent ici, et entrent dans l'export.
    private static final JavaMetrics METRICS = JavaMetrics.GLOBAL;
    private static final JavaMetrics.Budget RENDER_PREP = METRICS.budget("budgets.render_prep_ns", "ns");
    private static final JavaMetrics.Budget GPU_MEM = METRICS.budget("budgets.gpu_mem_bytes", "bytes");
    private static final JavaMetrics.Budget VISIBLE = METRICS.budget("budgets.max_visible_instances", "count");
    private static final JavaMetrics.Budget TRIANGLES = METRICS.budget("budgets.max_triangles_frame", "count");
    private static final JavaMetrics.Metric BACKEND = METRICS.gauge("axion.render.backend", "id");
    private static final JavaMetrics.Metric TEXTURE_BYTES = METRICS.gauge("axion.render.gpu_texture_bytes", "bytes");
    private static final JavaMetrics.Metric MESH_BYTES = METRICS.gauge("axion.render.gpu_mesh_bytes", "bytes");
    private static final JavaMetrics.Metric EVICTIONS = METRICS.counter("axion.render.gpu_evictions", "count");
    private static final JavaMetrics.Metric GPU_LEVEL = METRICS.gauge("axion.render.gpu_degradation_level", "level");
    private static final JavaMetrics.Metric GL_OBJECTS = METRICS.gauge("axion.render.gl_objects", "count");
    private static final JavaMetrics.Metric SHADER_COMPILED = METRICS.gauge("axion.render.shader.compiled", "count");
    private static final JavaMetrics.Metric SHADER_PENDING = METRICS.gauge("axion.render.shader.pending", "count");
    private static final JavaMetrics.Metric SHADER_REFUSED = METRICS.gauge("axion.render.shader.refused", "count");
    private static final JavaMetrics.Metric SHADER_CACHE_HITS = METRICS.gauge("axion.render.shader.cache_hits", "count");
    private static final JavaMetrics.Metric SHADER_CACHE_MISSES =
            METRICS.gauge("axion.render.shader.cache_misses", "count");
    // §31.1, ADR-146 §2 : le temps GPU des passes du backend natif ; nul hors de lui (R-741).
    private static final JavaMetrics.Metric GPU_OPAQUE_NS = METRICS.gauge("axion.render.gpu_opaque_ns", "ns");
    private static final JavaMetrics.Metric GPU_TRANSLUCENT_NS =
            METRICS.gauge("axion.render.gpu_translucent_ns", "ns");
    private static final JavaMetrics.Metric GPU_TIMINGS_DROPPED =
            METRICS.gauge("axion.render.gpu_timings_dropped", "count");

    /** Plafonds des budgets du rendu, lus à la création du cache d'assets ; zéro : aucun. */
    private static long renderPrepLimit;
    private static long visibleLimit;
    private static long trianglesLimit;

    /** Temps CPU des passes d'AXION à la frame courante, en nanosecondes. */
    private static long framePrepNanos;

    /** Vrai dès qu'une frame a été mesurée : la première n'a pas de précédente à publier. */
    private static boolean frameMeasured;

    private AxionRenderPass() {}

    /**
     * Relevé d'une passe opaque, pour le banc de rendu (ADR-126).
     *
     * @param assemblies assemblies à dessiner
     * @param withAsset celles dont l'asset était prêt ; les autres ont reçu la boîte de repli
     */
    public record FrameStats(int assemblies, int withAsset) {

        static FrameStats of(List<RenderBackend.Assembly> assemblies) {
            int ready = 0;
            for (RenderBackend.Assembly assembly : assemblies) {
                if (assembly.asset() != null) {
                    ready++;
                }
            }
            return new FrameStats(assemblies.size(), ready);
        }
    }

    /**
     * Coupe ou rétablit toutes les passes d'AXION. Le banc de rendu compare ainsi une même scène
     * avec et sans elles (T-470, ADR-126) ; rien d'autre ne l'appelle.
     *
     * @param enabled faux pour qu'AXION ne dessine plus rien
     */
    public static void setPassesEnabled(boolean enabled) {
        RenderSystem.assertOnRenderThread();
        passesEnabled = enabled;
    }

    /**
     * Fait échouer, ou non, la compilation des shaders du backend natif aux choix de backend
     * suivants. Le banc de rendu vérifie ainsi la bascule de R-761 (T-479) ; rien d'autre ne
     * l'appelle.
     *
     * @param failing vrai pour que la compilation échoue
     */
    public static void forceShaderFailure(boolean failing) {
        NativeShaders.forceFailure(failing);
    }

    /**
     * Fait échouer, ou non, les variantes de shader compilées à la demande qui suivent. Le banc de
     * rendu vérifie ainsi qu'un tel échec fait basculer sur vanilla (R-761) ; rien d'autre ne
     * l'appelle.
     *
     * @param failing vrai pour qu'elles échouent
     */
    public static void forceShaderVariantFailure(boolean failing) {
        NativeShaders.forceVariantFailure(failing);
    }

    /**
     * Oublie la variante des surfaces découpées du backend natif : sa prochaine demande la remet en
     * service, relue du cache ou compilée. Le banc de rendu mesure ainsi une mise en service à la
     * demande sur une scène établie (T-904) ; rien d'autre ne l'appelle.
     *
     * @param fromCache vrai pour qu'elle soit relue du cache binaire, faux pour qu'elle se compile
     * @return vrai si le natif est actif et qu'elle était prête
     */
    public static boolean forgetCutoutShaderVariant(boolean fromCache) {
        RenderSystem.assertOnRenderThread();
        return backend instanceof NativeGlBackend natif && natif.forgetCutout(fromCache);
    }

    /**
     * Compile, ou non, les variantes à la demande qui suivent comme sans compilation parallèle. Le
     * banc de rendu mesure ainsi ce chemin (T-904) ; rien d'autre ne l'appelle.
     *
     * @param serial vrai pour une variante par frame, dont l'état se lit à la frame suivante
     */
    public static void forceSerialShaderCompile(boolean serial) {
        NativeShaders.forceSerial(serial);
    }

    /**
     * {@return le temps, en nanosecondes, que les variantes à la demande du natif ont pris au render
     * thread à sa dernière frame ; 0 sans natif} Relevé par le banc de rendu (T-904).
     */
    public static long nativeShaderPollNanos() {
        RenderSystem.assertOnRenderThread();
        return backend instanceof NativeGlBackend natif ? natif.lastShaderPollNanos() : 0L;
    }

    /**
     * Les programmes du backend natif, tels que le banc de rendu les relève (T-512, T-905).
     *
     * @param cacheHits programmes relus du cache binaire
     * @param cacheMisses programmes cherchés au cache, absents, illisibles ou refusés
     * @param compiled programmes compilés depuis leurs sources
     * @param onDemand variantes mises en service à la demande
     * @param pending variantes en attente de compilation
     * @param refused variantes au-delà de la borne
     * @param cutoutReady vrai si la variante des surfaces découpées est prête
     */
    public record ShaderStats(
            int cacheHits, int cacheMisses, int compiled, int onDemand, int pending, int refused, boolean cutoutReady) {}

    /** {@return les programmes du backend natif actif, ou {@code null} s'il ne l'est pas} */
    public static ShaderStats nativeShaderStats() {
        RenderSystem.assertOnRenderThread();
        if (!(backend instanceof NativeGlBackend natif)) {
            return null;
        }
        NativeShaders.Stats stats = natif.shaderStats();
        return new ShaderStats(stats.cacheHits(), stats.cacheMisses(), stats.compiled(), stats.onDemand(),
                stats.pending(), stats.refused(), natif.cutoutState() == ShaderVariants.State.READY);
    }

    /** {@return le relevé de la dernière passe opaque} */
    public static FrameStats lastFrame() {
        RenderSystem.assertOnRenderThread();
        return lastFrame;
    }

    /** {@return le backend actif, ou {@code null} tant qu'aucun n'a été choisi} */
    public static BackendSelection.Kind activeBackend() {
        RenderSystem.assertOnRenderThread();
        return backend == null ? null : backend.kind();
    }

    /** {@return le nombre de choix de backend depuis le lancement : chacun en démarre un neuf} */
    public static int backendStarts() {
        RenderSystem.assertOnRenderThread();
        return backendStarts;
    }

    /**
     * {@return les objets GL que le backend natif tient en vie — zéro quand il n'est pas actif}
     * Le banc de rendu vérifie ainsi qu'une bascule ne laisse rien derrière elle (T-492).
     */
    public static int nativeGlObjects() {
        return NativeGlObjects.live();
    }

    /** {@return les fermetures du backend natif depuis le lancement} */
    public static int nativeCloses() {
        return NativeGlObjects.closes();
    }

    /** {@return les objets GL que la dernière fermeture du backend natif a laissés en vie : 0 attendu} */
    public static int nativeGlObjectsLeftAtLastClose() {
        return NativeGlObjects.leftAtLastClose();
    }

    /** {@return les overlays de debug, que la commande client allume et éteint} */
    static DebugOverlays overlays() {
        return OVERLAYS;
    }

    /**
     * Allume ou éteint tous les overlays de debug implémentés : le banc de rendu, qui n'a pas de
     * commande à taper (amendement A1 d'ADR-121). Render thread.
     *
     * @param on vrai pour les allumer
     */
    public static void setAllOverlays(boolean on) {
        for (DebugOverlays.Overlay overlay : DebugOverlays.Overlay.values()) {
            OVERLAYS.set(overlay, on);
        }
        if (!on) {
            DEBUG.reset();
        }
    }

    /** {@return les plages reçues au dernier tick, comptées par rang d'overlay au §31.4} */
    public static Map<Integer, Integer> debugRangesByOverlay() {
        Map<Integer, Integer> counts = new TreeMap<>();
        for (DebugGeometry.Range range : DEBUG.geometry().ranges()) {
            counts.merge(range.overlay(), 1, Integer::sum);
        }
        return counts;
    }

    /**
     * Chargement d'un monde client : le backend sera choisi de nouveau à sa première frame
     * (R-1490), quand un mod de shaders sait dire si son pack est en usage.
     *
     * @param event chargement d'un niveau
     */
    @SubscribeEvent
    public static void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel().isClientSide()) {
            closeBackend();
            nativeRefused = false;
            failed = false;
        }
    }

    /**
     * Dessine chaque passe à son stage (R-1570) : OPAQUE, CUTOUT et EMISSIVE à
     * {@code AFTER_ENTITIES}, TRANSLUCENT à {@code AFTER_TRANSLUCENT_BLOCKS}, DEBUG à
     * {@code AFTER_PARTICLES}.
     *
     * @param event étape du rendu du niveau
     */
    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (failed || !passesEnabled) {
            return;
        }
        RenderSystem.assertOnRenderThread();
        RenderLevelStageEvent.Stage stage = event.getStage();
        if (stage == RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            // Premier stage de la frame : le temps de la précédente se publie (INV-19).
            if (frameMeasured) {
                RENDER_PREP.report(framePrepNanos, renderPrepLimit);
            }
            framePrepNanos = 0;
            frameMeasured = true;
            long started = System.nanoTime();
            selectIfNeeded(event);
            renderSurfaces(event, false);
            framePrepNanos += System.nanoTime() - started;
        } else if (stage == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            long started = System.nanoTime();
            renderSurfaces(event, true);
            framePrepNanos += System.nanoTime() - started;
        } else if (stage == RenderLevelStageEvent.Stage.AFTER_PARTICLES && OVERLAYS.mask() != 0L) {
            // Éteints, les overlays ne coûtent que ce test (R-2280).
            long started = System.nanoTime();
            renderDebug(event);
            framePrepNanos += System.nanoTime() - started;
        }
    }

    /**
     * Passes OPAQUE, CUTOUT et EMISSIVE (§19.10, passes 1, 2 et 5), ou passe TRANSLUCENT (passe 4).
     *
     * @param translucent vrai pour la passe 4
     */
    private static void renderSurfaces(RenderLevelStageEvent event, boolean translucent) {
        Minecraft minecraft = Minecraft.getInstance();
        List<RenderBackend.Assembly> assemblies = assemblies(minecraft.level, true);
        if (!translucent) {
            lastFrame = FrameStats.of(assemblies);
            reportFrame(assemblies);
        }
        if (assemblies.isEmpty()) {
            return;
        }
        // R-1503 : en développement, l'état GL est relevé autour de la passe — après que son garde
        // (R-1502) a rendu celui qu'elle a trouvé.
        GlStateCheck.Snapshot before = GlStateCheck.ENABLED ? GlStateCheck.before() : null;
        GlStateGuard guard = GlStateGuard.open(event.getStage());
        try {
            if (backend == null) {
                backend = select();
            }
            RenderBackend.Frame frame = frame(event, minecraft, assemblies);
            if (translucent) {
                backend.renderTranslucent(frame);
            } else {
                enforceGpuBudget();
                backend.renderOpaque(frame);
            }
        } catch (RuntimeException failure) {
            failed = true;
            LOGGER.error("AXION : passe de rendu désactivée après une erreur", failure);
        } finally {
            guard.restore();
            if (before != null) {
                GlStateCheck.after(translucent ? "TRANSLUCENT" : "OPAQUE", before);
            }
        }
        // R-761 : un shader compilé à la demande et refusé par le pilote fait basculer sur vanilla,
        // jusqu'au prochain rechargement des ressources ou au prochain monde.
        String refused = backend == null ? null : backend.failure();
        if (refused != null) {
            LOGGER.error("AXION : shaders du backend natif refusés, bascule sur vanilla (E-4001) — {}", refused);
            closeBackend();
            nativeRefused = true;
            backend = select();
        }
    }

    /**
     * Tient {@code budgets.gpu_mem_bytes} avant la passe opaque, après que la frame a demandé ses
     * assets : textures des deux backends et maillages du natif (R-750, ADR-133 D3).
     */
    private static void enforceGpuBudget() {
        if (meshes == null) {
            return;
        }
        RenderBackend current = backend;
        lastResidency = meshes.enforceBudget(current::gpuBytes, current::evict);
        // INV-19 : la mémoire tenue, et un dépassement par frame qui a dû décharger, ou n'a pas pu.
        GPU_MEM.consumed().set(lastResidency.bytes());
        if (lastResidency.exceeded()) {
            GPU_MEM.overruns().increment();
        }
        EVICTIONS.add(lastResidency.evicted());
        TEXTURE_BYTES.set(meshes.textureBytes());
        MESH_BYTES.set(current.gpuBytes());
        GpuDegradation.Transition transition = gpuDegradation.observe(lastResidency, System.nanoTime());
        if (transition != null) {
            if (transition.degrades()) {
                LOGGER.warn("AXION : {}", transition.describe());
            } else {
                LOGGER.info("AXION : {}", transition.describe());
            }
        }
        GPU_LEVEL.set(gpuDegradation.level().ordinal());
    }

    /**
     * Publie ce que la frame dessine (INV-19, ADR-133 D1) : ses instances, les triangles des
     * maillages de leurs assets — la boîte de repli d'un asset absent n'y compte pas —, le backend,
     * les objets GL vivants et l'état des shaders du natif.
     */
    private static void reportFrame(List<RenderBackend.Assembly> assemblies) {
        long triangles = 0;
        for (RenderBackend.Assembly assembly : assemblies) {
            if (assembly.asset() != null) {
                triangles += assembly.asset().mesh().indexCount() / 3;
            }
        }
        VISIBLE.report(assemblies.size(), visibleLimit);
        TRIANGLES.report(triangles, trianglesLimit);
        BACKEND.set(backend == null ? 0 : backend.kind() == BackendSelection.Kind.NATIVE ? 2 : 1);
        GL_OBJECTS.set(NativeGlObjects.live());
        if (backend instanceof NativeGlBackend natif) {
            NativeShaders.Stats stats = natif.shaderStats();
            SHADER_COMPILED.set(stats.compiled());
            SHADER_PENDING.set(stats.pending());
            SHADER_REFUSED.set(stats.refused());
            SHADER_CACHE_HITS.set(stats.cacheHits());
            SHADER_CACHE_MISSES.set(stats.cacheMisses());
        }
        GpuPassTimings timings = backend instanceof NativeGlBackend natif ? natif.gpuTimings() : null;
        GPU_OPAQUE_NS.set(timings == null ? 0 : timings.lastNanos(GpuPassTimings.Pass.OPAQUE));
        GPU_TRANSLUCENT_NS.set(timings == null ? 0 : timings.lastNanos(GpuPassTimings.Pass.TRANSLUCENT));
        GPU_TIMINGS_DROPPED.set(timings == null ? 0 : timings.dropped());
    }

    /**
     * {@return le dernier temps GPU connu d'une passe du backend natif, en nanosecondes ; -1 hors de
     * lui} Le banc de rendu le juge (ADR-146 §2).
     */
    public static long gpuPassNanos(GpuPassTimings.Pass pass) {
        return backend instanceof NativeGlBackend natif ? natif.gpuTimings().lastNanos(pass) : -1;
    }

    /**
     * {@return les textures d'AXION vivantes sur le GPU : téléversées, pas encore fermées} Le banc
     * de rendu les compte à chaque bascule de backend (T-481).
     */
    public static int liveTextures() {
        return AxionTexture.live();
    }

    /** {@return ce que la tenue du budget GPU a fait à la dernière frame} */
    public static MeshCache.Residency lastResidency() {
        return lastResidency;
    }

    /** Passe DEBUG (§19.10, passe 6) : les overlays allumés, après le reste du niveau. */
    private static void renderDebug(RenderLevelStageEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        List<RenderBackend.Assembly> assemblies = assemblies(minecraft.level, false);
        if (assemblies.isEmpty()) {
            return;
        }
        GlStateCheck.Snapshot before = GlStateCheck.ENABLED ? GlStateCheck.before() : null;
        GlStateGuard guard = GlStateGuard.open(event.getStage());
        try {
            DEBUG.draw(frame(event, minecraft, assemblies));
        } catch (RuntimeException failure) {
            disableOverlays(failure);
        } finally {
            guard.restore();
            if (before != null) {
                GlStateCheck.after("DEBUG", before);
            }
        }
    }

    /**
     * {@return les assemblies à dessiner, avec leur asset si la passe en a besoin}
     *
     * <p>Une assembly inerte n'a pas de definition, donc rien à dessiner. La passe DEBUG ne
     * dessine que des lignes : elle ne demande pas les assets.
     */
    private static List<RenderBackend.Assembly> assemblies(ClientLevel level, boolean withAssets) {
        List<RenderBackend.Assembly> assemblies = new ArrayList<>();
        if (level == null) {
            return assemblies;
        }
        AxionRuntime runtime = withAssets ? RuntimeAccess.get() : null;
        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof AxionEntity assembly && !assembly.isInert()) {
                RenderAsset asset = withAssets ? assetOf(runtime, assembly) : null;
                assemblies.add(new RenderBackend.Assembly(assembly, asset));
            }
        }
        return assemblies;
    }

    private static RenderBackend.Frame frame(
            RenderLevelStageEvent event, Minecraft minecraft, List<RenderBackend.Assembly> assemblies) {
        return new RenderBackend.Frame(
                event.getPoseStack(),
                event.getCamera().getPosition(),
                event.getPartialTick(),
                minecraft.renderBuffers().bufferSource(),
                assemblies);
    }

    /**
     * Choisit le backend à la première frame d'un monde ou après un rechargement, et le choisit de
     * nouveau quand un shaderpack a été activé ou coupé depuis (R-1490), l'ancien rendant ses
     * ressources (R-744). Au stage {@code AFTER_ENTITIES} : le mod de shaders a préparé le pipeline
     * de la frame, et seul celui-ci dit si un pack est en usage — avant la première frame d'un
     * monde ou juste après un rechargement du pack, Oculus 1.8.0 répond « aucun » (bytecode de
     * {@code IrisApiV0Impl.isShaderPackInUse} : faux tant que son pipeline est nul). Aucun mod de
     * shaders n'annonce le changement : l'état se relit à chaque frame — sans mod de shaders, un
     * test de drapeau.
     */
    private static void selectIfNeeded(RenderLevelStageEvent event) {
        if (backend != null && shaderpack().inUse() == shaderpackAtSelection) {
            return;
        }
        // Le natif crée et rend des objets GL : sous garde, comme une passe (R-1502, R-1503).
        GlStateCheck.Snapshot before = GlStateCheck.ENABLED ? GlStateCheck.before() : null;
        GlStateGuard guard = GlStateGuard.open(event.getStage());
        try {
            closeBackend();
            backend = select();
        } finally {
            guard.restore();
            if (before != null) {
                GlStateCheck.after("CHOIX DU BACKEND", before);
            }
        }
    }

    /** {@return la sonde de l'état du shaderpack, créée au premier besoin} */
    private static ShaderpackProbe shaderpack() {
        if (shaderpack == null) {
            boolean modPresent = false;
            for (String id : SHADER_MODS) {
                modPresent |= ModList.get().isLoaded(id);
            }
            shaderpack = ShaderpackProbe.of(modPresent, ShaderpackProbe.IRIS_API);
        }
        return shaderpack;
    }

    /**
     * Fin de tick client : géométrie des overlays de debug allumés (ADR-121) — un appel natif
     * par tick, aucun quand tous sont éteints (R-2280).
     *
     * @param event tick client
     */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || failed) {
            return;
        }
        try {
            DEBUG.tick(RuntimeAccess.get(), Minecraft.getInstance());
        } catch (RuntimeException failure) {
            disableOverlays(failure);
        }
    }

    /** Après une erreur, les overlays s'éteignent : la passe continue, sans eux. */
    private static void disableOverlays(RuntimeException failure) {
        for (DebugOverlays.Overlay overlay : DebugOverlays.Overlay.values()) {
            OVERLAYS.set(overlay, false);
        }
        DEBUG.reset();
        LOGGER.error("AXION : overlays de debug éteints après une erreur", failure);
    }

    /**
     * Sortie d'un monde : les handles de maillage sont rendus au natif (ADR-119 §8, R-321).
     *
     * <p>Le cache resservira au monde suivant. Quitter le jeu depuis un monde passe aussi par
     * ici ; le libérateur posé sur le runtime couvre l'arrêt du natif dans tous les cas.
     *
     * @param event déconnexion du joueur local
     */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        closeBackend();
        if (meshes != null) {
            meshes.releaseAll();
        }
        // Un identifiant d'entité ne vaut que pour le serveur qui l'a donné : une géométrie
        // gardée d'ici se poserait, au monde suivant, sur d'autres assemblies.
        DEBUG.reset();
    }

    /**
     * {@return l'asset prêt d'une assembly, ou {@code null}}
     *
     * <p>Definition → asset → contenu publié → maillage, apparence et textures. Chaque maillon
     * peut manquer : runtime natif absent, monde distant (definitions et assets ne vivent qu'avec
     * le serveur intégré — le maillage en multijoueur est hors portée d'ADR-119), asset en
     * compilation ou refusé, chargement en cours. Le backend dessine alors la boîte de repli.
     */
    private static RenderAsset assetOf(AxionRuntime runtime, AxionEntity assembly) {
        if (runtime == null) {
            return null;
        }
        Definition definition = runtime.definitions().get(assembly.definitionId()).orElse(null);
        AssetRegistry assets = runtime.assets();
        if (definition == null || assets == null) {
            return null;
        }
        AssetRegistry.Published source = assets.published(definition.asset());
        if (source == null) {
            return null;
        }
        MeshCache cache = meshCache(runtime);
        return cache == null ? null : cache.get(definition.asset(), source);
    }

    /**
     * Rechargement des ressources du client (R-752) : maillages, textures et ressources GPU du backend
     * sont rendus, puis reconstruits à la demande ; le backend est choisi de nouveau à la passe
     * suivante (R-1490), ses shaders relus (R-760). Appelé sur le fil de rendu.
     */
    static void onResourcesReloaded() {
        closeBackend();
        nativeRefused = false;
        if (meshes != null) {
            meshes.releaseAll();
        }
    }

    /** Le backend actif rend ce qu'il tient sur le GPU ; le suivant sera choisi au besoin. */
    private static void closeBackend() {
        if (backend != null) {
            RenderBackend closing = backend;
            backend = null;
            closing.close();
        }
    }

    /**
     * {@return le cache de maillages, créé au premier besoin, ou {@code null} sans natif}
     *
     * <p>Il vit autant que le contexte natif : son libérateur, posé sur le runtime, rend ses
     * handles avant la fermeture du contexte, sur le thread qui arrête (R-322).
     */
    private static MeshCache meshCache(AxionRuntime runtime) {
        if (meshes == null) {
            BootstrapOutcome outcome = runtime.outcome();
            if (outcome == null || !outcome.isReady()) {
                return null;
            }
            // Le client est l'exécuteur de son propre fil de rendu : les textures s'y téléversent.
            long gpuBudget = outcome.config().getInt("budgets.gpu_mem_bytes");
            MeshCache cache = new MeshCache(
                    new NativeAssetLoader(outcome.context()),
                    new VanillaTexturePipeline(),
                    LOADER,
                    Minecraft.getInstance(),
                    line -> LOGGER.warn("AXION : maillage {}", line),
                    gpuBudget);
            gpuDegradation = new GpuDegradation(gpuBudget);
            renderPrepLimit = outcome.config().getInt("budgets.render_prep_ns");
            visibleLimit = outcome.config().getInt("budgets.max_visible_instances");
            trianglesLimit = outcome.config().getInt("budgets.max_triangles_frame");
            runtime.addNativeReleaser(cache::close);
            cache.setMaterialMaps(backend != null && backend.kind() == BackendSelection.Kind.NATIVE);
            meshes = cache;
        }
        return meshes;
    }

    /**
     * Choisit le backend d'après la configuration client et l'environnement (R-1490, ADR-127 §6) :
     * le natif quand il est demandé ou laissé au choix, hors shaderpack, et qu'il démarre.
     */
    private static RenderBackend select() {
        backendStarts++;
        AxionConfig config =
                ConfigLoader.load(Scope.CLIENT, FMLPaths.CONFIGDIR.get(), System.getProperties());
        boolean shaderpackInUse = shaderpack().inUse();
        shaderpackAtSelection = shaderpackInUse;
        if (shaderpack.failure() != null && !shaderpackUnreadableLogged) {
            shaderpackUnreadableLogged = true;
            LOGGER.warn("AXION : mod de shaders présent, état de son shaderpack illisible — tenu pour en usage ({})",
                    shaderpack.failure());
        }
        String requested = config.getString("render.backend");
        NativeGlBackend candidate =
                shaderpackInUse || nativeRefused || "vanilla".equals(requested) ? null : startNative();
        BackendSelection.Selection selection =
                BackendSelection.select(requested, candidate != null, shaderpackInUse);
        RenderCapabilities capabilities = RenderCapabilities.of(selection);
        // R-1493 : une entrée unique au démarrage du backend, ce qu'il ne sait pas faire compris.
        LOGGER.info("AXION : rendu — {}", String.join("\n  ", capabilities.describe()));
        AxionRuntime runtime = RuntimeAccess.get();
        if (runtime != null) {
            runtime.setRenderCapabilities(capabilities);
        }
        // ADR-127 §5 : le natif se sert des cartes de normales et ORM, le vanilla non (R-1513).
        boolean natif = selection.kind() == BackendSelection.Kind.NATIVE;
        if (meshes != null) {
            meshes.setMaterialMaps(natif);
        }
        if (natif) {
            return candidate;
        }
        if (candidate != null) {
            candidate.close();
        }
        return new VanillaConsumerBackend();
    }

    /**
     * {@return le backend natif démarré, ou {@code null} s'il ne peut pas l'être : contexte sans
     * OpenGL 3.3, ou shaders refusés — {@code E-4001}, journalisé avec le log du pilote, jamais un
     * crash (R-761)}
     */
    private static NativeGlBackend startNative() {
        String missing = NativeGlBackend.missingCapability();
        if (missing != null) {
            LOGGER.warn("AXION : backend natif indisponible — {}", missing);
            return null;
        }
        int variants = (int) Math.min(Integer.MAX_VALUE,
                ConfigLoader.load(Scope.CLIENT, FMLPaths.CONFIGDIR.get(), System.getProperties())
                        .getInt("render.max_shader_variants"));
        // R-760 : le cache binaire des programmes, sous <gameDir>/axion/cache/shaders/.
        Path cache = FMLPaths.GAMEDIR.get().resolve("axion").resolve("cache").resolve("shaders");
        try {
            return NativeGlBackend.create(Minecraft.getInstance().getResourceManager(), cache, variants);
        } catch (NativeShaders.ShaderFailure failure) {
            LOGGER.error("AXION : shaders du backend natif refusés, bascule sur vanilla (E-4001) — {}",
                    failure.getMessage());
            return null;
        }
    }
}
