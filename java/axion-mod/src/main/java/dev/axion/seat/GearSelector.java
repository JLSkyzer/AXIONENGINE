package dev.axion.seat;

/**
 * Le rapport que le clavier d'un client demande (ADR-159 D6) : deux touches le montent et le descendent, dans les
 * bornes de la boîte. Un autre véhicule repart du point mort. Logique pure, sans Forge.
 */
public final class GearSelector {

    /** L'identifiant réseau du véhicule dont le rapport est tenu, -1 sans. */
    private int vehicle = -1;

    private int gear;

    /**
     * {@return le rang demandé après ces appuis}
     *
     * @param vehicle identifiant réseau du véhicule où le joueur est assis
     * @param up appuis sur « rapport supérieur » depuis l'appel précédent
     * @param down appuis sur « rapport inférieur » depuis l'appel précédent
     * @param range les rangs que sa boîte accepte
     */
    public byte select(int vehicle, int up, int down, GearRange range) {
        if (vehicle != this.vehicle) {
            this.vehicle = vehicle;
            gear = 0;
        }
        gear = range.clamp(gear + up - down);
        return (byte) gear;
    }

    /** Oublie le véhicule : le joueur en est descendu. */
    public void reset() {
        vehicle = -1;
        gear = 0;
    }
}
