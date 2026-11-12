package dev.axion.forge.net;

import dev.axion.net.RequiredMod;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraftforge.fml.ModList;

/**
 * Les addons de contenu vus de Forge (ADR-165) : la version d'un mod installé, et le message qui refuse un client à
 * qui il en manque.
 */
public final class RequiredAddons {

    private RequiredAddons() {
        throw new AssertionError("classe utilitaire, non instanciable");
    }

    /**
     * {@return la version d'un mod installé, telle que Forge la donne, ou {@code null} s'il ne l'est pas}
     *
     * @param modId identifiant du mod
     */
    public static String installed(String modId) {
        return ModList.get().getModContainerById(modId)
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse(null);
    }

    /**
     * {@return le message de refus, traduit chez le client : un addon par ligne, avec la version attendue et celle
     * du client (ADR-165 D3)}
     *
     * @param mismatches les addons en cause, au moins un
     */
    public static Component refusal(List<RequiredMod.Mismatch> mismatches) {
        return refusal(mismatches, List.of());
    }

    /**
     * {@return le message de refus, traduit chez le client : les addons en cause, puis les contrôleurs de véhicule
     * que le client n'enregistre pas (ADR-165 D3, ADR-167 Q7)}
     *
     * @param mismatches les addons en cause
     * @param controllers les contrôleurs manquants ; l'une des deux listes au moins n'est pas vide
     */
    public static Component refusal(List<RequiredMod.Mismatch> mismatches, List<String> controllers) {
        // Sans contrôleur manquant, le message d'ADR-165 tel quel ; sans addon en cause, celui des contrôleurs seul.
        MutableComponent message = Component.translatable(mismatches.isEmpty()
                ? "axion.connection.required_controllers" : "axion.connection.required_addons");
        for (RequiredMod.Mismatch mismatch : mismatches) {
            message.append("\n").append(mismatch.absent()
                    ? Component.translatable("axion.connection.addon_missing", mismatch.modId(), mismatch.expected())
                    : Component.translatable("axion.connection.addon_version", mismatch.modId(), mismatch.expected(),
                            mismatch.actual()));
        }
        if (!mismatches.isEmpty() && !controllers.isEmpty()) {
            message.append("\n").append(Component.translatable("axion.connection.required_controllers"));
        }
        for (String controller : controllers) {
            message.append("\n").append(Component.translatable("axion.connection.controller_missing", controller));
        }
        return message;
    }

    /**
     * {@return le même refus en clair, pour le journal du serveur}
     *
     * @param mismatches les addons en cause
     */
    public static String describe(List<RequiredMod.Mismatch> mismatches) {
        return describe(mismatches, List.of());
    }

    /**
     * {@return le même refus en clair, contrôleurs compris, pour le journal du serveur}
     *
     * @param mismatches les addons en cause
     * @param controllers les contrôleurs manquants
     */
    public static String describe(List<RequiredMod.Mismatch> mismatches, List<String> controllers) {
        List<String> parts = new java.util.ArrayList<>(mismatches.stream()
                .map(m -> m.modId() + " " + m.expected() + (m.absent() ? " absent" : " (client : " + m.actual() + ")"))
                .toList());
        controllers.forEach(controller -> parts.add("contrôleur " + controller + " absent"));
        return String.join(", ", parts);
    }
}
