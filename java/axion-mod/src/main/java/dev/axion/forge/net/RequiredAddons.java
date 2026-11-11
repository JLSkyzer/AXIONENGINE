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
        MutableComponent message = Component.translatable("axion.connection.required_addons");
        for (RequiredMod.Mismatch mismatch : mismatches) {
            message.append("\n").append(mismatch.absent()
                    ? Component.translatable("axion.connection.addon_missing", mismatch.modId(), mismatch.expected())
                    : Component.translatable("axion.connection.addon_version", mismatch.modId(), mismatch.expected(),
                            mismatch.actual()));
        }
        return message;
    }

    /**
     * {@return le même refus en clair, pour le journal du serveur}
     *
     * @param mismatches les addons en cause
     */
    public static String describe(List<RequiredMod.Mismatch> mismatches) {
        return String.join(", ", mismatches.stream()
                .map(m -> m.modId() + " " + m.expected() + (m.absent() ? " absent" : " (client : " + m.actual() + ")"))
                .toList());
    }
}
