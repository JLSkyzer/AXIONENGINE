package dev.axion.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marque un élément réservé à l'implémentation (R-1752).
 *
 * <p>Cette annotation existe pour que l'implémentation puisse signaler, ailleurs
 * que dans ce module, ce qui n'appartient pas au contrat public. <strong>Aucun
 * élément d'{@code axion-api} n'en est annoté</strong> (R-1752) : tout ce que ce
 * module expose est du contrat public.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
public @interface Internal {}
