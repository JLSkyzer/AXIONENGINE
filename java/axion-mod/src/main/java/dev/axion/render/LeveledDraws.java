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
        if (meshes.length != rest.size()) {
            return rest;
        }
        List<GeometryTransfer.Draw> leveled = null;
        for (int rank = 0; rank < meshes.length; rank++) {
            GeometryTransfer.Draw draw = rest.get(rank);
            if (meshes[rank] == draw.mesh()) {
                continue;
            }
            if (meshes[rank] < 0 || meshes[rank] >= meshCount) {
                return rest;
            }
            if (leveled == null) {
                leveled = new ArrayList<>(rest);
            }
            leveled.set(rank, new GeometryTransfer.Draw(meshes[rank], draw.node(), draw.nodeFlags(), draw.model()));
        }
        return leveled == null ? rest : List.copyOf(leveled);
    }
}
