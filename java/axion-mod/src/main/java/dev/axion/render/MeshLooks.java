package dev.axion.render;

import dev.axion.asset.GeometryTransfer;
import dev.axion.asset.MaterialTransfer;
import java.util.ArrayList;
import java.util.List;

/**
 * L'apparence des meshes d'un asset, établie une fois au chargement, hors du fil de rendu
 * (ADR-122 §7).
 *
 * <p>Chaque mesh désigne son matériau par son index dans la table. Un index hors de la table —
 * table vide, ou défaut de la frontière, que le validateur refuse à la compilation — donne le
 * matériau par défaut de glTF : blanc, sans texture, couleur de sommet appliquée. Son mode et ses
 * faces sont ceux des drapeaux du mesh, recopiés de son matériau à la compilation (ADR-122 §3) :
 * c'est tout ce qui reste de lui. L'écart est rapporté une fois pour tout l'asset.
 */
public final class MeshLooks {

    /** Exposant du passage de l'espace linéaire à l'espace gamma (ADR-122 §7). */
    private static final double GAMMA = 1.0 / 2.2;

    private MeshLooks() {}

    /**
     * L'apparence des meshes d'un asset.
     *
     * @param looks une apparence par mesh, dans l'ordre des meshes
     * @param note l'écart à rapporter, une fois : des meshes sans matériau dans la table ; ou
     *     {@code null}
     */
    public record Result(List<MeshLook> looks, String note) {}

    /**
     * {@return l'apparence de chaque mesh d'un asset}
     *
     * @param geometry géométrie de l'asset
     * @param materials table des matériaux de l'asset, vide si elle n'a pu être lue
     */
    public static Result of(GeometryTransfer geometry, MaterialTransfer materials) {
        List<MaterialTransfer.Material> table = materials.materials();
        List<MeshLook> looks = new ArrayList<>(geometry.meshes().size());
        int defaulted = 0;
        String first = null;
        for (int rank = 0; rank < geometry.meshes().size(); rank++) {
            GeometryTransfer.Mesh mesh = geometry.meshes().get(rank);
            MaterialTransfer.Material material;
            if (mesh.material() < table.size()) {
                material = table.get(mesh.material());
            } else {
                material = defaultMaterial(mesh);
                if (defaulted++ == 0) {
                    first = "le mesh " + rank + " désigne le matériau " + mesh.material();
                }
            }
            looks.add(look(geometry, mesh, material));
        }
        String note = defaulted == 0
                ? null
                : first + ", absent d'une table de " + table.size()
                        + (defaulted > 1 ? " (" + defaulted + " meshes dans ce cas)" : "")
                        + " ; matériau par défaut";
        return new Result(List.copyOf(looks), note);
    }

    /**
     * {@return le matériau par défaut d'un mesh : celui de glTF, au mode et aux faces que disent
     * les drapeaux du mesh}
     *
     * @param mesh mesh sans matériau dans la table
     */
    static MaterialTransfer.Material defaultMaterial(GeometryTransfer.Mesh mesh) {
        int none = MaterialTransfer.NO_TEXTURE;
        return new MaterialTransfer.Material(
                0L,
                none, none, none, none, none, none,
                new float[] {1.0f, 1.0f, 1.0f, 1.0f},
                new float[] {0.0f, 0.0f, 0.0f},
                1.0f, // métal
                1.0f, // rugosité
                1.0f, // force de l'occlusion
                1.0f, // échelle des normales
                0.5f, // seuil de découpe
                0.0f, // parallaxe
                0.0f, // vernis
                0.0f, // rugosité du vernis
                0.0f, // lustre
                0.0f, // anisotropie
                mesh.has(GeometryTransfer.MESH_TRANSPARENT)
                        ? MaterialTransfer.BLEND_TRANSLUCENT
                        : MaterialTransfer.BLEND_OPAQUE,
                mesh.has(GeometryTransfer.MESH_DOUBLE_SIDED) ? MaterialTransfer.CULL_NONE : MaterialTransfer.CULL_BACK,
                MaterialTransfer.SHADING_PBR,
                MaterialTransfer.FLAG_VERTEX_COLOR,
                0xFFFF);
    }

    /** {@return l'apparence d'un mesh sous son matériau} */
    private static MeshLook look(GeometryTransfer geometry, GeometryTransfer.Mesh mesh, MaterialTransfer.Material material) {
        float[] factor = material.albedoFactor();
        int color = argb(factor[0], factor[1], factor[2], factor[3]);
        if ((material.flags() & MaterialTransfer.FLAG_VERTEX_COLOR) == 0) {
            return new MeshLook(material, color, null);
        }
        byte[] colors = geometry.colors();
        int[] vertexColors = new int[mesh.vertexCount()];
        for (int vertex = 0; vertex < vertexColors.length; vertex++) {
            int at = (mesh.vertexOffset() + vertex) * 4;
            vertexColors[vertex] = argb(
                    factor[0] * unorm(colors[at]),
                    factor[1] * unorm(colors[at + 1]),
                    factor[2] * unorm(colors[at + 2]),
                    factor[3] * unorm(colors[at + 3]));
        }
        return new MeshLook(material, color, vertexColors);
    }

    /**
     * {@return une couleur linéaire, ramenée en gamma et empaquetée en ARGB}
     *
     * @param red rouge, linéaire
     * @param green vert, linéaire
     * @param blue bleu, linéaire
     * @param alpha alpha, qui reste linéaire
     */
    static int argb(float red, float green, float blue, float alpha) {
        return channel(alpha, false) << 24 | channel(red, true) << 16 | channel(green, true) << 8 | channel(blue, true);
    }

    /**
     * {@return un canal sur huit bits : borné à {@code [0, 1]}, ramené en gamma s'il le faut, puis
     * arrondi au plus proche}
     *
     * <p>{@code StrictMath} : le même octet sur toute machine.
     */
    static int channel(float linear, boolean gamma) {
        double value = Math.min(1.0, Math.max(0.0, linear));
        if (gamma) {
            value = StrictMath.pow(value, GAMMA);
        }
        return (int) Math.round(value * 255.0);
    }

    /** {@return un octet non signé ramené à {@code [0, 1]}} */
    private static float unorm(byte value) {
        return Byte.toUnsignedInt(value) / 255.0f;
    }
}
