package dev.axion.render;

import dev.axion.asset.GeometryTransfer;
import java.util.ArrayList;
import java.util.List;

/**
 * Les dessins d'une assembly à son niveau de détail (ADR-154, tranche 2b) : ceux de la liste au repos,
 * le mesh de chacun remplacé par celui que le natif a choisi dans {@code RENDER_OUT}. Logique pure.
 */
public final class LeveledDraws {

    private LeveledDraws() {}

    /**
     * {@return les dessins au mesh donné, ou {@code rest} lui-même} si les meshes sont ceux de la liste au
     * repos, s'ils ne sont pas un par dessin, ou si l'un désigne un mesh que la géométrie n'a pas : le
     * natif et Java ne s'accorderaient pas, et le niveau 0 reste juste.
     *
     * @param rest la liste au repos
     * @param meshCount nombre de meshes de la géométrie
     * @param meshes le mesh de chaque dessin, dans l'ordre de la liste au repos
     */
    public static List<GeometryTransfer.Draw> of(List<GeometryTransfer.Draw> rest, int meshCount, int[] meshes) {
        return of(rest, meshCount, meshes, null);
    }

    /**
     * {@return les dessins au mesh donné et, pour une assembly animée, à la matrice que le natif a calculée
     * (ADR-155, {@code RENDER_OUT} schéma 3), ou {@code rest} lui-même} Les matrices sont ignorées si elles
     * ne sont pas une de douze flottants par dessin : le repos reste juste.
     *
     * @param rest la liste au repos
     * @param meshCount nombre de meshes de la géométrie
     * @param meshes le mesh de chaque dessin, dans l'ordre de la liste au repos
     * @param matrices la matrice {@code mat4x3} de chaque dessin, dans l'espace de l'asset comme celle du
     *     repos, ou {@code null} pour une assembly au repos
     */
    public static List<GeometryTransfer.Draw> of(
            List<GeometryTransfer.Draw> rest, int meshCount, int[] meshes, float[][] matrices) {
        if (meshes.length != rest.size()) {
            return rest;
        }
        boolean animated = matrices != null && matrices.length == rest.size();
        if (animated) {
            for (float[] matrix : matrices) {
                animated &= matrix != null && matrix.length == RenderPrepCodec.MATRIX_FLOATS;
            }
        }
        List<GeometryTransfer.Draw> leveled = null;
        for (int rank = 0; rank < meshes.length; rank++) {
            GeometryTransfer.Draw draw = rest.get(rank);
            if (meshes[rank] == draw.mesh() && !animated) {
                continue;
            }
            if (meshes[rank] < 0 || meshes[rank] >= meshCount) {
                return rest;
            }
            if (leveled == null) {
                leveled = new ArrayList<>(rest);
            }
            float[] model = animated ? matrices[rank] : draw.model();
            leveled.set(rank, new GeometryTransfer.Draw(meshes[rank], draw.node(), draw.nodeFlags(), model));
        }
        return leveled == null ? rest : List.copyOf(leveled);
    }
}
