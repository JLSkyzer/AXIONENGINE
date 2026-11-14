package dev.axion.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.axion.definition.ServerOverrides;
import dev.axion.world.BlockMaterials;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ADR-168 D6 : les correspondances bloc → matériau partent avec le registre, celle de l'administrateur comprise, et le
 * client distant en tire ce que le serveur en a tiré — les tuiles de son monde prédit prennent les matériaux du serveur.
 */
class BlockMaterialRegistryTest {

    private static byte[] json(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static final byte[] GLACE = json("{\"friction\": 0.05}");
    private static final byte[] BOUE = json("{\"friction\": 0.9}");
    private static final byte[] DU_MOD = json("{\"schema\": 1, \"by_block\": {\"minecraft:ice\": \"m:glace\","
            + " \"minecraft:mud\": \"m:glace\"}}");
    private static final byte[] DE_L_ADMIN = json("{\"schema\": 1, \"by_block\": {\"minecraft:mud\": \"m:boue\"}}");

    private static RegistryContent.Builder materiaux() {
        return RegistryContent.builder()
                .add(RegistryContent.Family.PHYSICS_MATERIALS, "m:axion/physics_materials/glace.json", GLACE)
                .add(RegistryContent.Family.PHYSICS_MATERIALS, "m:axion/physics_materials/boue.json", BOUE);
    }

    private static RegistryContent avecCorrespondances() {
        return materiaux()
                .add(RegistryContent.Family.BLOCK_MATERIALS, "m:axion/block_materials/base.json", DU_MOD)
                .add(RegistryContent.Family.BLOCK_MATERIALS, ServerOverrides.BLOCK_MAPPING_KEY, DE_L_ADMIN)
                .build();
    }

    @Test
    @DisplayName("ADR-168 D6 : les correspondances se relisent après le réseau ; celle de l'administrateur l'emporte")
    void allerRetour() throws MalformedPacketException {
        RegistryContent recu = RegistryContent.decode(avecCorrespondances().encode());
        assertEquals(2, recu.files(RegistryContent.Family.BLOCK_MATERIALS).size());
        RemoteRegistry.Loaded charge = RemoteRegistry.load(recu);
        BlockMaterials blocs = charge.blockMaterials();
        assertEquals(0.05f, blocs.resolve("minecraft:ice", List.of()).friction(), 1e-6f, "celle du mod");
        assertEquals(0.9f, blocs.resolve("minecraft:mud", List.of()).friction(), 1e-6f,
                "celle de l'administrateur passe après celle du mod");
        assertNull(blocs.resolve("minecraft:stone", List.of()).id(), "sans correspondance : le défaut du solveur");
    }

    @Test
    @DisplayName("21.3 : changer une correspondance change l'empreinte des matériaux ; sans elles, rien ne change")
    void empreinte() {
        RegistryContent sans = materiaux().build();
        assertNotEquals(sans.materialsHash(), avecCorrespondances().materialsHash());
        RegistryContent autre = materiaux()
                .add(RegistryContent.Family.BLOCK_MATERIALS, "m:axion/block_materials/base.json", DU_MOD)
                .build();
        assertNotEquals(autre.materialsHash(), avecCorrespondances().materialsHash(), "le fichier de l'administrateur");
        assertEquals(sans.definitionsHash(), avecCorrespondances().definitionsHash(),
                "les definitions n'en dépendent pas");
    }

    @Test
    @DisplayName("ADR-168 D6 : un registre sans correspondance donne au client le défaut du solveur, comme avant")
    void sansCorrespondance() throws MalformedPacketException {
        RemoteRegistry.Loaded charge = RemoteRegistry.load(RegistryContent.decode(materiaux().build().encode()));
        assertNull(charge.blockMaterials().resolve("minecraft:ice", List.of()).id());
    }
}
