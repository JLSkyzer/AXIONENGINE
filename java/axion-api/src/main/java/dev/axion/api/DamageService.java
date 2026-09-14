package dev.axion.api;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.phys.Vec3;

/**
 * Point d'entrée unique du dommage : tout passe par un impact (§23.2).
 *
 * <p>Toutes ces méthodes sont <strong>autoritatives (serveur) uniquement</strong>
 * et lèvent {@link IllegalStateException} hors du thread autoritatif (R-1755).
 */
@Stable
public interface DamageService {

    /**
     * Applique un impact physique complet à une assembly.
     *
     * <p>Effet : injecte l'impact dans le pipeline de dommage et de déformation.
     * Thread : autoritatif uniquement. Coût : celui d'un impact, borné et budgété.
     * Échec : sans effet si l'assembly est invalide.
     *
     * @param target assembly visée
     * @param spec impact, déjà validé et borné par son constructeur
     */
    void applyImpact(Assembly target, ImpactSpec spec);

    /**
     * Convertit un dégât Minecraft en impact selon le mappage déclaratif.
     *
     * <p>Effet : traduit puis applique le dégât (PARTIE 13.4). Thread : autoritatif
     * uniquement. Coût : celui d'un impact. Échec : sans effet si le mappage ne
     * couvre pas la source.
     *
     * @param target assembly visée
     * @param src source de dégât Minecraft
     * @param amount montant vanilla
     * @param point point d'impact monde, ou {@code null} si inconnu
     */
    void applyVanillaDamage(Assembly target, DamageSource src, float amount, Vec3 point);

    /**
     * Répare une assembly, en tout ou en partie.
     *
     * <p>Effet : rétablit selon le niveau demandé. Thread : autoritatif
     * uniquement. Coût : proportionnel à l'étendue réparée. Échec : sans effet si
     * la part nommée est inconnue.
     *
     * @param target assembly visée
     * @param part part à réparer, ou {@code null} pour toute l'assembly
     * @param level profondeur de réparation
     * @param amount fraction réparée, dans {@code [0, 1]}
     */
    void repair(Assembly target, String part, RepairLevel level, float amount);

    /**
     * Force le détachement d'une part.
     *
     * <p>Effet : détache la part, qui devient une assembly de débris. Thread :
     * autoritatif uniquement. Coût : négligeable. Échec : rend {@code false} si la
     * part est inconnue ou déjà détachée.
     *
     * @param target assembly visée
     * @param part part à détacher
     * @return vrai si le détachement a eu lieu
     */
    boolean detach(Assembly target, String part);
}
