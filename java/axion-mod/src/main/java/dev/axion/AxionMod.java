package dev.axion;

/**
 * Constantes et façade du mod AXION ENGINE.
 *
 * <p>Cette classe est délibérément dépourvue de toute dépendance à Forge : la
 * règle R-401 impose qu'aucune classe hors du paquet {@code dev.axion.forge}
 * n'importe {@code net.minecraftforge.*} (T-020). Le point d'entrée annoté
 * {@code @Mod} vit donc dans {@link dev.axion.forge.AxionForgeEntrypoint}.
 *
 * <p>Composants : C-01 (Forge Integration), C-02 (Bootstrap).
 */
public final class AxionMod {

    /**
     * Identifiant du mod, tel que figé par le cahier des charges.
     *
     * <p>Il conditionne les espaces de noms {@code assets/axion},
     * {@code data/axion}, le répertoire {@code <gameDir>/axion/} et les clés
     * NBT persistées : il ne peut pas changer sans casser les mondes existants.
     */
    public static final String MODID = "axion";

    private AxionMod() {
        throw new AssertionError("classe de constantes, non instanciable");
    }
}
