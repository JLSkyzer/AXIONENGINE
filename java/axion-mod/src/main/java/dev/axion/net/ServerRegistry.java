package dev.axion.net;

import dev.axion.asset.AssetSource;
import dev.axion.definition.DefinitionRegistry;
import java.io.IOException;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Ce que le serveur met dans {@code S2C_Registry} (ADR-150 D5), d'après ses rechargements de données.
 *
 * <p>Les matériaux et les groupes de collision partent tous, tels que lus : leurs lecteurs ne dépendent
 * d'aucune option, et le client en tire ce que le serveur en a tiré, refus compris. Les definitions ne
 * partent qu'acceptées : leur acceptation dépend de la politique du serveur — modules, options de
 * contenu, modèles compilés —, que le client ne rejuge pas ({@link RemoteRegistry}).
 */
public final class ServerRegistry {

    private ServerRegistry() {
        throw new AssertionError("non instanciable");
    }

    /**
     * Garde les textes d'un rechargement. Une ressource illisible n'est pas gardée : le lecteur du
     * serveur l'a déjà refusée, et celui du client la refuserait.
     *
     * @param source ressources d'une famille
     * @return les textes lisibles, par identifiant
     */
    public static SortedMap<String, byte[]> read(AssetSource source) {
        SortedMap<String, byte[]> texts = new TreeMap<>();
        for (String path : source.list()) {
            try {
                texts.put(path, source.read(path));
            } catch (IOException unreadable) {
                // Déjà consignée par le rechargement, qui l'a refusée.
            }
        }
        return texts;
    }

    /**
     * @param materials textes des matériaux physiques lus
     * @param groups textes des groupes de collision lus
     * @param definitions definitions acceptées par le serveur
     * @param definitionTexts textes des definitions lues
     * @return le registre à envoyer, sans pneu
     */
    public static RegistryContent.Builder collect(
            Map<String, byte[]> materials,
            Map<String, byte[]> groups,
            DefinitionRegistry definitions,
            Map<String, byte[]> definitionTexts) {
        return collect(materials, groups, Map.of(), definitions, definitionTexts);
    }

    /**
     * @param materials textes des matériaux physiques lus
     * @param groups textes des groupes de collision lus
     * @param tires textes des pneus lus (ADR-159 Q1)
     * @param definitions definitions acceptées par le serveur
     * @param definitionTexts textes des definitions lues
     * @return le registre à envoyer, entrées hors des bornes du protocole écartées
     */
    public static RegistryContent.Builder collect(
            Map<String, byte[]> materials,
            Map<String, byte[]> groups,
            Map<String, byte[]> tires,
            DefinitionRegistry definitions,
            Map<String, byte[]> definitionTexts) {
        return collect(materials, groups, tires, Map.of(), definitions, definitionTexts);
    }

    /**
     * @param materials textes des matériaux physiques lus
     * @param groups textes des groupes de collision lus
     * @param tires textes des pneus lus (ADR-159 Q1)
     * @param blockMaterials textes des correspondances bloc → matériau lues, celle de l'administrateur comprise
     *     (ADR-168 D6)
     * @param definitions definitions acceptées par le serveur
     * @param definitionTexts textes des definitions lues
     * @return le registre à envoyer, entrées hors des bornes du protocole écartées
     */
    public static RegistryContent.Builder collect(
            Map<String, byte[]> materials,
            Map<String, byte[]> groups,
            Map<String, byte[]> tires,
            Map<String, byte[]> blockMaterials,
            DefinitionRegistry definitions,
            Map<String, byte[]> definitionTexts) {
        RegistryContent.Builder builder = RegistryContent.builder();
        blockMaterials.forEach((path, json) -> builder.add(RegistryContent.Family.BLOCK_MATERIALS, path, json));
        tires.forEach((path, json) -> builder.add(RegistryContent.Family.TIRES, path, json));
        materials.forEach((path, json) -> builder.add(RegistryContent.Family.PHYSICS_MATERIALS, path, json));
        groups.forEach((path, json) -> builder.add(RegistryContent.Family.COLLISION_GROUPS, path, json));
        for (String id : definitions.ids()) {
            String location = definitions.get(id).orElseThrow().location();
            byte[] json = definitionTexts.get(location);
            if (json != null) {
                builder.add(RegistryContent.Family.DEFINITIONS, location, json);
            }
        }
        return builder;
    }
}
