package dev.axion.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ADR-135 — le fichier d'options voisin d'une source, par la règle d'{@code axion-cli}
 * ({@code t580_le_fichier_d_options_voisin_porte_le_nom_de_la_source}).
 */
class AssetOptionsFilesTest {

    @Test
    @DisplayName("ADR-135 : même répertoire, même nom sans l'extension de la source")
    void leFichierVoisinPorteLeNomDeLaSource() {
        assertEquals(
                "ns:axion/models/voiture/voiture.axion.json",
                AssetOptionsFiles.siblingOf("ns:axion/models/voiture/voiture.glb"));
        assertEquals("ns:caisse.axion.json", AssetOptionsFiles.siblingOf("ns:caisse.obj"));
        assertEquals("caisse.axion.json", AssetOptionsFiles.siblingOf("caisse.obj"));
        // Seule la dernière extension tombe, comme pour `Path::file_stem`.
        assertEquals(
                "ns:axion/models/a.b.axion.json", AssetOptionsFiles.siblingOf("ns:axion/models/a.b.stl"));
        // Le point d'un répertoire n'est pas une extension, ni celui qui ouvre un nom.
        assertEquals("ns:axion/v1.2/roue.axion.json", AssetOptionsFiles.siblingOf("ns:axion/v1.2/roue"));
        assertEquals("ns:axion/.glb.axion.json", AssetOptionsFiles.siblingOf("ns:axion/.glb"));
    }

    @Test
    @DisplayName("ADR-135 : un fichier d'options se reconnaît, et n'est pas une source")
    void unFichierDOptionsSeReconnait() {
        assertTrue(AssetOptionsFiles.isOptionsFile("ns:axion/models/voiture.axion.json"));
        assertFalse(AssetOptionsFiles.isOptionsFile("ns:axion/models/voiture.json"));
        assertFalse(AssetOptionsFiles.isOptionsFile("ns:axion/models/voiture.glb"));
        // Le registre ne le compile donc pas : il le lit avec sa source.
        assertEquals(
                SourceFormats.UNKNOWN, SourceFormats.fromPath("ns:axion/models/voiture.axion.json"));
    }
}
