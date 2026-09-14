package dev.axion.api;

import org.joml.Matrix4f;
import org.joml.Vector3d;

/**
 * Point d'ancrage nommé d'une assembly (PARTIE 9.3).
 *
 * <p>La position d'un socket tient compte de la déformation : elle applique le
 * champ de la région englobante au point d'ancrage (R-941). Un socket dont la
 * part porteuse est détruite ou détachée devient invalide (R-940).
 */
@Stable
public interface Socket {

    /**
     * {@return le nom du socket, tel que l'auteur l'a déclaré}
     *
     * <p>Effet : lecture. Thread : quelconque. Coût : négligeable. Échec : aucun.
     */
    String name();

    /**
     * {@return la transformation monde du socket}
     *
     * <p>Interpolée côté client, autoritative côté serveur. Effet : lecture.
     * Thread : autoritatif (serveur) ou rendu (client). Coût : négligeable.
     * Échec : aucun ; une matrice identité si le socket est invalide.
     */
    Matrix4f worldTransform();

    /**
     * {@return la position monde du socket, déformation comprise}
     *
     * <p>Effet : lecture. Thread : autoritatif ou rendu. Coût : négligeable.
     * Échec : aucun.
     */
    Vector3d worldPosition();

    /**
     * {@return vrai tant que la part porteuse existe et n'est pas détachée}
     *
     * <p>Effet : lecture. Thread : quelconque. Coût : négligeable. Échec : aucun.
     */
    boolean isValid();

    /**
     * {@return l'écart, en mètres, dû à la déformation locale}
     *
     * <p>Au-delà d'un seuil déclaré, une definition peut décider qu'un mécanisme
     * se grippe ou qu'une attache se rompt (R-941). Effet : lecture. Thread :
     * autoritatif ou rendu. Coût : négligeable. Échec : aucun.
     */
    float misalignment();
}
