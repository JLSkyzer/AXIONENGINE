package dev.axion.render;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-151 D9 : un squelette par jeu de corps secondaires, calculé une fois. */
class BodyRigTest {

    /** {@code n} racines, droites et à l'échelle 1, à la disposition de {@code NodeDesc}. */
    private static byte[] racines(int n) {
        ByteBuffer buffer = ByteBuffer.allocate(8 + 80 * n).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0, n);
        for (int index = 0; index < n; index++) {
            int at = 8 + 80 * index;
            buffer.putInt(at + 8, -1);
            buffer.putFloat(at + 36, 1f);
            for (int c = 0; c < 3; c++) {
                buffer.putFloat(at + 40 + 4 * c, 1f);
            }
        }
        return buffer.array();
    }

    @Test
    @DisplayName("Le même jeu de racines rend le même squelette ; un autre, un autre")
    void unSqueletteParJeuDeRacines() {
        BodyRig rig = new BodyRig(racines(4), 0);
        BodySkeleton un = rig.skeleton(new int[] {1, 2});
        assertNotNull(un);
        assertSame(un, rig.skeleton(new int[] {1, 2}), "calculé une fois");
        assertNotSame(un, rig.skeleton(new int[] {2, 1}), "l'ordre compte : le rang d'un corps en dépend");
        assertNull(rig.skeleton(new int[0]), "aucun corps secondaire : dessin d'un seul corps");
        assertNull(rig.skeleton(new int[] {9}), "racine hors de l'asset : dessin d'un seul corps");
    }

    @Test
    @DisplayName("Au-delà de seize jeux, le cache repart de zéro plutôt que de croître")
    void leCacheEstBorne() {
        BodyRig rig = new BodyRig(racines(40), 0);
        BodySkeleton premier = rig.skeleton(new int[] {1});
        for (int racine = 2; racine <= BodyRig.MAX_SKELETONS; racine++) {
            rig.skeleton(new int[] {racine});
        }
        assertSame(premier, rig.skeleton(new int[] {1}), "seize jeux tiennent");
        rig.skeleton(new int[] {30});
        assertNotSame(premier, rig.skeleton(new int[] {1}), "le dix-septième a vidé le cache");
    }
}
