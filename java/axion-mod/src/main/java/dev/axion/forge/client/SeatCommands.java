package dev.axion.forge.client;

import dev.axion.definition.Seating;
import dev.axion.forge.AxionEntity;
import java.util.List;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Ce que font les commandes génériques au siège que le joueur vient de prendre (ADR-163 D2, axe Présentation) : une
 * ligne dans la barre d'action, chaque commande avec la touche que le joueur lui a donnée et le libellé que la
 * definition déclare — une clé de traduction —, ou son nom générique sans libellé. Une fois par siège pris. Client
 * seul, thread du client.
 */
final class SeatCommands {

    private static int shownAssembly = -1;
    private static int shownSeat = -1;

    private SeatCommands() {}

    /** Le joueur n'est plus assis : le prochain siège qu'il prendra se montrera. */
    static void forget() {
        shownAssembly = -1;
        shownSeat = -1;
    }

    /**
     * Montre les commandes du siège, s'il vient d'être pris.
     *
     * @param player le joueur
     * @param assembly l'assembly où il est assis
     * @param seat le rang de son siège, {@code -1} sans
     */
    static void show(LocalPlayer player, AxionEntity assembly, int seat) {
        if (seat < 0 || (assembly.getId() == shownAssembly && seat == shownSeat)) {
            return;
        }
        List<Seating.Binding> bindings = assembly.seatBindings(seat);
        shownAssembly = assembly.getId();
        shownSeat = seat;
        if (!bindings.isEmpty()) {
            player.displayClientMessage(line(bindings), true);
        }
    }

    /** {@return la ligne des commandes : « touche : libellé », séparés} */
    static Component line(List<Seating.Binding> bindings) {
        MutableComponent line = Component.empty();
        for (int rank = 0; rank < bindings.size(); rank++) {
            Seating.Binding binding = bindings.get(rank);
            if (rank > 0) {
                line.append("   ");
            }
            line.append(VehicleKeys.COMMANDS.get(binding.command() - 1).getTranslatedKeyMessage())
                    .append(" : ")
                    .append(Component.translatable(binding.label() != null
                            ? binding.label()
                            : "key.axion.command_" + binding.command()));
        }
        return line;
    }
}
