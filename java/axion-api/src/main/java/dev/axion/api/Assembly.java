package dev.axion.api;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Une assembly runtime : l'objet AXION derrière une {@code AxionEntity} (§23.2).
 *
 * <p>Les lectures renvoient un instantané autoritatif. Les méthodes qui
 * appliquent une force, une impulsion ou une transform sont
 * <strong>mutantes</strong> : elles ne s'appellent que sur le thread autoritatif
 * et lèvent {@link IllegalStateException} ailleurs (R-1755). Une assembly dont
 * {@link #isValid()} est faux ne doit plus être utilisée.
 */
@Stable
public interface Assembly {

    /** {@return l'identifiant stable de l'assembly}. Thread : quelconque. */
    UUID id();

    /** {@return la definition dont l'assembly est issue}. Thread : quelconque. */
    DefinitionRef definition();

    /** {@return l'entité Minecraft qui porte l'assembly}. Thread : quelconque. */
    Entity entity();

    /** {@return la transformation monde courante}. Thread : autoritatif ou rendu. */
    Transform transform();

    /** {@return la vitesse linéaire, en blocs/s}. Thread : autoritatif. */
    Vec3 linearVelocity();

    /** {@return la vitesse angulaire, en rad/s}. Thread : autoritatif. */
    Vec3 angularVelocity();

    /**
     * Applique une force continue pour le pas courant.
     *
     * <p>Thread : autoritatif uniquement (R-1755). Coût : négligeable. Échec :
     * sans effet sur une assembly endormie sous le seuil de réveil.
     *
     * @param f force, en newtons
     * @param worldPoint point d'application monde, ou {@code null} pour le centre
     *     de masse
     */
    void applyForce(Vec3 f, Vec3 worldPoint);

    /**
     * Applique une impulsion.
     *
     * <p>Thread : autoritatif uniquement (R-1755). Coût : négligeable. Échec :
     * aucun.
     *
     * @param j impulsion, en newton-secondes
     * @param worldPoint point d'application monde, ou {@code null} pour le centre
     *     de masse
     */
    void applyImpulse(Vec3 j, Vec3 worldPoint);

    /**
     * Applique un couple pour le pas courant.
     *
     * <p>Thread : autoritatif uniquement (R-1755). Coût : négligeable. Échec :
     * aucun.
     *
     * @param t couple, en newton-mètres
     */
    void applyTorque(Vec3 t);

    /**
     * Impose une transform cinématique, sans vitesse.
     *
     * <p>Thread : autoritatif uniquement (R-1755). Coût : négligeable. Échec :
     * aucun.
     *
     * @param t transformation cible
     */
    void setKinematicTransform(Transform t);

    /**
     * {@return un socket par son nom, s'il existe et est valide}
     *
     * <p>Thread : autoritatif ou rendu. Coût : négligeable. Échec : vide si le
     * nom est inconnu ou le socket invalide.
     *
     * @param name nom du socket
     */
    Optional<Socket> socket(String name);

    /** {@return les noms des sockets}. Thread : quelconque. */
    Collection<String> socketNames();

    /**
     * {@return la vue d'une part par son nom}
     *
     * <p>Thread : autoritatif ou rendu. Coût : négligeable. Échec : lève {@link
     * IllegalArgumentException} si le nom est inconnu.
     *
     * @param name nom de la part
     */
    PartView part(String name);

    /** {@return les noms des parts}. Thread : quelconque. */
    Collection<String> partNames();

    /** {@return la vue du graphe structurel}. Thread : autoritatif ou rendu. */
    StructureView structure();

    /** {@return la vue de la déformation}. Thread : autoritatif ou rendu. */
    DeformationView deformation();

    /** {@return la vue des états de surface}. Thread : autoritatif ou rendu. */
    SurfaceView surface();

    /** {@return le contrôle véhicule, si l'assembly en est un}. Thread : autoritatif. */
    Optional<VehicleControl> vehicle();

    /** {@return le contrôle d'animation}. Thread : autoritatif. */
    AnimationControl animation();

    /** {@return les ensembles de particules}. Thread : autoritatif ou rendu. */
    Collection<ParticleSetView> particleSets();

    /** {@return les attaches vers d'autres assemblies}. Thread : autoritatif. */
    Collection<AttachmentView> attachments();

    /** {@return les données personnalisées d'un mod tiers}. Thread : autoritatif. */
    CustomData customData();

    /** {@return vrai tant que l'assembly existe côté runtime}. Thread : quelconque. */
    boolean isValid();

    /**
     * Retire l'assembly et son entité.
     *
     * <p>Thread : autoritatif uniquement (R-1755). Coût : négligeable. Échec :
     * sans effet si déjà invalide.
     */
    void remove();
}
