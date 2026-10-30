package dev.axion.asset;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32C;
import org.junit.jupiter.api.Test;

/**
 * Épingle le localisateur de sections A3D sur la disposition figée du conteneur
 * (PARTIE 7, {@code crates/ax-asset/src/a3d}) : en-tête de 64 o, table d'entrées
 * de 32 o, little-endian. Les A3D sont construits à la main ici — le format est un
 * contrat stable, et le bâtir explicitement documente ce que Java lit.
 */
class A3dSectionsTest {

    private static final int HEADER_BYTES = 64;
    private static final int ENTRY_BYTES = 32;
    private static final int FLAG_COMPRESSED = 1;

    /** Une section en attente d'écriture dans l'A3D de test. */
    private record Section(String tag, int flags, byte[] payload) {}

    private static int alignUp16(int value) {
        return (value + 15) & ~15;
    }

    /** Construit un A3D minimal, valide pour le localisateur (CRC non calculés). */
    private static byte[] buildA3d(int versionMajor, Section... sections) {
        int tableBytes = sections.length * ENTRY_BYTES;
        int cursor = alignUp16(HEADER_BYTES + tableBytes);
        int[] offsets = new int[sections.length];
        for (int i = 0; i < sections.length; i++) {
            offsets[i] = cursor;
            cursor = alignUp16(cursor + sections[i].payload().length);
        }
        int total = cursor;

        byte[] out = new byte[total];
        ByteBuffer b = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
        out[0] = 'A';
        out[1] = '3';
        out[2] = 'D';
        out[3] = 0;
        b.putShort(4, (short) versionMajor);
        b.putShort(6, (short) 1); // version mineure
        b.putInt(8, 0); // flags
        b.putInt(12, sections.length); // section_count
        b.putLong(40, total); // total_size

        for (int i = 0; i < sections.length; i++) {
            int base = HEADER_BYTES + i * ENTRY_BYTES;
            byte[] tag = sections[i].tag().getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(tag, 0, out, base, 4);
            b.putInt(base + 4, sections[i].flags());
            b.putLong(base + 8, offsets[i]);
            b.putLong(base + 16, sections[i].payload().length); // size_compressed
            b.putInt(base + 24, sections[i].payload().length); // size_uncompressed
            b.putInt(base + 28, 0); // crc32c, ignoré par le localisateur
        }
        for (int i = 0; i < sections.length; i++) {
            System.arraycopy(sections[i].payload(), 0, out, offsets[i], sections[i].payload().length);
        }
        return out;
    }

    @Test
    void localiseLaSectionPhys() {
        byte[] node = {1, 2, 3, 4, 5, 6, 7};
        byte[] phys = {10, 20, 30, 40, 50};
        byte[] file = buildA3d(1, new Section("NODE", 0, node), new Section("PHYS", 0, phys));

        assertArrayEquals(phys, A3dSections.section(file, "PHYS"), "octets PHYS");
        assertArrayEquals(node, A3dSections.section(file, "NODE"), "octets NODE");
    }

    @Test
    void uneSectionAbsenteEstNulle() {
        byte[] file = buildA3d(1, new Section("NODE", 0, new byte[] {1, 2, 3}));
        assertNull(A3dSections.section(file, "PHYS"), "PHYS absente");
    }

    @Test
    void uneSectionVideSeLit() {
        // Une section présente et vide n'est pas une section absente.
        byte[] file = buildA3d(1, new Section("PHYS", 0, new byte[0]));
        assertArrayEquals(new byte[0], A3dSections.section(file, "PHYS"), "PHYS vide");
    }

    @Test
    void unMagicInvalideEstRefuse() {
        byte[] file = buildA3d(1, new Section("PHYS", 0, new byte[] {1}));
        file[0] = 'X';
        assertThrows(IllegalArgumentException.class, () -> A3dSections.section(file, "PHYS"));
    }

    @Test
    void uneVersionMajeureInconnueEstRefusee() {
        byte[] file = buildA3d(2, new Section("PHYS", 0, new byte[] {1}));
        assertThrows(IllegalArgumentException.class, () -> A3dSections.section(file, "PHYS"));
    }

    @Test
    void uneSectionCompresseeEstRefusee() {
        // Java ne décode pas zstd : mieux vaut refuser que rendre des octets faux.
        byte[] file = buildA3d(1, new Section("PHYS", FLAG_COMPRESSED, new byte[] {1, 2, 3, 4}));
        assertThrows(IllegalStateException.class, () -> A3dSections.section(file, "PHYS"));
    }

    @Test
    void uneSectionCompresseeSeLitParLeDecompresseur() {
        // ADR-155 : le décompresseur reçoit la trame stockée et la taille déclarée par l'entrée.
        byte[] file = buildA3d(1, new Section("ANIM", FLAG_COMPRESSED, new byte[] {1, 2, 3, 4}));
        int[] demandee = {-1};
        byte[] lue = A3dSections.section(file, "ANIM", (trame, taille) -> {
            assertArrayEquals(new byte[] {1, 2, 3, 4}, trame);
            demandee[0] = taille;
            return new byte[] {4, 3, 2, 1};
        });
        assertArrayEquals(new byte[] {4, 3, 2, 1}, lue);
        assertEquals(4, demandee[0]);
        // Un contenu d'une autre taille que celle déclarée est refusé.
        assertThrows(IllegalArgumentException.class,
                () -> A3dSections.section(file, "ANIM", (trame, taille) -> new byte[taille + 1]));
        // Une section non compressée ne passe pas par lui.
        byte[] claire = buildA3d(1, new Section("ANIM", 0, new byte[] {7}));
        assertArrayEquals(new byte[] {7}, A3dSections.section(claire, "ANIM", (trame, taille) -> {
            throw new AssertionError("non compressée");
        }));
    }

    @Test
    void uneSectionHorsBornesEstRefusee() {
        byte[] file = buildA3d(1, new Section("PHYS", 0, new byte[] {1, 2, 3, 4}));
        // Gonfle la taille annoncée de l'entrée PHYS au-delà du fichier.
        ByteBuffer b = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
        b.putLong(HEADER_BYTES + 16, file.length + 1024L);
        assertThrows(IllegalArgumentException.class, () -> A3dSections.section(file, "PHYS"));
    }

    @Test
    void unTagMalFormeEstRefuse() {
        byte[] file = buildA3d(1, new Section("PHYS", 0, new byte[] {1}));
        assertThrows(IllegalArgumentException.class, () -> A3dSections.section(file, "PHY"));
    }

    @Test
    void lIdentifiantSeRecritAvecLeCrcDeLEnTete() {
        // C-25 : l'entrée du cache d'une source se reprend sous l'identifiant d'une autre aux
        // mêmes octets. Le chargement natif d'un A3D réécrit est dans NativeBridgeTest.
        byte[] file = buildA3d(1, new Section("PHYS", 0, new byte[] {1, 2, 3}));
        // Un CRC quelconque en place : le nouveau se calcule sans lui.
        ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN).putLong(16, 0x1111L).putInt(36, 0x5A5A_A5A5);
        assertSame(file, A3dSections.withAssetId(file, 0x1111L), "déjà à son nom : sans copie");

        byte[] repris = A3dSections.withAssetId(file, 0x2222L);
        ByteBuffer b = ByteBuffer.wrap(repris).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0x2222L, b.getLong(16));
        byte[] entete = Arrays.copyOf(repris, HEADER_BYTES);
        Arrays.fill(entete, 36, 40, (byte) 0);
        CRC32C crc = new CRC32C();
        crc.update(entete);
        assertEquals((int) crc.getValue(), b.getInt(36), "CRC des 64 octets, son propre champ annulé");
        assertArrayEquals(
                Arrays.copyOfRange(file, HEADER_BYTES, file.length),
                Arrays.copyOfRange(repris, HEADER_BYTES, repris.length),
                "le reste intact");
        assertEquals(0x1111L, ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN).getLong(16),
                "l'original intact");
    }

    @Test
    void unConteneurIllisibleNeSeRecritPas() {
        assertThrows(IllegalArgumentException.class, () -> A3dSections.withAssetId(new byte[63], 1L));
        byte[] inconnue = buildA3d(2, new Section("PHYS", 0, new byte[] {1}));
        assertThrows(IllegalArgumentException.class, () -> A3dSections.withAssetId(inconnue, 1L));
        byte[] sansMagic = buildA3d(1, new Section("PHYS", 0, new byte[] {1}));
        sansMagic[0] = 'X';
        assertThrows(IllegalArgumentException.class, () -> A3dSections.withAssetId(sansMagic, 1L));
    }
}
