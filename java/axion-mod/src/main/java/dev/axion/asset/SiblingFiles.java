package dev.axion.asset;

/**
 * Les fichiers voisins d'une source, que le compilateur demande par leur nom (ADR-139, R-830).
 *
 * <p>Un nom est relatif au répertoire de la source, sous sa forme normale — segments séparés
 * par {@code /}, sans {@code .} ni {@code ..} : celle que le natif rend avec
 * {@code NEEDS_FILES}, et celle que {@code axion-cli} joint au répertoire de la source.
 */
@FunctionalInterface
public interface SiblingFiles {

    /** Aucun fichier voisin : chaque demande part absente. */
    SiblingFiles NONE = name -> null;

    /**
     * {@return les octets du fichier, ou {@code null} s'il n'existe pas}
     *
     * @param name nom normalisé, relatif au répertoire de la source
     */
    byte[] read(String name);
}
