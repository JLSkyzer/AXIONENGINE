package dev.axion.forge.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.axion.forge.AxionEntity;
import dev.axion.render.BackendSelection;
import dev.axion.render.RenderAsset;
import java.util.List;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.Vec3;

/**
 * Backend de rendu des assemblies (ADR-118, §19.2).
 *
 * <p>Deux implémentations à terme : le backend vanilla (C-61), qui passe par les
 * {@code RenderType} de Minecraft sans appel GL direct (R-741), et le backend natif (C-60),
 * pipeline OpenGL dédié. Tous deux respectent les mêmes invariants géométriques (R-1492) :
 * même position, orientation, échelle, LOD et géométrie, quelle que soit la façon de dessiner.
 *
 * <p>Appelé uniquement sur le render thread, par la passe centrale ({@link AxionRenderPass}).
 */
interface RenderBackend {

    /** {@return le type de ce backend} */
    BackendSelection.Kind kind();

    /**
     * Dessine les surfaces opaques puis découpées des assemblies (passes 1 et 2, stage
     * {@code AFTER_ENTITIES}, R-1570).
     *
     * @param frame contexte de la frame
     */
    void renderOpaque(Frame frame);

    /**
     * Contexte d'une frame de rendu.
     *
     * @param pose pile de transformations de la vue (rotation caméra appliquée, translation non)
     * @param camera position monde de la caméra
     * @param partialTick fraction du tick écoulée, pour interpoler les positions
     * @param buffers tampons de sommets de Minecraft
     * @param assemblies assemblies à dessiner
     */
    record Frame(
            PoseStack pose,
            Vec3 camera,
            float partialTick,
            MultiBufferSource.BufferSource buffers,
            List<Assembly> assemblies) {}

    /**
     * Une assembly et ce qu'il faut pour la dessiner.
     *
     * @param entity entité de l'assembly
     * @param asset géométrie, pose de repos, apparence des meshes et textures (ADR-119, ADR-122),
     *     ou {@code null} tant que rien n'est prêt — chargement en cours, échec, ou multijoueur :
     *     le backend dessine alors sa boîte de repli
     */
    record Assembly(AxionEntity entity, RenderAsset asset) {}
}
