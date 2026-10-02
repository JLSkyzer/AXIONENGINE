package dev.axion.forge.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.axion.forge.AxionEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.phys.EntityHitResult;

/**
 * Rendu de debug d'une AxionEntity.
 *
 * <p>La géométrie de l'entité n'est pas dessinée ici mais par la passe centrale
 * ({@link AxionRenderPass}, C-61) : une passe pour toutes les assemblies, pas un
 * rendu par entité. Ce rendu-ci ajoute aux hitbox vanilla ({@code F3+B}) le
 * libellé de la definition, en attendant les overlays du debug renderer (C-67).
 *
 * <p>Le libellé n'est produit que lorsque les hitbox sont affichées : sans
 * elles, le rendu ne coûte qu'un test de drapeau, dans l'esprit de R-800
 * (décision de Killian du 2026-09-13).
 */
final class AxionEntityRenderer extends EntityRenderer<AxionEntity> {

    AxionEntityRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(
            AxionEntity entity,
            float yaw,
            float partialTick,
            PoseStack pose,
            MultiBufferSource buffers,
            int packedLight) {
        super.render(entity, yaw, partialTick, pose, buffers, packedLight);
        if (entityRenderDispatcher.shouldRenderHitBoxes() && !entity.isInvisible()) {
            Component label = entity.debugLabel();
            // La ligne « Targeted Entity » de F3 ne montre que les entités vivantes et les
            // cadres ; c'est donc ici que la visée d'une assembly se voit (R-702).
            if (Minecraft.getInstance().hitResult instanceof EntityHitResult hit
                    && hit.getEntity() == entity) {
                label = label.copy().append(" — visée");
            }
            renderNameTag(entity, label, pose, buffers, packedLight);
        }
    }

    @Override
    public ResourceLocation getTextureLocation(AxionEntity entity) {
        // Aucune texture n'est échantillonnée : l'atlas des blocs, toujours
        // chargé, satisfait le contrat sans en charger une autre.
        return InventoryMenu.BLOCK_ATLAS;
    }
}
