package dev.axion.attach;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Ce qu'un ensemble attaché garde chargé (ADR-171 D7, Q9, CAP-37) : une remorque ne reste pas seule au bord d'un chunk.
 * Un ensemble est <em>tenu</em> dès qu'un de ses membres est à portée de simulation d'un joueur ; tenu, il garde chargés
 * les chunks de tous ses membres. Plus tenu, il ne garde rien : ses chunks se déchargent ensemble. La portée est la
 * distance de simulation du serveur, en chunks, mesurée comme Minecraft la mesure (la plus grande des deux
 * différences).
 *
 * <p>Logique pure ; le ticket de chunk qui la sert vit au serveur ({@code dev.axion.forge.AttachmentRuntime}).
 */
public final class EnsembleHold {

    /** Un chunk, par ses coordonnées de chunk. */
    public record Chunk(int x, int z) {

        /** {@return la distance à un autre chunk, comme Minecraft la mesure pour la simulation} */
        int distance(Chunk other) {
            return Math.max(Math.abs(x - other.x), Math.abs(z - other.z));
        }
    }

    private EnsembleHold() {}

    /**
     * {@return les chunks qu'un ensemble garde chargés, sans doublon, dans l'ordre de ses membres ; vide s'il n'est pas
     * tenu}
     *
     * @param members le chunk de chaque membre de l'ensemble
     * @param players le chunk de chaque joueur de sa dimension
     * @param simulationDistance la distance de simulation du serveur, en chunks
     */
    public static Set<Chunk> chunksToHold(List<Chunk> members, List<Chunk> players, int simulationDistance) {
        boolean held = members.stream()
                .anyMatch(member -> players.stream().anyMatch(player -> member.distance(player) <= simulationDistance));
        return held ? new LinkedHashSet<>(members) : Set.of();
    }
}
