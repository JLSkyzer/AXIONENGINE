package dev.axion.render;

import dev.axion.asset.GeometryTransfer;
import dev.axion.net.Interpolation;
import java.util.List;

/**
 * La sphère qui contient une assembly dont des corps articulés s'écartent de leur pose de repos
 * (ADR-157 D2), dans le repère de son corps racine, pour le culling.
 *
 * <p>Chaque dessin est posé comme le dessin le pose ({@code BodyParts}) : sa transformation de repos
 * exprimée dans le repère du corps qui le porte, puis la pose de ce corps relative au corps racine —
 * sa position, puis son orientation. Les huit coins de l'emprise de son mesh y passent ; la sphère est
 * tirée de la boîte de tous ces coins, comme {@code LocalBounds::from_aabb} le fait côté natif. Logique
 * pure, sans Forge.
 */
public final class ArticulatedBounds {

    private ArticulatedBounds() {}

    /**
     * @param geometry la géométrie de l'asset, l'emprise de chaque mesh comprise
     * @param skeleton les corps de l'asset sous sa definition, pour ces corps secondaires
     * @param bodies les corps secondaires, en poses relatives au corps racine, dans l'ordre du squelette
     * @return la sphère {@code [cx, cy, cz, r]} dans le repère du corps racine, ou {@code null} : pas de
     *     squelette, un nombre de corps qui n'est pas le sien, aucun dessin, ou une valeur non finie — le
     *     candidat reste alors retenu sans test
     */
    public static float[] of(GeometryTransfer geometry, BodySkeleton skeleton, List<Interpolation.BodyPose> bodies) {
        if (skeleton == null || skeleton.bodies() != bodies.size() + 1 || geometry.draws().isEmpty()) {
            return null;
        }
        double[] min = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
        double[] max = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (GeometryTransfer.Draw draw : geometry.draws()) {
            float[] box = geometry.meshBounds(draw.mesh());
            if (box == null) {
                return null;
            }
            int body = skeleton.bodyOf(draw.node());
            float[] model = body == 0 ? draw.model() : skeleton.modelInBody(body, draw.model());
            Interpolation.BodyPose pose = body == 0 ? null : bodies.get(body - 1);
            for (int corner = 0; corner < 8; corner++) {
                float x = (corner & 1) == 0 ? box[0] : box[3];
                float y = (corner & 2) == 0 ? box[1] : box[4];
                float z = (corner & 4) == 0 ? box[2] : box[5];
                double[] point = {
                    model[0] * x + model[3] * y + model[6] * z + model[9],
                    model[1] * x + model[4] * y + model[7] * z + model[10],
                    model[2] * x + model[5] * y + model[8] * z + model[11]
                };
                if (pose != null) {
                    point = rotate(pose.rotation(), point);
                    for (int axis = 0; axis < 3; axis++) {
                        point[axis] += pose.offset()[axis];
                    }
                }
                for (int axis = 0; axis < 3; axis++) {
                    min[axis] = Math.min(min[axis], point[axis]);
                    max[axis] = Math.max(max[axis], point[axis]);
                }
            }
        }
        double radius = 0;
        float[] sphere = new float[4];
        for (int axis = 0; axis < 3; axis++) {
            sphere[axis] = (float) ((min[axis] + max[axis]) * 0.5);
            double half = (max[axis] - min[axis]) * 0.5;
            radius += half * half;
        }
        sphere[3] = (float) Math.sqrt(radius);
        for (float value : sphere) {
            if (!Float.isFinite(value)) {
                return null;
            }
        }
        return sphere[3] > 0f ? sphere : null;
    }

    /** {@return {@code point} tourné par le quaternion unitaire {@code q = [x, y, z, w]}} */
    private static double[] rotate(float[] q, double[] point) {
        double qx = q[0];
        double qy = q[1];
        double qz = q[2];
        double qw = q[3];
        // t = 2 (q × p) ; p' = p + w t + q × t
        double tx = 2 * (qy * point[2] - qz * point[1]);
        double ty = 2 * (qz * point[0] - qx * point[2]);
        double tz = 2 * (qx * point[1] - qy * point[0]);
        return new double[] {
            point[0] + qw * tx + (qy * tz - qz * ty),
            point[1] + qw * ty + (qz * tx - qx * tz),
            point[2] + qw * tz + (qx * ty - qy * tx)
        };
    }
}
