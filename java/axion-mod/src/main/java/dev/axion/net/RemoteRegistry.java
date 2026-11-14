package dev.axion.net;

import dev.axion.asset.AssetSource;
import dev.axion.definition.CollisionGroupRegistry;
import dev.axion.definition.DefinitionRegistry;
import dev.axion.definition.DefinitionRules;
import dev.axion.definition.PhysicsMaterials;
import dev.axion.definition.TireProfiles;
import dev.axion.definition.VehicleExtensions;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import java.util.stream.Stream;

/**
 * Relit chez le client le registre reçu du serveur (ADR-150 D5), par les lecteurs stricts du serveur.
 *
 * <p>Les matériaux et les groupes de collision partent tels que le serveur les a lus : leurs lecteurs ne
 * dépendent d'aucune option, et le client en tire exactement ce que le serveur en a tiré. Les
 * definitions, elles, dépendent de la politique du serveur — ses modules, ses options de contenu, les
 * modèles qu'il a compilés : il n'envoie que celles qu'il a acceptées, et le client ne rejuge pas cette
 * politique. Il les admet toutes, véhicules et démonstrations compris, sans exiger leur modèle, qui vient
 * de ses resource packs et dont l'absence donne la boîte de repli, jamais un refus (R-1640).
 */
public final class RemoteRegistry {

    /**
     * Un registre relu.
     *
     * @param definitions definitions acceptées, avec les groupes et les matériaux qu'elles nomment
     * @param materials les matériaux physiques du serveur : le monde prédit du client les tient comme lui
     *     (ADR-168 D1)
     * @param blockMaterials les correspondances bloc → matériau du serveur : les tuiles du monde prédit prennent ses
     *     matériaux (ADR-168 D6)
     * @param journal ce qui est à journaliser : le bilan, puis chaque refus
     */
    public record Loaded(DefinitionRegistry definitions, PhysicsMaterials materials,
            dev.axion.world.BlockMaterials blockMaterials, List<String> journal) {

        /** Copie le journal. */
        public Loaded {
            journal = List.copyOf(journal);
        }
    }

    private RemoteRegistry() {
        throw new AssertionError("non instanciable");
    }

    /**
     * @param registry registre reçu, empreintes vérifiées
     * @return les definitions qui en sont tirées
     */
    public static Loaded load(RegistryContent registry) {
        PhysicsMaterials materials =
                PhysicsMaterials.load(source(registry.files(RegistryContent.Family.PHYSICS_MATERIALS)));
        CollisionGroupRegistry groups =
                CollisionGroupRegistry.load(source(registry.files(RegistryContent.Family.COLLISION_GROUPS)));
        TireProfiles tires = TireProfiles.load(source(registry.files(RegistryContent.Family.TIRES)));
        // ADR-167 D9 : le client tient les mêmes contrôleurs que le serveur, le mod qui les enregistre est exigé.
        DefinitionRules rules = new DefinitionRules(true, model -> true, true, groups, materials, tires,
                VehicleExtensions.current());
        DefinitionRegistry definitions =
                DefinitionRegistry.load(source(registry.files(RegistryContent.Family.DEFINITIONS)), rules);
        dev.axion.definition.BlockMaterialMappings.Result blocks = dev.axion.definition.BlockMaterialMappings.load(
                source(registry.files(RegistryContent.Family.BLOCK_MATERIALS)), materials);

        List<String> journal = new ArrayList<>();
        journal.add("registre du serveur appliqué : " + definitions.size() + " definition(s), "
                + materials.ids().size() + " matériau(x) physique(s), "
                + groups.ids().size() + " groupe(s) de collision, "
                + registry.files(RegistryContent.Family.BLOCK_MATERIALS).size() + " fichier(s) de correspondances"
                + " bloc → matériau");
        Stream.of(materials.refusals(), groups.refusals(), tires.refusals(), definitions.refusals(), blocks.refusals())
                .flatMap(List::stream)
                .forEach(refusal -> journal.add("refusé chez le client : " + refusal));
        return new Loaded(definitions, materials, blocks.mapping(), journal);
    }

    private static AssetSource source(SortedMap<String, byte[]> files) {
        return new AssetSource() {
            @Override
            public List<String> list() {
                return List.copyOf(files.keySet());
            }

            @Override
            public byte[] read(String path) throws IOException {
                byte[] content = files.get(path);
                if (content == null) {
                    throw new IOException("absent du registre reçu : " + path);
                }
                return content.clone();
            }
        };
    }
}
