package dev.axion.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-168 D5, précision 6c-B : l'écart entre deux blocs d'état, au format de {@code world::state}. */
class AssemblyStateViewTest {

    /** Un bloc de deux corps ; la queue (joints, véhicule) reste opaque et ne compte pas. */
    private static byte[] bloc(double[][] positions, float[][] rotations, float[][] vitesses) {
        int corps = positions.length;
        ByteBuffer b = ByteBuffer.allocate(16 + corps * 64 + 24).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short) 1).putShort((short) 0).putShort((short) corps).putShort((short) 0).putShort((short) 0);
        b.position(16);
        for (int i = 0; i < corps; i++) {
            for (double v : positions[i]) {
                b.putDouble(v);
            }
            for (float v : rotations[i]) {
                b.putFloat(v);
            }
            for (float v : vitesses[i]) {
                b.putFloat(v);
            }
            b.putFloat(0f).putFloat(0f).putFloat(0f);
        }
        return b.array();
    }

    private static final float[] IDENTITE = {0f, 0f, 0f, 1f};

    @Test
    @DisplayName("l'écart se lit au pire des corps : position, angle entre rotations, vitesse")
    void ecarts() {
        byte[] a = bloc(new double[][] {{100, 64, -2000}, {101, 64, -2000}}, new float[][] {IDENTITE, IDENTITE},
                new float[][] {{0, 0, -5}, {0, 0, -5}});
        // Le second corps décalé de 3-4-0 cm, tourné de 0,01 rad autour de Y, sa vitesse changée de 0,1 m/s.
        float demi = (float) Math.sin(0.005);
        byte[] b = bloc(new double[][] {{100, 64, -2000}, {101.03, 64.04, -2000}},
                new float[][] {IDENTITE, {0f, demi, 0f, (float) Math.cos(0.005)}},
                new float[][] {{0, 0, -5}, {0, 0, -5.1f}});
        AssemblyStateView.Errors e = AssemblyStateView.errors(a, b);
        assertEquals(0.05, e.position(), 1e-9);
        assertEquals(0.01, e.rotation(), 1e-6);
        assertEquals(0.1, e.velocity(), 1e-6);
        assertTrue(e.within(0.05 + 1e-9, 0.01 + 1e-6, 0.1 + 1e-6), "seuils atteints, pas dépassés");
        assertFalse(e.within(0.049, 1, 1), "position au-delà");
        assertFalse(e.within(1, 0.009, 1), "rotation au-delà");
        assertFalse(e.within(1, 1, 0.09), "vitesse au-delà");
        // Une rotation et son opposée sont la même : l'angle est nul.
        byte[] oppose = bloc(new double[][] {{100, 64, -2000}, {101, 64, -2000}},
                new float[][] {IDENTITE, {0f, 0f, 0f, -1f}}, new float[][] {{0, 0, -5}, {0, 0, -5}});
        assertEquals(0.0, AssemblyStateView.errors(a, oppose).rotation(), 1e-9);
    }

    @Test
    @DisplayName("des blocs aux corps différents, tronqués ou absents ne se comparent pas")
    void illisibles() {
        byte[] deux = bloc(new double[][] {{0, 0, 0}, {1, 0, 0}}, new float[][] {IDENTITE, IDENTITE},
                new float[][] {{0, 0, 0}, {0, 0, 0}});
        byte[] un = bloc(new double[][] {{0, 0, 0}}, new float[][] {IDENTITE}, new float[][] {{0, 0, 0}});
        assertNull(AssemblyStateView.errors(deux, un));
        assertNull(AssemblyStateView.errors(deux, Arrays.copyOf(deux, 16 + 64)));
        assertNull(AssemblyStateView.errors(deux, null));
        assertNull(AssemblyStateView.errors(new byte[3], new byte[3]));
        assertEquals(0.0, AssemblyStateView.errors(deux, deux.clone()).position());
    }

    /** Un bloc d'un corps et d'un véhicule sans roue, au format v6 d'{@code world::state} : réserves, mouillé. */
    private static byte[] blocDeVehicule(double fuel, double battery) {
        ByteBuffer b = ByteBuffer.allocate(16 + 64 + 104).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short) 6).putShort((short) 1).putShort((short) 1).putShort((short) 0).putShort((short) 0);
        b.position(16 + 64 + 56);
        b.putDouble(fuel).putDouble(battery);
        return b.array();
    }

    @Test
    @DisplayName("ADR-176 D5 : l'écart du mouillé force une correction au-delà de son seuil, pas en deçà")
    void ecartDuMouille() {
        byte[] sec = blocDeVehicule(0, 0);
        byte[] humide = blocDeVehicule(0, 0);
        ByteBuffer.wrap(humide).order(ByteOrder.LITTLE_ENDIAN).putDouble(16 + 64 + 88, 0.5);
        byte[] presque = blocDeVehicule(0, 0);
        ByteBuffer.wrap(presque).order(ByteOrder.LITTLE_ENDIAN).putDouble(16 + 64 + 88, 0.004);
        AssemblyStateView.Errors ecart = AssemblyStateView.errors(sec, humide);
        assertEquals(0.5, ecart.wet(), 1e-12);
        assertFalse(ecart.within(1, 1, 1, 1, 1, 0.05), "un véhicule mouillé par le serveur se corrige");
        assertTrue(AssemblyStateView.errors(sec, presque).within(1, 1, 1, 1, 1, 0.05), "un cran ne corrige pas");
    }

    @Test
    @DisplayName("ADR-176 D5 : le bloc de véhicule porte le mouillé, un f64 après les réserves")
    void mouille() {
        byte[] bloc = blocDeVehicule(0, 0);
        ByteBuffer.wrap(bloc).order(ByteOrder.LITTLE_ENDIAN).putDouble(16 + 64 + 88, 0.625);
        assertEquals(0.625, AssemblyStateView.vehicle(bloc).wet());
        assertEquals(0.0, AssemblyStateView.vehicle(blocDeVehicule(0, 0)).wet());
    }

    @Test
    @DisplayName("ADR-176 D3, ADR-182 : le bloc de véhicule porte ses réserves ; l'écart est relatif, avec un plancher")
    void energie() {
        AssemblyStateView.Vehicle v = AssemblyStateView.vehicle(blocDeVehicule(12.5, 0.75));
        assertEquals(12.5, v.store(0));
        assertEquals(0.75, v.store(1));
        byte[] quatrieme = blocDeVehicule(12.5, 0.75);
        ByteBuffer.wrap(quatrieme).order(ByteOrder.LITTLE_ENDIAN).putDouble(16 + 64 + 80, 5.0);
        assertEquals(5.0, AssemblyStateView.vehicle(quatrieme).store(3));
        assertEquals(1.0, AssemblyStateView.errors(blocDeVehicule(12.5, 0.75), quatrieme).energy(), 1e-12,
                "chaque réserve compte à l'écart");
        assertEquals(0.0, AssemblyStateView.errors(blocDeVehicule(12.5, 0.75), blocDeVehicule(12.5, 0.75)).energy());
        // Un plein fait par le serveur : 0 contre 40 litres, l'écart est entier ; le seuil de la prédiction le voit.
        AssemblyStateView.Errors plein = AssemblyStateView.errors(blocDeVehicule(0, 1), blocDeVehicule(40, 1));
        assertEquals(1.0, plein.energy(), 1e-12);
        assertFalse(plein.within(1, 1, 1, 0.01));
        assertTrue(plein.within(1, 1, 1, 1.0));
        // Une dérive d'un ulp ne se corrige pas, et deux réservoirs vides se valent.
        assertTrue(AssemblyStateView.errors(blocDeVehicule(12.5, 1), blocDeVehicule(12.5 + 1e-9, 1))
                .within(1, 1, 1, 0.01));
        assertEquals(0.0, AssemblyStateView.errors(blocDeVehicule(0, 0), blocDeVehicule(1e-12, 0)).energy(), 1e-8,
                "sous le plancher, le bruit ne compte pas");
    }

    /** Un bloc d'un corps et d'un véhicule à une roue, au format v6 d'{@code world::state} : l'usure de la roue. */
    private static byte[] blocAUneRoue(double usure) {
        ByteBuffer b = ByteBuffer.allocate(16 + 64 + 104 + 40).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short) 6).putShort((short) 1).putShort((short) 1).putShort((short) 0).putShort((short) 1);
        b.position(16 + 64 + 104 + 32);
        b.putDouble(usure);
        return b.array();
    }

    @Test
    @DisplayName("ADR-176 D4 : le bloc porte l'usure de chaque pneu ; l'écart est le plus grand écart absolu")
    void usure() {
        AssemblyStateView.Vehicle v = AssemblyStateView.vehicle(blocAUneRoue(0.375));
        assertEquals(1, v.wheels().size());
        assertEquals(0.375, v.wheels().get(0).wear());
        AssemblyStateView.Errors e = AssemblyStateView.errors(blocAUneRoue(0.1), blocAUneRoue(0.4));
        assertEquals(0.3, e.wear(), 1e-12);
        assertFalse(e.within(1, 1, 1, 1, 0.01), "une usure posée par le serveur force une correction");
        assertTrue(e.within(1, 1, 1, 1, 0.5));
        assertTrue(AssemblyStateView.errors(blocAUneRoue(0.1), blocAUneRoue(0.1 + 0.003)).within(1, 1, 1, 1, 0.01),
                "un cran d'écart ne corrige pas");
    }
}
