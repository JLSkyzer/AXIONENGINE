package dev.axion.physics;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MaterialMarkingTest {

    private static final int BOX = 1;
    private static final int COMPOUND = 8;
    private static final int NONE = -1;
    private static final Map<String, Integer> INDEX = Map.of("mymod:steel", 1, "mymod:glass", 2, "mymod:wood", 3);

    /** Un collider de 104 octets : étiquette à 0, enfants d'un composé à 4 et 8, matériau 0xFFFF à 68. */
    private static void collider(ByteBuffer out, int tag, int firstChild, int childCount) {
        int at = out.position();
        out.putInt(at, tag);
        out.putInt(at + 4, firstChild);
        out.putInt(at + 8, childCount);
        out.putShort(at + 68, (short) 0xFFFF);
        out.position(at + 104);
    }

    /** Section PHYS : colliders de premier niveau {tag, premier enfant, enfants}, puis enfants. */
    private static byte[] phys(int[][] colliders, int children) {
        ByteBuffer out = ByteBuffer.allocate(4 + colliders.length * 104 + 4 + 4 + children * 104)
                .order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(colliders.length);
        for (int[] c : colliders) {
            collider(out, c[0], c[1], c[2]);
        }
        out.putInt(0); // aucun point d'enveloppe
        out.putInt(children);
        for (int child = 0; child < children; child++) {
            collider(out, BOX, 0, 0);
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

    private static int material(byte[] phys, int collider) {
        return Short.toUnsignedInt(ByteBuffer.wrap(phys).order(ByteOrder.LITTLE_ENDIAN).getShort(4 + collider * 104 + 68));
    }

    private static int childMaterial(byte[] phys, int topLevel, int child) {
        int childrenAt = 4 + topLevel * 104 + 4 + 4;
        return Short.toUnsignedInt(
                ByteBuffer.wrap(phys).order(ByteOrder.LITTLE_ENDIAN).getShort(childrenAt + child * 104 + 68));
    }

    /**
     * Une portière (acier) dont la vitre (verre) est un node enfant, une caisse (bois) en composé de
     * deux enfants, et un collider que nul node ne porte.
     */
    private static final byte[] NODES = nodes(
            new String[] {"racine", "portiere", "vitre", "caisse"},
            new int[] {NONE, 0, 1, 0},
            new int[] {NONE, 0, 1, 2});

    private static final byte[] PHYS = phys(new int[][] {{BOX, 0, 0}, {BOX, 0, 0}, {COMPOUND, 0, 2}, {BOX, 0, 0}}, 2);

    private static final Map<String, String> PARTS =
            Map.of("portiere", "mymod:steel", "vitre", "mymod:glass", "caisse", "mymod:wood");

    @Test
    @DisplayName("ADR-149 D2 : chaque collider prend le matériau de sa part la plus proche, l'acier générique sans")
    void chaqueColliderPrendLeMateriauDeSaPart() {
        byte[] marked = MaterialMarking.mark(PHYS, NODES, PARTS, INDEX::get);
        assertEquals(1, material(marked, 0), "portière : acier");
        assertEquals(2, material(marked, 1), "vitre, enfant de la portière : sa propre part l'emporte");
        assertEquals(3, material(marked, 2), "caisse : bois");
        assertEquals(3, childMaterial(marked, 4, 0), "enfant du composé : son composé");
        assertEquals(3, childMaterial(marked, 4, 1));
        assertEquals(MaterialMarking.GENERIC_STEEL, material(marked, 3), "sans node : acier générique");
        assertEquals(0xFFFF, material(PHYS, 0), "l'original n'est pas touché");
    }

    @Test
    @DisplayName("ADR-149 D2 : sans part nommant un matériau, ou sans NODE, tout est acier générique")
    void sansPartToutEstAcierGenerique() {
        for (byte[] marked : new byte[][] {
            MaterialMarking.mark(PHYS, NODES, Map.of(), INDEX::get),
            MaterialMarking.mark(PHYS, null, PARTS, INDEX::get),
        }) {
            for (int collider = 0; collider < 4; collider++) {
                assertEquals(MaterialMarking.GENERIC_STEEL, material(marked, collider));
            }
            assertEquals(MaterialMarking.GENERIC_STEEL, childMaterial(marked, 4, 1));
        }
    }

    @Test
    @DisplayName("ADR-149 D2 : une section PHYS incohérente est rendue telle quelle")
    void unePhysIncoherenteEstRendueTelleQuelle() {
        byte[] incoherente = PHYS.clone();
        ByteBuffer.wrap(incoherente).order(ByteOrder.LITTLE_ENDIAN).putInt(0, 9_999);
        assertArrayEquals(incoherente, MaterialMarking.mark(incoherente, NODES, PARTS, INDEX::get));
    }

    @Test
    @DisplayName("ADR-149 D2 : seules les parts qui nomment un matériau et un root_node comptent")
    void racinesDesPartsMaterielles() {
        Map<String, String> roots = MaterialMarking.rootsOf(JsonParser.parseString(
                "{\"parts\": [{\"name\": \"a\", \"root_node\": \"porte\", \"material\": \"mymod:steel\"},"
                        + " {\"name\": \"b\", \"root_node\": \"siege\"},"
                        + " {\"name\": \"c\", \"material\": \"mymod:wood\"}]}").getAsJsonObject());
        assertEquals(Map.of("porte", "mymod:steel"), roots);
        assertEquals(Map.of(), MaterialMarking.rootsOf(JsonParser.parseString("{}").getAsJsonObject()));
    }
}
