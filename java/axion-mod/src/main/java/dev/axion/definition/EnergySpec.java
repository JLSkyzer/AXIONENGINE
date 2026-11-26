package dev.axion.definition;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntToDoubleFunction;

/**
 * L'énergie qu'une definition déclare (ADR-176 D3, ADR-182) : ses réserves, dans l'ordre où le natif les range, celle que
 * le moteur brûle et celle que tire le démarreur. Au schéma 7, le réservoir {@code fuel} puis la batterie
 * {@code battery}, chacun s'il est déclaré ; au schéma 8, les réserves de {@code stores} dans leur ordre, puis la batterie
 * {@code battery} si elle est déclarée. Logique pure.
 *
 * @param stores les réserves, au plus {@link #MAX_STORES} ; leur rang est celui du natif
 * @param fuelStore le rang de la réserve que le moteur brûle, ou {@code -1}
 * @param batteryStore le rang de la réserve du démarreur, ou {@code -1}
 */
public record EnergySpec(List<Store> stores, int fuelStore, int batteryStore) {

    /** Au plus ce nombre de réserves : {@code MAX_STORES} du natif. */
    public static final int MAX_STORES = 4;

    /** Aucune énergie : ce qu'une definition sans {@code energy} déclare. */
    public static final EnergySpec NONE = new EnergySpec(List.of(), -1, -1);

    /** La cause que le moteur pose sur la masse : celle de ce que portent les réserves (ADR-176 D2, D3). */
    public static final String MASS_CAUSE = "axion:energy";

    /**
     * Le nombre de crans de la masse d'une réserve : elle suit son contenu par crans de 1/255 de sa capacité, jamais par
     * son {@code f32} brut, si bien que le serveur et le client qui prédit dérivent le même nombre, et que la masse
     * renvoyée au natif — qui réveille — ne change qu'au franchissement d'un cran.
     */
    public static final int MASS_STEPS = 255;

    /** La batterie du démarreur, {@code energy.battery} : son nom de réserve. */
    public static final String BATTERY = "battery";

    /**
     * Une réserve.
     *
     * @param name son nom dans la definition : {@code fuel} et {@code battery} au schéma 7
     * @param carrier ce qu'elle contient, un identifiant libre ; {@code null} : elle accepte tout (ADR-182 D1)
     * @param capacity sa capacité, dans l'unité de son contenu, positive
     * @param unitMass la masse d'une unité, en kg ; 0 : la masse ne la suit pas
     * @param maxFillRate les unités qu'on peut y verser par seconde, au plus ; 0 : sans limite
     */
    public record Store(String name, String carrier, float capacity, float unitMass, float maxFillRate) {

        /** Une réserve qui accepte tout, sans limite de débit. */
        public Store(String name, float capacity, float unitMass) {
            this(name, null, capacity, unitMass, 0f);
        }

        /**
         * {@return vrai si elle accepte un versement de ce type}
         *
         * @param offered le type versé, ou {@code null} sans type
         */
        public boolean accepts(String offered) {
            return carrier == null || carrier.equals(offered);
        }
    }

    /** Copie les réserves. */
    public EnergySpec {
        stores = List.copyOf(stores);
    }

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
        List<Store> stores = new ArrayList<>();
        int fuel = -1;
        int battery = -1;
        if (energy.get("stores") instanceof JsonArray declared) {
            for (JsonElement element : declared) {
                if (element instanceof JsonObject store && number(store, "capacity") > 0f) {
                    stores.add(new Store(text(store, "name"), text(store, "carrier"), number(store, "capacity"),
                            number(store, "unit_mass"), number(store, "max_fill_rate")));
                }
            }
            fuel = rank(stores, text(object(object(object(root, "powertrain"), "engine"), "fuel"), "store"));
            battery = rank(stores, text(starter(root), "store"));
        } else if (energy.get("fuel") instanceof JsonObject tank && number(tank, "capacity") > 0f) {
            fuel = stores.size();
            stores.add(new Store("fuel", number(tank, "capacity"), number(tank, "unit_mass")));
        }
        if (energy.get("battery") instanceof JsonObject cell && number(cell, "capacity") > 0f) {
            battery = stores.size();
            stores.add(new Store(BATTERY, number(cell, "capacity"), 0f));
        }
        return new EnergySpec(stores, fuel, battery);
    }

    private static JsonObject starter(JsonObject root) {
        return object(object(object(root, "powertrain"), "engine"), "starter");
    }

    private static JsonObject object(JsonObject parent, String key) {
        return parent != null && parent.get(key) instanceof JsonObject child ? child : null;
    }

    private static String text(JsonObject parent, String key) {
        return parent != null && parent.get(key) instanceof JsonPrimitive value && value.isString()
                ? value.getAsString() : null;
    }

    private static int rank(List<Store> stores, String name) {
        for (int slot = 0; slot < stores.size(); slot++) {
            if (stores.get(slot).name().equals(name)) {
                return slot;
            }
        }
        return -1;
    }

    /**
     * {@return le rang de la réserve de ce nom, ou {@code -1}}
     *
     * @param name son nom dans la definition
     */
    public int indexOf(String name) {
        return rank(stores, name);
    }

    private static float number(JsonObject parent, String key) {
        return parent != null && parent.get(key) != null ? parent.get(key).getAsFloat() : 0f;
    }

    /** {@return vrai si la definition déclare au moins une réserve} */
    public boolean declared() {
        return !stores.isEmpty();
    }

    /** {@return la capacité de la réserve au rang {@code slot} ; 0 pour un rang sans réserve} */
    public float capacity(int slot) {
        return slot >= 0 && slot < stores.size() ? stores.get(slot).capacity() : 0f;
    }

    /** {@return la capacité de la réserve que le moteur brûle ; 0 sans} */
    public float fuelCapacity() {
        return capacity(fuelStore);
    }

    /** {@return la capacité de la réserve du démarreur ; 0 sans} */
    public float batteryCapacity() {
        return capacity(batteryStore);
    }

    /** {@return la masse d'une unité de la réserve que le moteur brûle, en kg ; 0 sans} */
    public float unitMass() {
        return fuelStore >= 0 && fuelStore < stores.size() ? stores.get(fuelStore).unitMass() : 0f;
    }

    /** {@return vrai si la masse suit le contenu d'une réserve au moins} */
    public boolean massDeclared() {
        return stores.stream().anyMatch(store -> store.unitMass() > 0f);
    }

    /**
     * {@return la masse que porte la réserve au rang {@code slot} remplie à ce rapport de sa capacité, en kg, au cran le
     * plus proche}
     *
     * @param slot le rang de la réserve
     * @param ratio son contenu sur sa capacité
     */
    public double massOfRatio(int slot, double ratio) {
        if (slot < 0 || slot >= stores.size() || !(stores.get(slot).unitMass() > 0f)) {
            return 0.0;
        }
        Store store = stores.get(slot);
        long step = Math.round(Math.max(0.0, Math.min(1.0, ratio)) * MASS_STEPS);
        return step / (double) MASS_STEPS * store.capacity() * store.unitMass();
    }

    /**
     * {@return la masse que portent toutes les réserves, en kg, chacune à son cran} Le client la tire des rapports en
     * octet que le snapshot porte : il retombe sur les crans du serveur.
     *
     * @param ratio le contenu de chaque rang sur sa capacité
     */
    public double massOfRatios(IntToDoubleFunction ratio) {
        double mass = 0.0;
        for (int slot = 0; slot < stores.size(); slot++) {
            mass += massOfRatio(slot, ratio.applyAsDouble(slot));
        }
        return mass;
    }

    /**
     * {@return la masse que portent les réserves de ces contenus, en kg}
     *
     * @param amounts le contenu de chaque rang, dans son unité
     */
    public double mass(double[] amounts) {
        return massOfRatios(slot -> amounts[slot] / capacity(slot));
    }

    /**
     * {@return la masse de la réserve que le moteur brûle quand elle porte {@code fuel}, en kg}
     *
     * @param fuel le contenu, dans son unité
     */
    public double fuelMass(double fuel) {
        return fuelCapacity() > 0f ? massOfRatio(fuelStore, fuel / fuelCapacity()) : 0.0;
    }
}
