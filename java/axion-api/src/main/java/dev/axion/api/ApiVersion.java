package dev.axion.api;

/**
 * Version de l'API publique, en versionnement sémantique (R-1751).
 *
 * <p>Indépendante de celle du mod. En 1.x, une hausse du {@code minor} n'ajoute
 * que du compatible ; le {@code major} ne change qu'en cas de rupture.
 *
 * @param major version majeure ; une rupture de compatibilité l'incrémente
 * @param minor version mineure ; un ajout compatible l'incrémente
 * @param patch correctif sans changement de surface
 */
@Stable
public record ApiVersion(int major, int minor, int patch) {

    /** Version de cette API. */
    public static final ApiVersion CURRENT = new ApiVersion(1, 0, 0);

    /**
     * Indique si cette version satisfait un besoin exprimé pour {@code required}.
     *
     * <p>Effet : comparaison pure, sans état. Thread : quelconque. Coût :
     * négligeable. Échec : aucun.
     *
     * @param required version minimale attendue par l'appelant
     * @return vrai si cette version est compatible, au sens de la série 1.x
     */
    public boolean satisfies(ApiVersion required) {
        return major == required.major
                && (minor > required.minor
                        || (minor == required.minor && patch >= required.patch));
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
