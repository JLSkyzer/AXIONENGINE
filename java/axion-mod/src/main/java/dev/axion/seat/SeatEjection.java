package dev.axion.seat;

/**
 * Un occupant que son siège a perdu (ADR-158 D5, R-712). L'événement public {@code SeatExitEvent} attend
 * l'{@code EventBus} (ADR-137, M9) ; d'ici là, l'éjection est journalisée et l'entité retient la dernière.
 *
 * @param seat nom du siège, {@code null} si le passager n'en avait pas
 * @param occupant identifiant d'entité de l'occupant
 * @param cause ce qui l'a éjecté
 */
public record SeatEjection(String seat, int occupant, Cause cause) {

    /** Ce qui éjecte un occupant. */
    public enum Cause {
        /** Le node du siège, ou un ancêtre, est détaché de l'assembly. */
        DETACHED,
        /** La part qui porte le siège est détruite. */
        DESTROYED,
        /** L'assembly est supprimée. */
        ASSEMBLY_REMOVED,
        /** Plus aucun siège pour lui : un rechargement a retiré le sien, ou l'assembly est pleine. */
        NO_SEAT
    }
}
