package dev.axion.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marque un élément d'API expérimental (R-1752).
 *
 * <p>Un élément {@code @Experimental} peut changer de signature ou disparaître
 * dans une version mineure : la garantie de compatibilité de la série 1.x ne le
 * couvre pas. Un mod qui s'en sert accepte de suivre ces évolutions.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
public @interface Experimental {}
