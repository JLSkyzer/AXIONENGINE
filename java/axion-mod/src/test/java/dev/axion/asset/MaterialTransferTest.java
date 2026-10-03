package dev.axion.asset;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Épingle la lecture de la table des matériaux et des textures (ADR-122 §6).
 *
 * <p>Les octets sont écrits à la main d'après la disposition ratifiée, comme le test de
 * disposition côté Rust : c'est la disposition qui est vérifiée, pas un lecteur contre un
 * encodeur de sa propre main.
 */
class MaterialTransferTest {

    private static final int NO = MaterialTransfer.NO_TEXTURE;

    private static final byte[] CHEMIN = "tex/caisse.png".getBytes(StandardCharsets.UTF_8);

    /** Début des deux {@code TextureDesc}, après l'en-tête et deux matériaux. */
    private static final int TEXTURES_AT = 16 + 2 * 96;

    /** Début de la zone des chemins, après les deux textures. */
    private static final int PATHS_AT = TEXTURES_AT + 2 * 16;

    /**
     * Deux matériaux, deux textures, un chemin. « peint » : albedo de la texture 0, PNG
     * embarqué de 2×2 ; émissive de la texture 1, une ressource ; découpe, deux faces, sans
     * éclairage. « nu » : aucun slot, valeurs par défaut de glTF.
     */
    private static byte[] table() {
        ByteBuffer out = ByteBuffer.allocate(PATHS_AT + CHEMIN.length).order(ByteOrder.LITTLE_ENDIAN);
        // En-tête : matériaux, textures, octets de chemins, réserve.
        out.putInt(2).putInt(2).putInt(CHEMIN.length).putInt(0);

        // MaterialDesc (96 o) : empreinte, six slots, dix-sept flottants, énumérations,
        // réserve, drapeaux, profil d'usure.
        out.putLong(0x0102_0304_0506_0708L);
        for (int slot : new int[] {0, NO, NO, 1, NO, NO}) {
            out.putShort((short) slot);
        }
        // Albedo RGBA, émissive RGB, puis métal, rugosité, occlusion, échelle des normales,
        // découpe, parallaxe, vernis, rugosité du vernis, lustre, anisotropie.
        for (float value : new float[] {1, 0.5f, 0.25f, 1, 1, 1, 1, 0.25f, 0.75f, 1, 1, 0.5f, 0, 0, 0, 0, 0}) {
            out.putFloat(value);
        }
        out.put((byte) 1).put((byte) 1).put((byte) 3).put((byte) 0);
        out.putShort((short) (1 | 4)).putShort((short) 0xFFFF);

        out.putLong(0x1112_1314_1516_1718L);
        for (int slot = 0; slot < 6; slot++) {
            out.putShort((short) NO);
        }
        for (float value : new float[] {1, 1, 1, 1, 0, 0, 0, 1, 1, 1, 1, 0.5f, 0, 0, 0, 0, 0}) {
            out.putFloat(value);
        }
        out.put((byte) 0).put((byte) 0).put((byte) 0).put((byte) 0);
        out.putShort((short) 0).putShort((short) 0xFFFF);

        // TextureDesc (16 o) : provenance, format, échantillonneur, réserve, largeur, hauteur,
        // décalage, taille. Le PNG ne voyage pas ici : décalage nul, sa taille seule.
        out.put((byte) 1).put((byte) 1).put((byte) (1 | 4)).put((byte) 0);
        out.putShort((short) 2).putShort((short) 2).putInt(0).putInt(33);
        out.put((byte) 2).put((byte) 1).put((byte) (2 | 8)).put((byte) 0);
        out.putShort((short) 0).putShort((short) 0).putInt(0).putInt(CHEMIN.length);

        out.put(CHEMIN);
        return out.array();
    }

    @Test
    void laDispositionDAdr122SeLit() {
        MaterialTransfer table = MaterialTransfer.parse(table());

        assertEquals(2, table.materials().size());
        MaterialTransfer.Material peint = table.materials().get(0);
        assertEquals(0x0102_0304_0506_0708L, peint.nameHash());
        assertArrayEquals(new int[] {0, NO, NO, 1, NO, NO}, peint.textureSlots());
        assertArrayEquals(new float[] {1, 0.5f, 0.25f, 1}, peint.albedoFactor());
        assertArrayEquals(new float[] {1, 1, 1}, peint.emissiveFactor());
        assertEquals(0.25f, peint.metallic());
        assertEquals(0.75f, peint.roughness());
        assertEquals(1.0f, peint.occlusionStrength());
        assertEquals(1.0f, peint.normalScale());
        assertEquals(0.5f, peint.alphaCutoff());
        assertEquals(1, peint.blendMode(), "découpe");
        assertEquals(1, peint.cullMode(), "deux faces");
        assertEquals(3, peint.shadingModel(), "sans éclairage");
        assertEquals(1 | 4, peint.flags(), "couleur de sommet, pleine lumière");
        assertEquals(0xFFFF, peint.wearProfile());

        MaterialTransfer.Material nu = table.materials().get(1);
        assertEquals(0x1112_1314_1516_1718L, nu.nameHash());
        assertArrayEquals(new int[] {NO, NO, NO, NO, NO, NO}, nu.textureSlots());
        assertEquals(1.0f, nu.roughness());
        assertEquals(0, nu.blendMode());

        assertEquals(2, table.textures().size());
        MaterialTransfer.Texture albedo = table.textures().get(0);
        assertTrue(albedo.embedded());
        assertEquals(new MaterialTransfer.Texture(1, 1, 1 | 4, 2, 2, 33, null), albedo);
        assertNull(albedo.path(), "les octets d'un PNG voyagent par axion_asset_texture");
        assertEquals(
                new MaterialTransfer.Texture(2, 1, 2 | 8, 0, 0, CHEMIN.length, "tex/caisse.png"),
                table.textures().get(1));
    }

    @Test
    void uneTableVideEstValide() {
        // Un asset d'avant ADR-122 : son MATL provisoire est ignoré, la table est vide.
        MaterialTransfer table = MaterialTransfer.parse(new byte[16]);
        assertTrue(table.materials().isEmpty());
        assertTrue(table.textures().isEmpty());
    }

    @Test
    void uneLongueurQuiNeCorrespondPasEstRefusee() {
        byte[] valide = table();
        for (int length : new int[] {0, 15, valide.length - 1, valide.length + 1}) {
            byte[] tronque = Arrays.copyOf(valide, length);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> MaterialTransfer.parse(tronque),
                    () -> length + " octets");
        }
    }

    @Test
    void unDefautDeLaFrontiereSeVoitAuLieuDIndexerACote() {
        assertRefuse("réserve non nulle", in -> in.putInt(12, 1));
        assertRefuse("slot hors de la table", in -> in.putShort(16 + 96 + 8, (short) 2));
        assertRefuse("PNG à décalage non nul", in -> in.putInt(TEXTURES_AT + 8, 4));
        assertRefuse("provenance inconnue", in -> in.put(TEXTURES_AT, (byte) 7));
        assertRefuse("chemin hors de sa zone", in -> in.putInt(TEXTURES_AT + 16 + 8, 1));
        assertRefuse("chemin non UTF-8", in -> in.put(PATHS_AT, (byte) 0xFF));
        // Énumérations du premier matériau, aux octets 88 à 90 de son MaterialDesc : la
        // dernière valeur connue passe, la suivante est refusée.
        assertRefuse("mode de mélange inconnu", in -> in.put(16 + 88, (byte) 3));
        assertRefuse("mode de faces inconnu", in -> in.put(16 + 89, (byte) 2));
        assertRefuse("modèle d'éclairage inconnu", in -> in.put(16 + 90, (byte) 5));
    }

    @Test
    void lesDernieresValeursDesEnumerationsSontAdmises() {
        byte[] bytes = table();
        ByteBuffer in = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        in.put(16 + 88, (byte) MaterialTransfer.BLEND_TRANSLUCENT);
        in.put(16 + 89, (byte) MaterialTransfer.CULL_NONE);
        in.put(16 + 90, (byte) MaterialTransfer.SHADING_VANILLA_COMPAT);
        MaterialTransfer.Material peint = MaterialTransfer.parse(bytes).materials().get(0);
        assertEquals(2, peint.blendMode());
        assertEquals(1, peint.cullMode());
        assertEquals(4, peint.shadingModel());
    }

    private static void assertRefuse(String defaut, Consumer<ByteBuffer> alteration) {
        byte[] bytes = table();
        alteration.accept(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN));
        assertThrows(IllegalArgumentException.class, () -> MaterialTransfer.parse(bytes), defaut);
    }
}
