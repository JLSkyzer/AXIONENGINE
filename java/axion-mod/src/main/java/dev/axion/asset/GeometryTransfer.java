package dev.axion.asset;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Géométrie d'un asset chargé, telle que le natif la remet (ADR-119 §3).
 *
 * <pre>
 * u32 mesh_count, u32 vertex_count, u32 index_count, u32 draw_count
 * MeshDesc[mesh_count]       48 octets (DM-04)
 * Vertex[vertex_count]       48 octets (DM-04, R-140)
 * u32 indices[index_count]   locaux au mesh : sommet = vertexOffset + indice
 * RestDraw[draw_count]       64 octets : mesh, node, drapeaux du node, réservé, mat4x3
 * </pre>
 *
 * <p>Le natif a vérifié la cohérence avant de déposer (plages, triangles, indices locaux,
 * maillages désignés) ; Java ne décode jamais le contenu d'un A3D. Ce lecteur ne contrôle
 * que la <strong>longueur annoncée</strong> — un écart y serait un défaut de la frontière,
 * qui doit se voir — et dé-quantifie <strong>une fois</strong> les attributs dont le rendu a
 * besoin, plutôt qu'à chaque frame.
 *
 * <p>Les tableaux rendus par les accesseurs sont ceux de l'instance, sans copie : le rendu
 * les lit à chaque frame. Ils ne doivent pas être modifiés.
 */
public final class GeometryTransfer {

    /** Taille de l'en-tête : quatre dénombrements. */
    public static final int HEADER_BYTES = 16;

    /** Taille d'un {@code MeshDesc} (DM-04). */
    public static final int MESH_BYTES = 48;

    /** Taille d'un {@code Vertex} (DM-04). */
    public static final int VERTEX_BYTES = 48;

    /** Taille d'un {@code RestDraw} (ADR-119). */
    public static final int DRAW_BYTES = 64;

    /** Drapeau de mesh : déformé par un squelette. */
    public static final int MESH_SKINNED = 1;

    /** Drapeau de mesh : les deux faces sont rendues. */
    public static final int MESH_DOUBLE_SIDED = 1 << 1;

    /** Drapeau de mesh : passe transparente. */
    public static final int MESH_TRANSPARENT = 1 << 2;

    /** Drapeau de mesh : soumis à la déformation continue. */
    public static final int MESH_DEFORMABLE = 1 << 3;

    /**
     * Un mesh (DM-04).
     *
     * @param vertexOffset premier sommet, dans le tableau des sommets
     * @param vertexCount nombre de sommets
     * @param indexOffset premier indice, dans le tableau des indices
     * @param indexCount nombre d'indices, multiple de trois
     * @param material matériau de rendu
     * @param lod niveau de détail
     * @param flags drapeaux {@code MESH_*}
     * @param uv0Range plage de décodage des UV (ADR-122 §4) : octet bas la borne inférieure,
     *     signée ; octet haut l'étendue ; {@code 0} pour {@code [0, 1]}
     */
    public record Mesh(
            int vertexOffset,
            int vertexCount,
            int indexOffset,
            int indexCount,
            int material,
            int lod,
            int flags,
            int uv0Range) {

        /** {@return vrai si le mesh porte ce drapeau} */
        public boolean has(int flag) {
            return (flags & flag) != 0;
        }

        /** {@return la borne inférieure de la plage d'UV} */
        public float uvMin() {
            return rangeMin(uv0Range);
        }

        /** {@return l'étendue de la plage d'UV, de 1 à 17} */
        public float uvSpan() {
            return rangeSpan(uv0Range);
        }
    }

    /** {@return la borne inférieure que code {@code uv0_range} : son octet bas, signé} */
    static float rangeMin(int range) {
        return (byte) range;
    }

    /** {@return l'étendue que code {@code uv0_range} : son octet haut ; 1 pour le code 0} */
    static float rangeSpan(int range) {
        return range == 0 ? 1.0f : (range >>> 8) & 0xFF;
    }

    /**
     * Un mesh à dessiner dans la pose de repos (ADR-119).
     *
     * @param mesh index du mesh
     * @param node node qui le porte
     * @param nodeFlags drapeaux du node (DM-03)
     * @param model transformation de repos du node, {@code mat4x3} colonne-major : trois
     *     axes puis la translation, douze flottants
     */
    public record Draw(int mesh, int node, int nodeFlags, float[] model) {}

    private final List<Mesh> meshes;
    private final float[] positions;
    private final float[] normals;
    private final float[] uvs;
    private final byte[] colors;
    private final int[] indices;
    private final List<Draw> draws;

    /** Le transfert reçu, gardé tel quel : le backend natif en téléverse sommets et indices. */
    private final byte[] raw;
    private final int verticesAt;
    private final int indicesAt;

    private GeometryTransfer(
            List<Mesh> meshes,
            float[] positions,
            float[] normals,
            float[] uvs,
            byte[] colors,
            int[] indices,
            List<Draw> draws,
            byte[] raw,
            int verticesAt,
            int indicesAt) {
        this.meshes = meshes;
        this.positions = positions;
        this.normals = normals;
        this.uvs = uvs;
        this.colors = colors;
        this.indices = indices;
        this.draws = draws;
        this.raw = raw;
        this.verticesAt = verticesAt;
        this.indicesAt = indicesAt;
    }

    /**
     * Lit un transfert.
     *
     * @param bytes charge utile déposée par {@code axion_asset_geometry}
     * @return la géométrie, attributs dé-quantifiés
     * @throws IllegalArgumentException si la longueur ne correspond pas aux dénombrements
     */
    public static GeometryTransfer parse(byte[] bytes) {
        if (bytes.length < HEADER_BYTES) {
            throw new IllegalArgumentException("transfert de " + bytes.length + " octets : en-tête tronqué");
        }
        ByteBuffer in = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        long meshCount = Integer.toUnsignedLong(in.getInt(0));
        long vertexCount = Integer.toUnsignedLong(in.getInt(4));
        long indexCount = Integer.toUnsignedLong(in.getInt(8));
        long drawCount = Integer.toUnsignedLong(in.getInt(12));
        long expected = HEADER_BYTES
                + meshCount * MESH_BYTES
                + vertexCount * VERTEX_BYTES
                + indexCount * Integer.BYTES
                + drawCount * DRAW_BYTES;
        if (expected != bytes.length) {
            throw new IllegalArgumentException("transfert de " + bytes.length
                    + " octets, " + expected + " annoncés par ses dénombrements");
        }

        int meshesAt = HEADER_BYTES;
        int verticesAt = meshesAt + (int) meshCount * MESH_BYTES;
        int indicesAt = verticesAt + (int) vertexCount * VERTEX_BYTES;
        int drawsAt = indicesAt + (int) indexCount * Integer.BYTES;

        List<Mesh> meshes = new ArrayList<>((int) meshCount);
        for (int rank = 0; rank < meshCount; rank++) {
            int at = meshesAt + rank * MESH_BYTES;
            meshes.add(new Mesh(
                    in.getInt(at),
                    in.getInt(at + 4),
                    in.getInt(at + 8),
                    in.getInt(at + 12),
                    Short.toUnsignedInt(in.getShort(at + 16)),
                    Byte.toUnsignedInt(in.get(at + 18)),
                    Byte.toUnsignedInt(in.get(at + 19)),
                    Short.toUnsignedInt(in.getShort(at + 46))));
        }

        int vertices = (int) vertexCount;
        // La plage d'UV de chaque sommet est celle du mesh qui le porte (ADR-122 §4). Un LOD
        // partage les sommets de sa source et en reprend la plage : le natif a vérifié qu'un
        // sommet partagé n'en a qu'une, et qu'aucun mesh ne sort du tableau.
        int[] uvRanges = new int[vertices];
        for (Mesh mesh : meshes) {
            if (mesh.uv0Range() != 0) {
                Arrays.fill(uvRanges, mesh.vertexOffset(), mesh.vertexOffset() + mesh.vertexCount(),
                        mesh.uv0Range());
            }
        }

        float[] positions = new float[vertices * 3];
        float[] normals = new float[vertices * 3];
        float[] uvs = new float[vertices * 2];
        byte[] colors = new byte[vertices * 4];
        for (int vertex = 0; vertex < vertices; vertex++) {
            int at = verticesAt + vertex * VERTEX_BYTES;
            for (int axis = 0; axis < 3; axis++) {
                positions[vertex * 3 + axis] = in.getFloat(at + axis * 4);
                // Normale i8 normalisée : ±127 code ±1 (DM-04).
                normals[vertex * 3 + axis] = Math.max(-1.0f, in.get(at + 12 + axis) / 127.0f);
            }
            // uv0 UNORM16, à l'offset 20, décodé dans sa plage : min + q / 65535 × étendue, dans
            // l'ordre exact du natif — un autre ordre ne donnerait pas les mêmes flottants.
            float uvMin = rangeMin(uvRanges[vertex]);
            float uvSpan = rangeSpan(uvRanges[vertex]);
            uvs[vertex * 2] = uvMin + Short.toUnsignedInt(in.getShort(at + 20)) / 65535.0f * uvSpan;
            uvs[vertex * 2 + 1] = uvMin + Short.toUnsignedInt(in.getShort(at + 22)) / 65535.0f * uvSpan;
            // Couleur RGBA, à l'offset 28.
            for (int channel = 0; channel < 4; channel++) {
                colors[vertex * 4 + channel] = in.get(at + 28 + channel);
            }
        }

        int[] indices = new int[(int) indexCount];
        for (int rank = 0; rank < indices.length; rank++) {
            indices[rank] = in.getInt(indicesAt + rank * Integer.BYTES);
        }

        List<Draw> draws = new ArrayList<>((int) drawCount);
        for (int rank = 0; rank < drawCount; rank++) {
            int at = drawsAt + rank * DRAW_BYTES;
            float[] model = new float[12];
            for (int value = 0; value < 12; value++) {
                model[value] = in.getFloat(at + 16 + value * 4);
            }
            draws.add(new Draw(in.getInt(at), in.getInt(at + 4), in.getInt(at + 8), model));
        }

        return new GeometryTransfer(
                List.copyOf(meshes), positions, normals, uvs, colors, indices, List.copyOf(draws),
                bytes, verticesAt, indicesAt);
    }

    /** {@return les meshes, dans l'ordre du transfert} */
    public List<Mesh> meshes() {
        return meshes;
    }

    /** {@return la liste de dessin au repos} */
    public List<Draw> draws() {
        return draws;
    }

    /** {@return le nombre de sommets} */
    public int vertexCount() {
        return positions.length / 3;
    }

    /** {@return le nombre d'indices} */
    public int indexCount() {
        return indices.length;
    }

    /** {@return les positions, trois flottants par sommet, en espace du node} */
    public float[] positions() {
        return positions;
    }

    /** {@return les normales dé-quantifiées, trois flottants par sommet} */
    public float[] normals() {
        return normals;
    }

    /**
     * {@return les coordonnées de texture principales, deux flottants par sommet, décodées dans la
     * plage de leur mesh : une texture répétée y garde ses tuiles, hors de {@code [0, 1]}}
     */
    public float[] uvs() {
        return uvs;
    }

    /** {@return les couleurs RGBA, quatre octets par sommet (à lire non signés)} */
    public byte[] colors() {
        return colors;
    }

    /** {@return les indices, locaux à leur mesh} */
    public int[] indices() {
        return indices;
    }

    /**
     * {@return les sommets tels que le natif les a déposés — le format GPU du §19.4, 48 octets
     * chacun —, en lecture seule et petit-boutiste} Le backend natif les téléverse sans les réécrire
     * (ADR-127 §2) : une normale ou une coordonnée de texture re-quantifiée depuis les tableaux
     * décodés ne serait plus exactement celle de l'asset.
     */
    public ByteBuffer vertexData() {
        return ByteBuffer.wrap(raw, verticesAt, vertexCount() * VERTEX_BYTES)
                .slice()
                .order(ByteOrder.LITTLE_ENDIAN)
                .asReadOnlyBuffer()
                .order(ByteOrder.LITTLE_ENDIAN);
    }

    /** {@return les indices tels que le natif les a déposés, {@code u32} petit-boutistes, en lecture seule} */
    public ByteBuffer indexData() {
        return ByteBuffer.wrap(raw, indicesAt, indexCount() * Integer.BYTES)
                .slice()
                .order(ByteOrder.LITTLE_ENDIAN)
                .asReadOnlyBuffer()
                .order(ByteOrder.LITTLE_ENDIAN);
    }
}
