package dev.axion.debug;

import java.util.Properties;

/**
 * Mode développeur d'AXION (§31.3) : {@code -Daxion.dev=true}.
 *
 * <p>Il rend, entre autres, les overlays de debug disponibles (décision 2 d'ADR-121). Lu une
 * fois, au chargement de la classe : il ne change pas en cours de partie. Sans la propriété,
 * il est inerte (R-2270).
 */
public final class DevMode {

    /** Propriété système qui l'active. */
    public static final String PROPERTY = "axion.dev";

    /**
     * Propriété qui, en mode développeur seulement, désactive le cache d'assets (§31.3) :
     * {@code -Daxion.dev.asset_cache=false}. Chaque asset se recompile alors à chaque
     * rechargement, ce qui sert à vérifier le compilateur sans soupçonner le cache.
     */
    public static final String ASSET_CACHE_PROPERTY = "axion.dev.asset_cache";

    private static final boolean ENABLED = isEnabledIn(System.getProperties());

    private static final boolean ASSET_CACHE_DISABLED = assetCacheDisabledIn(System.getProperties());

    private DevMode() {}

    /** {@return vrai si le mode développeur est actif dans ce processus} */
    public static boolean enabled() {
        return ENABLED;
    }

    /** {@return vrai si le cache d'assets est désactivé, ce que seul le mode développeur permet} */
    public static boolean assetCacheDisabled() {
        return ASSET_CACHE_DISABLED;
    }

    /**
     * {@return vrai si ces propriétés désactivent le cache d'assets}
     *
     * <p>Il faut le mode développeur <em>et</em> {@code false} pour {@link #ASSET_CACHE_PROPERTY} :
     * sans {@code -Daxion.dev=true}, la propriété est ignorée (R-2270).
     *
     * @param properties propriétés à consulter
     */
    static boolean assetCacheDisabledIn(Properties properties) {
        return isEnabledIn(properties)
                && "false".equalsIgnoreCase(properties.getProperty(ASSET_CACHE_PROPERTY));
    }

    /**
     * {@return vrai si ces propriétés activent le mode développeur}
     *
     * <p>Seul {@code true} (sans égard à la casse) l'active, comme pour
     * {@link Boolean#getBoolean} : une valeur absente ou autre le laisse éteint.
     *
     * @param properties propriétés à consulter
     */
    static boolean isEnabledIn(Properties properties) {
        return Boolean.parseBoolean(properties.getProperty(PROPERTY));
    }
}
