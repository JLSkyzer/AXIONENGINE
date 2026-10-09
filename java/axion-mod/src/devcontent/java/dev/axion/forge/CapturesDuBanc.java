package dev.axion.forge;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

/**
 * Captures du banc de rendu (ADR-126) : l'image de la cible principale, lue à la fin d'une frame,
 * et ce qu'on en mesure. Seules les couleurs comptent ; l'alpha de la cible principale ne s'affiche
 * pas.
 *
 * <p>Contenu de développement, jamais empaqueté (R-1790). Render thread seul.
 */
final class CapturesDuBanc {

    /** Les trois canaux de couleur d'un pixel {@code ABGR}. */
    private static final int COULEUR = 0x00FFFFFF;

    private CapturesDuBanc() {}

    /**
     * Une capture : pixels {@code ABGR}, ligne à ligne depuis le haut.
     *
     * @param largeur largeur en pixels
     * @param hauteur hauteur en pixels
     * @param pixels pixels
     */
    record Capture(int largeur, int hauteur, int[] pixels) {

        int pixel(int x, int y) {
            return pixels[y * largeur + x];
        }
    }

    /**
     * Les pixels qui distinguent une capture d'une autre, et leur boîte.
     *
     * @param change vrai pour chaque pixel dont la couleur diffère
     * @param nombre pixels différents
     * @param minX bord gauche de la boîte, inclus ; {@code -1} si rien ne diffère
     * @param minY bord haut, inclus
     * @param maxX bord droit, inclus
     * @param maxY bord bas, inclus
     */
    record Masque(boolean[] change, int nombre, int minX, int minY, int maxX, int maxY) {

        /** {@return la boîte, {@code [minX, minY, maxX, maxY]}} */
        int[] boite() {
            return new int[] {minX, minY, maxX, maxY};
        }
    }

    /**
     * Capture la cible principale, et l'écrit en PNG si un fichier est donné.
     *
     * @param minecraft le client
     * @param fichier PNG à écrire, ou {@code null}
     * @return la capture
     */
    static Capture capturer(Minecraft minecraft, Path fichier) {
        try (NativeImage image = Screenshot.takeScreenshot(minecraft.getMainRenderTarget())) {
            int largeur = image.getWidth();
            int hauteur = image.getHeight();
            int[] pixels = new int[largeur * hauteur];
            for (int y = 0; y < hauteur; y++) {
                for (int x = 0; x < largeur; x++) {
                    pixels[y * largeur + x] = image.getPixelRGBA(x, y);
                }
            }
            if (fichier != null) {
                image.writeToFile(fichier);
            }
            return new Capture(largeur, hauteur, pixels);
        } catch (IOException echec) {
            throw new UncheckedIOException("capture impossible à écrire : " + fichier, echec);
        }
    }

    /**
     * {@return les pixels de {@code autre} dont la couleur diffère de {@code reference}}
     *
     * @param reference capture de référence
     * @param autre capture comparée, de même taille
     */
    static Masque masque(Capture reference, Capture autre) {
        if (reference.largeur() != autre.largeur() || reference.hauteur() != autre.hauteur()) {
            throw new IllegalStateException("captures de tailles différentes : "
                    + reference.largeur() + "×" + reference.hauteur() + " et "
                    + autre.largeur() + "×" + autre.hauteur());
        }
        int largeur = reference.largeur();
        boolean[] change = new boolean[reference.pixels().length];
        int nombre = 0;
        int minX = -1;
        int minY = -1;
        int maxX = -1;
        int maxY = -1;
        for (int i = 0; i < change.length; i++) {
            if (((reference.pixels()[i] ^ autre.pixels()[i]) & COULEUR) != 0) {
                change[i] = true;
                nombre++;
                int x = i % largeur;
                int y = i / largeur;
                minX = minX < 0 ? x : Math.min(minX, x);
                minY = minY < 0 ? y : Math.min(minY, y);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
            }
        }
        return new Masque(change, nombre, minX, minY, maxX, maxY);
    }

    /**
     * {@return la luminance moyenne, de 0 à 255, des pixels du masque dans la capture}
     *
     * @param capture capture lue
     * @param masque pixels retenus
     */
    static double luminance(Capture capture, Masque masque) {
        double somme = 0;
        for (int i = 0; i < masque.change().length; i++) {
            if (masque.change()[i]) {
                somme += luminance(capture.pixels()[i]);
            }
        }
        return masque.nombre() == 0 ? 0 : somme / masque.nombre();
    }

    /**
     * {@return la luminance moyenne du carré de côté {@code 2 rayon + 1} centré en {@code (x, y)},
     * bornée à l'image}
     */
    static double luminanceAutour(Capture capture, int x, int y, int rayon) {
        double somme = 0;
        int nombre = 0;
        for (int py = Math.max(0, y - rayon); py <= Math.min(capture.hauteur() - 1, y + rayon); py++) {
            for (int px = Math.max(0, x - rayon); px <= Math.min(capture.largeur() - 1, x + rayon); px++) {
                somme += luminance(capture.pixel(px, py));
                nombre++;
            }
        }
        return nombre == 0 ? 0 : somme / nombre;
    }

    /** Luminance relative d'un pixel {@code ABGR}, coefficients de Rec. 709. */
    private static double luminance(int abgr) {
        int rouge = abgr & 0xFF;
        int vert = (abgr >>> 8) & 0xFF;
        int bleu = (abgr >>> 16) & 0xFF;
        return 0.2126 * rouge + 0.7152 * vert + 0.0722 * bleu;
    }
}
