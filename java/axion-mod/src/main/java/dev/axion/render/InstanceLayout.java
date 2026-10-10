package dev.axion.render;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Le bloc d'instance du §19.4 — 88 octets, diviseur 1 — et la transformation qu'il porte (ADR-127
 * §2). Logique pure.
 *
 * <p>Disposition, petit-boutiste, attributs 9 à 14 :
 *
 * <pre>
 *  0  vec4 × 3  model               lignes d'une matrice affine 3 × 4 (attributs 9, 10, 11)
 * 48  vec4      tint                couleur RGBA, flottants                (12)
 * 64  ivec2     lightmap            lumière de bloc, puis de ciel          (13)
 * 72  uint      palette_offset      ┐
 * 76  uint      deform_offset       │ lus d'un bloc, en un uvec4          (14)
 * 80  uint      decal_offset_count  │
 * 84  uint      flags               ┘
 * </pre>
 *
 * <p>Le §19.4 donne à chacun des quatre {@code uint} finaux son emplacement, de 14 à 17 : dix-huit
 * emplacements en tout, sommet compris, quand OpenGL 3.3 n'en garantit que seize
 * ({@code GL_MAX_VERTEX_ATTRIBS}) — et le pilote NVIDIA refuse 16 et 17. Les lire en un
 * {@code uvec4} ramène le compte à quinze ; la disposition en mémoire reste celle du §19.4, octet
 * pour octet (ADR-127 §9).
 *
 * <p>Le §19.4 nomme la matrice {@code mat4x3} et lui donne trois emplacements : ce sont les trois
 * lignes de la matrice affine, quatre flottants chacune — les douze valeurs de la {@code mat4x3}
 * colonne-major d'ADR-119, transposées —, que le shader recompose. La translation y est relative à
 * la caméra : composée en {@code double}, elle ne passe en {@code float} qu'après la soustraction,
 * et un objet loin de l'origine du monde ne tremble pas.
 *
 * <p>La lumière garde l'empaquetage de Minecraft ({@code LevelRenderer.getLightColor}) : niveau de
 * bloc et niveau de ciel, chacun multiplié par 16. Palette, déformation et décalques restent à zéro
 * jusqu'à leurs composants (C-66, M6, C-69).
 */
public final class InstanceLayout {

    /** Taille du bloc. */
    public static final int BYTES = 88;

    /** Décalages des champs dans le bloc. */
    public static final int MODEL_OFFSET = 0;
    public static final int TINT_OFFSET = 48;
    public static final int LIGHTMAP_OFFSET = 64;
    public static final int PALETTE_OFFSET = 72;
    public static final int DEFORM_OFFSET = 76;
    public static final int DECAL_OFFSET = 80;
    public static final int FLAGS_OFFSET = 84;

    /**
     * Emplacements des attributs : la première des trois lignes de la matrice, la teinte, la
     * lightmap, puis le {@code uvec4} des quatre {@code uint} finaux, à partir de
     * {@link #PALETTE_OFFSET}.
     */
    public static final int MODEL_LOCATION = 9;
    public static final int TINT_LOCATION = 12;
    public static final int LIGHTMAP_LOCATION = 13;
    public static final int OFFSETS_LOCATION = 14;

    /** Dernier emplacement d'attribut, sommet et instance : quinze en tout, sous les seize garantis. */
    public static final int LAST_LOCATION = OFFSETS_LOCATION;

    private InstanceLayout() {}

    /**
     * Compose la transformation d'un mesh posé : sa transformation de repos (ADR-119), la rotation du
     * corps, puis la translation du corps relative à la caméra.
     *
     * @param dx position du corps moins celle de la caméra, en blocs
     * @param dy idem
     * @param dz idem
     * @param qx rotation du corps, quaternion unitaire
     * @param qy idem
     * @param qz idem
     * @param qw idem
     * @param rest transformation de repos du mesh, {@code mat4x3} colonne-major : trois axes puis la
     *     translation, douze flottants
     * @return les trois lignes de la matrice affine, douze flottants
     */
    public static float[] modelRows(
            double dx, double dy, double dz, float qx, float qy, float qz, float qw, float[] rest) {
        if (rest.length != 12) {
            throw new IllegalArgumentException("transformation de repos de " + rest.length + " flottants, 12 attendus");
        }
        // Matrice de rotation du quaternion, la convention de JOML : v' = q v q*.
        float[][] r = {
            {1 - 2 * (qy * qy + qz * qz), 2 * (qx * qy - qz * qw), 2 * (qx * qz + qy * qw)},
            {2 * (qx * qy + qz * qw), 1 - 2 * (qx * qx + qz * qz), 2 * (qy * qz - qx * qw)},
            {2 * (qx * qz - qy * qw), 2 * (qy * qz + qx * qw), 1 - 2 * (qx * qx + qy * qy)},
        };
        double[] offset = {dx, dy, dz};
        float[] rows = new float[12];
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                // (R × A)[row][column], A[i][column] = rest[column × 3 + i].
                rows[row * 4 + column] = r[row][0] * rest[column * 3]
                        + r[row][1] * rest[column * 3 + 1]
                        + r[row][2] * rest[column * 3 + 2];
            }
            double translation = r[row][0] * rest[9] + r[row][1] * rest[10] + r[row][2] * rest[11];
            rows[row * 4 + 3] = (float) (offset[row] + translation);
        }
        return rows;
    }

    /**
     * {@return vrai si la transformation retourne les faces — un miroir : l'ordre des sommets doit
     * alors être inversé pour que la face avant reste celle de l'asset}
     *
     * @param rows les trois lignes d'une matrice affine
     */
    public static boolean mirrors(float[] rows) {
        float a = rows[0];
        float b = rows[1];
        float c = rows[2];
        float d = rows[4];
        float e = rows[5];
        float f = rows[6];
        float g = rows[8];
        float h = rows[9];
        float i = rows[10];
        return a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g) < 0.0f;
    }

    /**
     * Écrit un bloc à la position courante du tampon, qui avance de {@link #BYTES}.
     *
     * @param out tampon petit-boutiste
     * @param rows les trois lignes de la matrice, relatives à la caméra
     * @param tint couleur RGBA, quatre flottants
     * @param packedLight lumière empaquetée comme Minecraft : bloc en bas, ciel en haut
     * @param flags drapeaux de l'instance
     */
    public static void write(ByteBuffer out, float[] rows, float[] tint, int packedLight, int flags) {
        if (out.order() != ByteOrder.LITTLE_ENDIAN) {
            throw new IllegalArgumentException("le bloc d'instance s'écrit en petit-boutiste");
        }
        for (float value : rows) {
            out.putFloat(value);
        }
        for (float value : tint) {
            out.putFloat(value);
        }
        out.putInt(packedLight & 0xFFFF);
        out.putInt(packedLight >>> 16);
        out.putInt(0); // palette_offset : C-66
        out.putInt(0); // deform_offset : M6
        out.putInt(0); // decal_offset_count : C-69
        out.putInt(flags);
    }
}
