package dev.axion.forge.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.axion.AxionMod;
import dev.axion.asset.AssetRegistry;
import dev.axion.asset.NativeAssetLoader;
import dev.axion.bootstrap.BootstrapOutcome;
import dev.axion.config.AxionConfig;
import dev.axion.config.ConfigLoader;
import dev.axion.config.ConfigSchema.Scope;
import dev.axion.debug.DebugOverlays;
import dev.axion.definition.Definition;
import dev.axion.forge.AxionEntity;
import dev.axion.forge.RuntimeAccess;
import dev.axion.lifecycle.AxionRuntime;
import dev.axion.render.BackendSelection;
import dev.axion.render.MeshCache;
import dev.axion.render.RenderAsset;
import dev.axion.render.RenderCapabilities;
import dev.axion.render.ShaderVariants;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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

    /** Identifiants des mods de shaders sous Forge : leur présence impose vanilla (fiche 5.48). */
    private static final String[] SHADER_MODS = {"oculus", "iris"};

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
     * Chargement d'un monde client : (re)choix du backend (R-1490).
     *
     * @param event chargement d'un niveau
     */
    @SubscribeEvent
    public static void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel().isClientSide()) {
            closeBackend();
            nativeRefused = false;
            backend = select();
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
        RenderLevelStageEvent.Stage stage = event.getStage();
        if (stage == RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            renderSurfaces(event, false);
        } else if (stage == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            renderSurfaces(event, true);
        } else if (stage == RenderLevelStageEvent.Stage.AFTER_PARTICLES && OVERLAYS.mask() != 0L) {
            // Éteints, les overlays ne coûtent que ce test (R-2280).
            renderDebug(event);
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
            MeshCache cache = new MeshCache(
                    new NativeAssetLoader(outcome.context()),
                    new VanillaTexturePipeline(),
                    LOADER,
                    Minecraft.getInstance(),
                    line -> LOGGER.warn("AXION : maillage {}", line));
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
        boolean shaderMod = false;
        for (String id : SHADER_MODS) {
            shaderMod |= ModList.get().isLoaded(id);
        }
        String requested = config.getString("render.backend");
        NativeGlBackend candidate = shaderMod || nativeRefused || "vanilla".equals(requested) ? null : startNative();
        BackendSelection.Selection selection = BackendSelection.select(requested, candidate != null, shaderMod);
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
        long budget = ConfigLoader.load(Scope.COMMON, FMLPaths.CONFIGDIR.get(), System.getProperties())
                .getInt("budgets.gpu_mem_bytes");
        int variants = (int) Math.min(Integer.MAX_VALUE,
                ConfigLoader.load(Scope.CLIENT, FMLPaths.CONFIGDIR.get(), System.getProperties())
                        .getInt("render.max_shader_variants"));
        // R-760 : le cache binaire des programmes, sous <gameDir>/axion/cache/shaders/.
        Path cache = FMLPaths.GAMEDIR.get().resolve("axion").resolve("cache").resolve("shaders");
        try {
            return NativeGlBackend.create(Minecraft.getInstance().getResourceManager(), budget, cache, variants);
        } catch (NativeShaders.ShaderFailure failure) {
            LOGGER.error("AXION : shaders du backend natif refusés, bascule sur vanilla (E-4001) — {}",
                    failure.getMessage());
            return null;
        }
    }
}
