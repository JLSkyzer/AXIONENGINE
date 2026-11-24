package dev.axion.forge;

/**
 * La pose de l'occupant qu'on dessine (ADR-177 D1), pour {@link AxionEntity#shouldRiderSit()}.
 *
 * <p>Minecraft demande à la monture si son passager s'assoit, une fois par passager dessiné, sans lui dire lequel :
 * la réponse est donc posée par le rendu, juste avant de dessiner un occupant que son siège met debout, et retirée
 * juste après. Le dessin est mono-fil (le fil de rendu) ; aucune autre lecture n'en dépend.
 */
public final class OccupantStance {

    private static boolean standing;

    private OccupantStance() {}

    /** {@return vrai pendant le dessin d'un occupant debout} */
    public static boolean standing() {
        return standing;
    }

    /**
     * Pose la réponse, au fil de rendu.
     *
     * @param value vrai pour dessiner l'occupant debout
     */
    public static void standing(boolean value) {
        standing = value;
    }
}
