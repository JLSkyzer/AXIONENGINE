package dev.axion.damage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.axion.api.ImpactFlag;
import dev.axion.api.ImpactSource;
import dev.axion.api.ImpactSpec;
import java.util.Set;
import java.util.function.UnaryOperator;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** R-1762 — un mod tiers ne peut pas injecter d'impact invalide ni d'énergie aberrante (C-70). */
class BoundedImpactSpecsTest {

    private static final ImpactSource API = new ImpactSource() {};

    @AfterEach
    void plafondDuRegistre() {
        BoundedImpactSpecs.bind(BoundedImpactSpecs.registryDefault());
    }

    /** Un impact complet et valide, que chaque cas altère d'un point. */
    private static ImpactSpec.Builder valide() {
        return ImpactSpec.builder()
                .point(new Vec3(1, 2, 3))
                .normal(new Vec3(0, 2, 0))
                .energy(500f)
                .area(0.01f)
                .effectiveMass(1200f)
                .flags(ImpactFlag.BLUNT)
                .source(API);
    }

    @Test
    @DisplayName("R-1762 : ImpactSpec.builder() se résout par ServiceLoader vers la fabrique du mod")
    void leBuilderPublicSeResout() {
        ImpactSpec spec = valide().build();
        BoundedImpactSpecs.Bounded impact = assertInstanceOf(BoundedImpactSpecs.Bounded.class, spec);
        assertEquals(500f, impact.energy());
        assertEquals(Set.of(ImpactFlag.BLUNT), impact.flags());
        // Borné : la normale est rendue unitaire.
        assertEquals(new Vec3(0, 1, 0), impact.normal());
    }

    @Test
    @DisplayName("R-1762, R-673 : une énergie au-delà du plafond est ramenée au plafond")
    void lEnergieEstPlafonnee() {
        assertEquals(5_000_000.0, BoundedImpactSpecs.registryDefault(), "défaut du registre");
        BoundedImpactSpecs.Bounded impact = (BoundedImpactSpecs.Bounded) valide().energy(1e12f).build();
        assertEquals(5_000_000f, impact.energy());

        // Le plafond est celui de la configuration chargée, pas une constante.
        BoundedImpactSpecs.bind(2_000.0);
        assertEquals(2_000f, ((BoundedImpactSpecs.Bounded) valide().energy(9_999f).build()).energy());
    }

    @Test
    @DisplayName("R-1762 : une valeur absente, non finie ou hors de son domaine est refusée")
    void lInvalideEstRefuse() {
        java.util.Map<String, UnaryOperator<ImpactSpec.Builder>> cas = new java.util.LinkedHashMap<>();
        cas.put("point absent", b -> b.point(null));
        cas.put("point non fini", b -> b.point(new Vec3(Double.NaN, 0, 0)));
        cas.put("normale absente", b -> b.normal(null));
        cas.put("normale nulle", b -> b.normal(Vec3.ZERO));
        cas.put("normale infinie", b -> b.normal(new Vec3(0, Double.POSITIVE_INFINITY, 0)));
        cas.put("énergie NaN", b -> b.energy(Float.NaN));
        cas.put("énergie infinie", b -> b.energy(Float.POSITIVE_INFINITY));
        cas.put("énergie négative", b -> b.energy(-1f));
        cas.put("aire nulle", b -> b.area(0f));
        cas.put("aire NaN", b -> b.area(Float.NaN));
        cas.put("masse négative", b -> b.effectiveMass(-5f));
        cas.put("nature nulle", b -> b.flags((ImpactFlag) null));
        cas.put("natures absentes", b -> b.flags((ImpactFlag[]) null));
        cas.put("origine absente", b -> b.source(null));
        for (var entry : cas.entrySet()) {
            IllegalArgumentException refus = assertThrows(
                    IllegalArgumentException.class,
                    () -> entry.getValue().apply(valide()).build(),
                    entry.getKey());
            assertTrue(refus.getMessage().contains("R-1762"), refus.getMessage());
        }
        // Une énergie nulle n'est pas invalide : un frôlement est un impact.
        assertEquals(0f, ((BoundedImpactSpecs.Bounded) valide().energy(0f).build()).energy());
    }

    @Test
    @DisplayName("R-1762 : un champ jamais donné est refusé, pas complété par un défaut")
    void unChampOublieEstRefuse() {
        ImpactSpec.Builder incomplet = ImpactSpec.builder()
                .point(Vec3.ZERO)
                .normal(new Vec3(1, 0, 0))
                .area(1f)
                .effectiveMass(1f)
                .source(API);
        assertThrows(IllegalArgumentException.class, incomplet::build, "énergie jamais donnée");
    }
}
