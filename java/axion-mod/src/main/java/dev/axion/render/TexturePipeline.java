package dev.axion.render;

import dev.axion.asset.MaterialTransfer;

/**
 * Préparation, téléversement et libération des textures d'un asset (ADR-122 §7).
 *
 * <p>Chaque méthode a son fil. {@link #prepare} tourne sur le thread de fond : lecture,
 * contrôles, découpe, mipmaps. {@link #upload} et {@link #release} tournent sur le fil de rendu,
 * seul à toucher au contexte graphique (R-751). {@link #discard} tourne n'importe où, pour une
 * texture préparée qu'aucun téléversement n'attend plus.
 *
 * <p>Injectable pour que le cycle de vie des textures — et ses entrelacements avec les
 * libérations — se teste sans Minecraft.
 */
public interface TexturePipeline {

    /**
     * Prépare une texture, hors du fil de rendu.
     *
     * @param request texture à préparer
     * @return la texture prête à téléverser
     * @throws TextureRefusal si la texture est refusée : introuvable, pas un PNG (E-3004), trop
     *     grande (E-3006), illisible
     */
    Prepared prepare(Request request) throws TextureRefusal;

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
     * @param location nom sous lequel l'enregistrer
     * @param key texture et variante
     * @param texture entrée de la table : provenance, échantillonneur, dimensions
     * @param embedded octets PNG d'une texture embarquée ; {@code null} pour une ressource
     * @param resource {@code ResourceLocation} d'une ressource ; {@code null} pour une texture
     *     embarquée
     */
    record Request(
            String location,
            TextureKey key,
            MaterialTransfer.Texture texture,
            byte[] embedded,
            String resource) {}

    /** Une texture prête à téléverser. */
    interface Prepared {

        /**
         * {@return le nom sous lequel l'enregistrer, et le filtrage que le téléversement lui
         * donnera : celui que ses types de rendu devront reprendre}
         */
        TextureBinding binding();

        /** {@return ce qu'il faut signaler une fois à son sujet, ou {@code null}} */
        String note();
    }
}
