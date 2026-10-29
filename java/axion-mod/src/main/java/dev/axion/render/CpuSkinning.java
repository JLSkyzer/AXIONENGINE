package dev.axion.render;

import dev.axion.asset.GeometryTransfer;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Le skinning du backend vanilla (C-61, R-742) : sur le CPU, à l'émission des sommets, par la palette d'os
 * que le natif a évaluée pour l'instance ({@code RENDER_OUT} schéma 4). Le calcul est celui du shader du
 * backend natif (C-66) : chaque sommet porté par la somme de ses quatre os pondérés, sa position par la
 * matrice entière, sa normale par sa partie 3×3 — le backend la renormalise ensuite. Logique pure.
 *
 * <p>Une instance de cette classe tient la palette d'une assembly pour une frame, et skinne chacun de ses
 * meshes une seule fois : la surface d'un mesh et son émission partagent les mêmes sommets. Render thread
 * seul.
 */
public final class CpuSkinning {

    /**
     * Les sommets d'un mesh skinné, trois flottants chacun, de son premier sommet au dernier.
     *
     * @param positions positions, dans l'espace de l'asset
     * @param normals normales, non renormalisées
     */
    public record Skinned(float[] positions, float[] normals) {}

    private final float[] palette;

    /** Les meshes déjà skinnés à cette frame, par rang ; alloué à la première demande. */
    private Skinned[] meshes;

    /**
     * @param palette la palette d'os de l'instance, {@link RenderPrepCodec#MATRIX_FLOATS} flottants par os
     *     dans l'ordre du squelette, ses colonnes x, y, z puis sa translation
     */
    public CpuSkinning(float[] palette) {
        this.palette = palette;
    }

    /**
     * {@return les sommets skinnés d'un mesh, calculés à sa première demande}
     *
     * @param geometry la géométrie de l'asset
     * @param mesh rang du mesh
     */
    public Skinned mesh(GeometryTransfer geometry, int mesh) {
        if (meshes == null) {
            meshes = new Skinned[geometry.meshes().size()];
        }
        Skinned skinned = meshes[mesh];
        if (skinned == null) {
            GeometryTransfer.Mesh desc = geometry.meshes().get(mesh);
            skinned = new Skinned(new float[desc.vertexCount() * 3], new float[desc.vertexCount() * 3]);
            skin(geometry, desc.vertexOffset(), desc.vertexCount(), palette, skinned.positions(), skinned.normals());
            meshes[mesh] = skinned;
        }
        return skinned;
    }

    /**
     * Skinne des sommets consécutifs.
     *
     * @param geometry la géométrie de l'asset
     * @param first premier sommet, dans le tableau des sommets
     * @param count nombre de sommets
     * @param palette la palette d'os de l'instance
     * @param positions reçoit la position de chaque sommet, trois flottants à partir du rang 0
     * @param normals reçoit sa normale, de même
     */
    public static void skin(
            GeometryTransfer geometry, int first, int count, float[] palette, float[] positions, float[] normals) {
        float[] restPositions = geometry.positions();
        float[] restNormals = geometry.normals();
        float[] m = new float[RenderPrepCodec.MATRIX_FLOATS];
        for (int rank = 0; rank < count; rank++) {
            int vertex = first + rank;
            Arrays.fill(m, 0.0f);
            // Les os dans l'ordre de leurs places, comme le shader somme les siennes. Un poids nul n'ajoute
            // rien : son os n'est pas lu.
            for (int slot = 0; slot < GeometryTransfer.BONES_PER_VERTEX; slot++) {
                float weight = geometry.weight(vertex, slot);
                if (weight == 0.0f) {
                    continue;
                }
                int at = geometry.bone(vertex, slot) * RenderPrepCodec.MATRIX_FLOATS;
                for (int i = 0; i < RenderPrepCodec.MATRIX_FLOATS; i++) {
                    m[i] += weight * palette[at + i];
                }
            }
            float x = restPositions[vertex * 3];
            float y = restPositions[vertex * 3 + 1];
            float z = restPositions[vertex * 3 + 2];
            positions[rank * 3] = m[0] * x + m[3] * y + m[6] * z + m[9];
            positions[rank * 3 + 1] = m[1] * x + m[4] * y + m[7] * z + m[10];
            positions[rank * 3 + 2] = m[2] * x + m[5] * y + m[8] * z + m[11];
            float nx = restNormals[vertex * 3];
            float ny = restNormals[vertex * 3 + 1];
            float nz = restNormals[vertex * 3 + 2];
            normals[rank * 3] = m[0] * nx + m[3] * ny + m[6] * nz;
            normals[rank * 3 + 1] = m[1] * nx + m[4] * ny + m[7] * nz;
            normals[rank * 3 + 2] = m[2] * nx + m[5] * ny + m[8] * nz;
        }
    }

    /**
     * {@return les sommets qu'une assembly skinne à cette frame} Ceux de chaque mesh skinné et visible qu'elle
     * dessine, comptés une fois : c'est ce que {@link #mesh} calcule.
     *
     * @param asset géométrie et apparence de l'assembly
     * @param draws ses dessins à cette frame, à leur niveau de détail
     */
    public static int vertices(RenderAsset asset, List<GeometryTransfer.Draw> draws) {
        List<GeometryTransfer.Mesh> meshes = asset.mesh().meshes();
        boolean[] counted = new boolean[meshes.size()];
        int vertices = 0;
        for (GeometryTransfer.Draw draw : draws) {
            GeometryTransfer.Mesh mesh = meshes.get(draw.mesh());
            if (!counted[draw.mesh()] && mesh.has(GeometryTransfer.MESH_SKINNED) && asset.look(draw.mesh()).visible()) {
                counted[draw.mesh()] = true;
                vertices += mesh.vertexCount();
            }
        }
        return vertices;
    }

    /**
     * {@return les instances que {@code render.vanilla_max_skinned_vertices} laisse skinner (R-742)} Les plus
     * proches d'abord, à distance égale dans l'ordre donné. La première qui ferait dépasser le plafond reste
     * au repos, et toutes les plus lointaines avec elle : jamais une instance lointaine animée quand une plus
     * proche ne l'est pas. Une instance sans sommet skinné n'entre pas dans le compte.
     *
     * @param vertices les sommets skinnés de chaque instance, par {@link #vertices} ; zéro sans skin
     * @param distances la distance de chaque instance à la caméra
     * @param limit le plafond de sommets skinnés de la frame
     */
    public static boolean[] admit(int[] vertices, float[] distances, long limit) {
        boolean[] admitted = new boolean[vertices.length];
        int[] nearestFirst = IntStream.range(0, vertices.length)
                .boxed()
                .sorted(Comparator.comparingDouble(rank -> distances[rank]))
                .mapToInt(Integer::intValue)
                .toArray();
        long total = 0;
        for (int rank : nearestFirst) {
            if (vertices[rank] == 0) {
                continue;
            }
            total += vertices[rank];
            if (total > limit) {
                break;
            }
            admitted[rank] = true;
        }
        return admitted;
    }
}
