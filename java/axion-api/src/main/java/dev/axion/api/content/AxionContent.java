package dev.axion.api.content;

import dev.axion.api.Experimental;

/**
 * Les noms qu'un mod emploie pour envoyer son contenu à AXION par les communications entre mods de Forge
 * (ADR-162 D2).
 */
@Experimental
public final class AxionContent {

    /** Le modId d'AXION, destinataire des messages. */
    public static final String MOD_ID = "axion";

    /** La méthode du message qui porte un {@link ContentProvider}. */
    public static final String REGISTER = "register_content";

    private AxionContent() {
        throw new AssertionError("classe de constantes, non instanciable");
    }
}
