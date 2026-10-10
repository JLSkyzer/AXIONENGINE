package dev.axion.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-512 — R-762 : des variantes compilées à la demande, bornées par {@code render.max_shader_variants},
 * avec la variante de base en secours (ADR-127 §4).
 */
class ShaderVariantsTest {

    private static final ShaderVariant CUTOUT = ShaderVariant.of(ShaderVariant.Define.CUTOUT);
    private static final ShaderVariant SKINNED = ShaderVariant.of(ShaderVariant.Define.SKINNED);

    @Test
    @DisplayName("T-512 : la base est prête dès le démarrage, sans compilation à la demande")
    void baseDuDemarrage() {
        ShaderVariants<String> variantes = new ShaderVariants<>("base", 64);
        assertSame("base", variantes.program(ShaderVariant.BASE));
        assertEquals(ShaderVariants.State.READY, variantes.state(ShaderVariant.BASE));
        assertNull(variantes.nextToCompile());
    }

    @Test
    @DisplayName("T-512 : une variante demandée se compile à la demande ; la base dessine en attendant")
    void aLaDemande() {
        ShaderVariants<String> variantes = new ShaderVariants<>("base", 64);
        assertNull(variantes.state(CUTOUT));
        assertSame("base", variantes.program(CUTOUT));
        assertEquals(ShaderVariants.State.PENDING, variantes.state(CUTOUT));
        // Redemandée avant d'être prête : toujours la base, et une seule mise en compilation.
        assertSame("base", variantes.program(CUTOUT));
        assertEquals(CUTOUT, variantes.nextToCompile());
        assertNull(variantes.nextToCompile());

        variantes.ready(CUTOUT, "cutout");
        assertSame("cutout", variantes.program(CUTOUT));
        assertEquals(List.of("base", "cutout"), variantes.programs());
    }

    @Test
    @DisplayName("T-512 : au-delà de la borne, la base compte, une variante n'est jamais compilée")
    void borne() {
        ShaderVariants<String> variantes = new ShaderVariants<>("base", 2);
        variantes.program(CUTOUT);
        assertSame("base", variantes.program(SKINNED));
        assertEquals(ShaderVariants.State.REFUSED, variantes.state(SKINNED));
        assertEquals(CUTOUT, variantes.nextToCompile());
        assertNull(variantes.nextToCompile());
        assertEquals(List.of(SKINNED), variantes.inState(ShaderVariants.State.REFUSED));
    }

    @Test
    @DisplayName("T-512 : sous une borne nulle, la base seule est compilée")
    void borneNulle() {
        ShaderVariants<String> variantes = new ShaderVariants<>("base", 0);
        assertEquals(1, variantes.maxVariants());
        assertSame("base", variantes.program(CUTOUT));
        assertEquals(ShaderVariants.State.REFUSED, variantes.state(CUTOUT));
    }

    @Test
    @DisplayName("R-761 : une variante qui ne compile pas est perdue, la base la remplace")
    void echec() {
        ShaderVariants<String> variantes = new ShaderVariants<>("base", 64);
        variantes.program(CUTOUT);
        variantes.nextToCompile();
        variantes.failed(CUTOUT);
        assertSame("base", variantes.program(CUTOUT));
        assertEquals(List.of(CUTOUT), variantes.inState(ShaderVariants.State.FAILED));
        assertThrows(IllegalStateException.class, () -> variantes.ready(CUTOUT, "tard"));
    }
}
