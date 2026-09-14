package dev.axion.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marque un élément d'API stable (R-1752).
 *
 * <p>Un élément {@code @Stable} est couvert par la garantie de compatibilité
 * binaire ascendante de la série 1.x (R-1751) : sa signature ne change pas et il
 * n'est pas retiré tant que la version majeure ne change pas.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
public @interface Stable {}
