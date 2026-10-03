package dev.axion.asset;

/**
 * Version du compilateur natif (R-562).
 *
 * <p>Elle entre dans la clé de cache (voir {@link AssetKey}). Sans elle, une
 * correction de C-21, C-22, C-23 ou C-28 qui change la sortie n'atteindrait
 * jamais les assets déjà compilés : leur entrée passerait pour à jour, et le
 * cache rendrait un asset produit par l'ancien compilateur.
 *
 * <p>La valeur <strong>double</strong> celle de
 * {@code ax_asset::compile::COMPILER_VERSION} : c'est le compilateur natif qui
 * fait foi, et la clé calculée ici ne protège le cache que si elle porte la même
 * version que le binaire qui compile. Les deux constantes avancent donc
 * ensemble, et {@code tools/ci/check_compiler_version.py} le vérifie en CI
 * (R-562) — une divergence bloque la CI plutôt que de laisser le cache reprendre
 * silencieusement un asset périmé.
 *
 * <p>Historique, tenu en regard du côté Rust : 2 — C-23 tranche A ; 3 — C-23
 * tranche B ; 4 — C-23 tranche C ; 5 — section {@code NODE} et empreintes de nom
 * (ADR-110) ; 6 — section {@code PHYS} (colliders C-32, ADR-115) ; 7 — sections
 * {@code MATL} (DM-05) et {@code TEXR}, matériaux et textures importés (ADR-122). À
 * incrémenter des deux côtés à toute modification de C-21, C-22, C-23 ou C-28 qui
 * change la sortie.
 */
public final class CompilerVersion {

    /**
     * Version courante, égale à {@code ax_asset::compile::COMPILER_VERSION}.
     *
     * <p>La CI ({@code tools/ci/check_compiler_version.py}) refuse tout écart
     * entre cette valeur et celle du crate {@code ax-asset}.
     */
    public static final int CURRENT = 7;

    private CompilerVersion() {
        throw new AssertionError("classe utilitaire, non instanciable");
    }
}
