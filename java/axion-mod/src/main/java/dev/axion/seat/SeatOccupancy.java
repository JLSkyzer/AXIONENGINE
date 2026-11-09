package dev.axion.seat;

import java.util.Arrays;

/**
 * Qui occupe quel siège d'une assembly (ADR-158 D3) : un occupant par siège au plus, un siège par
 * occupant au plus. Les occupants sont désignés par l'identifiant d'entité de Minecraft, que le
 * serveur et ses clients partagent : la table voyage telle quelle vers le client.
 *
 * <p>Logique pure, sans Forge. Non synchronisée : l'entité qui la tient ne la touche que sur son fil.
 */
public final class SeatOccupancy {

    /** Un siège libre. */
    public static final int FREE = -1;

    /** Un siège perdu : son node est détaché, ou sa part détruite (R-712). Plus personne ne s'y assoit. */
    public static final int LOST = -2;

    private final int[] occupants;

    /**
     * @param seats nombre de sièges de la definition
     */
    public SeatOccupancy(int seats) {
        occupants = new int[Math.max(0, seats)];
        Arrays.fill(occupants, FREE);
    }

    /**
     * {@return une table relue de sa forme transportée} Les entrées au-delà de {@code seats} sont
     * ignorées, celles qui manquent sont libres, et un occupant présent deux fois ne garde que son
     * premier siège : une table reçue ne peut pas rompre la règle d'un siège par occupant. Plusieurs
     * sièges peuvent être perdus.
     *
     * @param seats nombre de sièges de la definition
     * @param occupants l'occupant de chaque siège, {@link #FREE} sans
     */
    public static SeatOccupancy of(int seats, int[] occupants) {
        SeatOccupancy table = new SeatOccupancy(seats);
        for (int seat = 0; seat < table.occupants.length && seat < occupants.length; seat++) {
            int occupant = occupants[seat];
            if (occupant == LOST || (occupant != FREE && table.seatOf(occupant) == FREE)) {
                table.occupants[seat] = occupant;
            }
        }
        return table;
    }

    /** {@return le nombre de sièges} */
    public int seats() {
        return occupants.length;
    }

    /**
     * {@return l'occupant d'un siège, {@link #FREE} s'il est libre ou hors de la table}
     *
     * @param seat rang du siège
     */
    public int occupant(int seat) {
        return seat >= 0 && seat < occupants.length ? occupants[seat] : FREE;
    }

    /**
     * {@return le siège qu'occupe une entité, {@link #FREE} sans}
     *
     * @param entity identifiant d'entité
     */
    public int seatOf(int entity) {
        for (int seat = 0; seat < occupants.length; seat++) {
            if (occupants[seat] == entity) {
                return seat;
            }
        }
        return FREE;
    }

    /** {@return le premier siège libre, {@link #FREE} si tous sont pris} */
    public int firstFree() {
        return seatOf(FREE);
    }

    /**
     * Assoit une entité dans un siège libre ; assise ailleurs dans la même assembly, elle en change.
     *
     * @param entity identifiant d'entité
     * @param seat rang du siège
     * @return vrai si elle y est assise ; faux si le siège est hors de la table ou pris par un autre
     */
    public boolean sit(int entity, int seat) {
        if (entity == FREE || entity == LOST || seat < 0 || seat >= occupants.length) {
            return false;
        }
        if (occupants[seat] == entity) {
            return true;
        }
        if (occupants[seat] != FREE) {
            return false;
        }
        release(entity);
        occupants[seat] = entity;
        return true;
    }

    /**
     * Libère le siège d'une entité.
     *
     * @param entity identifiant d'entité
     * @return le siège qu'elle occupait, {@link #FREE} sans
     */
    public int release(int entity) {
        int seat = seatOf(entity);
        if (seat != FREE) {
            occupants[seat] = FREE;
        }
        return seat;
    }

    /**
     * Perd un siège : il ne reçoit plus personne. Son occupant, s'il en a un, est à faire descendre avant.
     *
     * @param seat rang du siège
     */
    public void lose(int seat) {
        if (seat >= 0 && seat < occupants.length) {
            occupants[seat] = LOST;
        }
    }

    /** {@return l'occupant de chaque siège, {@link #FREE} sans : la forme transportée, copie} */
    public int[] toArray() {
        return occupants.clone();
    }
}
