package dev.axion.entity;

import dev.axion.definition.Definition;
import dev.axion.definition.DefinitionRegistry;
import java.util.HexFormat;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * Ce qu'une AxionEntity rechargée désigne (C-50).
 *
 * <p>Une entité est <em>liée</em> à sa definition, ou <em>inerte</em>. Inerte ne
 * veut jamais dire supprimée : R-704 veut qu'une definition inconnue laisse
 * l'entité intacte, et R-1710 qu'un schéma NBT postérieur soit conservé et
 * réécrit tel quel. Un monde chargé avec un pack incomplet, puis resauvegardé,
 * ne perd donc rien.
 *
 * @param definition definition liée, ou {@code null} si l'entité est inerte
 * @param inertReason ce qui rend l'entité inerte, ou {@code null} si elle est liée
 */
public record AssemblyBinding(Definition definition, String inertReason) {

    /** Version du schéma NBT d'une assembly (PARTIE 22.2, {@code axion:v}). */
    public static final int NBT_SCHEMA = 2;

    /**
     * Résout ce qu'une entité désigne depuis son NBT.
     *
     * @param version valeur de {@code axion:v}, absente si le NBT n'en porte pas
     * @param definitionHash valeur de {@code axion:def}, absente si le NBT n'en porte pas
     * @param registry definitions chargées
     * @return la liaison, ou la raison de l'inertie
     */
    public static AssemblyBinding resolve(
            OptionalInt version, OptionalLong definitionHash, DefinitionRegistry registry) {
        if (version.isEmpty() || definitionHash.isEmpty()) {
            return inert("NBT sans axion:v ni axion:def");
        }
        int schema = version.getAsInt();
        if (schema > NBT_SCHEMA) {
            return inert("schéma NBT " + schema + " postérieur à " + NBT_SCHEMA
                    + ", conservé tel quel (R-1710)");
        }
        if (schema < NBT_SCHEMA) {
            // Aucune version antérieure n'a jamais été écrite par AXION : il n'y
            // a pas de migration à écrire, et pas de raison de deviner. Inerte,
            // les octets restent intacts.
            return inert("schéma NBT " + schema + " antérieur à " + NBT_SCHEMA
                    + ", sans migration connue");
        }
        long hash = definitionHash.getAsLong();
        return registry.byHash(hash)
                .map(definition -> new AssemblyBinding(definition, null))
                .orElseGet(() -> inert("definition " + HexFormat.of().toHexDigits(hash)
                        + " inconnue (R-704)"));
    }

    private static AssemblyBinding inert(String reason) {
        return new AssemblyBinding(null, reason);
    }

    /** {@return vrai si l'entité n'est liée à aucune definition} */
    public boolean isInert() {
        return definition == null;
    }

    /** {@return la definition liée, ou vide} */
    public Optional<Definition> bound() {
        return Optional.ofNullable(definition);
    }
}
