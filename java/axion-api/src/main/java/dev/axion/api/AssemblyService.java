package dev.axion.api;

import java.util.Collection;
import java.util.Optional;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/**
 * Création et recherche d'assemblies (§23.2).
 */
@Stable
public interface AssemblyService {

    /**
     * Crée une assembly depuis une definition et la fait apparaître.
     *
     * <p>Effet : instancie une {@code AxionEntity} et son assembly. Thread :
     * autoritatif (serveur) uniquement ; lève {@link IllegalStateException}
     * ailleurs (R-1755). Coût : une compilation d'asset si l'asset n'est pas en
     * cache, sinon une allocation. Échec : lève {@link IllegalArgumentException}
     * si la definition est inconnue.
     *
     * @param level monde d'apparition
     * @param def definition à instancier
     * @param pos position monde
     * @param rot orientation
     * @return l'assembly créée
     */
    Assembly spawn(Level level, DefinitionRef def, Vec3 pos, Quaternionf rot);

    /**
     * {@return l'assembly portée par une entité, si c'en est une}
     *
     * <p>Effet : lecture. Thread : quelconque. Coût : négligeable. Échec : vide
     * si l'entité n'est pas une {@code AxionEntity}.
     *
     * @param e entité à interroger
     */
    Optional<Assembly> byEntity(Entity e);

    /**
     * {@return les assemblies d'un monde dans un rayon donné}
     *
     * <p>Effet : lecture. Thread : autoritatif. Coût : proportionnel au nombre
     * d'entités du secteur. Échec : collection vide si aucune.
     *
     * @param level monde interrogé
     * @param center centre de la recherche
     * @param radius rayon, en blocs
     */
    Collection<Assembly> inRange(Level level, Vec3 center, double radius);
}
