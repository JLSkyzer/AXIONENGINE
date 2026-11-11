package dev.axion.forge;

import dev.axion.AxionMod;
import dev.axion.asset.AssetRegistry;
import dev.axion.forge.client.AxionRenderPass;
import dev.axion.lifecycle.AxionRuntime;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.commands.CommandSource;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ADR-162 D9, essai de bout en bout en solo : le développeur ajoute un modèle et une definition au dossier de son mod,
 * tape {@code /axion dev reload}, et voit l'objet en jeu sans redémarrer. Le GameTest du rechargement couvre le
 * serveur ; cet essai couvre le client de la même JVM, qui recompile ses modèles depuis le contenu réenregistré.
 *
 * <p>Inerte sans {@code -Daxion.essai.rechargement=true} ({@code ./gradlew runEssaiRechargementDev}) ; le contenu de
 * développement ne part jamais dans le mod livré. L'essai écrit au journal chaque étape, prend une capture, retire ses
 * fichiers, recharge une dernière fois et arrête le jeu.
 */
@Mod.EventBusSubscriber(modid = AxionMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class EssaiRechargementDev {

    private static final Logger LOGGER = LoggerFactory.getLogger("AXION essai");

    private static final boolean ACTIF = Boolean.getBoolean("axion.essai.rechargement");

    /** Le dossier du contenu de développement tel que le jeu le lit (build.gradle). */
    private static final String DOSSIER = System.getProperty("axion.essai.devcontent");

    private static final String MONDE = "essai-rechargement-dev";

    /** Le cube de test, copié sous un nouveau nom : un modèle que le client ne connaît pas avant le rechargement. */
    private static final String SOURCE_MODELE = "axion/models/test/cube.gltf";
    private static final String FICHIER_MODELE = "axion/models/test/cube_rechargement_dev.gltf";
    private static final String ASSET = "axion:models/test/cube_rechargement_dev.gltf";

    private static final String FICHIER_DEFINITION = "axion/definitions/test/cube_rechargement_dev.json";
    private static final String DEFINITION = "axion:test/cube_rechargement_dev";

    private enum Phase { TITRE, MONDE, VOIR, NETTOYAGE, FIN }

    private static Phase phase = Phase.TITRE;
    private static long debutPhase = System.nanoTime();
    private static volatile boolean pret;
    private static volatile String echec;

    private EssaiRechargementDev() {}

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (!ACTIF || event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (echec != null) {
            LOGGER.error("[essai] ÉCHEC : {}", echec);
            minecraft.stop();
            return;
        }
        switch (phase) {
            case TITRE -> {
                if (minecraft.getOverlay() == null && minecraft.level == null && minecraft.screen != null) {
                    if (DOSSIER == null) {
                        echec = "propriété axion.essai.devcontent absente : lancer par runEssaiRechargementDev";
                        return;
                    }
                    creerLeMonde(minecraft);
                    passer(Phase.MONDE);
                }
            }
            case MONDE -> {
                MinecraftServer serveur = minecraft.getSingleplayerServer();
                if (minecraft.level != null && minecraft.player != null && serveur != null && serveur.isReady()
                        && ecoule(3)) {
                    minecraft.player.setXRot(25f);
                    Vec3 devant = minecraft.player.position().add(minecraft.player.getForward().multiply(4, 0, 4))
                            .add(0, 2, 0);
                    serveur.execute(() -> recharger(serveur, devant));
                    passer(Phase.VOIR);
                }
            }
            case VOIR -> {
                if (pret && ecoule(8)) {
                    constater(minecraft);
                    MinecraftServer serveur = minecraft.getSingleplayerServer();
                    serveur.execute(() -> nettoyer(serveur));
                    passer(Phase.NETTOYAGE);
                } else if (ecoule(60)) {
                    echec = "le rechargement n'a pas abouti en 60 s";
                }
            }
            case NETTOYAGE -> {
                if (ecoule(4)) {
                    LOGGER.info("[essai] fin de l'essai");
                    passer(Phase.FIN);
                    minecraft.stop();
                }
            }
            case FIN -> { }
        }
    }

    /** Sur le thread du serveur : le refus d'avant, les fichiers, la commande, l'objet. */
    private static void recharger(MinecraftServer serveur, Vec3 devant) {
        try {
            Path racine = Path.of(DOSSIER);
            Files.deleteIfExists(racine.resolve(FICHIER_MODELE));
            Files.deleteIfExists(racine.resolve(FICHIER_DEFINITION));
            String spawn = String.format(Locale.ROOT, "axion spawn %s %.2f %.2f %.2f", DEFINITION, devant.x, devant.y,
                    devant.z);
            int avant = commande(serveur, spawn);
            LOGGER.info("[essai] avant le rechargement, {} a rendu {}", spawn, avant);

            Files.copy(racine.resolve(SOURCE_MODELE), racine.resolve(FICHIER_MODELE));
            Files.writeString(racine.resolve(FICHIER_DEFINITION),
                    "{\"schema\": 1, \"asset\": \"" + ASSET + "\", \"kind\": \"rigid_object\"}", StandardCharsets.UTF_8);
            LOGGER.info("[essai] ajoutés au dossier du mod : {} et {}", FICHIER_MODELE, FICHIER_DEFINITION);

            int recharge = commande(serveur, "axion dev reload");
            LOGGER.info("[essai] /axion dev reload a rendu {}", recharge);
            int apres = commande(serveur, spawn);
            LOGGER.info("[essai] après le rechargement, {} a rendu {}", spawn, apres);
            if (avant != 0 || recharge != 1 || apres != 1) {
                echec = "attendu 0, 1, 1 ; rendu " + avant + ", " + recharge + ", " + apres;
                return;
            }
            pret = true;
        } catch (IOException faute) {
            echec = "fichiers de l'essai : " + faute;
        }
    }

    /** Sur le thread du client : ce que le client a compilé, ce que la frame dessine, la capture. */
    private static void constater(Minecraft minecraft) {
        AxionRuntime runtime = RuntimeAccess.get();
        AssetRegistry chezLeClient = runtime.clientAssets();
        // L'identifiant tel que le registre le range : celui de la definition chargée, normalisé.
        String asset = runtime.definitions().get(DEFINITION).map(definition -> definition.asset()).orElse(ASSET);
        boolean connu = chezLeClient != null && asset.equals(chezLeClient.resolve(asset));
        boolean compile = chezLeClient != null && chezLeClient.published(asset) != null;
        AxionRenderPass.FrameStats frame = AxionRenderPass.lastFrame();
        LOGGER.info("[essai] client : modèle {} {}, {} ; frame : {} assembly(s), dont {} avec leur asset", asset,
                connu ? "connu" : "INCONNU", compile ? "compilé" : "NON COMPILÉ", frame.assemblies(),
                frame.withAsset());
        if (!connu || !compile || frame.withAsset() < 1) {
            echec = "le client ne dessine pas le modèle ajouté";
        }
        Screenshot.grab(minecraft.gameDirectory, "essai_rechargement_dev.png", minecraft.getMainRenderTarget(),
                message -> LOGGER.info("[essai] capture : {}", message.getString()));
    }

    /** Sur le thread du serveur : les fichiers de l'essai retirés, et le contenu rechargé sans eux. */
    private static void nettoyer(MinecraftServer serveur) {
        try {
            Path racine = Path.of(DOSSIER);
            Files.deleteIfExists(racine.resolve(FICHIER_MODELE));
            Files.deleteIfExists(racine.resolve(FICHIER_DEFINITION));
        } catch (IOException faute) {
            throw new UncheckedIOException(faute);
        }
        LOGGER.info("[essai] fichiers retirés ; /axion dev reload a rendu {}", commande(serveur, "axion dev reload"));
    }

    /** {@return ce que rend une commande tapée avec les droits du serveur} Ses réponses vont au journal. */
    private static int commande(MinecraftServer serveur, String ligne) {
        List<String> sortie = new ArrayList<>();
        CommandSource recueil = new CommandSource() {
            @Override
            public void sendSystemMessage(Component message) {
                sortie.add(message.getString());
            }

            @Override
            public boolean acceptsSuccess() {
                return true;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }

            @Override
            public boolean shouldInformAdmins() {
                return false;
            }
        };
        int rendu = serveur.getCommands().performPrefixedCommand(
                serveur.createCommandSourceStack().withSource(recueil), ligne);
        sortie.forEach(ligneSortie -> LOGGER.info("[essai] « {} » répond : {}", ligne, ligneSortie));
        return rendu;
    }

    private static void creerLeMonde(Minecraft minecraft) {
        GameRules regles = new GameRules();
        regles.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
        regles.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
        regles.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
        LevelSettings reglages = new LevelSettings(
                MONDE, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, regles, WorldDataConfiguration.DEFAULT);
        minecraft.createWorldOpenFlows().createFreshLevel(
                MONDE,
                reglages,
                new WorldOptions(0L, false, false),
                acces -> acces.registryOrThrow(Registries.WORLD_PRESET)
                        .getHolderOrThrow(WorldPresets.FLAT)
                        .value()
                        .createWorldDimensions());
    }

    private static void passer(Phase suivante) {
        phase = suivante;
        debutPhase = System.nanoTime();
    }

    private static boolean ecoule(int secondes) {
        return System.nanoTime() - debutPhase > TimeUnit.SECONDS.toNanos(secondes);
    }
}
