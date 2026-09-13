package dev.axion.forge.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.axion.forge.AxionEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;

/**
 * Rendu de debug d'une AxionEntity en M2.
 *
 * <p>L'entité n'a pas encore de géométrie rendue : le rendu d'assets arrive en
 * M3 (C-60..C-63), avec les overlays du debug renderer (C-67). En M2, elle se
 * voit par les hitbox vanilla ({@code F3+B}), que Minecraft dessine pour toute
 * entité, et ce rendu y ajoute le libellé de sa definition.
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
            renderNameTag(entity, entity.debugLabel(), pose, buffers, packedLight);
        }
    }

    @Override
    public ResourceLocation getTextureLocation(AxionEntity entity) {
        // Aucune texture n'est échantillonnée : l'atlas des blocs, toujours
        // chargé, satisfait le contrat sans en charger une autre.
        return InventoryMenu.BLOCK_ATLAS;
    }
}
