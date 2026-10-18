package dev.axion.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Le codec des snapshots (21.5, ADR-150 D6) : chaque champ quantifié selon son échelle déclarée
 * (R-1660), un delta qui redonne sa cible au bit près, et tout message mal formé refusé (T-422).
 */
class SnapshotCodecTest {

    private static AssemblyState etat(double x, double y, double z, float vy, boolean dort) {
        return AssemblyState.of(new double[] {x, y, z}, new float[] {0f, 0.38268343f, 0f, 0.9238795f},
                new float[] {0f, vy, 0f}, new float[] {0f, 0.5f, 0f},
                new float[] {-0.5f, -0.5f, -0.5f}, new float[] {0.5f, 0.5f, 0.5f}, dort);
    }

    private static AssemblyState hasard(Random random) {
        float[] q = {random.nextFloat() * 2 - 1, random.nextFloat() * 2 - 1, random.nextFloat() * 2 - 1,
            random.nextFloat() * 2 - 1};
        return AssemblyState.of(
                new double[] {random.nextDouble() * 6e7 - 3e7, random.nextDouble() * 400 - 64, random.nextDouble() * 6e7 - 3e7},
                q,
                new float[] {random.nextFloat() * 80 - 40, random.nextFloat() * 80 - 40, random.nextFloat() * 80 - 40},
                new float[] {random.nextFloat() * 20 - 10, random.nextFloat() * 20 - 10, random.nextFloat() * 20 - 10},
                random.nextBoolean() ? null : new float[] {-random.nextFloat() * 3, -random.nextFloat(), -random.nextFloat() * 3},
                new float[] {random.nextFloat() * 3, random.nextFloat(), random.nextFloat() * 3},
                random.nextBoolean());
    }

    @Test
    @DisplayName("R-1660 : chaque champ a son échelle déclarée, et la vitesse est bornée comme le natif la borne")
    void chaqueChampASonEchelle() {
        assertEquals(1024L, Quantization.position(1.0));
        assertEquals(-1L, Quantization.position(-1.0 / 1024));
        assertEquals(1.5, Quantization.position(Quantization.position(1.5)));
        assertEquals(Short.MAX_VALUE, Quantization.linear(300f));
        assertEquals(Short.MAX_VALUE, Quantization.linear(1e9f), "au-delà de R-180, la vitesse est bornée");
        assertEquals(-Short.MAX_VALUE, Quantization.angular(-1e9f));
        assertEquals(Short.MAX_VALUE, Quantization.angular(100f));
        assertEquals(12.5f, Quantization.linear(Quantization.linear(12.5f)), 300f / Short.MAX_VALUE);
        // L'emprise quantifiée ne rétrécit jamais : le minimum descend, le maximum monte.
        // ±0,3010 tombe à 77,06 pas : l'arrondi au plus proche rétrécirait l'emprise d'un pas.
        assertTrue(Quantization.bounds(Quantization.boundsMin(-0.3010f)) <= -0.3010f);
        assertTrue(Quantization.bounds(Quantization.boundsMax(0.3010f)) >= 0.3010f);
        assertEquals(-78, Quantization.boundsMin(-0.3010f));
        assertEquals(78, Quantization.boundsMax(0.3010f));
    }

    @Test
    @DisplayName("Smallest three : rotation unitaire, opposée identique, erreur angulaire sous sa borne théorique de 0,28°")
    void smallestThreeEstFidele() {
        Random random = new Random(150);
        double pire = 0;
        for (int index = 0; index < 200_000; index++) {
            float[] q = {(float) random.nextGaussian(), (float) random.nextGaussian(), (float) random.nextGaussian(),
                (float) random.nextGaussian()};
            double n = Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]);
            for (int c = 0; c < 4; c++) {
                q[c] /= n;
            }
            int code = SmallestThree.encode(q[0], q[1], q[2], q[3]);
            assertEquals(code, SmallestThree.encode(-q[0], -q[1], -q[2], -q[3]), "q et -q sont la même rotation");
            float[] lu = SmallestThree.decode(code);
            double dot = Math.abs(q[0] * lu[0] + q[1] * lu[1] + q[2] * lu[2] + q[3] * lu[3]);
            pire = Math.max(pire, Math.toDegrees(2 * Math.acos(Math.min(1.0, dot))));
        }
        System.out.printf("smallest three : erreur angulaire maximale mesurée %.4f° sur 200 000 rotations%n", pire);
        // Borne théorique : trois composantes à un demi-pas (√2/2046) près, la quatrième reconstituée par
        // la norme, qui amplifie leur erreur d'au plus √0,75/0,5 — soit 0,274°.
        assertTrue(pire < 0.275, "erreur angulaire " + pire + "°");

        assertArrayEquals(new float[] {0f, 0f, 0f, 1f}, SmallestThree.decode(SmallestThree.encode(0f, 0f, 0f, 1f)), 1e-3f);
        assertArrayEquals(new float[] {0f, 0f, 0f, 1f},
                SmallestThree.decode(SmallestThree.encode(Float.NaN, 0f, 0f, 0f)), 1e-3f, "non fini : l'identité");
        assertArrayEquals(new float[] {0f, 0f, 0f, 1f}, SmallestThree.decode(SmallestThree.encode(0f, 0f, 0f, 0f)), 1e-3f);
        // Tout motif de 32 bits se décode en quaternion unitaire, même hors de ce qu'un émetteur produit.
        for (int motif : new int[] {-1, 0, 0x3FFFFFFF, 0xC0000000, 0x7FFFFFFF}) {
            float[] lu = SmallestThree.decode(motif);
            double norme = Math.sqrt(lu[0] * lu[0] + lu[1] * lu[1] + lu[2] * lu[2] + lu[3] * lu[3]);
            assertEquals(1.0, norme, 1e-5, Integer.toHexString(motif));
        }
    }

    @Test
    @DisplayName("Un delta redonne sa cible au bit près, le long d'une chaîne de bases, après aller-retour sur le fil")
    void unDeltaRedonneSaCible() throws MalformedPacketException {
        Random random = new Random(1661);
        AssemblyState base = hasard(random);
        int sequenceBase = 1;
        SnapshotMessage premier = new SnapshotMessage(1, 100, SnapshotMessage.DESCRIPTOR_VERSION,
                List.of(SnapshotMessage.Entry.full(7, base)));
        AssemblyState tenu = SnapshotMessage.decode(premier.encode()).entries().get(0).resolve(null);
        assertEquals(base, tenu);

        for (int sequence = 2; sequence < 400; sequence++) {
            AssemblyState suivant = random.nextInt(4) == 0 ? hasard(random) : new AssemblyState(
                    base.x() + random.nextInt(4096) - 2048, base.y() + random.nextInt(64), base.z() - random.nextInt(4096),
                    random.nextInt(), random.nextBoolean() ? base.velocity() : hasard(random).velocity(),
                    random.nextBoolean() ? base.bounds() : hasard(random).bounds(), random.nextBoolean());
            SnapshotMessage.Entry entree = SnapshotMessage.Entry.delta(7, sequenceBase, base, suivant);
            SnapshotMessage lu = SnapshotMessage.decode(new SnapshotMessage(sequence, 100 + sequence,
                    SnapshotMessage.DESCRIPTOR_VERSION, List.of(entree)).encode());
            SnapshotMessage.Entry relue = lu.entries().get(0);
            assertEquals(entree, relue);
            assertEquals(suivant, relue.resolve(tenu), "séquence " + sequence);
            base = suivant;
            tenu = relue.resolve(tenu);
            sequenceBase = sequence;
        }
    }

    @Test
    @DisplayName("Un delta tait ce qui n'a pas changé, et devient complet quand il ne peut pas dire l'écart")
    void unDeltaTaitCeQuiNAPasChange() {
        AssemblyState base = etat(10, 64, -10, 0f, false);
        AssemblyState bouge = etat(10.5, 64, -10, 0f, false);
        SnapshotMessage.Entry delta = SnapshotMessage.Entry.delta(3, 5, base, bouge);
        assertEquals(5, delta.base());
        assertEquals(512L, delta.x());
        assertNull(delta.velocity(), "vitesse inchangée : tue");
        assertNull(delta.bounds(), "emprise inchangée : tue");
        assertEquals(bouge, delta.resolve(base));

        AssemblyState loin = etat(10 + 3e6, 64, -10, 0f, false);
        assertTrue(SnapshotMessage.Entry.delta(3, 5, base, loin).full(), "un écart de plus de 32 bits : entrée complète");
        AssemblyState sansEmprise = new AssemblyState(bouge.x(), bouge.y(), bouge.z(), bouge.rotation(),
                bouge.velocity(), null, false);
        assertTrue(SnapshotMessage.Entry.delta(3, 5, base, sansEmprise).full(), "une emprise disparue : entrée complète");

        AssemblyState auRepos = new AssemblyState(0, 0, 0, 0, AssemblyState.Velocity.ZERO, null, true);
        SnapshotMessage.Entry complete = SnapshotMessage.Entry.full(3, auRepos);
        assertNull(complete.velocity(), "vitesse nulle d'une entrée complète : tue");
        assertEquals(auRepos, complete.resolve(null));
    }

    @Test
    @DisplayName("Une position non finie ne se quantifie pas")
    void unePositionNonFinieEstRefusee() {
        assertThrows(IllegalArgumentException.class, () -> AssemblyState.of(new double[] {Double.NaN, 0, 0},
                new float[] {0, 0, 0, 1}, new float[3], new float[3], null, null, false));
        assertThrows(IllegalArgumentException.class, () -> AssemblyState.of(new double[] {0, Double.POSITIVE_INFINITY, 0},
                new float[] {0, 0, 0, 1}, new float[3], new float[3], null, null, false));
    }

    @Test
    @DisplayName("L'acquittement se relit, et ses demandes sont bornées")
    void lAcquittementSeRelit() throws MalformedPacketException {
        AckMessage ack = new AckMessage(42, List.of(1, 7, 300));
        assertEquals(ack, AckMessage.decode(ack.encode()));
        List<Integer> trop = new ArrayList<>();
        for (int index = 0; index <= AckMessage.MAX_FULL_REQUESTS; index++) {
            trop.add(index);
        }
        byte[] tropDeDemandes = new AckMessage(1, trop).encode();
        assertThrows(MalformedPacketException.class, () -> AckMessage.decode(tropDeDemandes));
        byte[] bon = ack.encode();
        assertThrows(MalformedPacketException.class, () -> AckMessage.decode(Arrays.copyOf(bon, bon.length - 1)));
        assertThrows(MalformedPacketException.class, () -> AckMessage.decode(Arrays.copyOf(bon, bon.length + 1)));
    }

    @Test
    @DisplayName("T-422 : un snapshot mal formé est refusé, jamais appliqué")
    void t422_unSnapshotMalFormeEstRefuse() {
        AssemblyState etat = etat(1, 2, 3, 4f, false);
        byte[] bon = new SnapshotMessage(9, 1000, SnapshotMessage.DESCRIPTOR_VERSION,
                List.of(SnapshotMessage.Entry.full(1, etat), SnapshotMessage.Entry.delta(2, 8, etat, etat(1.5, 2, 3, 4f, false))))
                .encode();
        assertEquals(2, lu(bon).entries().size());

        assertRefuse(new WireWriter().u8(MessageKinds.SNAPSHOT).varUInt(0).varULong(1).u8(1).varUInt(0).toByteArray(),
                "séquence nulle");
        assertRefuse(new WireWriter().u8(MessageKinds.SNAPSHOT).varUInt(1).varULong(1).u8(2).varUInt(0).toByteArray(),
                "autre descripteur");
        assertRefuse(new WireWriter().u8(MessageKinds.SNAPSHOT).varUInt(1).varULong(1).u8(1)
                .varUInt(SnapshotMessage.MAX_ENTRIES + 1).toByteArray(), "trop d'entrées");
        // Sur une entrée complète, que rien d'autre ne fasse refuser.
        assertRefuse(new WireWriter().u8(MessageKinds.SNAPSHOT).varUInt(9).varULong(1).u8(1).varUInt(1)
                .varUInt(4).u8(0x10 | SnapshotMessage.FLAG_FULL).i64(0).i64(0).i64(0).i32(0).toByteArray(),
                "drapeau inconnu");
        assertRefuse(entree(9, 0, 9), "base égale à la séquence");
        assertRefuse(entree(9, 0, 0), "base nulle d'un delta");
        WireWriter enDouble = new WireWriter().u8(MessageKinds.SNAPSHOT).varUInt(9).varULong(1).u8(1).varUInt(2);
        for (int index = 0; index < 2; index++) {
            enDouble.varUInt(4).u8(SnapshotMessage.FLAG_FULL).i64(0).i64(0).i64(0).i32(0);
        }
        assertRefuse(enDouble.toByteArray(), "entité en double");
        assertRefuse(Arrays.copyOf(bon, bon.length - 1), "tronqué");
        assertRefuse(Arrays.copyOf(bon, bon.length + 1), "octets en trop");
        assertRefuse(new AckMessage(1, List.of()).encode(), "autre genre");
    }

    private static byte[] entree(int sequence, int flags, int base) {
        return new WireWriter().u8(MessageKinds.SNAPSHOT).varUInt(sequence).varULong(1).u8(1).varUInt(1)
                .varUInt(4).u8(flags).varUInt(base).i32(0).i32(0).i32(0).i32(0).toByteArray();
    }

    private static SnapshotMessage lu(byte[] message) {
        try {
            return SnapshotMessage.decode(message);
        } catch (MalformedPacketException error) {
            throw new AssertionError(error.getMessage(), error);
        }
    }

    private static void assertRefuse(byte[] message, String cas) {
        assertThrows(MalformedPacketException.class, () -> SnapshotMessage.decode(message), cas);
    }
}
