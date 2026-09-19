package dev.axion.api;

/**
 * Événement : le retrait d'une assembly (§23.2).
 *
 * <p>Émis sur le thread autoritatif en phase {@code TICK_COLLECT} (R-1760).
 * En 1.0 il porte son type stable et son abonnement ; ses accesseurs sont
 * ajoutés de façon compatible (R-1751) par le composant qui produit
 * l'événement, quand celui-ci existe.
 */
@Stable
public interface AssemblyRemoveEvent extends AxionEvent {}
