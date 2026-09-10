package dev.axion.asset;

/**
 * Version du compilateur natif (R-562).
 *
 * <p>Elle entre dans la cle de cache. Sans elle, une correction de C-21, C-22,
 * C-23 ou C-28 n'atteindrait jamais les assets deja compiles : leur entree
 * passerait pour a jour.
 *
 * <p>La valeur double celle de {@code ax_asset::compile::COMPILER_VERSION}. Les
 * deux doivent avancer ensemble, ce que R-562 confie a la CI — un controle qui
 * reste a ecrire, et qui figure en dette.
 */
public final class CompilerVersion {

    /** Version courante. */
    public static final int CURRENT = 1;

    private CompilerVersion() {
        throw new AssertionError("classe utilitaire, non instanciable");
    }
}
