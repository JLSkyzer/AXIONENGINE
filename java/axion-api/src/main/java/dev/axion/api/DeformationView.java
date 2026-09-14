package dev.axion.api;

/**
 * Vue de la déformation d'une assembly (§23.2, C-42).
 *
 * <p>Les lectures portent sur un instantané autoritatif. Thread : autoritatif ou
 * rendu, sauf mention contraire. Une part inconnue rend une valeur neutre.
 */
@Stable
public interface DeformationView {

    /**
     * {@return vrai si l'assembly porte une déformation plastique}
     *
     * <p>Une assembly intacte n'alloue aucun champ (INV-16). Effet : lecture.
     * Thread : quelconque. Coût : négligeable. Échec : aucun.
     */
    boolean isDeformed();

    /**
     * {@return le déplacement plastique maximal d'une part, en mètres}
     *
     * <p>Effet : lecture. Thread : autoritatif ou rendu. Coût : négligeable.
     * Échec : {@code 0} si la part est inconnue.
     *
     * @param part nom de la part
     */
    float maxDisplacement(String part);

    /**
     * {@return le déplacement plastique moyen d'une part, en mètres}
     *
     * <p>Effet : lecture. Thread : autoritatif ou rendu. Coût : négligeable.
     * Échec : {@code 0} si la part est inconnue.
     *
     * @param part nom de la part
     */
    float meanDisplacement(String part);

    /**
     * {@return la déformation maximale d'une part}
     *
     * <p>Effet : lecture. Thread : autoritatif ou rendu. Coût : négligeable.
     * Échec : {@code 0} si la part est inconnue.
     *
     * @param part nom de la part
     */
    float maxStrain(String part);

    /**
     * {@return le nombre de régions de déformation de l'assembly}
     *
     * <p>Effet : lecture. Thread : quelconque. Coût : négligeable. Échec : aucun.
     */
    int regionCount();

    /**
     * {@return le niveau de qualité de déformation appliqué}
     *
     * <p>Effet : lecture. Thread : autoritatif ou rendu. Coût : négligeable.
     * Échec : aucun.
     */
    QualityLevel activeLevel();

    /**
     * {@return une copie du champ plastique d'une région}
     *
     * <p>Lecture seule, copiée. Coûteux : réservé au débogage et à l'outillage.
     * Thread : autoritatif ou rendu. Échec : tableau vide si la région est
     * inconnue.
     *
     * @param region nom de la région
     */
    @Experimental
    float[] sampleField(String region);

    /**
     * Restaure progressivement la forme d'une part.
     *
     * <p>Effet : réduit le champ plastique de la part. Thread : autoritatif
     * (serveur) uniquement ; lève {@link IllegalStateException} ailleurs
     * (R-1755). Coût : proportionnel à la taille du champ. Échec : sans effet si
     * la part est inconnue.
     *
     * @param part nom de la part
     * @param amount fraction restaurée, dans {@code [0, 1]}
     */
    void restore(String part, float amount);
}
