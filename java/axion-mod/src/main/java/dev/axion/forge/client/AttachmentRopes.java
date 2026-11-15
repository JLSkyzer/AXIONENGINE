package dev.axion.forge.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.axion.definition.SocketTable;
import dev.axion.forge.AxionEntity;
import dev.axion.net.AssemblyState;
import dev.axion.seat.SeatPlacement;
import java.util.List;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import org.joml.Matrix4f;

/**
 * Les cordes et les câbles des attaches (ADR-171 D7) : une corde ({@code rope}) ou un treuil ({@code winch}) se dessine
 * du socket de l'assembly qui l'a posée au socket d'en face, ou à son point d'ancrage ; tendue, droite ; avec du mou, elle
 * pend d'autant. Les attaches qui tiennent deux sockets ensemble ne se dessinent pas : elles n'ont pas de longueur.
 *
 * <p>Le ruban est celui des laisses de Minecraft ({@link RenderType#leash()}) : deux bandes croisées, éclairées comme
 * l'assembly.
 */
final class AttachmentRopes {

    /** Segments d'une corde. */
    private static final int SEGMENTS = 24;

    /** Demi-largeur du ruban, en blocs. */
    private static final float HALF_WIDTH = 0.03f;

    /** Rang des types dessinés ({@link SocketTable#KINDS}). */
    private static final int ROPE = SocketTable.KINDS.indexOf("rope");
    private static final int WINCH = SocketTable.KINDS.indexOf("winch");

    private AttachmentRopes() {}

    /** {@return vrai si l'assembly a posé une corde ou un câble : elle se dessine même hors champ} */
    static boolean hasRopes(AxionEntity entity) {
        for (AssemblyState.Link link : entity.receivedLinks().links()) {
            if (link.kind() == ROPE || link.kind() == WINCH) {
                return true;
            }
        }
        return false;
    }

    /**
     * Dessine les cordes qu'une assembly a posées, dans le repère de son rendu.
     *
     * @param entity l'assembly
     * @param partialTick la fraction du tick
     * @param pose la pile de poses, à l'origine du rendu de l'entité
     * @param buffers les tampons
     * @param light la lumière de l'assembly
     */
    static void draw(AxionEntity entity, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
        List<AssemblyState.Link> links = entity.receivedLinks().links();
        List<SocketTable.Spec> sockets = entity.sockets().sockets();
        if (links.isEmpty() || sockets.isEmpty()) {
            return;
        }
        AxionEntity.RenderPose shown = entity.renderPose(partialTick);
        double originX = Mth.lerp(partialTick, entity.xo, entity.getX());
        double originY = Mth.lerp(partialTick, entity.yo, entity.getY());
        double originZ = Mth.lerp(partialTick, entity.zo, entity.getZ());
        for (AssemblyState.Link link : links) {
            if ((link.kind() != ROPE && link.kind() != WINCH) || link.socket() >= sockets.size()) {
                continue;
            }
            SeatPlacement.Pose start = entity.nodePoseAt(sockets.get(link.socket()).node(), shown);
            if (start == null) {
                continue;
            }
            double endX;
            double endY;
            double endZ;
            if (link.anchored()) {
                endX = link.anchorX();
                endY = link.anchorY();
                endZ = link.anchorZ();
            } else {
                Entity found = entity.level().getEntity(link.other());
                if (!(found instanceof AxionEntity other) || link.otherSocket() < 0
                        || link.otherSocket() >= other.sockets().sockets().size()) {
                    continue;
                }
                SeatPlacement.Pose end = other.nodePoseAt(other.sockets().sockets().get(link.otherSocket()).node(),
                        other.renderPose(partialTick));
                if (end == null) {
                    continue;
                }
                endX = end.x();
                endY = end.y();
                endZ = end.z();
            }
            float[] color = link.kind() == WINCH ? new float[] {0.36f, 0.36f, 0.4f} : new float[] {0.55f, 0.4f, 0.24f};
            strip(buffers.getBuffer(RenderType.leash()), pose.last().pose(),
                    (float) (start.x() - originX), (float) (start.y() - originY), (float) (start.z() - originZ),
                    (float) (endX - originX), (float) (endY - originY), (float) (endZ - originZ),
                    link.length() / 1000.0, color, light);
        }
    }

    /** Un ruban croisé de {@code (ax, ay, az)} à {@code (bx, by, bz)}, qui pend du mou que laisse {@code length}. */
    private static void strip(VertexConsumer out, Matrix4f matrix, float ax, float ay, float az, float bx, float by,
            float bz, double length, float[] color, int light) {
        float dx = bx - ax;
        float dy = by - ay;
        float dz = bz - az;
        double span = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (span < 1e-4) {
            return;
        }
        // Une parabole dont la flèche vient de l'excès de longueur : à longueur égale à la portée, la corde est droite.
        float sag = (float) (0.5 * Math.sqrt(Math.max(0.0, length * length - span * span)));
        // Le côté d'une bande : horizontal, perpendiculaire à la corde ; celui de l'autre bande, vertical.
        float sideX = -dz;
        float sideZ = dx;
        float horizontal = (float) Math.sqrt(sideX * sideX + sideZ * sideZ);
        if (horizontal < 1e-4f) {
            sideX = 1f;
            sideZ = 0f;
            horizontal = 1f;
        }
        sideX = sideX / horizontal * HALF_WIDTH;
        sideZ = sideZ / horizontal * HALF_WIDTH;
        for (int i = 0; i <= SEGMENTS; i++) {
            vertexPair(out, matrix, ax, ay, az, dx, dy, dz, sag, i, sideX, 0f, sideZ, color, light);
        }
        for (int i = SEGMENTS; i >= 0; i--) {
            vertexPair(out, matrix, ax, ay, az, dx, dy, dz, sag, i, 0f, HALF_WIDTH, 0f, color, light);
        }
    }

    private static void vertexPair(VertexConsumer out, Matrix4f matrix, float ax, float ay, float az, float dx,
            float dy, float dz, float sag, int segment, float offsetX, float offsetY, float offsetZ, float[] color,
            int light) {
        float t = segment / (float) SEGMENTS;
        float x = ax + dx * t;
        float y = ay + dy * t - 4f * sag * t * (1f - t);
        float z = az + dz * t;
        // Une teinte qui alterne d'un segment à l'autre, comme une laisse : la torsion se lit.
        float shade = segment % 2 == 0 ? 1f : 0.82f;
        float r = color[0] * shade;
        float g = color[1] * shade;
        float b = color[2] * shade;
        out.vertex(matrix, x - offsetX, y - offsetY, z - offsetZ).color(r, g, b, 1f).uv2(light).endVertex();
        out.vertex(matrix, x + offsetX, y + offsetY, z + offsetZ).color(r, g, b, 1f).uv2(light).endVertex();
    }
}
