package dev.axion.api.assembly;

import dev.axion.api.Experimental;

/**
 * Un siège d'une assembly, tel que sa definition le déclare (ADR-158), avec ce qu'il règle du dessin de son occupant
 * (ADR-177 D1).
 *
 * @param name son nom dans la definition
 * @param pose la pose de son occupant
 * @param scale l'échelle du dessin de son occupant, de 0,25 à 4 ; la boîte de collision de l'occupant ne change pas
 */
@Experimental
public record Seat(String name, Pose pose, double scale) {

    /** Comment l'occupant d'un siège est posé. */
    public enum Pose {
        /** Assis, comme sur une monture. */
        SIT,
        /** Debout, les pieds au point du siège. */
        STAND
    }
}
