package dev.axion.physics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ce qu'une assembly fait aux entités vanilla qu'elle heurte (R-614, ADR-123 §7), calculé
 * depuis les {@code CONTACT_IMPULSE} qui désignent une entité
 * ({@link PhysicsEventCodes.Data#CONTACT_OTHER_ENTITY}).
 *
 * <ul>
 *   <li><b>Poussée</b>, pour une entité non joueuse : {@code Δv = Σ J·n / (m + m_eff)}.
 *       {@code J} est l'impulsion normale du contact et {@code n} sa normale, de l'assembly vers
 *       l'entité (ADR-123 §6) ; {@code m} est la masse prêtée à l'entité — le volume de son AABB
 *       à 1 000 kg/m³, borné à [1, 10 000] kg — et {@code m_eff} la masse effective de
 *       l'assembly au contact (R-615). Le proxy est cinématique, de masse infinie (§10.2) :
 *       {@code J} est l'impulsion qui arrête l'assembly comme contre un mur. Partagée entre deux
 *       masses finies, elle rend à l'entité {@code J / (m + m_eff)} ; {@code J / m}
 *       surestimerait cette vitesse d'un facteur {@code (m + m_eff) / m} — neuf pour une poule
 *       heurtée par une voiture d'une tonne. Les deux se rejoignent quand l'entité est bien plus
 *       lourde que l'assembly.
 *   <li><b>Dégâts</b>, pour une entité vivante : la courbe de chute vanilla, sur la hauteur
 *       équivalente à la vitesse d'approche {@code v} — {@code h = v² / (2 × 9,81)}, dégâts
 *       {@code max(0, h − 3)}. Un choc à 10 m/s pèse une chute de 5 blocs.
 * </ul>
 *
 * <p>Un {@code CONTACT_IMPULSE} part à chaque sous-pas d'un contact au-dessus du seuil
 * (R-1012) : les impulsions d'une même entité s'additionnent sur le tick, et la vitesse
 * d'approche retenue est la plus forte — l'impact, pas le contact qui le prolonge. Un événement
 * aux grandeurs non finies est ignoré : une donnée venue du natif reste une donnée externe.
 *
 * <p>Agnostique de Forge : la couche Forge résout les entités, lit leur AABB et applique.
 */
public final class EntityImpacts {

    /** Masse volumique prêtée aux entités, en kg/m³ : celle de l'eau. */
    public static final double DENSITY = 1000.0;

    /** Masse prêtée minimale, en kg. */
    public static final double MIN_MASS = 1.0;

    /** Masse prêtée maximale, en kg. */
    public static final double MAX_MASS = 10_000.0;

    /** Gravité de la hauteur de chute équivalente, en m/s² (ADR-123 §7). */
    public static final double GRAVITY = 9.81;

    /** Hauteur de chute sans dégâts, en blocs : celle de vanilla. */
    public static final double SAFE_FALL = 3.0;

    private EntityImpacts() {
        throw new AssertionError("classe utilitaire, non instanciable");
    }

    /**
     * {@return vrai pour l'impulsion d'un contact entre une assembly et le proxy d'une entité
     * vanilla : genre {@code CONTACT_IMPULSE}, bit {@code CONTACT_OTHER_ENTITY}, et l'autre
     * corps sans génération d'assembly (ADR-123 §6)}
     *
     * @param event événement collecté
     */
    public static boolean concernsEntity(PhysicsEvent event) {
        return event.kind() == PhysicsEventCodes.Kind.CONTACT_IMPULSE
                && (event.data() & PhysicsEventCodes.Data.CONTACT_OTHER_ENTITY) != 0
                && event.assemblyBGeneration() == 0;
    }

    /**
     * {@return la masse prêtée à une entité, en kg : le volume de son AABB à
     * {@value #DENSITY} kg/m³, borné à [{@value #MIN_MASS}, {@value #MAX_MASS}]}
     *
     * @param volume volume de l'AABB, en m³ (un volume nul, négatif ou non fini donne la borne
     *     basse)
     */
    public static double massOf(double volume) {
        if (!(volume > 0.0)) {
            return MIN_MASS;
        }
        return Math.min(MAX_MASS, Math.max(MIN_MASS, volume * DENSITY));
    }

    /**
     * {@return les dégâts d'un choc, selon la courbe de chute vanilla : {@code max(0, h − 3)}
     * pour la hauteur {@code h = v² / (2 × 9,81)}}
     *
     * @param approachSpeed vitesse d'approche, en m/s (négative ou non finie : aucun dégât)
     */
    public static double fallDamage(double approachSpeed) {
        if (!(approachSpeed > 0.0) || Double.isInfinite(approachSpeed)) {
            return 0.0;
        }
        double height = approachSpeed * approachSpeed / (2.0 * GRAVITY);
        return Math.max(0.0, height - SAFE_FALL);
    }

    /**
     * Regroupe par entité les contacts d'un tick qui en désignent une.
     *
     * @param events événements du tick, dans l'ordre du natif
     * @return un impact par entité touchée, dans l'ordre de leur premier contact (R-1020)
     */
    public static List<Impact> gather(List<PhysicsEvent> events) {
        Map<Integer, Impact> impacts = new LinkedHashMap<>();
        for (PhysicsEvent event : events) {
            if (!concernsEntity(event) || !isFinite(event)) {
                continue;
            }
            impacts.computeIfAbsent(
                            event.assemblyBIndex(),
                            entity -> new Impact(
                                    entity, event.assemblyAIndex(), event.assemblyAGeneration()))
                    .add(event);
        }
        return List.copyOf(impacts.values());
    }

    private static boolean isFinite(PhysicsEvent event) {
        float[] normal = event.normal();
        return Float.isFinite(event.impulse())
                && Float.isFinite(event.relativeVelocity())
                && Float.isFinite(event.effectiveMass())
                && Float.isFinite(normal[0])
                && Float.isFinite(normal[1])
                && Float.isFinite(normal[2]);
    }

    /** Ce qu'une entité a reçu des assemblies pendant un tick. */
    public static final class Impact {

        private final int entity;
        private final int assemblyIndex;
        private final int assemblyGeneration;
        /** Par contact : {@code J·n} en N·s, puis la masse effective de l'assembly en kg. */
        private final List<double[]> pushes = new ArrayList<>();
        private double approachSpeed;

        private Impact(int entity, int assemblyIndex, int assemblyGeneration) {
            this.entity = entity;
            this.assemblyIndex = assemblyIndex;
            this.assemblyGeneration = assemblyGeneration;
        }

        private void add(PhysicsEvent event) {
            double impulse = Math.max(0.0, event.impulse());
            float[] normal = event.normal();
            pushes.add(new double[] {
                impulse * normal[0],
                impulse * normal[1],
                impulse * normal[2],
                Math.max(0.0, event.effectiveMass())
            });
            approachSpeed = Math.max(approachSpeed, event.relativeVelocity());
        }

        /** {@return l'identifiant réseau de l'entité touchée} */
        public int entity() {
            return entity;
        }

        /** {@return l'index du handle de la première assembly qui l'a touchée} */
        public int assemblyIndex() {
            return assemblyIndex;
        }

        /** {@return la génération du handle de cette assembly} */
        public int assemblyGeneration() {
            return assemblyGeneration;
        }

        /** {@return la plus forte vitesse d'approche du tick, en m/s (nulle ou positive)} */
        public double approachSpeed() {
            return approachSpeed;
        }

        /**
         * {@return la variation de vitesse de l'entité, en m/s : {@code Σ J·n / (m + m_eff)}}
         *
         * @param mass masse prêtée à l'entité, en kg (voir {@link EntityImpacts#massOf}) ;
         *     ramenée à {@value EntityImpacts#MIN_MASS} au moins
         */
        public double[] velocityChange(double mass) {
            double entityMass = Math.max(MIN_MASS, mass);
            double[] change = new double[3];
            for (double[] push : pushes) {
                double shared = entityMass + push[3];
                change[0] += push[0] / shared;
                change[1] += push[1] / shared;
                change[2] += push[2] / shared;
            }
            return change;
        }
    }
}
