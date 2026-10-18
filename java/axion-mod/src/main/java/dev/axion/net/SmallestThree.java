package dev.axion.net;

/**
 * La rotation d'un snapshot en 32 bits, « smallest three » (21.5, ADR-150 D6) : l'indice de la plus
 * grande composante sur 2 bits, puis les trois autres sur 10 bits chacune.
 *
 * <p>Un quaternion unitaire et son opposé décrivent la même rotation : la plus grande composante est
 * rendue positive, et se reconstitue par la norme. Les trois autres tiennent alors dans
 * [−1/√2, 1/√2], au pas de √2/1023. L'erreur angulaire est bornée à 0,274° par le calcul (trois
 * composantes à un demi-pas près, la quatrième reconstituée par la norme) ; {@code SnapshotCodecTest}
 * la mesure, 0,234° au pire sur 200 000 rotations tirées au hasard.
 */
public final class SmallestThree {

    private static final int BITS = 10;

    private static final int MAX_STEP = (1 << BITS) - 1;

    private static final double RANGE = Math.sqrt(0.5);

    private SmallestThree() {
        throw new AssertionError("non instanciable");
    }

    /**
     * @param x composante x
     * @param y composante y
     * @param z composante z
     * @param w composante w
     * @return la rotation codée ; un quaternion nul ou non fini donne l'identité
     */
    public static int encode(float x, float y, float z, float w) {
        double[] q = {x, y, z, w};
        double norm = Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]);
        if (!(norm > 0) || !Double.isFinite(norm)) {
            q = new double[] {0, 0, 0, 1};
            norm = 1;
        }
        int largest = 0;
        for (int index = 1; index < 4; index++) {
            if (Math.abs(q[index]) > Math.abs(q[largest])) {
                largest = index;
            }
        }
        double sign = q[largest] < 0 ? -1 : 1;
        int packed = largest << (3 * BITS);
        int shift = 2 * BITS;
        for (int index = 0; index < 4; index++) {
            if (index == largest) {
                continue;
            }
            double value = sign * q[index] / norm;
            long step = Math.round((value + RANGE) / (2 * RANGE) * MAX_STEP);
            packed |= (int) Math.max(0, Math.min(MAX_STEP, step)) << shift;
            shift -= BITS;
        }
        return packed;
    }

    /**
     * @param packed rotation codée ; tout motif de 32 bits se décode
     * @return le quaternion {@code (x, y, z, w)}, unitaire
     */
    public static float[] decode(int packed) {
        int largest = packed >>> (3 * BITS);
        double[] q = new double[4];
        double sum = 0;
        int shift = 2 * BITS;
        for (int index = 0; index < 4; index++) {
            if (index == largest) {
                continue;
            }
            int step = (packed >>> shift) & MAX_STEP;
            q[index] = (double) step / MAX_STEP * (2 * RANGE) - RANGE;
            sum += q[index] * q[index];
            shift -= BITS;
        }
        q[largest] = Math.sqrt(Math.max(0, 1 - sum));
        // Trois composantes au bord de leur plage ne laissent pas une norme unitaire : on la rend.
        double norm = Math.sqrt(sum + q[largest] * q[largest]);
        return new float[] {
            (float) (q[0] / norm), (float) (q[1] / norm), (float) (q[2] / norm), (float) (q[3] / norm),
        };
    }
}
