package dev.axion.forge.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.axion.forge.AxionEntity;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/**
 * Pose interpolée d'une assembly pour une frame, et lumière du monde à son centre.
 *
 * <p>Partagée par tout ce qui se dessine en repère du corps — le maillage (C-61) comme les
 * overlays de debug (C-67, ADR-121) — pour qu'ils soient posés <b>exactement</b> au même
 * endroit : même position interpolée, même orientation interpolée.
 *
 * @param x position interpolée de l'origine du corps (bas-centre de l'entité)
 * @param y position interpolée
 * @param z position interpolée
 * @param rotation orientation interpolée du corps, autour de son origine
 * @param light lumière empaquetée (bloc et ciel)
 */
record AssemblyPlacement(double x, double y, double z, Quaternionf rotation, int light) {

    /**
     * {@return la pose d'une assembly pour cette frame}
     *
     * @param partialTick fraction du tick écoulée
     * @param assembly assembly à poser
     */
    static AssemblyPlacement of(float partialTick, AxionEntity assembly) {
        double x = Mth.lerp(partialTick, assembly.xo, assembly.getX());
        double y = Mth.lerp(partialTick, assembly.yo, assembly.getY());
        double z = Mth.lerp(partialTick, assembly.zo, assembly.getZ());
        Quaternionf rotation = assembly.renderRotation(partialTick, new Quaternionf());
        int light = LevelRenderer.getLightColor(
                assembly.level(), BlockPos.containing(x, y + assembly.getBbHeight() / 2.0, z));
        return new AssemblyPlacement(x, y, z, rotation, light);
    }

    /** Place la pile à l'origine du corps, dans son orientation. */
    void apply(PoseStack pose, Vec3 camera) {
        pose.translate(x - camera.x, y - camera.y, z - camera.z);
        pose.mulPose(rotation);
    }
}
