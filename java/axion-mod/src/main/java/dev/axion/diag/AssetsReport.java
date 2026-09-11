package dev.axion.diag;

import dev.axion.asset.AssetEntry;
import dev.axion.asset.AssetRegistry;
import dev.axion.asset.AssetState;
import dev.axion.asset.SourceFormats;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Rapports des commandes d'assets (C-71).
 *
 * <p>Composés hors de toute API de plateforme, comme {@link StatusReport} : ce
 * sont des listes de lignes, vérifiables sans démarrer le jeu et réutilisables
 * par {@code /axion diag dump} quand il arrivera.
 */
public final class AssetsReport {

    /** Nombre maximal d'assets détaillés par {@code /axion assets list}. */
    private static final int MAX_LINES = 32;

    private AssetsReport() {
        throw new AssertionError("classe utilitaire, non instanciable");
    }

    /**
     * Compose le résumé de {@code /axion assets list}.
     *
     * <p>Les assets sont groupés par état plutôt qu'énumérés un par un : sur un
     * pack fourni, la liste complète dépasse ce qu'un chat peut montrer, et
     * c'est la répartition qui dit si quelque chose ne va pas.
     *
     * @param registry registre d'assets, ou {@code null}
     * @return les lignes du rapport
     */
    public static List<String> list(AssetRegistry registry) {
        List<String> lines = new ArrayList<>();
        if (registry == null) {
            lines.add("AXION : aucun asset découvert — les ressources n'ont pas été chargées");
            return List.copyOf(lines);
        }

        List<AssetEntry> entries = registry.entries();
        lines.add("AXION ENGINE — assets (" + entries.size() + ")");

        Map<AssetState, Integer> counts = new EnumMap<>(AssetState.class);
        for (AssetEntry entry : entries) {
            counts.merge(entry.state(), 1, Integer::sum);
        }
        for (AssetState state : AssetState.values()) {
            Integer count = counts.get(state);
            if (count != null) {
                lines.add("  " + state + " : " + count);
            }
        }

        // Les refusés d'abord : ce sont eux qu'on cherche quand on tape cette
        // commande.
        entries.stream()
                .filter(entry -> entry.state() == AssetState.FAILED)
                .limit(MAX_LINES)
                .forEach(entry -> lines.add("  refusé : " + entry.path()));

        if (registry.cache() != null) {
            lines.add("  cache : " + registry.cache().size() + " entrée(s), "
                    + registry.cache().totalBytes() + " octets");
        }
        return List.copyOf(lines);
    }

    /**
     * Compose le détail d'un asset.
     *
     * @param registry registre d'assets, ou {@code null}
     * @param path chemin de l'asset
     * @return les lignes du rapport
     */
    public static List<String> info(AssetRegistry registry, String path) {
        List<String> lines = new ArrayList<>();
        AssetEntry entry = registry == null ? null : registry.entry(path);
        if (entry == null) {
            // Dire lequel manque, et non « introuvable » : sur un pack fourni,
            // c'est presque toujours une faute de frappe.
            lines.add("AXION : aucun asset à « " + path + " »");
            return List.copyOf(lines);
        }

        lines.add("AXION ENGINE — " + entry.path());
        lines.add("  état : " + entry.state());
        lines.add("  format : " + SourceFormats.name(entry.format()));
        lines.add("  clé : " + (entry.key() == null ? "aucune" : entry.key().hex()));
        lines.add("  source : " + entry.content().length + " octets");
        if (entry.compiledSize() > 0) {
            lines.add("  compilé : " + entry.compiledSize() + " octets");
        }
        if (entry.state() == AssetState.FAILED) {
            lines.add("  remplacé par " + AssetRegistry.FALLBACK_ID);
        }
        return List.copyOf(lines);
    }
}
