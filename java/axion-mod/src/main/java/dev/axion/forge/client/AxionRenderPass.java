package dev.axion.forge.client;

import dev.axion.AxionMod;
import dev.axion.asset.AssetRegistry;
import dev.axion.asset.GeometryTransfer;
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
 * instancing (C-65) puissent s'y loger. Chaque passe a son stage (R-1570) : OPAQUE à
 * {@code AFTER_ENTITIES}, DEBUG à {@code AFTER_PARTICLES} ; la passe translucide viendra avec
 * les matériaux (C-26), à {@code AFTER_TRANSLUCENT_BLOCKS}.
 *
 * <p>Le backend est choisi au chargement de chaque monde client (R-1490), d'après
 * {@code render.backend} relu à ce moment-là. Tout se passe sur le render thread, sauf le
 * chargement des maillages (ADR-119) : le {@link MeshCache} le confie à un thread de fond, et
 * le backend dessine une boîte de repli tant qu'il n'a pas abouti. Chaque choix de backend
 * journalise, en une entrée, ce qu'il sait faire et ce qu'il ne sait pas faire (R-1493).
 *
 * <p>Les overlays de debug (C-67, ADR-121) forment la passe DEBUG ; leur géométrie est
 * demandée au natif une fois par tick client, et seulement s'ils sont allumés (R-2280). Une
 * erreur les éteint sans arrêter le rendu des assemblies.
 */
@Mod.EventBusSubscriber(modid = AxionMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class AxionRenderPass {

    private static final Logger LOGGER = LoggerFactory.getLogger("axion");

    /** Le backend natif (C-60) n'est pas encore livré : la sélection retombe sur vanilla. */
    private static final boolean NATIVE_GL_AVAILABLE = false;

    /** Identifiants des mods de shaders sous Forge : leur présence impose vanilla (fiche 5.48). */
    private static final String[] SHADER_MODS = {"oculus", "iris"};

    /** Backend actif ; {@code null} tant qu'aucun monde n'a été chargé. Render thread seul. */
    private static RenderBackend backend;

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

    private AxionRenderPass() {}

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
            backend = select();
            failed = false;
        }
    }

    /**
     * Dessine chaque passe à son stage (R-1570) : OPAQUE à {@code AFTER_ENTITIES}, DEBUG à
     * {@code AFTER_PARTICLES}.
     *
     * @param event étape du rendu du niveau
     */
    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (failed) {
            return;
        }
        RenderLevelStageEvent.Stage stage = event.getStage();
        if (stage == RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            renderOpaque(event);
        } else if (stage == RenderLevelStageEvent.Stage.AFTER_PARTICLES && OVERLAYS.mask() != 0L) {
            // Éteints, les overlays ne coûtent que ce test (R-2280).
            renderDebug(event);
        }
    }

    /** Passe OPAQUE (§19.10, passe 1). */
    private static void renderOpaque(RenderLevelStageEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        List<RenderBackend.Assembly> assemblies = assemblies(minecraft.level, true);
        if (assemblies.isEmpty()) {
            return;
        }
        try {
            if (backend == null) {
                backend = select();
            }
            backend.renderOpaque(frame(event, minecraft, assemblies));
        } catch (RuntimeException failure) {
            failed = true;
            LOGGER.error("AXION : passe de rendu désactivée après une erreur", failure);
        }
    }

    /** Passe DEBUG (§19.10, passe 6) : les overlays allumés, après le reste du niveau. */
    private static void renderDebug(RenderLevelStageEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        List<RenderBackend.Assembly> assemblies = assemblies(minecraft.level, false);
        if (assemblies.isEmpty()) {
            return;
        }
        try {
            DEBUG.draw(frame(event, minecraft, assemblies));
        } catch (RuntimeException failure) {
            disableOverlays(failure);
        }
    }

    /**
     * {@return les assemblies à dessiner, avec leur géométrie si la passe en a besoin}
     *
     * <p>Une assembly inerte n'a pas de definition, donc rien à dessiner. La passe DEBUG ne
     * dessine que des lignes : elle ne demande pas les maillages.
     */
    private static List<RenderBackend.Assembly> assemblies(ClientLevel level, boolean withMeshes) {
        List<RenderBackend.Assembly> assemblies = new ArrayList<>();
        if (level == null) {
            return assemblies;
        }
        AxionRuntime runtime = withMeshes ? RuntimeAccess.get() : null;
        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof AxionEntity assembly && !assembly.isInert()) {
                GeometryTransfer mesh = withMeshes ? meshOf(runtime, assembly) : null;
                assemblies.add(new RenderBackend.Assembly(assembly, mesh));
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
        if (meshes != null) {
            meshes.releaseAll();
        }
        // Un identifiant d'entité ne vaut que pour le serveur qui l'a donné : une géométrie
        // gardée d'ici se poserait, au monde suivant, sur d'autres assemblies.
        DEBUG.reset();
    }

    /**
     * {@return la géométrie prête d'une assembly, ou {@code null}}
     *
     * <p>Definition → asset → contenu publié → maillage. Chaque maillon peut manquer : runtime
     * natif absent, monde distant (definitions et assets ne vivent qu'avec le serveur intégré —
     * le maillage en multijoueur est hors portée d'ADR-119), asset en compilation ou refusé,
     * maillage en chargement. Le backend dessine alors la boîte de repli.
     */
    private static GeometryTransfer meshOf(AxionRuntime runtime, AxionEntity assembly) {
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
        RenderAsset asset = cache == null ? null : cache.get(definition.asset(), source);
        return asset == null ? null : asset.mesh();
    }

    /**
     * Rechargement des ressources du client (R-752) : maillages et textures sont rendus, puis
     * reconstruits à la demande. Appelé sur le fil de rendu.
     */
    static void onResourcesReloaded() {
        if (meshes != null) {
            meshes.releaseAll();
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
            meshes = cache;
        }
        return meshes;
    }

    /** Choisit le backend d'après la configuration client et l'environnement. */
    private static RenderBackend select() {
        AxionConfig config =
                ConfigLoader.load(Scope.CLIENT, FMLPaths.CONFIGDIR.get(), System.getProperties());
        boolean shaderMod = false;
        for (String id : SHADER_MODS) {
            shaderMod |= ModList.get().isLoaded(id);
        }
        BackendSelection.Selection selection = BackendSelection.select(
                config.getString("render.backend"), NATIVE_GL_AVAILABLE, shaderMod);
        RenderCapabilities capabilities = RenderCapabilities.of(selection);
        // R-1493 : une entrée unique au démarrage du backend, ce qu'il ne sait pas faire compris.
        LOGGER.info("AXION : rendu — {}", String.join("\n  ", capabilities.describe()));
        AxionRuntime runtime = RuntimeAccess.get();
        if (runtime != null) {
            runtime.setRenderCapabilities(capabilities);
        }
        return switch (selection.kind()) {
            case VANILLA -> new VanillaConsumerBackend();
            case NATIVE -> throw new IllegalStateException(
                    "backend natif retenu alors qu'il n'est pas disponible");
        };
    }
}
