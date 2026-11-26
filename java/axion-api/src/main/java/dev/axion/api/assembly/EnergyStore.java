package dev.axion.api.assembly;

import dev.axion.api.Experimental;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

/**
 * Une réserve d'énergie d'une assembly (ADR-182 D1) : ce que sa definition nomme dans {@code energy.stores} — au schéma 7,
 * {@code fuel} et {@code battery}. Sa quantité est dans l'unité de son contenu.
 *
 * <p>Thread : autoritatif uniquement, en lecture comme en écriture ; ailleurs, {@link IllegalStateException} (R-1755).
 */
@Experimental
public interface EnergyStore {

    /** {@return son nom dans la definition} */
    String name();

    /** {@return ce qu'elle contient ; vide : elle accepte tout} */
    Optional<ResourceLocation> carrier();

    /** {@return sa capacité, dans l'unité de son contenu} */
    double capacity();

    /** {@return son contenu} */
    double amount();

    /** {@return son contenu sur sa capacité, de 0 à 1} */
    default double ratio() {
        return capacity() > 0.0 ? amount() / capacity() : 0.0;
    }

    /**
     * Verse une quantité d'un type : rien pour un autre type que celui de la réserve, jamais plus que sa place, ni, si la
     * definition borne son débit ({@code max_fill_rate}), que ce débit sur le tick du jeu. Une borne ou une pompe appelle
     * cette méthode à chaque tick et reçoit ce qui est entré. Le natif le reçoit sans réveiller le véhicule.
     *
     * @param carrier le type versé, ou {@code null} pour une réserve sans type
     * @param amount ce qu'on verse, de 0 ou plus
     * @return ce qui est entré
     * @throws IllegalArgumentException si {@code amount} est négatif ou n'est pas fini
     */
    double fill(ResourceLocation carrier, double amount);

    /**
     * Pose son contenu, ramené à sa capacité, quel que soit le type : l'administration et la logique d'un addon.
     *
     * @param amount le contenu, de 0 ou plus
     * @throws IllegalArgumentException si {@code amount} est négatif ou n'est pas fini
     */
    void set(double amount);
}
