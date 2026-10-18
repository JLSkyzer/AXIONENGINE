package dev.axion.net;

import dev.axion.asset.AssetSource;
import dev.axion.definition.CollisionGroupRegistry;
import dev.axion.definition.DefinitionRegistry;
import dev.axion.definition.DefinitionRules;
import dev.axion.definition.PhysicsMaterials;
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
     * @param journal ce qui est à journaliser : le bilan, puis chaque refus
     */
    public record Loaded(DefinitionRegistry definitions, List<String> journal) {

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
        DefinitionRules rules = new DefinitionRules(true, model -> true, true, groups, materials);
        DefinitionRegistry definitions =
                DefinitionRegistry.load(source(registry.files(RegistryContent.Family.DEFINITIONS)), rules);

        List<String> journal = new ArrayList<>();
        journal.add("registre du serveur appliqué : " + definitions.size() + " definition(s), "
                + materials.ids().size() + " matériau(x) physique(s), "
                + groups.ids().size() + " groupe(s) de collision");
        Stream.of(materials.refusals(), groups.refusals(), definitions.refusals())
                .flatMap(List::stream)
                .forEach(refusal -> journal.add("refusé chez le client : " + refusal));
        return new Loaded(definitions, journal);
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
