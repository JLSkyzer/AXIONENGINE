package dev.axion.render;

import dev.axion.asset.GeometryTransfer;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Quelles textures d'un asset peuvent entrer dans son atlas (fiche C-26, ADR-122 T-c).
 *
 * <p>Une tuile ne se répète pas : une texture n'y entre que si chaque mesh qui la lit — en albedo
 * ou en émission — a ses coordonnées de texture dans {@code [0, 1]}, c'est-à-dire une plage de
 * décodage {@code [0, 1]} (ADR-122 §4). Une texture qu'un seul mesh répète garde sa texture
 * individuelle, et sa répétition. Les dimensions, elles, ne se connaissent qu'une fois l'image
 * lue ({@link AtlasLayout#fits}).
 */
public final class AtlasPlan {

    private AtlasPlan() {}

    /**
     * {@return les textures que tous leurs meshes lisent dans {@code [0, 1]}}
     *
     * @param geometry géométrie de l'asset
     * @param looks apparence de chaque mesh, dans l'ordre des meshes
     */
    public static Set<TextureKey> unrepeated(GeometryTransfer geometry, List<MeshLook> looks) {
        Set<TextureKey> inside = new HashSet<>();
        Set<TextureKey> repeated = new HashSet<>();
        for (int rank = 0; rank < looks.size(); rank++) {
            GeometryTransfer.Mesh mesh = geometry.meshes().get(rank);
            Set<TextureKey> into = mesh.uvMin() == 0.0f && mesh.uvSpan() == 1.0f ? inside : repeated;
            MeshLook look = looks.get(rank);
            if (look.albedo() != null) {
                into.add(look.albedo());
            }
            if (look.emission() != null) {
                into.add(look.emission());
            }
        }
        inside.removeAll(repeated);
        return inside;
    }
}
