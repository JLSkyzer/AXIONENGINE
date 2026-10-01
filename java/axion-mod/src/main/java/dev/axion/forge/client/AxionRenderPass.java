package dev.axion.forge.client;

import dev.axion.AxionMod;
import dev.axion.config.AxionConfig;
import dev.axion.config.ConfigLoader;
import dev.axion.config.ConfigSchema.Scope;
import dev.axion.forge.AxionEntity;
import dev.axion.render.BackendSelection;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
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
 * instancing (C-65) puissent s'y loger. Premier périmètre : passe OPAQUE au stage
 * {@code AFTER_ENTITIES} (R-1570).
 *
 * <p>Le backend est choisi au chargement de chaque monde client (R-1490), d'après
 * {@code render.backend} relu à ce moment-là. Tout se passe sur le render thread.
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

    private AxionRenderPass() {}

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
     * Dessine les assemblies au stage de leur passe.
     *
     * @param event étape du rendu du niveau
     */
    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (failed || event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            return;
        }
        List<AxionEntity> assemblies = new ArrayList<>();
        for (Entity entity : level.entitiesForRendering()) {
            // Une assembly inerte n'a pas de definition, donc rien à dessiner.
            if (entity instanceof AxionEntity assembly && !assembly.isInert()) {
                assemblies.add(assembly);
            }
        }
        if (assemblies.isEmpty()) {
            return;
        }
        try {
            if (backend == null) {
                backend = select();
            }
            backend.renderOpaque(new RenderBackend.Frame(
                    event.getPoseStack(),
                    event.getCamera().getPosition(),
                    event.getPartialTick(),
                    minecraft.renderBuffers().bufferSource(),
                    assemblies));
        } catch (RuntimeException failure) {
            failed = true;
            LOGGER.error("AXION : passe de rendu désactivée après une erreur", failure);
        }
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
        LOGGER.info("AXION : backend de rendu {} ({})", selection.kind(), selection.reason());
        return switch (selection.kind()) {
            case VANILLA -> new VanillaConsumerBackend();
            case NATIVE -> throw new IllegalStateException(
                    "backend natif retenu alors qu'il n'est pas disponible");
        };
    }
}
