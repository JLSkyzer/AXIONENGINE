package dev.axion.forge;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.ParticleStatus;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ce que les films de l'avancée partagent (recette de {@code D:\BDC}) : les options d'un client qui se filme, son monde
 * plat neuf, et la capture — l'image du jeu lue à chaque image du film, 60 par seconde, en 1920 × 1080 avec un bandeau
 * en dessous, écrite en PNG que {@code tools/film/encoder.py} assemble en MP4. Contenu de développement, jamais
 * empaqueté.
 */
final class Tournage {

    private static final Logger LOGGER = LoggerFactory.getLogger("AXION film");

    /** Le film en 1920 × 1080, comme un écran d'aujourd'hui : le jeu au-dessus, le bandeau en dessous. */
    static final int LARGEUR = 1920;

    static final int HAUTEUR_JEU = 984;

    static final int BANDEAU = 96;

    private static final long IMAGE_NS = 1_000_000_000L / 60L;

    /**
     * Lecture asynchrone de l'image du jeu : le GPU la copie dans un tampon (PBO) pendant que le jeu continue, et elle
     * n'est reprise que deux images plus tard, quand la copie est faite. Lire l'image tout de suite coûtait 12 ms au
     * fil de rendu en 1920 × 1080 (médiane mesurée le 2026-11-11), assez pour tomber sous 60 images par seconde.
     */
    private static final int TAMPONS = 3;

    private final Path images;
    private final String signature;
    private final ThreadPoolExecutor ecriture;
    private final long debut;
    private int[] tampons;
    private int tamponLargeur;
    private int tamponHauteur;
    private final Image[] enCopie = new Image[TAMPONS];
    private int prochainTampon;
    private int imagesEcrites;
    private int imagesUniques;
    /** Le temps que chaque capture prend au fil de rendu : lecture de l'image du jeu et copie en pixels. */
    private final List<Long> capturesNs = new ArrayList<>();
    private volatile String echec;

    /** Une image du film en attente de ses pixels : sous quels numéros l'écrire, et son bandeau. */
    private record Image(int premiere, int repetitions, String legende, String valeurs, String temps) {}

    /**
     * Ouvre le tournage : le dossier des images, et l'écriture qui les tient à 60 par seconde.
     *
     * @param images le dossier des images
     * @param signature ce que le bandeau dit en haut à droite, avant le temps
     */
    Tournage(Path images, String signature) {
        try {
            Files.createDirectories(images);
        } catch (IOException faute) {
            throw new UncheckedIOException(faute);
        }
        this.images = images;
        this.signature = signature;
        // En 1920 × 1080, une image coûte environ 50 ms à écrire (mesuré le 2026-11-11, compression rapide) : douze
        // fils tiennent les 60 images par seconde sans que le fil de rendu ait à écrire lui-même.
        ecriture = new ThreadPoolExecutor(12, 12, 0L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(96),
                new ThreadPoolExecutor.CallerRunsPolicy());
        debut = System.nanoTime();
    }

    /** Les options d'un client qui se filme : fenêtre à la taille du film, ni interface ni son, rien qui bouge seul. */
    static void reglerLesOptions(Minecraft minecraft) {
        Options options = minecraft.options;
        options.hideGui = true;
        options.fullscreen().set(false);
        minecraft.getWindow().setWindowed(LARGEUR, HAUTEUR_JEU);
        options.getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.MASTER).set(0.0);
        options.pauseOnLostFocus = false;
        options.tutorialStep = TutorialSteps.NONE;
        options.onboardAccessibility = false;
        options.fov().set(70);
        options.renderDistance().set(12);
        options.cloudStatus().set(CloudStatus.OFF);
        options.particles().set(ParticleStatus.MINIMAL);
        options.bobView().set(false);
        options.fovEffectScale().set(0.0);
        options.screenEffectScale().set(0.0);
        options.enableVsync().set(false);
        options.framerateLimit().set(260);
        minecraft.mouseHandler.releaseMouse();
    }

    /**
     * Crée un monde plat neuf, en créatif, sans cycle du jour ni météo ni créature.
     *
     * @param minecraft le client
     * @param nom le nom du monde
     */
    static void creerLeMonde(Minecraft minecraft, String nom) {
        GameRules regles = new GameRules();
        regles.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
        regles.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
        regles.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
        regles.getRule(GameRules.RULE_RANDOMTICKING).set(0, null);
        // Créatif, pas spectateur : le joueur doit rester un observateur de la physique (R-612).
        LevelSettings reglages = new LevelSettings(
                nom, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, regles, WorldDataConfiguration.DEFAULT);
        minecraft.createWorldOpenFlows().createFreshLevel(
                nom,
                reglages,
                new WorldOptions(0L, false, false),
                acces -> acces.registryOrThrow(Registries.WORLD_PRESET)
                        .getHolderOrThrow(WorldPresets.FLAT)
                        .value()
                        .createWorldDimensions());
    }

    /** {@return l'instant où le tournage a commencé, en nanosecondes de {@link System#nanoTime()}} */
    long debut() {
        return debut;
    }

    /** {@return vrai si l'horloge du film attend une image : à 60 par seconde, depuis le début} */
    boolean imageAttendue() {
        return (int) ((System.nanoTime() - debut) / IMAGE_NS) >= imagesEcrites;
    }

    /**
     * Capture l'image du jeu, et la confie à l'écriture avec son bandeau. Une image que le rendu a sautée se répète,
     * par des liens : le film garde 60 images par seconde.
     *
     * @param cible l'image du jeu
     * @param legende la ligne du haut du bandeau
     * @param valeurs la ligne du bas
     * @param temps le temps affiché après la signature
     */
    void capturer(RenderTarget cible, String legende, String valeurs, String temps) {
        int attendues = (int) ((System.nanoTime() - debut) / IMAGE_NS);
        int repetitions = attendues - imagesEcrites + 1;
        int premiere = imagesEcrites + 1;
        imagesEcrites += repetitions;
        imagesUniques++;
        long debutCapture = System.nanoTime();
        lire(cible, new Image(premiere, repetitions, legende, valeurs, temps));
        capturesNs.add(System.nanoTime() - debutCapture);
    }

    /** {@return le nombre d'images du film, répétitions comprises} */
    int images() {
        return imagesEcrites;
    }

    /** {@return le nombre d'images réellement capturées} */
    int uniques() {
        return imagesUniques;
    }

    /** {@return la faute d'écriture d'une image, ou {@code null}} */
    String echec() {
        return echec;
    }

    /**
     * Reprend les dernières images, encore en copie dans leurs tampons, puis attend que tout soit écrit.
     *
     * @return {@code null} si tout est écrit, la faute sinon
     */
    String fermer() {
        viderLesTampons();
        ecriture.shutdown();
        try {
            if (!ecriture.awaitTermination(120, TimeUnit.SECONDS)) {
                return "écriture non terminée";
            }
        } catch (InterruptedException interrompu) {
            Thread.currentThread().interrupt();
            return "écriture interrompue";
        }
        return echec;
    }

    /**
     * {@return ce quantile du temps de capture au fil de rendu, en millisecondes ; 0 sans capture}
     *
     * @param q le quantile, entre 0 et 1
     */
    double quantileMs(double q) {
        if (capturesNs.isEmpty()) {
            return 0.0;
        }
        List<Long> tries = new ArrayList<>(capturesNs);
        Collections.sort(tries);
        return tries.get(Math.min(tries.size() - 1, (int) (q * tries.size()))) / 1e6;
    }

    /** Lance la copie de l'image du jeu dans le prochain tampon, et reprend celle lancée deux images plus tôt. */
    private void lire(RenderTarget cible, Image image) {
        if (tampons == null || cible.width != tamponLargeur || cible.height != tamponHauteur) {
            viderLesTampons();
            tamponLargeur = cible.width;
            tamponHauteur = cible.height;
            tampons = new int[TAMPONS];
            for (int i = 0; i < TAMPONS; i++) {
                tampons[i] = GL15.glGenBuffers();
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, tampons[i]);
                GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, 4L * tamponLargeur * tamponHauteur, GL15.GL_STREAM_READ);
            }
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
        }
        int tampon = prochainTampon;
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, tampons[tampon]);
        GlStateManager._pixelStore(GL11.GL_PACK_ALIGNMENT, 4);
        GlStateManager._bindTexture(cible.getColorTextureId());
        GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L);
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
        enCopie[tampon] = image;
        prochainTampon = (tampon + 1) % TAMPONS;
        // Le tampon suivant a été lancé deux images plus tôt : sa copie est faite.
        reprendre(prochainTampon);
    }

    /** Reprend les pixels d'un tampon lancé plus tôt et les confie à l'écriture ; rien s'il n'attend rien. */
    private void reprendre(int tampon) {
        Image image = enCopie[tampon];
        if (image == null) {
            return;
        }
        enCopie[tampon] = null;
        int largeur = tamponLargeur;
        int hauteur = tamponHauteur;
        int[] rgba = new int[largeur * hauteur];
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, tampons[tampon]);
        ByteBuffer copie = GL15.glMapBuffer(GL21.GL_PIXEL_PACK_BUFFER, GL15.GL_READ_ONLY);
        if (copie != null) {
            copie.order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(rgba);
        }
        GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER);
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
        if (copie == null) {
            LOGGER.error("[film] tampon de l'image {} illisible", image.premiere());
            return;
        }
        ecriture.execute(() -> ecrire(image, retourner(rgba, largeur, hauteur), largeur, hauteur));
    }

    /** Reprend toutes les images encore en copie, de la plus ancienne à la plus récente, puis rend les tampons. */
    private void viderLesTampons() {
        if (tampons == null) {
            return;
        }
        for (int k = 0; k < TAMPONS; k++) {
            reprendre((prochainTampon + k) % TAMPONS);
        }
        for (int tampon : tampons) {
            GL15.glDeleteBuffers(tampon);
        }
        tampons = null;
    }

    /**
     * {@return l'image en ARGB, haut en premier} Le GPU la rend en octets RGBA, ligne du bas en premier ; lue en
     * entiers petit-boutistes, chaque pixel vaut {@code A B G R} du poids fort au poids faible.
     */
    private static int[] retourner(int[] rgba, int largeur, int hauteur) {
        int[] argb = new int[rgba.length];
        for (int y = 0; y < hauteur; y++) {
            int source = (hauteur - 1 - y) * largeur;
            int cible = y * largeur;
            for (int x = 0; x < largeur; x++) {
                int p = rgba[source + x];
                argb[cible + x] = 0xFF000000 | (p & 0xFF) << 16 | (p & 0xFF00) | (p >>> 16) & 0xFF;
            }
        }
        return argb;
    }

    private void ecrire(Image image, int[] argb, int largeur, int hauteur) {
        try {
            BufferedImage film = new BufferedImage(LARGEUR, HAUTEUR_JEU + BANDEAU, BufferedImage.TYPE_INT_RGB);
            int[] rgb = new int[argb.length];
            for (int i = 0; i < argb.length; i++) {
                rgb[i] = argb[i] & 0xFFFFFF;
            }
            // La fenêtre peut avoir une autre taille : l'image entière, mise à l'échelle.
            BufferedImage jeu = new BufferedImage(largeur, hauteur, BufferedImage.TYPE_INT_RGB);
            jeu.setRGB(0, 0, largeur, hauteur, rgb, 0, largeur);
            Graphics2D g = film.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(jeu, 0, 0, LARGEUR, HAUTEUR_JEU, null);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(new Color(18, 20, 26));
            g.fillRect(0, HAUTEUR_JEU, LARGEUR, BANDEAU);
            g.setColor(Color.WHITE);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 30));
            g.drawString(image.legende(), 24, HAUTEUR_JEU + 39);
            g.setColor(new Color(170, 200, 235));
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 27));
            g.drawString(image.valeurs(), 24, HAUTEUR_JEU + 78);
            // Calée sur la marge droite : une signature plus longue, ou un temps à deux chiffres, ne déborde pas.
            String signe = signature + "   " + image.temps();
            g.drawString(signe, LARGEUR - 24 - g.getFontMetrics().stringWidth(signe), HAUTEUR_JEU + 39);
            g.dispose();
            Path fichier = images.resolve(nom(image.premiere()));
            ecrirePng(film, fichier);
            for (int k = 1; k < image.repetitions(); k++) {
                Files.createLink(images.resolve(nom(image.premiere() + k)), fichier);
            }
        } catch (IOException | RuntimeException faute) {
            echec = "écriture : " + faute;
            LOGGER.error("écriture d'une image", faute);
        }
    }

    private static String nom(int rang) {
        return String.format(Locale.ROOT, "f%06d.png", rang);
    }

    /**
     * Écrit une image en PNG, compression rapide : en 1920 × 1080, 49 ms au lieu de 86 au réglage par défaut, pour un
     * fichier un quart plus gros (mesuré le 2026-11-11 sur une image du film).
     */
    private static void ecrirePng(BufferedImage image, Path fichier) throws IOException {
        ImageWriter ecrivain = ImageIO.getImageWritersByFormatName("png").next();
        try (ImageOutputStream sortie = ImageIO.createImageOutputStream(fichier.toFile())) {
            ImageWriteParam reglage = ecrivain.getDefaultWriteParam();
            reglage.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            reglage.setCompressionQuality(0.9f);
            ecrivain.setOutput(sortie);
            ecrivain.write(null, new IIOImage(image, null, null), reglage);
        } finally {
            ecrivain.dispose();
        }
    }
}
