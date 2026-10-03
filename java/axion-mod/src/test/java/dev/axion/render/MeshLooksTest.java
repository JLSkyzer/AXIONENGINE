package dev.axion.render;

import static dev.axion.render.TestMaterials.material;
import static dev.axion.render.TestMaterials.table;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.axion.asset.GeometryTransfer;
import dev.axion.asset.MaterialTransfer;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * L'apparence des meshes : matériau résolu, couleurs ramenées en gamma, pleine lumière,
 * visibilité d'une découpe (ADR-122 §7).
 *
 * <p>Les octets attendus viennent d'un calcul indépendant — puissance 1/2,2 en double, arrondi au
 * plus proche —, sur des entrées choisies loin d'un arrondi à mi-chemin, pour qu'un écart d'un ulp
 * entre deux bibliothèques mathématiques ne puisse pas changer l'octet.
 */
class MeshLooksTest {

    /** Trois sommets aux couleurs distinctes. */
    private static final int[] COLORS = {255, 128, 64, 200, 10, 20, 30, 40, 200, 100, 50, 230};

    private static int argb(int alpha, int red, int green, int blue) {
        return alpha << 24 | red << 16 | green << 8 | blue;
    }

    private static MeshLook only(MaterialTransfer table, TestGeometry.Mesh mesh) {
        return MeshLooks.of(TestGeometry.of(mesh), table).looks().get(0);
    }

    @Test
    @DisplayName("Un canal passe en gamma 1/2,2, l'alpha reste linéaire, sur huit bits")
    void unCanalPasseEnGammaSurHuitBits() {
        assertEquals(0, MeshLooks.channel(0.0f, true));
        assertEquals(255, MeshLooks.channel(1.0f, true));
        assertEquals(186, MeshLooks.channel(0.5f, true));
        assertEquals(136, MeshLooks.channel(0.25f, true));
        assertEquals(64, MeshLooks.channel(0.25f, false));
        assertEquals(191, MeshLooks.channel(0.75f, false));
    }

    @Test
    @DisplayName("Un canal hors de l'unité est borné")
    void unCanalHorsDeLUniteEstBorne() {
        assertEquals(255, MeshLooks.channel(1.5f, true));
        assertEquals(0, MeshLooks.channel(-0.2f, true));
        assertEquals(255, MeshLooks.channel(2.0f, false));
    }

    @Test
    @DisplayName("Sans VERTEX_COLOR, le facteur seul colore tout le mesh")
    void sansCouleurDeSommetLeFacteurSeulColoreToutLeMesh() {
        MeshLook look = only(
                table(List.of(material().factor(0.8f, 0.4f, 0.1f, 0.75f))),
                new TestGeometry.Mesh(0, 0, COLORS));

        assertNull(look.vertexColors());
        int expected = argb(191, 230, 168, 90);
        assertEquals(expected, look.color());
        for (int vertex = 0; vertex < 3; vertex++) {
            assertEquals(expected, look.colorOf(vertex), "sommet " + vertex);
        }
    }

    @Test
    @DisplayName("Avec VERTEX_COLOR, la couleur du sommet module le facteur, puis passe en gamma")
    void laCouleurDuSommetModuleLeFacteur() {
        MeshLook look = only(
                table(List.of(material().factor(1.0f, 0.5f, 0.25f, 0.5f).flags(MaterialTransfer.FLAG_VERTEX_COLOR))),
                new TestGeometry.Mesh(0, 0, COLORS));

        assertEquals(argb(100, 255, 136, 72), look.colorOf(0));
        assertEquals(argb(20, 59, 59, 51), look.colorOf(1));
        assertEquals(argb(115, 228, 122, 65), look.colorOf(2));
    }

    @Test
    @DisplayName("Un facteur supérieur à 1 multiplie avant que le produit soit borné")
    void unFacteurSuperieurAUnMultiplieAvantDEtreBorne() {
        int[] colors = {100, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255};
        MeshLook look = only(
                table(List.of(material().factor(1.5f, 1.0f, 1.0f, 1.0f).flags(MaterialTransfer.FLAG_VERTEX_COLOR))),
                new TestGeometry.Mesh(0, 0, colors));

        // 1,5 × 100/255 = 0,588 : 200 en gamma. Borner le facteur d'abord donnerait 167.
        assertEquals(200, (look.colorOf(0) >>> 16) & 0xFF);
        // 1,5 × 1 se borne à 1.
        assertEquals(255, (look.colorOf(1) >>> 16) & 0xFF);
    }

    @Test
    @DisplayName("Les couleurs d'un mesh se lisent à partir de son premier sommet")
    void lesCouleursDUnMeshSeLisentAPartirDeSonPremierSommet() {
        int[] second = {10, 20, 30, 40, 200, 100, 50, 230, 255, 128, 64, 200};
        GeometryTransfer geometry = TestGeometry.of(
                new TestGeometry.Mesh(0, 0, COLORS), new TestGeometry.Mesh(0, 0, second));
        MaterialTransfer table = table(
                List.of(material().factor(1.0f, 0.5f, 0.25f, 0.5f).flags(MaterialTransfer.FLAG_VERTEX_COLOR)));

        MeshLook look = MeshLooks.of(geometry, table).looks().get(1);
        assertEquals(argb(20, 59, 59, 51), look.colorOf(0));
        assertEquals(argb(100, 255, 136, 72), look.colorOf(2));
    }

    @Test
    @DisplayName("Un matériau absent de la table donne le matériau par défaut, signalé une fois")
    void unMateriauAbsentDonneLeMateriauParDefaut() {
        GeometryTransfer geometry = TestGeometry.of(
                TestGeometry.white(0, 0),
                new TestGeometry.Mesh(3, 0, COLORS),
                TestGeometry.white(7, GeometryTransfer.MESH_TRANSPARENT | GeometryTransfer.MESH_DOUBLE_SIDED));
        MaterialTransfer table = table(List.of(material().factor(0.8f, 0.4f, 0.1f, 0.75f)));

        MeshLooks.Result result = MeshLooks.of(geometry, table);
        assertEquals(3, result.looks().size());
        assertSame(table.materials().get(0), result.looks().get(0).material());

        // Blanc, opaque, faces arrière cachées, couleur de sommet appliquée.
        MeshLook parDefaut = result.looks().get(1);
        assertEquals(SurfacePass.OPAQUE, parDefaut.pass());
        assertFalse(parDefaut.doubleSided());
        assertFalse(parDefaut.fullbright());
        assertNull(parDefaut.albedo());
        assertEquals(argb(200, 255, 186, 136), parDefaut.colorOf(0));

        // Ce que disent les drapeaux du mesh : translucide, deux faces.
        MeshLook drapeaux = result.looks().get(2);
        assertEquals(SurfacePass.TRANSLUCENT, drapeaux.pass());
        assertTrue(drapeaux.doubleSided());

        String note = result.note();
        assertTrue(note.contains("mesh 1") && note.contains("matériau 3"), note);
        assertTrue(note.contains("table de 1") && note.contains("2 meshes"), note);
        assertTrue(note.endsWith("matériau par défaut"), note);
    }

    @Test
    @DisplayName("Sans écart, il n'y a rien à signaler")
    void sansEcartRienASignaler() {
        MeshLooks.Result result = MeshLooks.of(
                TestGeometry.of(TestGeometry.white(0, 0), TestGeometry.white(1, 0)),
                table(List.of(material(), material())));
        assertNull(result.note());
        assertEquals(2, result.looks().size());
    }

    @Test
    @DisplayName("Une table vide donne le matériau par défaut à tous les meshes")
    void uneTableVideDonneLeMateriauParDefaut() {
        MeshLooks.Result result = MeshLooks.of(TestGeometry.of(TestGeometry.white(0, 0)), MaterialTransfer.empty());
        assertEquals(SurfacePass.OPAQUE, result.looks().get(0).pass());
        assertTrue(result.note().contains("table de 0"), result.note());
    }

    @Test
    @DisplayName("Sans éclairage ou FULLBRIGHT : pleine lumière ; tout autre modèle : lumière du monde")
    void pleineLumiere() {
        TestGeometry.Mesh mesh = TestGeometry.white(0, 0);
        assertTrue(only(table(List.of(material().shading(MaterialTransfer.SHADING_UNLIT))), mesh).fullbright());
        assertTrue(only(table(List.of(material().flags(MaterialTransfer.FLAG_FULLBRIGHT))), mesh).fullbright());
        assertFalse(only(table(List.of(material())), mesh).fullbright());
        assertFalse(only(table(List.of(material().shading(MaterialTransfer.SHADING_VANILLA_COMPAT))), mesh)
                .fullbright());
    }

    @Test
    @DisplayName("Le mode de mélange donne la passe, le mode de faces les faces")
    void passeEtFaces() {
        TestGeometry.Mesh mesh = TestGeometry.white(0, 0);
        MeshLook opaque = only(table(List.of(material())), mesh);
        assertEquals(SurfacePass.OPAQUE, opaque.pass());
        assertFalse(opaque.doubleSided());

        MeshLook grille = only(table(List.of(material().cutout(0.5f).doubleSided())), mesh);
        assertEquals(SurfacePass.CUTOUT, grille.pass());
        assertTrue(grille.doubleSided());

        assertEquals(SurfacePass.TRANSLUCENT, only(table(List.of(material().translucent())), mesh).pass());
    }

    @Test
    @DisplayName("Une découpe dont le seuil dépasse 1 ne laisse rien à l'écran")
    void uneDecoupeQuiRejetteToutNeLaisseRien() {
        TestGeometry.Mesh mesh = TestGeometry.white(0, 0);
        // alpha 0,25, seuil 0,5 : il faudrait un texel d'alpha 2.
        assertFalse(only(table(List.of(material().factor(1, 1, 1, 0.25f).cutout(0.5f))), mesh).visible());
        // alpha 0,25, seuil 0,25 : un texel d'alpha 1 passe tout juste.
        assertTrue(only(table(List.of(material().factor(1, 1, 1, 0.25f).cutout(0.25f))), mesh).visible());
        // alpha nul : rien ne passe, sauf à un seuil nul.
        assertFalse(only(table(List.of(material().factor(1, 1, 1, 0).cutout(0.1f))), mesh).visible());
        assertTrue(only(table(List.of(material().factor(1, 1, 1, 0).cutout(0.0f))), mesh).visible());
        // Hors découpe, l'alpha ne cache rien.
        assertTrue(only(table(List.of(material().factor(1, 1, 1, 0))), mesh).visible());
    }

    @Test
    @DisplayName("L'émission est le facteur émissif seul, en gamma, borné à 1, sans la couleur des sommets")
    void lEmissionEstLeFacteurEmissifSeul() {
        MeshLook look = only(
                table(List.of(material()
                        .emissive(MaterialTransfer.NO_TEXTURE, 1.0f, 0.5f, 2.0f)
                        .flags(MaterialTransfer.FLAG_VERTEX_COLOR))),
                new TestGeometry.Mesh(0, 0, COLORS));

        assertTrue(look.emits());
        // 0,5 → 186 en gamma ; 2 se borne à 1 ; les sommets colorés n'y changent rien.
        assertEquals(argb(255, 255, 186, 255), look.emissiveColor());
        assertNull(look.emission(), "sans texture d'émissive : le facteur seul, sur du blanc");

        MeshLook eteint = only(table(List.of(material())), TestGeometry.white(0, 0));
        assertFalse(eteint.emits());
        assertEquals(argb(255, 0, 0, 0), eteint.emissiveColor());
    }

    @Test
    @DisplayName("Le centre d'un mesh est celui de la boîte qui enclôt ses propres sommets")
    void leCentreDUnMeshEstCeluiDeSaBoite() {
        // Sommets du mesh de rang r : (r, 0, 0), (r, 1, 0), (r, 2, 0).
        GeometryTransfer geometry = TestGeometry.of(TestGeometry.white(0, 0), TestGeometry.white(0, 0));
        MeshLooks.Result result = MeshLooks.of(geometry, table(List.of(material())));
        assertArrayEquals(new float[] {0, 1, 0}, result.looks().get(0).center());
        assertArrayEquals(new float[] {1, 1, 0}, result.looks().get(1).center(), "les sommets d'un autre mesh n'y entrent pas");
    }

    @Test
    @DisplayName("La texture d'albedo suit la variante qu'exige le mode de mélange")
    void laTextureDAlbedoSuitLaVariante() {
        TestGeometry.Mesh mesh = TestGeometry.white(0, 0);
        String embedded = TestMaterials.EMBEDDED;
        assertEquals(TextureKey.plain(0), only(table(List.of(material().albedo(0)), embedded), mesh).albedo());
        assertEquals(TextureKey.cutout(0, 0.5f),
                only(table(List.of(material().albedo(0).cutout(0.5f)), embedded), mesh).albedo());
        assertNull(only(table(List.of(material())), mesh).albedo());
    }
}
