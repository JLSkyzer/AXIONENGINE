package dev.axion.seat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.axion.asset.Anatomy;
import dev.axion.definition.Seating;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-158 D3, T-440 : un siège à la pose de son corps, composée avec la sienne dans ce corps. */
class SeatPlacementTest {

    private static final float DEMI = (float) Math.sqrt(0.5);

    /** Une racine au corps 0 ; un siège à (1, 0,5, 0) dans le corps 0 ; un autre au corps 1, en son origine. */
    private static Anatomy anatomie() {
        float[] poses = {
            0, 0, 0, 0, 0, 0, 1,
            1, 0.5f, 0, 0, 0, 0, 1,
            0, 0, 0, 0, 0, 0, 1,
            0, 0, 0, 0, 0, 0, 1
        };
        return Anatomy.of(new int[] {-1, 0, 0, 0}, new int[] {0, 0, 1, -1}, poses, new int[][] {{0}, {2}});
    }

    private static void proche(double attendu, double obtenu, String quoi) {
        assertEquals(attendu, obtenu, 1e-5, quoi);
    }

    @Test
    @DisplayName("T-440 : le siège suit la position et l'orientation de son corps")
    void suitLeCorps() {
        // Le corps tourné d'un quart de tour autour de y : (1, 0,5, 0) devient (0, 0,5, −1).
        SeatPlacement.BodyPose corps = new SeatPlacement.BodyPose(10, 64, -3, new float[] {0, DEMI, 0, DEMI});
        SeatPlacement.Pose siege = SeatPlacement.place(anatomie(), 1, List.of(corps));
        proche(10, siege.x(), "x");
        proche(64.5, siege.y(), "y");
        proche(-4, siege.z(), "z");
        proche(DEMI, siege.rotation()[1], "orientation du corps");
        proche(DEMI, siege.rotation()[3], "orientation du corps");
    }

    @Test
    @DisplayName("T-440 : un siège porté par un corps secondaire suit ce corps")
    void corpsSecondaire() {
        SeatPlacement.BodyPose racine = new SeatPlacement.BodyPose(0, 0, 0, new float[] {0, 0, 0, 1});
        // Le second corps à (2, 0, 0) de la racine, elle-même tournée d'un demi-tour autour de y.
        SeatPlacement.BodyPose tournee = new SeatPlacement.BodyPose(5, 0, 0, new float[] {0, 1, 0, 0});
        SeatPlacement.BodyPose second = SeatPlacement.compose(tournee, new double[] {2, 0, 0}, new float[] {0, 0, 0, 1});
        proche(3, second.x(), "le demi-tour renvoie le décalage vers −x");
        SeatPlacement.Pose siege = SeatPlacement.place(anatomie(), 2, List.of(racine, second));
        proche(3, siege.x(), "x");
        proche(0, siege.z(), "z");
    }

    @Test
    @DisplayName("ADR-158 D3 : un node sans pose, ou un corps inconnu, ne se place pas")
    void sansPose() {
        SeatPlacement.BodyPose corps = new SeatPlacement.BodyPose(0, 0, 0, new float[] {0, 0, 0, 1});
        assertNull(SeatPlacement.place(anatomie(), 3, List.of(corps)), "chaîne rompue");
        assertNull(SeatPlacement.place(anatomie(), 2, List.of(corps)), "le corps 1 manque");
        List<SeatPlacement.BodyPose> inconnu = new ArrayList<>(Arrays.asList(corps, null));
        assertNull(SeatPlacement.place(anatomie(), 2, inconnu), "sa pose n'est pas connue");
        assertNull(SeatPlacement.place(anatomie(), 9, List.of(corps)), "hors de la table");
    }

    @Test
    @DisplayName("T-444 : le siège retrouvé par son nom s'il est libre, sinon le premier libre")
    void siegeRetrouve() {
        Seating seating = sieges();
        SeatOccupancy table = new SeatOccupancy(2);
        assertEquals(1, SeatPlacement.seatFor(seating, table, "passager"));
        assertEquals(0, SeatPlacement.seatFor(seating, table, null));
        assertEquals(0, SeatPlacement.seatFor(seating, table, "disparu"), "un siège retiré de la definition");
        table.sit(4, 1);
        assertEquals(0, SeatPlacement.seatFor(seating, table, "passager"), "pris : le premier libre");
        table.sit(5, 0);
        assertEquals(SeatOccupancy.FREE, SeatPlacement.seatFor(seating, table, "passager"), "aucun libre");
    }

    private static Seating sieges() {
        return new Seating(List.of(
                new Seating.Seat("conducteur", 1, true, List.of(), 0),
                new Seating.Seat("passager", 2, false, List.of(), 0)), List.of(), List.of());
    }

    @Test
    @DisplayName("ADR-158 D3 : la composition des quaternions suit l'ordre corps puis node")
    void ordre() {
        // Un quart de tour en y suivi d'un quart de tour en x : appliqué à x̂, il donne −ẑ.
        float[] y = {0, DEMI, 0, DEMI};
        float[] x = {DEMI, 0, 0, DEMI};
        float[] compose = SeatPlacement.multiply(y, x);
        float[] image = SeatPlacement.rotate(compose, new float[] {1, 0, 0});
        proche(0, image[0], "x");
        proche(0, image[1], "y");
        proche(-1, image[2], "z");
        float[] versY = SeatPlacement.rotate(compose, new float[] {0, 1, 0});
        proche(1, versY[0], "ŷ tourné en x puis en y : x̂");
    }
}
