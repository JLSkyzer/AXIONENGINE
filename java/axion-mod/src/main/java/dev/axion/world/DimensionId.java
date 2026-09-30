package dev.axion.world;

import java.nio.charset.StandardCharsets;

/**
 * Convention d'identifiant de dimension pour la frontière (C-31 R-610, IF-03).
 *
 * <p>Le champ {@code dimension: u64} des commandes ({@code CREATE_ASSEMBLY},
 * {@code SET_WORLD_*}, {@code SET_DIMENSION_ENV}) est une <b>clé opaque</b> côté natif : le
 * moteur ne l'interprète pas, il exige seulement qu'une même dimension Minecraft y mappe
 * <b>toujours la même valeur</b>. L'encodage est donc un choix Java, et il doit être
 * <b>unique et partagé</b> par tout ce qui émet un id de dimension — les tuiles du monde
 * (C-38) comme les corps (C-40) — sans quoi corps et tuiles vivraient dans des mondes
 * physiques distincts.
 *
 * <p>On dérive l'id d'un hachage <b>FNV-1a 64 bits</b> du {@code ResourceLocation} de la
 * dimension (ex. {@code "minecraft:overworld"}) : déterministe, stable entre exécutions et
 * machines, sans état ni table d'attribution. La probabilité de collision sur la poignée
 * de dimensions d'une partie est négligeable.
 */
public final class DimensionId {

    private static final long FNV_OFFSET_BASIS = 0xcbf2_9ce4_8422_2325L;
    private static final long FNV_PRIME = 0x0000_0100_0000_01B3L;

    private DimensionId() {}

    /**
     * {@return la clé de dimension pour la frontière}
     *
     * @param dimensionLocation le {@code ResourceLocation} de la dimension, par ex.
     *     {@code "minecraft:overworld"} (côté Forge : {@code level.dimension().location().toString()})
     */
    public static long of(String dimensionLocation) {
        long hash = FNV_OFFSET_BASIS;
        for (byte b : dimensionLocation.getBytes(StandardCharsets.UTF_8)) {
            hash ^= (b & 0xffL);
            hash *= FNV_PRIME;
        }
        return hash;
    }
}
