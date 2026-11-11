package dev.axion.content;

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
