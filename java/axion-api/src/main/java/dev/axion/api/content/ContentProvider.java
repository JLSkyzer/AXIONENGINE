package dev.axion.api.content;

import dev.axion.api.Experimental;

/**
 * Le contenu d'un mod, tel qu'il le déclare à AXION (ADR-162 D2).
 *
 * <p>Un mod l'envoie à AXION par les communications entre mods de Forge, pendant
 * {@code InterModEnqueueEvent} :
 *
 * <pre>{@code
 * InterModComms.sendTo(AxionContent.MOD_ID, AxionContent.REGISTER,
 *         () -> (ContentProvider) registrar -> registrar.registerFolder("axion/"));
 * }</pre>
 *
 * <p>AXION appelle chaque fournisseur une fois par phase d'enregistrement, l'un après l'autre, sur le thread
 * principal, dans l'ordre des modId. Un fournisseur ne garde pas le {@link ContentRegistrar} qu'il reçoit :
 * il n'est valable que pendant l'appel.
 *
 * <p>En développement, {@code /axion dev reload} rappelle le même fournisseur, sur le thread du serveur, avec un
 * registraire neuf (ADR-162 D9) : ce qu'il enregistre remplace tout ce qu'il avait enregistré, et un dossier lu
 * depuis les sources du mod l'est de nouveau depuis le disque. Un fournisseur ne fait donc qu'enregistrer, sans autre
 * effet, pour pouvoir être rappelé.
 */
@Experimental
@FunctionalInterface
public interface ContentProvider {

    /**
     * Déclare le contenu du mod.
     *
     * @param registrar le moyen d'enregistrer, valable pendant cet appel seulement
     */
    void register(ContentRegistrar registrar);
}
