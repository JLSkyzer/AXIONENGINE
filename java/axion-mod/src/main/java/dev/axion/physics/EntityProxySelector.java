package dev.axion.physics;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Qui la physique doit voir parmi les entités vanilla d'une dimension, pour un tick (R-614,
 * ADR-123 §5 et §7) — la règle seule, sans Minecraft : la couche Forge relève les assemblies et
 * les entités candidates, cette classe choisit les proxies.
 *
 * <p>Le rayon d'influence d'une assembly est son emprise gonflée de
 * {@code max(1 bloc, (|v_assembly| + |v_entité|) × 2 ticks)} : là où un contact peut survenir au
 * tick suivant. Il se déduit des vitesses, sans réglage.
 */
public final class EntityProxySelector {

    /** Marge minimale du rayon d'influence, en blocs. */
    public static final double MIN_MARGIN = 1.0;

    /** Horizon du rayon d'influence, en secondes : deux ticks de 1/20 s. */
    public static final double HORIZON_SECONDS = 2.0 / 20.0;

    private EntityProxySelector() {}

    /**
     * Une boîte alignée sur les axes, en coordonnées monde.
     *
     * @param minX borne inférieure en x
     * @param minY borne inférieure en y
     * @param minZ borne inférieure en z
     * @param maxX borne supérieure en x
     * @param maxY borne supérieure en y
     * @param maxZ borne supérieure en z
     */
    public record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {

        /**
         * {@return la boîte gonflée de {@code margin} sur chaque face}
         *
         * @param margin marge, en blocs
         */
        public Box inflate(double margin) {
            return new Box(minX - margin, minY - margin, minZ - margin, maxX + margin, maxY + margin, maxZ + margin);
        }

        /**
         * {@return vrai si les deux boîtes se recouvrent ou se touchent}
         *
         * @param other l'autre boîte
         */
        public boolean intersects(Box other) {
            return minX <= other.maxX && other.minX <= maxX
                    && minY <= other.maxY && other.minY <= maxY
                    && minZ <= other.maxZ && other.minZ <= maxZ;
        }
    }

    /**
     * Une assembly dotée d'un corps.
     *
     * @param bounds son emprise (R-702)
     * @param speed norme de sa vitesse, en m/s
     */
    public record Assembly(Box bounds, double speed) {}

    /**
     * Une entité candidate, telle que la couche Forge l'a relevée : déjà éligible (solide ou
     * poussable, vivante, ni spectatrice, ni assembly, ni passagère d'une assembly).
     *
     * @param entity identifiant réseau de l'entité
     * @param bounds son AABB
     * @param velocity sa vitesse {@code [x, y, z]}, en m/s
     * @param living vrai pour un être vivant : capsule plutôt que boîte
     */
    public record Candidate(int entity, Box bounds, double[] velocity, boolean living) {}

    /**
     * {@return la marge d'influence entre une assembly et une entité, en blocs}
     *
     * @param assemblySpeed norme de la vitesse de l'assembly, en m/s
     * @param entitySpeed norme de la vitesse de l'entité, en m/s
     */
    public static double margin(double assemblySpeed, double entitySpeed) {
        return Math.max(MIN_MARGIN, (assemblySpeed + entitySpeed) * HORIZON_SECONDS);
    }

    /**
     * Les proxies du tick : chaque candidate dans le rayon d'influence d'au moins une assembly,
     * une seule fois, dans l'ordre des candidates.
     *
     * @param assemblies assemblies de la dimension
     * @param candidates entités éligibles relevées autour d'elles
     * @return les proxies à déclarer, sans doublon
     */
    public static List<SimCommandStream.EntityProxy> select(List<Assembly> assemblies, List<Candidate> candidates) {
        List<SimCommandStream.EntityProxy> proxies = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (Candidate candidate : candidates) {
            double speed = norm(candidate.velocity());
            for (Assembly assembly : assemblies) {
                if (assembly.bounds().inflate(margin(assembly.speed(), speed)).intersects(candidate.bounds())) {
                    if (seen.add(candidate.entity())) {
                        proxies.add(proxyOf(candidate));
                    }
                    break;
                }
            }
        }
        return proxies;
    }

    /** {@return le proxy d'une candidate : centre et demi-dimensions de son AABB, sa vitesse} */
    private static SimCommandStream.EntityProxy proxyOf(Candidate candidate) {
        Box box = candidate.bounds();
        double[] center = {
            (box.minX() + box.maxX()) * 0.5, (box.minY() + box.maxY()) * 0.5, (box.minZ() + box.maxZ()) * 0.5
        };
        float[] halfExtents = {
            (float) ((box.maxX() - box.minX()) * 0.5),
            (float) ((box.maxY() - box.minY()) * 0.5),
            (float) ((box.maxZ() - box.minZ()) * 0.5)
        };
        double[] v = candidate.velocity();
        float[] velocity = {(float) v[0], (float) v[1], (float) v[2]};
        int shape = candidate.living() ? SimCommandStream.PROXY_CAPSULE : SimCommandStream.PROXY_BOX;
        return new SimCommandStream.EntityProxy(candidate.entity(), center, halfExtents, velocity, shape);
    }

    private static double norm(double[] v) {
        return Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    }
}
