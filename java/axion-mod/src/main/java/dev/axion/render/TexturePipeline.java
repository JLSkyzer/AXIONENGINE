package dev.axion.render;

import dev.axion.asset.MaterialTransfer;
import java.util.List;

/**
 * Préparation, téléversement et libération des textures d'un asset (ADR-122 §7, T-c).
 *
 * <p>Chaque méthode a son fil. {@link #decode}, {@link #prepare} et {@link #atlas} tournent sur le
 * thread de fond : lecture, contrôles, variantes, puis mipmaps d'une texture seule ou d'une page
 * d'atlas. {@link #upload} et {@link #release} tournent sur le fil de rendu, seul à toucher au
 * contexte graphique (R-751). {@link #discard} et {@link #close} tournent n'importe où, pour ce
 * qu'aucun téléversement n'attend plus.
 *
 * <p>Une image décodée appartient à qui l'a reçue : {@link #prepare} la prend, réussite ou échec ;
 * {@link #atlas} ne fait que la lire ; toute autre se rend par {@link #close}.
 *
 * <p>Injectable pour que le cycle de vie des textures — et ses entrelacements avec les
 * libérations — se teste sans Minecraft.
 */
public interface TexturePipeline {

    /**
     * Lit une texture et en produit la variante demandée, hors du fil de rendu.
     *
     * @param request texture à lire
     * @return l'image, sans mipmaps
     * @throws TextureRefusal si la texture est refusée : introuvable, pas un PNG (E-3004), trop
     *     grande (E-3006), illisible
     */
    Image decode(Request request) throws TextureRefusal;

    /**
     * Prépare une texture individuelle — ses mipmaps —, hors du fil de rendu. Prend l'image, même
     * en cas d'échec.
     *
     * @param request la texture, et le nom sous lequel l'enregistrer
     * @param image son image décodée
     * @return la texture prête à téléverser
     */
    Prepared prepare(Request request, Image image);

    /**
     * Prépare une page d'atlas — tuiles margées, mipmaps de chacune —, hors du fil de rendu. Les
     * images sont lues, pas prises.
     *
     * @param location nom sous lequel enregistrer la page
     * @param blur filtrage linéaire de la page ; au plus proche sinon
     * @param page disposition des tuiles
     * @param images les images rangées, au rang que leur donnent les tuiles
     * @return la page prête à téléverser
     */
    Prepared atlas(String location, boolean blur, AtlasLayout.Page page, List<Image> images);

    /** {@return le réglage des mipmaps du jeu, de 0 à 4} */
    int mipmapSetting();

    /**
     * Rend la mémoire d'une image décodée que rien ne prendra.
     *
     * @param image image décodée
     */
    void close(Image image);

    /**
     * Téléverse et enregistre une texture préparée, sur le fil de rendu. En cas d'échec, rien ne
     * reste enregistré.
     *
     * @param prepared texture préparée
     */
    void upload(Prepared prepared);

    /**
     * Libère une texture enregistrée, sur le fil de rendu.
     *
     * @param location nom sous lequel elle a été enregistrée
     */
    void release(String location);

    /**
     * Abandonne une texture préparée qui ne sera jamais téléversée : sa mémoire est rendue.
     *
     * @param prepared texture préparée
     */
    void discard(Prepared prepared);

    /**
     * Une texture à préparer.
     *
     * @param location nom sous lequel l'enregistrer, si elle reste individuelle
     * @param key texture et variante
     * @param image l'image de la texture ; {@code null} pour le blanc d'une émissive masquée
     * @param mask l'albedo qui masque une émissive ; {@code null} hors masque
     */
    record Request(String location, TextureKey key, Source image, Source mask) {}

    /**
     * D'où lire une image.
     *
     * @param texture entrée de la table : provenance, échantillonneur, dimensions
     * @param embedded octets PNG d'une texture embarquée ; {@code null} pour une ressource
     * @param resource {@code ResourceLocation} d'une ressource ; {@code null} pour une texture
     *     embarquée
     */
    record Source(MaterialTransfer.Texture texture, byte[] embedded, String resource) {}

    /** Une image décodée, variante produite, sans mipmaps. */
    interface Image {

        /** {@return sa largeur, en texels} */
        int width();

        /** {@return sa hauteur, en texels} */
        int height();

        /** {@return le filtrage et la répétition que sa source déclare} */
        TextureSampling sampling();
    }

    /** Une texture prête à téléverser. */
    interface Prepared {

        /**
         * {@return le nom sous lequel l'enregistrer, et le filtrage que le téléversement lui
         * donnera : celui que ses types de rendu devront reprendre}
         */
        TextureBinding binding();
    }
}
