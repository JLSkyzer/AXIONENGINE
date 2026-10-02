package dev.axion.forge;

import dev.axion.lifecycle.AxionRuntime;

/**
 * Le runtime, pour les classes que Forge abonne statiquement.
 *
 * <p>La passe de rendu client est un abonné statique, réservé à {@code Dist.CLIENT} : le point
 * d'entrée commun ne peut pas lui tendre le runtime sans référencer une classe de rendu, ce qui
 * ferait échouer le chargement d'un serveur dédié. Le point d'entrée le dépose donc ici, à sa
 * construction — comme il dépose les definitions pour l'entité ({@link AxionEntities}).
 */
public final class RuntimeAccess {

    private static volatile AxionRuntime runtime;

    private RuntimeAccess() {}

    /** Dépose le runtime ; appelé par le point d'entrée à sa construction. */
    static void install(AxionRuntime value) {
        runtime = value;
    }

    /** {@return le runtime, ou {@code null} avant la construction du mod} */
    public static AxionRuntime get() {
        return runtime;
    }
}
