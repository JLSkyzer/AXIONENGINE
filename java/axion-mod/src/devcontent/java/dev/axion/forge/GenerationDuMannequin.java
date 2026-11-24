package dev.axion.forge;

import dev.axion.AxionMod;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Écrit le mannequin du joueur (ADR-177 D2, CAP-56) : {@code ./gradlew genMannequin}.
 *
 * <p>Le modèle du joueur de Minecraft ne se charge que dans un client ({@code @OnlyIn(CLIENT)}) : le fichier s'écrit donc ici, par
 * un client qui crée un monde plat, prend son joueur comme entité de {@code setupAnim}, écrit {@code joueur_large.glb} et
 * {@code joueur_fin.glb} dans {@code content/examples/axion/models/mannequin/}, et s'arrête. Le banc de rendu les juge ensuite
 * contre le jeu ({@code BancDeRendu}). Inerte sans {@code -Daxion.mannequin.update=true}.
 */
@Mod.EventBusSubscriber(modid = AxionMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class GenerationDuMannequin {

    private static final Logger LOGGER = LoggerFactory.getLogger("AXION mannequin");

    private static final boolean ACTIF = Boolean.getBoolean("axion.mannequin.update");

    private enum Phase { TITRE, CREATION, MONDE, FIN }

    private static Phase phase = Phase.TITRE;
    private static long debutPhase = System.nanoTime();

    private GenerationDuMannequin() {}

    /** @param event frame du client */
    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (!ACTIF || event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        try {
            switch (phase) {
                case TITRE -> {
                    if (minecraft.getOverlay() == null && minecraft.level == null && minecraft.screen != null) {
                        Tournage.reglerLesOptions(minecraft);
                        passer(Phase.CREATION);
                    }
                }
                case CREATION -> {
                    Tournage.creerLeMonde(minecraft, "axion-film-mannequin");
                    passer(Phase.MONDE);
                }
                case MONDE -> {
                    if (minecraft.level != null && minecraft.player != null && System.nanoTime() - debutPhase > 3_000_000_000L) {
                        ecrire(minecraft);
                        passer(Phase.FIN);
                        minecraft.stop();
                    } else if (System.nanoTime() - debutPhase > 120_000_000_000L) {
                        throw new IllegalStateException("le monde ne vient pas");
                    }
                }
                case FIN -> { }
                default -> throw new IllegalStateException();
            }
        } catch (RuntimeException faute) {
            LOGGER.error("le mannequin n'a pas été écrit", faute);
            passer(Phase.FIN);
            minecraft.stop();
        }
    }

    private static void passer(Phase suivante) {
        LOGGER.info("[mannequin] {} -> {}", phase, suivante);
        phase = suivante;
        debutPhase = System.nanoTime();
    }

    private static void ecrire(Minecraft minecraft) {
        Path dossier = Path.of(System.getProperty("axion.mannequin.dir"));
        float decalage = (float) minecraft.player.getMyRidingOffset();
        try {
            Files.createDirectories(dossier);
            for (Mannequin.Modele modele : Mannequin.Modele.values()) {
                byte[] octets = Mannequin.ecrire(modele, minecraft.player, decalage);
                Files.write(dossier.resolve(modele.fichier + ".glb"), octets);
                LOGGER.info("[mannequin] {} : {} octets", modele.fichier, octets.length);
            }
        } catch (IOException faute) {
            throw new java.io.UncheckedIOException(faute);
        }
    }
}
