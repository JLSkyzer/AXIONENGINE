package dev.axion.forge.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.axion.forge.AxionEntity;
import dev.axion.net.Interpolation;
import java.util.List;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Pose interpolée d'une assembly pour une frame, et lumière du monde à son centre.
 *
 * <p>Partagée par tout ce qui se dessine en repère du corps — le maillage (C-61) comme les
 * overlays de debug (C-67, ADR-121) — pour qu'ils soient posés <b>exactement</b> au même
 * endroit : la pose de l'interpolation de 21.7, datée de l'image (ADR-150 D7).
 *
 * @param x position interpolée de l'origine du corps (bas-centre de l'entité)
 * @param y position interpolée
 * @param z position interpolée
 * @param rotation orientation interpolée du corps, autour de son origine
 * @param light lumière empaquetée (bloc et ciel)
 * @param bodies les corps autres que le corps racine, en poses relatives à lui (ADR-151 D9)
 */
record AssemblyPlacement(
        double x, double y, double z, Quaternionf rotation, int light, List<Interpolation.BodyPose> bodies) {

    /**
     * {@return la pose d'une assembly pour cette frame}
     *
     * @param partialTick fraction du tick écoulée
     * @param assembly assembly à poser
     */
    static AssemblyPlacement of(float partialTick, AxionEntity assembly) {
        AxionEntity.RenderPose pose = assembly.renderPose(partialTick);
        int light = LevelRenderer.getLightColor(
                assembly.level(), BlockPos.containing(pose.x(), pose.y() + assembly.getBbHeight() / 2.0, pose.z()));
        return new AssemblyPlacement(pose.x(), pose.y(), pose.z(), pose.rotation(), light, pose.bodies());
    }

    /** {@return les nodes racines des corps secondaires, dans l'ordre de leurs poses} */
    int[] bodyNodes() {
        int[] nodes = new int[bodies.size()];
        for (int rank = 0; rank < nodes.length; rank++) {
            nodes[rank] = bodies.get(rank).node();
        }
        return nodes;
    }

    /**
     * {@return la pose monde du corps secondaire de rang {@code rank}} Sa position relative, tournée par
     * l'orientation du corps racine, s'ajoute à la position de celui-ci ; son orientation relative se compose
     * après l'orientation racine. La lumière reste celle de l'assembly.
     *
     * @param rank rang du corps dans {@link #bodies}
     */
    AssemblyPlacement body(int rank) {
        Interpolation.BodyPose body = bodies.get(rank);
        double[] offset = body.offset();
        Vector3f turned = rotation.transform(new Vector3f((float) offset[0], (float) offset[1], (float) offset[2]));
        float[] r = body.rotation();
        Quaternionf orientation = new Quaternionf(rotation).mul(new Quaternionf(r[0], r[1], r[2], r[3]));
        return new AssemblyPlacement(x + turned.x, y + turned.y, z + turned.z, orientation, light, List.of());
    }

    /** Place la pile à l'origine du corps, dans son orientation. */
    void apply(PoseStack pose, Vec3 camera) {
        pose.translate(x - camera.x, y - camera.y, z - camera.z);
        pose.mulPose(rotation);
    }
}
