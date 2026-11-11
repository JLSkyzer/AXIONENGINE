package dev.axion.content;

import java.util.Set;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import dev.axion.definition.ServerOverrides;
import dev.axion.api.content.ContentKind;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Le contenu que les mods ont enregistré, une fois résolu (ADR-162 D2).
 *
 * <p>Publié une fois l'enregistrement fini, pendant le chargement des mods ; lu ensuite par les écouteurs qui
 * remettent chaque famille au runtime, au démarrage du serveur comme au rechargement des ressources du client. Le
 * même contenu sert au serveur et au client d'une même JVM : ils ont les mêmes mods.
 */
public final class RegisteredContent {

    private static volatile Map<ContentKind, List<ContentRegistry.Entry>> current;
    private static volatile Map<String, String> owners = Map.of();

    private RegisteredContent() {
        throw new AssertionError("classe utilitaire, non instanciable");
    }

    /**
     * Publie le contenu résolu.
     *
     * @param resolved les entrées de chaque famille
     */
    public static void publish(Map<ContentKind, List<ContentRegistry.Entry>> resolved) {
        Map<ContentKind, List<ContentRegistry.Entry>> copy = new EnumMap<>(ContentKind.class);
        resolved.forEach((kind, entries) -> copy.put(kind, List.copyOf(entries)));
        current = copy;
    }

    /**
     * {@return le contenu d'une famille, les surcharges de l'administrateur appliquées (ADR-165 D4)} Une entrée
     * surchargée garde son propriétaire ; sa source le dit. Le fichier de correspondances de l'administrateur
     * appartient à AXION.
     *
     * @param kind la famille
     * @param overrides les surcharges
     * @param applied les surcharges appliquées, dites
     * @param refused les surcharges refusées, avec leur cause
     */
    public static RegisteredSource source(ContentKind kind, ServerOverrides overrides, List<String> applied,
            List<String> refused) {
        Map<ContentKind, List<ContentRegistry.Entry>> content = current;
        List<ContentRegistry.Entry> entries = content == null ? List.of() : content.getOrDefault(kind, List.of());
        Map<String, byte[]> files = new LinkedHashMap<>();
        Map<String, ContentRegistry.Entry> byKey = new LinkedHashMap<>();
        for (ContentRegistry.Entry entry : entries) {
            files.put(entry.key(), entry.bytes());
            byKey.put(entry.key(), entry);
        }
        // Les matériaux qu'une correspondance de blocs peut nommer : ceux que des mods ont enregistrés.
        Set<String> materialIds = new HashSet<>();
        String prefix = "axion/" + ContentKind.PHYSICS_MATERIAL.folder() + "/";
        for (ContentRegistry.Entry entry : content == null ? List.<ContentRegistry.Entry>of()
                : content.getOrDefault(ContentKind.PHYSICS_MATERIAL, List.of())) {
            String key = entry.key();
            int colon = key.indexOf(':');
            if (key.startsWith(prefix, colon + 1) && key.endsWith(".json")) {
                materialIds.add(key.substring(0, colon) + ":"
                        + key.substring(colon + 1 + prefix.length(), key.length() - ".json".length()));
            }
        }
        Map<String, byte[]> overridden = overrides.apply(kind, files, materialIds, applied, refused);
        List<ContentRegistry.Entry> out = new ArrayList<>(overridden.size());
        overridden.forEach((key, bytes) -> {
            ContentRegistry.Entry original = byKey.get(key);
            if (original == null) {
                out.add(new ContentRegistry.Entry(kind, key, "axion", ServerOverrides.FILE_NAME, bytes));
            } else if (bytes != original.bytes()) {
                out.add(new ContentRegistry.Entry(kind, key, original.owner(),
                        original.source() + ", surchargé par " + ServerOverrides.FILE_NAME, bytes));
            } else {
                out.add(original);
            }
        });
        return new RegisteredSource(out);
    }

    /**
     * Publie la version de chaque mod propriétaire de contenu, que le serveur exige du client (ADR-165 D1).
     *
     * @param versions la version de chaque propriétaire, par identifiant
     */
    public static void publishOwners(Map<String, String> versions) {
        owners = Map.copyOf(versions);
    }

    /** {@return la version de chaque mod propriétaire de contenu, vide avant l'enregistrement} */
    public static Map<String, String> owners() {
        return owners;
    }

    /** {@return vrai si l'enregistrement a eu lieu} */
    public static boolean published() {
        return current != null;
    }

    /**
     * {@return le contenu d'une famille, vide avant l'enregistrement}
     *
     * @param kind la famille
     */
    public static RegisteredSource source(ContentKind kind) {
        Map<ContentKind, List<ContentRegistry.Entry>> content = current;
        return new RegisteredSource(content == null ? List.of() : content.getOrDefault(kind, List.of()));
    }
}
