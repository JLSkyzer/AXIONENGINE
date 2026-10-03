package dev.axion.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Noms des textures : ressources résolues (ADR-122 §2, R-531), textures enregistrées (§7). */
class TextureLocationsTest {

    private static final String ASSET = "axion:axion/models/test/cube.gltf";

    @Test
    @DisplayName("Une ressource se résout contre le répertoire du modèle")
    void uneRessourceSeResoutContreLeRepertoireDuModele() {
        assertEquals("axion:axion/models/test/tex/a.png", TextureLocations.resolveResource(ASSET, "tex/a.png"));
        assertEquals("axion:axion/models/test/a.png", TextureLocations.resolveResource(ASSET, "a.png"));
    }

    @Test
    @DisplayName("R-531 : Java ne croit pas le natif sur parole")
    void r531LeCheminEstControleDeNouveau() {
        for (String refused : new String[] {"", "/abs.png", "..\\x.png", "c:/x.png", "../x.png",
            "tex/../a.png", "tex//a.png", "./a.png", "tex/"}) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> TextureLocations.resolveResource(ASSET, refused), () -> "« " + refused + " » admis");
            assertTrue(error.getMessage().startsWith("E-3002"), error::getMessage);
        }
    }

    @Test
    @DisplayName("Un chemin qu'une ResourceLocation n'admet pas est refusé, tel qu'écrit")
    void unCheminHorsDuJeuDeCaracteresEstRefuse() {
        for (String refused : new String[] {"Tex/Caisse.png", "tex/a b.png", "tex/%41.png"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> TextureLocations.resolveResource(ASSET, refused), () -> "« " + refused + " » admis");
        }
    }

    @Test
    @DisplayName("Le nom enregistré porte l'asset, le chargement, le rang et la variante")
    void leNomEnregistrePorteLeChargementEtLaVariante() {
        assertEquals("axion:texture/axion/axion/models/test/cube.gltf/7/2",
                TextureLocations.registered(ASSET, 7, TextureKey.plain(2)));
        assertEquals("axion:texture/axion/axion/models/test/cube.gltf/7/2/cutout/3f000000",
                TextureLocations.registered(ASSET, 7, TextureKey.cutout(2, 0.5f)));
        // Deux chargements du même asset ne partagent jamais un nom.
        assertTrue(!TextureLocations.registered(ASSET, 7, TextureKey.plain(0))
                .equals(TextureLocations.registered(ASSET, 8, TextureKey.plain(0))));
    }
}
