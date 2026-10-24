package dev.axion.forge;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.axion.entity.AssemblyBinding;
import java.util.Arrays;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import org.joml.Quaternionf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T-433, T-434, et le schéma 3 de §22.2 (ADR-153 D2, D5). */
class PersistedStateTest {

    private static PersistedState etat() {
        return new PersistedState(
                new double[] {1.5, 64.0, -3.25},
                new float[] {0f, 0.38268343f, 0f, 0.9238795f},
                new float[] {0.5f, -2.0f, 0.125f},
                new float[] {0f, 1.25f, -0.75f},
                true,
                List.of(
                        new PersistedState.Body(7, 2.5, 63.0, -3.0, new float[] {0.5f, 0.5f, 0.5f, 0.5f},
                                new float[] {1f, 2f, 3f}, new float[] {-1f, -2f, -3f}),
                        new PersistedState.Body(9, 30_000_000.25, -60.5, 12.0, new float[] {0f, 0f, 0f, 1f},
                                new float[] {0f, 0f, 0f}, new float[] {0f, 0f, 0.5f})),
                List.of(new PersistedState.Joint(true, 0f), new PersistedState.Joint(false, 0.5f)));
    }

    private static void memesEtats(PersistedState attendu, PersistedState lu) {
        assertArrayEquals(attendu.position(), lu.position());
        assertArrayEquals(attendu.rotation(), lu.rotation(), "au bit près, sans renormalisation");
        assertArrayEquals(attendu.linear(), lu.linear());
        assertArrayEquals(attendu.angular(), lu.angular());
        assertEquals(attendu.sleeping(), lu.sleeping());
        assertEquals(attendu.bodies().size(), lu.bodies().size());
        for (int i = 0; i < attendu.bodies().size(); i++) {
            PersistedState.Body a = attendu.bodies().get(i);
            PersistedState.Body b = lu.bodies().get(i);
            assertEquals(a.node(), b.node());
            assertEquals(a.x(), b.x());
            assertEquals(a.y(), b.y());
            assertEquals(a.z(), b.z());
            assertArrayEquals(a.rotation(), b.rotation());
            assertArrayEquals(a.linear(), b.linear());
            assertArrayEquals(a.angular(), b.angular());
        }
        assertEquals(attendu.joints(), lu.joints());
    }

    @Test
    @DisplayName("ADR-153 D2 : l'état écrit se relit au bit près, corps secondaires et joints compris")
    void allerRetour() {
        CompoundTag gardees = new CompoundTag();
        etat().write(gardees);
        assertEquals(2 * PersistedState.Body.BYTES, gardees.getByteArray(PersistedState.BODIES).length);
        assertEquals(2 * PersistedState.Joint.BYTES, gardees.getByteArray(PersistedState.JOINTS).length);
        memesEtats(etat(), PersistedState.read(gardees).orElseThrow());
    }

    @Test
    @DisplayName("ADR-153 D2 : la position monde d'un corps loin de l'origine garde ses décimales (f64)")
    void positionsEnDouble() {
        CompoundTag gardees = new CompoundTag();
        etat().write(gardees);
        // 30 000 000,25 n'est pas représentable en f32, dont le pas y vaut 2.
        assertEquals(30_000_000.25, PersistedState.read(gardees).orElseThrow().bodies().get(1).x());
    }

    @Test
    @DisplayName("ADR-153 D2 : les octets d'un corps et d'un joint sont ceux du format, en little-endian")
    void disposition() {
        CompoundTag gardees = new CompoundTag();
        new PersistedState(new double[3], new float[] {0f, 0f, 0f, 1f}, new float[3], new float[3], false,
                List.of(new PersistedState.Body(0x01020304, 1.0, 0.0, 0.0, new float[] {0f, 0f, 0f, 1f},
                        new float[3], new float[3])),
                List.of(new PersistedState.Joint(true, 1.0f)))
                .write(gardees);
        byte[] corps = gardees.getByteArray(PersistedState.BODIES);
        assertEquals(68, corps.length);
        assertArrayEquals(new byte[] {4, 3, 2, 1}, Arrays.copyOfRange(corps, 0, 4), "node u32 LE");
        // 1.0 en f64 LE : 00 00 00 00 00 00 F0 3F.
        assertEquals((byte) 0xF0, corps[4 + 6]);
        assertEquals((byte) 0x3F, corps[4 + 7]);
        byte[] joint = gardees.getByteArray(PersistedState.JOINTS);
        // Drapeaux, trois réservés, puis 1.0 en f32 LE : 00 00 80 3F.
        assertArrayEquals(new byte[] {1, 0, 0, 0, 0, 0, (byte) 0x80, 0x3F}, joint);
    }

    @Test
    @DisplayName("ADR-153 D2 : le sommeil est le bit 0 de axion:flags, les bits réservés restent")
    void drapeaux() {
        CompoundTag gardees = new CompoundTag();
        gardees.putInt(PersistedState.FLAGS, 0b100);
        etat().write(gardees);
        assertEquals(0b101, gardees.getInt(PersistedState.FLAGS));
        PersistedState eveillee = new PersistedState(etat().position(), etat().rotation(), etat().linear(),
                etat().angular(), false, List.of(), List.of());
        eveillee.write(gardees);
        assertEquals(0b100, gardees.getInt(PersistedState.FLAGS));
        assertFalse(PersistedState.read(gardees).orElseThrow().sleeping());
    }

    @Test
    @DisplayName("ADR-153 D2 : un état illisible n'est pas lu — l'assembly repart de sa pose de repos")
    void illisible() {
        CompoundTag base = new CompoundTag();
        etat().write(base);

        CompoundTag courte = base.copy();
        courte.putByteArray(PersistedState.BODIES, new byte[PersistedState.Body.BYTES - 1]);
        assertTrue(PersistedState.read(courte).isEmpty(), "corps tronqué");

        CompoundTag joint = base.copy();
        joint.putByteArray(PersistedState.JOINTS, new byte[PersistedState.Joint.BYTES + 1]);
        assertTrue(PersistedState.read(joint).isEmpty(), "joint tronqué");

        CompoundTag nul = base.copy();
        nul.putByteArray(PersistedState.BODIES, new byte[PersistedState.Body.BYTES]);
        assertTrue(PersistedState.read(nul).isEmpty(), "quaternion nul");

        CompoundTag infinie = base.copy();
        ListTag position = new ListTag();
        position.add(DoubleTag.valueOf(0.0));
        position.add(DoubleTag.valueOf(Double.POSITIVE_INFINITY));
        position.add(DoubleTag.valueOf(0.0));
        infinie.put(PersistedState.POSITION, position);
        assertTrue(PersistedState.read(infinie).isEmpty(), "position non finie");

        CompoundTag sansDrapeaux = base.copy();
        sansDrapeaux.remove(PersistedState.FLAGS);
        assertTrue(PersistedState.read(sansDrapeaux).isEmpty(), "sans axion:flags");

        CompoundTag sansVitesse = base.copy();
        sansVitesse.remove(PersistedState.LINEAR);
        assertTrue(PersistedState.read(sansVitesse).isEmpty(), "sans axion:lvel");
    }

    @Test
    @DisplayName("T-434 : un NBT au schéma 2 se migre au 3, sans état inventé ni clé perdue")
    void migration2Vers3() {
        CompoundTag v2 = new CompoundTag();
        v2.putInt("axion:v", 2);
        v2.putLong("axion:def", 0x1234L);
        AssemblyTags.putRotation(v2, new Quaternionf(0f, 0.38268343f, 0f, 0.9238795f));
        v2.putByteArray("axion:parts", new byte[] {1, 2, 3});

        CompoundTag v3 = AssemblyTags.migrate(v2);
        assertEquals(AssemblyBinding.NBT_SCHEMA, AssemblyTags.version(v3).orElseThrow());
        assertEquals(0x1234L, AssemblyTags.definition(v3).orElseThrow());
        assertEquals(v2.get("axion:rot"), v3.get("axion:rot"));
        assertArrayEquals(new byte[] {1, 2, 3}, v3.getByteArray("axion:parts"), "une clé inconnue reste");
        assertTrue(PersistedState.read(v3).isEmpty(), "le schéma 2 n'avait ni vitesses ni corps : rien d'inventé");
        assertEquals(2, v2.getInt("axion:v"), "l'original n'est pas touché");
    }

    @Test
    @DisplayName("T-433 : un schéma postérieur, ou antérieur à 2, traverse la migration intact (R-1710)")
    void autresVersionsIntactes() {
        for (int version : new int[] {1, 4, 9}) {
            CompoundTag gardees = new CompoundTag();
            gardees.putInt("axion:v", version);
            gardees.putLong("axion:def", 77L);
            gardees.putString("axion:inconnue", "à garder");
            assertSame(gardees, AssemblyTags.migrate(gardees), "schéma " + version);
            assertEquals(version, gardees.getInt("axion:v"));
        }
        CompoundTag sansVersion = new CompoundTag();
        assertSame(sansVersion, AssemblyTags.migrate(sansVersion));
    }
}
