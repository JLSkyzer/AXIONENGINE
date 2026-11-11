package dev.axion.api.assembly;

import dev.axion.api.Experimental;
import dev.axion.api.content.DefinitionRef;

import java.util.UUID;
import net.minecraft.world.entity.Entity;

/**
 * Une assembly runtime : l'objet AXION derrière une {@code AxionEntity} (§23.2).
 *
 * <p>Les lectures renvoient un instantané : celui du serveur sur le thread autoritatif, celui que le client a reçu
 * et interpolé sur le thread de rendu. Les méthodes <strong>mutantes</strong> ne s'appellent que sur le thread
 * autoritatif et lèvent {@link IllegalStateException} ailleurs (R-1755). Une assembly dont {@link #isValid()} est faux
 * ne doit plus être utilisée.
 *
 * <p><strong>Une surface réelle</strong> (ADR-162 D1) : forces et impulsions, vitesses, sockets, pièces, structure,
 * déformation, surface, véhicule, particules et attaches y entrent avec le composant qui les rend, en méthodes
 * {@code default}.
 */
@Experimental
public interface Assembly {

    /** {@return l'identifiant stable de l'assembly, celui de son entité}. Thread : quelconque. */
    UUID id();

    /** {@return la definition dont l'assembly est issue}. Thread : quelconque. */
    DefinitionRef definition();

    /** {@return l'entité Minecraft qui porte l'assembly}. Thread : quelconque. */
    Entity entity();

    /**
     * {@return la transformation monde courante du corps racine}
     *
     * <p>Thread : autoritatif ou rendu. L'échelle vaut 1 : une assembly n'est pas mise à l'échelle au monde.
     */
    Transform transform();

    /** {@return le contrôle des animations}. Thread : autoritatif ou rendu. */
    AnimationControl animation();

    /** {@return les données personnalisées des mods tiers}. Thread : autoritatif. */
    CustomData customData();

    /** {@return les variables de contenu de l'assembly (ADR-163 D2)}. Thread : autoritatif. */
    Variables variables();

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
