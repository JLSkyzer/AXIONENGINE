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

    private static final boolean ENABLED = isEnabledIn(System.getProperties());

    private DevMode() {}

    /** {@return vrai si le mode développeur est actif dans ce processus} */
    public static boolean enabled() {
        return ENABLED;
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
