package dev.axion.damage;

import dev.axion.api.ImpactFlag;
import dev.axion.api.ImpactSource;
import dev.axion.api.ImpactSpec;
import dev.axion.api.ImpactSpecFactory;
import dev.axion.config.AxionConfig;
import dev.axion.config.ConfigSchema.Scope;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import net.minecraft.world.phys.Vec3;

/**
 * Fabrique des {@link ImpactSpec} de l'API publique (C-70, R-1762).
 *
 * <p>Un mod tiers décrit un impact par {@link ImpactSpec#builder()}, que
 * {@code axion-api} résout par {@link java.util.ServiceLoader} vers cette classe
 * ({@code META-INF/services}). {@link ImpactSpec.Builder#build()} <strong>valide
 * et borne</strong> toutes les valeurs : un mod tiers ne peut pas injecter une
 * énergie aberrante (R-1762).
 *
 * <ul>
 *   <li><strong>Refusé</strong>, par {@link IllegalArgumentException} : une valeur
 *       absente, non finie ou négative, une normale nulle, une aire ou une masse
 *       qui n'est pas strictement positive, une nature d'impact nulle ;
 *   <li><strong>Borné</strong> : l'énergie au-delà de {@code damage.max_impact_energy}
 *       est ramenée au plafond (R-673), et la normale est rendue unitaire.
 * </ul>
 *
 * <p>Le plafond est celui du registre de configuration tant que le runtime n'a
 * pas lu la sienne, puis celui que {@link #bind(AxionConfig)} lui donne.
 */
public final class BoundedImpactSpecs implements ImpactSpecFactory {

    /** Option qui plafonne l'énergie d'un impact (R-673). */
    static final String MAX_ENERGY = "damage.max_impact_energy";

    private static volatile double maxEnergy = registryDefault();

    /** Constructeur public, exigé par {@link java.util.ServiceLoader}. */
    public BoundedImpactSpecs() {
        // Sans état : le plafond est partagé, la configuration étant celle du runtime.
    }

    /**
     * Retient le plafond d'énergie de la configuration chargée.
     *
     * @param config configuration du runtime
     */
    public static void bind(AxionConfig config) {
        bind(config.getFloat(MAX_ENERGY));
    }

    static void bind(double joules) {
        maxEnergy = joules;
    }

    /** {@return le plafond d'énergie en vigueur, en joules} */
    static double maxEnergy() {
        return maxEnergy;
    }

    /** {@return le plafond que le registre déclare par défaut} */
    static double registryDefault() {
        return ((Number) AxionConfig.option(Scope.COMMON, MAX_ENERGY).defaultValue()).doubleValue();
    }

    @Override
    public ImpactSpec.Builder newBuilder() {
        return new Builder(maxEnergy);
    }

    /**
     * Un impact validé et borné.
     *
     * @param point point d'impact monde
     * @param normal normale de contact monde, unitaire
     * @param energy énergie, en joules, au plus le plafond
     * @param area aire de contact, en mètres carrés
     * @param effectiveMass masse effective, en kilogrammes
     * @param flags nature de l'impact
     * @param source origine de l'impact
     */
    public record Bounded(
            Vec3 point,
            Vec3 normal,
            float energy,
            float area,
            float effectiveMass,
            Set<ImpactFlag> flags,
            ImpactSource source) implements ImpactSpec {}

    private static final class Builder implements ImpactSpec.Builder {

        private final double cap;
        private Vec3 point;
        private Vec3 normal;
        private Float energy;
        private Float area;
        private Float effectiveMass;
        private final EnumSet<ImpactFlag> flags = EnumSet.noneOf(ImpactFlag.class);
        private boolean flagsInvalid;
        private ImpactSource source;

        Builder(double cap) {
            this.cap = cap;
        }

        @Override
        public ImpactSpec.Builder point(Vec3 worldPoint) {
            this.point = worldPoint;
            return this;
        }

        @Override
        public ImpactSpec.Builder normal(Vec3 worldNormal) {
            this.normal = worldNormal;
            return this;
        }

        @Override
        public ImpactSpec.Builder energy(float joules) {
            this.energy = joules;
            return this;
        }

        @Override
        public ImpactSpec.Builder area(float squareMeters) {
            this.area = squareMeters;
            return this;
        }

        @Override
        public ImpactSpec.Builder effectiveMass(float kg) {
            this.effectiveMass = kg;
            return this;
        }

        @Override
        public ImpactSpec.Builder flags(ImpactFlag... values) {
            if (values == null) {
                flagsInvalid = true;
                return this;
            }
            for (ImpactFlag flag : values) {
                if (flag == null) {
                    flagsInvalid = true;
                } else {
                    flags.add(flag);
                }
            }
            return this;
        }

        @Override
        public ImpactSpec.Builder source(ImpactSource src) {
            this.source = src;
            return this;
        }

        @Override
        public ImpactSpec build() {
            require(point != null, "point absent");
            require(finite(point), "point non fini : " + point);
            require(normal != null, "normale absente");
            require(finite(normal), "normale non finie : " + normal);
            double length = normal.length();
            require(length > 0.0, "normale nulle");
            require(energy != null, "énergie absente");
            require(Float.isFinite(energy) && energy >= 0f, "énergie invalide : " + energy + " J");
            require(area != null, "aire absente");
            require(Float.isFinite(area) && area > 0f, "aire invalide : " + area + " m²");
            require(effectiveMass != null, "masse effective absente");
            require(Float.isFinite(effectiveMass) && effectiveMass > 0f,
                    "masse effective invalide : " + effectiveMass + " kg");
            require(!flagsInvalid, "nature d'impact nulle");
            require(source != null, "origine absente");

            // R-673 : borner plutôt que refuser une énergie énorme mais finie — le
            // plafond est là pour qu'aucune valeur n'explose le calcul, pas pour
            // punir un mod qui décrit un choc violent.
            float bounded = (float) Math.min(energy, cap);
            Vec3 unit = new Vec3(normal.x / length, normal.y / length, normal.z / length);
            return new Bounded(point, unit, bounded, area, effectiveMass,
                    Collections.unmodifiableSet(EnumSet.copyOf(flags)), source);
        }

        private static boolean finite(Vec3 vector) {
            return Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
        }

        private static void require(boolean condition, String message) {
            if (!condition) {
                throw new IllegalArgumentException("ImpactSpec refusé — " + message + " (R-1762)");
            }
        }
    }
}
