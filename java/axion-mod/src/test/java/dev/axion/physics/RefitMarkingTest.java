package dev.axion.physics;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T-312 (R-623, ADR-130) : REFITTABLE posé par Java sur les colliders des parts déformables. */
class RefitMarkingTest {

    private static final int BOX = 1;
    private static final int COMPOUND = 8;
    private static final int NONE = -1;

    /** Un collider de 104 octets : forme étiquetée à 0, enfants d'un composé à 4 et 8, drapeaux à 80. */
    private static void collider(ByteBuffer out, int tag, int firstChild, int childCount, int flags) {
        int at = out.position();
        out.putInt(at, tag);
        out.putInt(at + 4, firstChild);
        out.putInt(at + 8, childCount);
        out.putInt(at + 80, flags);
        out.position(at + 104);
    }

    /** Section PHYS : colliders de premier niveau {tag, premier enfant, enfants, drapeaux}, puis enfants. */
    private static byte[] phys(int[][] colliders, int[] childFlags) {
        ByteBuffer out = ByteBuffer.allocate(4 + colliders.length * 104 + 4 + 4 + childFlags.length * 104)
                .order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(colliders.length);
        for (int[] c : colliders) {
            collider(out, c[0], c[1], c[2], c[3]);
        }
        out.putInt(0); // aucun point d'enveloppe
        out.putInt(childFlags.length);
        for (int flags : childFlags) {
            collider(out, BOX, 0, 0, flags);
        }
        return out.array();
    }

    /** Section NODE : noms, parents et colliders portés. */
    private static byte[] nodes(String[] names, int[] parents, int[] colliders) {
        ByteArrayOutputStream blob = new ByteArrayOutputStream();
        int[][] refs = new int[names.length][2];
        for (int i = 0; i < names.length; i++) {
            byte[] utf8 = names[i].getBytes(StandardCharsets.UTF_8);
            refs[i] = new int[] {blob.size(), utf8.length};
            blob.writeBytes(utf8);
        }
        ByteBuffer out = ByteBuffer.allocate(8 + names.length * 80 + names.length * 8 + blob.size())
                .order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(names.length);
        out.putInt(blob.size());
        for (int i = 0; i < names.length; i++) {
            int at = out.position();
            out.putInt(at + 8, parents[i]);
            out.putInt(at + 60, colliders[i]);
            out.position(at + 80);
        }
        for (int[] ref : refs) {
            out.putInt(ref[0]);
            out.putInt(ref[1]);
        }
        out.put(blob.toByteArray());
        return out.array();
    }

    private static int flags(byte[] phys, int collider) {
        return ByteBuffer.wrap(phys).order(ByteOrder.LITTLE_ENDIAN).getInt(4 + collider * 104 + 80);
    }

    private static int childFlags(byte[] phys, int topLevel, int child) {
        return ByteBuffer.wrap(phys).order(ByteOrder.LITTLE_ENDIAN).getInt(4 + topLevel * 104 + 4 + 4 + child * 104 + 80);
    }

    /**
     * Une carrosserie déformable : l'aile et la porte en descendent, la porte figée par l'auteur,
     * le capot est un composé ; le moteur est d'une autre part.
     */
    private static final byte[] NODES = nodes(
            new String[] {"carrosserie", "aile", "moteur", "porte", "capot"},
            new int[] {NONE, 0, NONE, 0, 0},
            new int[] {NONE, 0, 1, 2, 3});

    private static final byte[] PHYS = phys(
            new int[][] {{BOX, 0, 0, 0}, {BOX, 0, 0, 0}, {BOX, 0, 0, RefitMarking.NO_REFIT}, {COMPOUND, 0, 2, 0}},
            new int[] {0, RefitMarking.NO_REFIT});

    @Test
    @DisplayName("Les colliders sous la racine d'une part déformable sont REFITTABLE, NO_REFIT l'emporte")
    void marqueLesPartsDeformables() {
        byte[] marked = RefitMarking.mark(PHYS, NODES, Set.of("carrosserie"));
        assertEquals(RefitMarking.REFITTABLE, flags(marked, 0), "l'aile descend de la carrosserie");
        assertEquals(0, flags(marked, 1), "le moteur est d'une autre part");
        assertEquals(RefitMarking.NO_REFIT, flags(marked, 2), "la porte est figée par l'auteur");
        assertEquals(RefitMarking.REFITTABLE, flags(marked, 3), "le capot, composé");
        assertEquals(RefitMarking.REFITTABLE, childFlags(marked, 4, 0), "un enfant suit son composé");
        assertEquals(RefitMarking.NO_REFIT, childFlags(marked, 4, 1), "sauf s'il est figé");
        // La section reçue n'est pas touchée : seule la copie l'est.
        assertEquals(0, flags(PHYS, 0));
    }

    @Test
    @DisplayName("Sans part déformable, sans section NODE ou sur une section incohérente, rien n'est marqué")
    void rienAMarquer() {
        assertArrayEquals(PHYS, RefitMarking.mark(PHYS, NODES, Set.of()));
        assertArrayEquals(PHYS, RefitMarking.mark(PHYS, null, Set.of("carrosserie")));
        assertArrayEquals(PHYS, RefitMarking.mark(PHYS, NODES, Set.of("roue")));
        byte[] tronquee = Arrays.copyOf(NODES, NODES.length - 5);
        assertArrayEquals(PHYS, RefitMarking.mark(PHYS, tronquee, Set.of("carrosserie")));
    }

    @Test
    @DisplayName("Les racines retenues sont celles des parts qui portent au moins une région")
    void racinesDesPartsDeformables() {
        Set<String> roots = RefitMarking.rootsOf(JsonParser.parseString("""
                {"parts": [
                  {"name": "caisse", "root_node": "carrosserie", "deform_regions": ["avant", "arriere"]},
                  {"name": "bloc", "root_node": "moteur", "deform_regions": []},
                  {"name": "chassis", "root_node": "longeron"},
                  {"name": "sans_racine", "deform_regions": ["toit"]}
                ]}
                """).getAsJsonObject());
        assertEquals(Set.of("carrosserie"), roots);
    }
}
