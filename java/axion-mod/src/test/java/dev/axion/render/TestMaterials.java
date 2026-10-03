package dev.axion.render;

import dev.axion.asset.MaterialTransfer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Tables de matériaux d'essai, écrites à la main d'après la disposition d'ADR-122 §6, puis lues
 * comme le rendu les lit.
 */
final class TestMaterials {

    /** Une texture embarquée ; tout autre texte est le chemin d'une ressource. */
    static final String EMBEDDED = "<embarquée>";

    private TestMaterials() {}

    /** Un matériau d'essai, aux valeurs par défaut de glTF ; chaque champ se change. */
    static final class Material {
        private int albedo = MaterialTransfer.NO_TEXTURE;
        private float[] albedoFactor = {1.0f, 1.0f, 1.0f, 1.0f};
        private float cutoff = 0.5f;
        private int blend = MaterialTransfer.BLEND_OPAQUE;
        private int cull = MaterialTransfer.CULL_BACK;
        private int shading = MaterialTransfer.SHADING_PBR;
        private int flags;

        Material albedo(int slot) {
            albedo = slot;
            return this;
        }

        Material factor(float red, float green, float blue, float alpha) {
            albedoFactor = new float[] {red, green, blue, alpha};
            return this;
        }

        Material cutout(float threshold) {
            blend = MaterialTransfer.BLEND_CUTOUT;
            cutoff = threshold;
            return this;
        }

        Material translucent() {
            blend = MaterialTransfer.BLEND_TRANSLUCENT;
            return this;
        }

        Material doubleSided() {
            cull = MaterialTransfer.CULL_NONE;
            return this;
        }

        Material shading(int model) {
            shading = model;
            return this;
        }

        Material flags(int value) {
            flags = value;
            return this;
        }
    }

    /** {@return un matériau aux valeurs par défaut de glTF} */
    static Material material() {
        return new Material();
    }

    /**
     * {@return une table : ces matériaux, puis ces textures — {@link #EMBEDDED}, ou le chemin d'une
     * ressource}
     */
    static MaterialTransfer table(List<Material> materials, String... textures) {
        StringBuilder paths = new StringBuilder();
        for (String texture : textures) {
            if (!texture.equals(EMBEDDED)) {
                paths.append(texture);
            }
        }
        byte[] pathBytes = paths.toString().getBytes(StandardCharsets.UTF_8);
        ByteBuffer out = ByteBuffer.allocate(16 + materials.size() * 96 + textures.length * 16 + pathBytes.length)
                .order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(materials.size()).putInt(textures.length).putInt(pathBytes.length).putInt(0);
        for (Material material : materials) {
            // Empreinte, six slots, dix-sept flottants, énumérations, réserve, drapeaux, usure.
            out.putLong(0L);
            out.putShort((short) material.albedo);
            for (int slot = 0; slot < 5; slot++) {
                out.putShort((short) MaterialTransfer.NO_TEXTURE);
            }
            for (float value : material.albedoFactor) {
                out.putFloat(value);
            }
            // Émissive nulle ; métal, rugosité, occlusion, échelle des normales, découpe, puis
            // cinq nuls.
            for (float value : new float[] {0, 0, 0, 1, 1, 1, 1, material.cutoff, 0, 0, 0, 0, 0}) {
                out.putFloat(value);
            }
            out.put((byte) material.blend).put((byte) material.cull).put((byte) material.shading).put((byte) 0);
            out.putShort((short) material.flags).putShort((short) 0xFFFF);
        }
        int offset = 0;
        for (String texture : textures) {
            if (texture.equals(EMBEDDED)) {
                out.put((byte) MaterialTransfer.SOURCE_EMBEDDED).put((byte) 1).put((byte) 0).put((byte) 0);
                out.putShort((short) 2).putShort((short) 2).putInt(0).putInt(33);
            } else {
                int size = texture.getBytes(StandardCharsets.UTF_8).length;
                out.put((byte) MaterialTransfer.SOURCE_RESOURCE).put((byte) 1).put((byte) 0).put((byte) 0);
                out.putShort((short) 0).putShort((short) 0).putInt(offset).putInt(size);
                offset += size;
            }
        }
        out.put(pathBytes);
        return MaterialTransfer.parse(out.array());
    }
}
