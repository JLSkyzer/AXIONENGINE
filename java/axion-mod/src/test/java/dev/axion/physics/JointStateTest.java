package dev.axion.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Épingle le décodage de {@link JointState} sur la disposition figée d'ADR-151 D7 (24 octets,
 * little-endian) et celui de sa section dans {@code SIM_OUT}. Les octets sont posés aux offsets du
 * natif ; si le décodeur lisait ailleurs, ce test échouerait.
 */
class JointStateTest {

    private static ByteBuffer tampon(int octets) {
        return ByteBuffer.allocate(octets).order(ByteOrder.LITTLE_ENDIAN);
    }

    /** Pose un état à l'octet {@code base}, champ par champ, aux offsets du natif. */
    private static void poser(ByteBuffer buffer, int base, int index, int flags, float fraction) {
        buffer.putInt(base, 7); // handle.index @0
        buffer.putInt(base + 4, 3); // handle.generation @4
        buffer.putShort(base + 8, (short) index); // index @8
        buffer.putShort(base + 10, (short) flags); // flags @10
        buffer.putFloat(base + 12, fraction); // fraction @12
        buffer.putFloat(base + 16, 250.0f); // force @16
        buffer.putFloat(base + 20, -12.5f); // torque @20
    }

    @Test
    void tailleFigee() {
        assertEquals(24, JointState.BYTES, "ADR-151 D7 fige JointState à 24 octets");
        assertEquals(8, JointState.SECTION_HEADER_BYTES, "nombre u32 et 4 octets réservés");
        assertEquals(1, JointState.BROKEN, "joint_state_flags::BROKEN");
        assertEquals(2, JointState.JAMMED, "joint_state_flags::JAMMED");
    }

    @Test
    void decodeLitLesChampsAuxOffsetsFiges() {
        ByteBuffer buffer = tampon(JointState.BYTES);
        poser(buffer, 0, 0xFFFF, JointState.JAMMED, 0.5f);

        JointState joint = JointState.decode(buffer, 0);

        assertEquals(7, joint.handleIndex());
        assertEquals(3, joint.handleGeneration());
        assertEquals(0xFFFF, joint.index(), "le rang est un u16, non signé");
        assertEquals(JointState.JAMMED, joint.flags());
        assertEquals(0.5f, joint.fraction(), 0.0f);
        assertEquals(250.0f, joint.force(), 0.0f);
        assertEquals(-12.5f, joint.torque(), 0.0f);
        assertTrue(joint.jammed());
        assertFalse(joint.broken());
    }

    @Test
    void lesDrapeauxSeLisentSeparement() {
        JointState rompu = new JointState(1, 1, 0, JointState.BROKEN, 1.0f, 0.0f, 0.0f);
        assertTrue(rompu.broken());
        assertFalse(rompu.jammed());
        JointState lesDeux = new JointState(1, 1, 0, JointState.BROKEN | JointState.JAMMED, 0.0f, 0.0f, 0.0f);
        assertTrue(lesDeux.broken());
        assertTrue(lesDeux.jammed());
    }

    @Test
    void laSectionLitSonNombrePuisSesEnregistrements() {
        // Une section posée après 40 octets quelconques : nombre, 4 octets réservés, 2 états.
        int base = 40;
        ByteBuffer buffer = tampon(base + JointState.SECTION_HEADER_BYTES + 2 * JointState.BYTES);
        buffer.putInt(base, 2);
        buffer.putInt(base + 4, 0xDEAD); // réservé : ignoré
        poser(buffer, base + 8, 0, 0, 1.0f);
        poser(buffer, base + 8 + JointState.BYTES, 1, JointState.BROKEN, 1.0f);

        List<JointState> joints = JointState.decodeSection(buffer, base);

        assertEquals(2, joints.size());
        assertEquals(0, joints.get(0).index());
        assertEquals(1, joints.get(1).index(), "le second suit le premier, 24 octets plus loin");
        assertTrue(joints.get(1).broken());
    }

    @Test
    void uneSectionVideSeLit() {
        ByteBuffer buffer = tampon(JointState.SECTION_HEADER_BYTES);
        assertEquals(List.of(), JointState.decodeSection(buffer, 0));
    }

    @Test
    void uneSectionTronqueeDonneUneListeVide() {
        // Le nombre annoncé dépasse ce que le tampon couvre, d'un octet : rien n'est lu au-delà.
        ByteBuffer buffer = tampon(JointState.SECTION_HEADER_BYTES + 2 * JointState.BYTES - 1);
        buffer.putInt(0, 2);
        assertEquals(List.of(), JointState.decodeSection(buffer, 0));
        // Un nombre hostile, non signé, ne déborde pas l'arithmétique.
        buffer.putInt(0, -1);
        assertEquals(List.of(), JointState.decodeSection(buffer, 0));
        // Un en-tête que le tampon ne couvre pas.
        assertEquals(List.of(), JointState.decodeSection(tampon(7), 0));
        assertEquals(List.of(), JointState.decodeSection(tampon(64), 60));
    }
}
