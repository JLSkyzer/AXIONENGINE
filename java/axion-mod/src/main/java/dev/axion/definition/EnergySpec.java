package dev.axion.definition;

import com.google.gson.JsonObject;

/**
 * L'énergie qu'une definition déclare (ADR-176 D3) : les capacités de son réservoir et de sa batterie, et la masse d'un
 * litre. Logique pure ; une capacité nulle veut dire « pas de réservoir » ou « pas de batterie ».
 *
 * @param fuelCapacity capacité du réservoir, en litres
 * @param unitMass masse d'un litre de carburant, en kg
 * @param batteryCapacity capacité de la batterie, en unités de charge
 */
public record EnergySpec(float fuelCapacity, float unitMass, float batteryCapacity) {

    /** Aucune énergie : ce qu'une definition sans {@code energy} déclare. */
    public static final EnergySpec NONE = new EnergySpec(0f, 0f, 0f);

    /** La cause que le moteur pose sur la masse : la masse du carburant que le réservoir porte (ADR-176 D2, D3). */
    public static final String MASS_CAUSE = "axion:energy";

    /**
     * Le nombre de crans de la masse du carburant : elle suit le réservoir par crans de 1/255 de sa capacité, jamais par
     * son {@code f32} brut, si bien que le serveur et le client qui prédit dérivent le même nombre, et que la masse
     * renvoyée au natif — qui réveille — ne change qu'au franchissement d'un cran.
     */
    public static final int MASS_STEPS = 255;

    /**
     * Lit l'énergie d'une definition acceptée.
     *
     * @param root le document
     * @return ce qu'il déclare, {@link #NONE} sans {@code energy}
     */
    public static EnergySpec of(JsonObject root) {
        if (!(root.get("energy") instanceof JsonObject energy)) {
            return NONE;
        }
        JsonObject fuel = energy.get("fuel") instanceof JsonObject f ? f : null;
        JsonObject battery = energy.get("battery") instanceof JsonObject b ? b : null;
        return new EnergySpec(number(fuel, "capacity"), number(fuel, "unit_mass"), number(battery, "capacity"));
    }

    private static float number(JsonObject parent, String key) {
        return parent != null && parent.get(key) != null ? parent.get(key).getAsFloat() : 0f;
    }

    /** {@return vrai si la definition déclare un réservoir ou une batterie} */
    public boolean declared() {
        return fuelCapacity > 0f || batteryCapacity > 0f;
    }

    /**
     * {@return la masse du carburant d'un réservoir qui en porte {@code fuel} litres, en kg, au cran le plus proche}
     *
     * @param fuel le carburant, en litres
     */
    public double fuelMass(double fuel) {
        return fuelCapacity > 0f ? fuelMassOfRatio(fuel / fuelCapacity) : 0.0;
    }

    /**
     * {@return la masse du carburant d'un réservoir rempli à ce rapport de sa capacité, en kg, au cran le plus proche} Le
     * client la tire du rapport en octet que le snapshot porte : il retombe sur le cran du serveur.
     *
     * @param ratio le carburant sur la capacité
     */
    public double fuelMassOfRatio(double ratio) {
        if (!(fuelCapacity > 0f) || !(unitMass > 0f)) {
            return 0.0;
        }
        long step = Math.round(Math.max(0.0, Math.min(1.0, ratio)) * MASS_STEPS);
        return step / (double) MASS_STEPS * fuelCapacity * unitMass;
    }
}
