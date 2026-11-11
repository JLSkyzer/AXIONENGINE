package dev.axion.content;

import dev.axion.api.content.ContentProvider;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

/**
 * Les fournisseurs de contenu reçus des mods, gardés pour être rappelés (ADR-162 D2, D9).
 *
 * <p>Logique pure, sans Forge. La couche Forge les reçoit une fois, au chargement des mods ; l'enregistrement les
 * appelle tous sur un registre neuf, et {@code /axion dev reload} les rappelle de même. Chaque appel relit ce que le
 * fournisseur désigne : un dossier lu depuis le disque, quand le mod tourne depuis ses sources, donne son contenu du
 * moment ; lu dans un JAR, le même.
 */
public final class ContentProviders {

    /**
     * Un fournisseur et le mod qui l'a envoyé.
     *
     * @param modId le mod, certifié par Forge : l'espace de noms de ses entrées
     * @param provider ce qu'il déclare
     */
    public record Provided(String modId, ContentProvider provider) {

        /** Refuse un champ absent. */
        public Provided {
            Objects.requireNonNull(modId, "modId");
            Objects.requireNonNull(provider, "provider");
        }
    }

    private final List<Provided> providers;

    /**
     * @param providers les fournisseurs reçus ; ils seront appelés dans l'ordre des modId, et, pour un même mod,
     *     dans leur ordre d'arrivée
     */
    public ContentProviders(List<Provided> providers) {
        List<Provided> sorted = new ArrayList<>(providers);
        sorted.sort(Comparator.comparing(Provided::modId));
        this.providers = List.copyOf(sorted);
    }

    /** {@return les mods qui ont envoyé un fournisseur, sans doublon, dans l'ordre d'appel} */
    public List<String> modIds() {
        return providers.stream().map(Provided::modId).distinct().toList();
    }

    /**
     * Appelle chaque fournisseur, l'un après l'autre, sur le registre donné. Un fournisseur qui lève n'arrête pas
     * les suivants : son échec est rendu à {@code failures}, et ce qu'il avait déjà enregistré reste.
     *
     * @param registry le registre, neuf
     * @param files résout, pour un mod, un chemin relatif à la racine de son JAR ; rend {@code null} s'il est absent
     * @param failures reçoit le mod et l'exception d'un fournisseur qui a échoué
     * @return le registre, à résoudre
     */
    public ContentRegistry registerAll(ContentRegistry registry, BiFunction<String, String, Path> files,
            BiConsumer<String, RuntimeException> failures) {
        for (Provided provided : providers) {
            String mod = provided.modId();
            try (ContentRegistry.Session session = registry.open(mod, relative -> files.apply(mod, relative))) {
                provided.provider().register(session);
            } catch (RuntimeException failure) {
                failures.accept(mod, failure);
            }
        }
        return registry;
    }
}
