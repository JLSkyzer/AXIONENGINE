package dev.axion.render;

import dev.axion.asset.GeometryTransfer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Transferts de géométrie d'essai, écrits à la main d'après la disposition d'ADR-119 §3 : un
 * triangle de trois sommets par mesh, chaque mesh dessiné une fois par son propre node.
 */
final class TestGeometry {

    private TestGeometry() {}

    /**
     * Un mesh d'essai.
     *
     * @param material index de son matériau dans la table
     * @param flags drapeaux {@code MESH_*}
     * @param rgba couleur de ses trois sommets, quatre octets chacun
     * @param uvRange plage de décodage de ses UV (ADR-122 §4) ; {@code 0} pour {@code [0, 1]}
     */
    record Mesh(int material, int flags, int[] rgba, int uvRange) {

        /** Un mesh dont les UV sont dans {@code [0, 1]}. */
        Mesh(int material, int flags, int[] rgba) {
            this(material, flags, rgba, 0);
        }
    }

    /** {@return un mesh à trois sommets blancs, ses UV dans {@code [0, 1]}} */
    static Mesh white(int material, int flags) {
        return new Mesh(material, flags, new int[] {255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255});
    }

    /** {@return un mesh à trois sommets blancs, dont les UV couvrent {@code [0, 3]} : répétés} */
    static Mesh repeated(int material) {
        return new Mesh(material, 0, white(material, 0).rgba(), 0x0300);
    }

    /** {@return les octets du transfert de ces meshes} */
    static byte[] bytes(Mesh... meshes) {
        int vertices = meshes.length * 3;
        ByteBuffer out = ByteBuffer.allocate(16 + meshes.length * 48 + vertices * 48 + vertices * 4 + meshes.length * 64)
                .order(ByteOrder.LITTLE_ENDIAN);
        // En-tête : meshes, sommets, indices, dessins.
        out.putInt(meshes.length).putInt(vertices).putInt(vertices).putInt(meshes.length);

        // MeshDesc (48 o) : plages, matériau, LOD, drapeaux, AABB, région, plage d'UV.
        for (int rank = 0; rank < meshes.length; rank++) {
            out.putInt(rank * 3).putInt(3).putInt(rank * 3).putInt(3);
            out.putShort((short) meshes[rank].material()).put((byte) 0).put((byte) meshes[rank].flags());
            out.putFloat(0).putFloat(0).putFloat(0).putFloat(1).putFloat(1).putFloat(1);
            out.putShort((short) 0xFFFF).putShort((short) meshes[rank].uvRange());
        }

        // Vertex (48 o) : position, normale, tangente, uv0, uv1, couleur, os, poids, région,
        // poids de déformation, réserve.
        for (int rank = 0; rank < meshes.length; rank++) {
            for (int vertex = 0; vertex < 3; vertex++) {
                out.putFloat(rank).putFloat(vertex).putFloat(0);
                out.put(new byte[] {0, 127, 0, 0});
                out.put(new byte[4]);
                out.putShort((short) 0).putShort((short) 0);
                out.putShort((short) 0).putShort((short) 0);
                for (int channel = 0; channel < 4; channel++) {
                    out.put((byte) meshes[rank].rgba()[vertex * 4 + channel]);
                }
                out.put(new byte[4]);
                out.put(new byte[] {(byte) 255, 0, 0, 0});
                out.put((byte) 255).put((byte) 0);
                out.put(new byte[6]);
            }
        }

        // Indices, locaux à chaque mesh.
        for (int rank = 0; rank < meshes.length; rank++) {
            out.putInt(0).putInt(1).putInt(2);
        }

        // RestDraw (64 o) : mesh, node, drapeaux du node, réservé, mat4x3 identité.
        for (int rank = 0; rank < meshes.length; rank++) {
            out.putInt(rank).putInt(rank).putInt(0).putInt(0);
            for (float value : new float[] {1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0}) {
                out.putFloat(value);
            }
        }
        return out.array();
    }

    /** {@return la géométrie de ces meshes, lue comme le rendu la lit} */
    static GeometryTransfer of(Mesh... meshes) {
        return GeometryTransfer.parse(bytes(meshes));
    }
}
